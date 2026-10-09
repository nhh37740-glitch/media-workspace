<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError, media, spaces } from '../api/client'
import { session } from '../stores/session'
import { formatInstant, formatRelative } from '../utils/format'

/**
 * Workspace membership and share links.
 *
 * Two things this page is careful about. The raw share token is shown once, immediately after
 * creation, and is never fetched again - the server does not store it, so offering a "show link
 * again" button would be a lie. And the interface hides controls the current role may not use,
 * while still handling the 403 the server would return, because hiding a button is not the
 * permission check.
 */
const spaceList = ref([])
const spaceId = ref('')
const members = ref([])
const mediaRows = ref([])
const loading = ref(false)
const failure = ref('')

const sharingMedia = ref(null)
const shareList = ref([])
const createdShare = ref(null)

const currentRole = computed(
  () => spaceList.value.find((space) => space.spaceId === spaceId.value)?.role ?? null
)
const isOwner = computed(() => !session.user?.guest && currentRole.value === 'OWNER')
const canShare = computed(() => !session.user?.guest && (isOwner.value || currentRole.value === 'EDITOR'))

onMounted(async () => {
  try {
    spaceList.value = await spaces.list()
    if (spaceList.value.length) {
      spaceId.value = spaceList.value[0].spaceId
      await load()
    }
  } catch (error) {
    failure.value = error.message
  }
})

async function load() {
  if (!spaceId.value) {
    return
  }
  loading.value = true
  failure.value = ''
  try {
    // Members are only readable by an owner; a non-owner simply sees no member list rather than an
    // error banner, because the request is expected to be refused.
    members.value = isOwner.value ? await spaces.members(spaceId.value) : []
    const listing = await media.list(spaceId.value, { pageSize: 100 })
    mediaRows.value = listing.items
  } catch (error) {
    failure.value = error.message
  } finally {
    loading.value = false
  }
}

async function addMember() {
  try {
    const { value: userId } = await ElMessageBox.prompt('对方的用户编号', '添加成员', {
      inputPattern: /^[0-9a-f-]{36}$/,
      inputErrorMessage: '请输入标准格式的用户编号'
    })
    const { value: role } = await ElMessageBox.prompt('角色：EDITOR 或 VIEWER', '选择角色', {
      inputValue: 'VIEWER',
      inputPattern: /^(EDITOR|VIEWER)$/,
      inputErrorMessage: '只能是 EDITOR 或 VIEWER'
    })
    await spaces.putMember(spaceId.value, userId.trim(), role.trim())
    ElMessage.success('成员已更新')
    await load()
  } catch (error) {
    if (error === 'cancel') {
      return
    }
    ElMessage.error(`操作失败：${error.message}`)
  }
}

async function removeMember(member) {
  try {
    await ElMessageBox.confirm(`确认移除成员 ${member.username}？`, '移除成员', { type: 'warning' })
  } catch {
    return
  }
  try {
    await spaces.removeMember(spaceId.value, member.userId)
    ElMessage.success('已移除')
    await load()
  } catch (error) {
    ElMessage.error(`移除失败：${error.message}`)
  }
}

async function openShares(row) {
  sharingMedia.value = row
  createdShare.value = null
  try {
    shareList.value = await media.shares(row.mediaId)
  } catch (error) {
    shareList.value = []
    ElMessage.error(`无法读取分享：${error.message}`)
  }
}

async function createShare() {
  if (!sharingMedia.value) {
    return
  }
  const minutes = 60
  const expiresAt = new Date(Date.now() + minutes * 60 * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')
  try {
    createdShare.value = await media.createShare(sharingMedia.value.mediaId, expiresAt)
    shareList.value = await media.shares(sharingMedia.value.mediaId)
    ElMessage.success('分享已创建。链接只显示这一次，请立即复制')
  } catch (error) {
    if (error instanceof ApiError && error.code === 'QUOTA_EXCEEDED') {
      ElMessage.warning('该素材的有效分享数量已达上限，请先撤销旧的分享')
      return
    }
    ElMessage.error(`创建失败：${error.message}`)
  }
}

function shareLink() {
  if (!createdShare.value) {
    return ''
  }
  // The token travels in the fragment so it never reaches the server in a path or a query string,
  // and therefore never lands in an access log.
  return `${window.location.origin}${window.location.pathname}#/share#token=${createdShare.value.token}`
}

async function copyLink() {
  const link = shareLink()
  try {
    await navigator.clipboard.writeText(link)
    ElMessage.success('链接已复制。请不要长期保存在浏览器里')
  } catch {
    ElMessage.warning('浏览器拒绝了剪贴板访问，请手动复制')
  }
}

async function revoke(share) {
  try {
    await media.revokeShare(share.shareId)
    ElMessage.success('已撤销')
    if (createdShare.value?.shareId === share.shareId) {
      createdShare.value = null
    }
    shareList.value = await media.shares(sharingMedia.value.mediaId)
  } catch (error) {
    ElMessage.error(`撤销失败：${error.message}`)
  }
}
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-intro">
      <div>
        <p class="page-eyebrow">WORKSPACE / 团队协作</p>
        <h1 class="page-title">共享空间</h1>
        <p class="page-description">{{ session.user?.guest ? '查看已开放的演示空间和素材，游客仅可浏览。' : '管理成员权限，创建有时效的素材分享。' }}</p>
      </div>
    </div>
    <el-card class="toolbar">
      <div class="toolbar-row">
        <el-select v-model="spaceId" class="space-select" placeholder="选择空间" @change="load">
          <el-option
            v-for="space in spaceList"
            :key="space.spaceId"
            :label="`${space.name}（${space.role}）`"
            :value="space.spaceId"
          />
        </el-select>
        <el-tag v-if="currentRole" size="small">我的角色：{{ currentRole }}</el-tag>
        <el-button v-if="isOwner" type="primary" plain @click="addMember">添加或修改成员</el-button>
      </div>
    </el-card>

    <el-alert v-if="failure" type="error" :closable="false" show-icon :title="failure" class="gap" />

    <el-card v-if="isOwner" class="gap">
      <template #header>成员</template>
      <el-table :data="members" empty-text="还没有其他成员" class="desktop-table">
        <el-table-column prop="username" label="用户名" min-width="160" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'OWNER' ? 'warning' : 'info'">
              {{ row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户编号" min-width="280">
          <template #default="{ row }"><code class="id">{{ row.userId }}</code></template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              v-if="!session.user?.guest"
              link
              type="danger"
              :disabled="row.role === 'OWNER'"
              @click="removeMember(row)"
            >
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mobile-list" aria-label="空间成员">
        <el-empty v-if="!members.length" description="还没有其他成员" />
        <article v-for="member in members" :key="member.userId" class="mobile-item">
          <div class="member-heading"><h3 class="mobile-item-title">{{ member.username }}</h3><el-tag size="small" :type="member.role === 'OWNER' ? 'warning' : 'info'">{{ member.role }}</el-tag></div>
          <div class="mobile-item-meta id">{{ member.userId }}</div>
          <div class="mobile-item-actions"><el-button type="danger" plain :disabled="member.role === 'OWNER'" @click="removeMember(member)">移除</el-button></div>
        </article>
      </div>
      <div class="hint">
        每个空间只有一个所有者，所有者不能被移除或降级。系统不提供全站用户搜索，添加成员需要对方的用户编号。
      </div>
    </el-card>

    <el-card class="gap">
      <template #header>限时分享</template>
      <el-table :data="mediaRows" empty-text="这个空间还没有素材" class="desktop-table">
        <el-table-column prop="title" label="素材" min-width="200" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'READY' ? 'success' : 'info'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              v-if="!session.user?.guest"
              :disabled="row.status !== 'READY' || !canShare"
              @click="openShares(row)"
            >
              管理分享
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mobile-list" aria-label="可分享素材">
        <el-empty v-if="!mediaRows.length" description="这个空间还没有素材" />
        <article v-for="row in mediaRows" :key="row.mediaId" class="mobile-item">
          <div class="member-heading"><h3 class="mobile-item-title">{{ row.title }}</h3><el-tag size="small" :type="row.status === 'READY' ? 'success' : 'info'">{{ row.status }}</el-tag></div>
          <div class="mobile-item-actions"><el-button v-if="!session.user?.guest" type="primary" plain :disabled="row.status !== 'READY' || !canShare" @click="openShares(row)">管理分享</el-button></div>
        </article>
      </div>
      <div class="hint">只有转码完成的素材可以分享；分享只授予该素材的播放权限。</div>
    </el-card>

    <el-drawer
      :model-value="!!sharingMedia"
      :title="`分享：${sharingMedia?.title ?? ''}`"
      size="520px"
      @close="sharingMedia = null"
    >
      <el-button v-if="canShare" type="primary" @click="createShare">创建 1 小时有效的分享</el-button>

      <el-alert
        v-if="createdShare"
        class="gap"
        type="success"
        :closable="false"
        title="链接只显示这一次"
        description="服务器只保存令牌的散列值，关闭后无法再次查看。如果丢失，请撤销这条分享后重新创建。"
      />
      <div v-if="createdShare" class="link-box">
        <code class="link">{{ shareLink() }}</code>
        <el-button size="small" @click="copyLink">复制</el-button>
      </div>

      <el-table :data="shareList" class="gap desktop-table" empty-text="还没有分享">
        <el-table-column label="分享编号" min-width="200">
          <template #default="{ row }"><code class="id">{{ row.shareId }}</code></template>
        </el-table-column>
        <el-table-column label="到期" width="170">
          <template #default="{ row }">{{ formatInstant(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.revokedAt ? 'info' : 'success'">
              {{ row.revokedAt ? '已撤销' : '有效' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="danger" :disabled="!!row.revokedAt" @click="revoke(row)">
              撤销
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mobile-list gap" aria-label="分享列表">
        <el-empty v-if="!shareList.length" description="还没有分享" />
        <article v-for="item in shareList" :key="item.shareId" class="mobile-item">
          <div class="member-heading">
            <h3 class="mobile-item-title">分享 {{ item.shareId }}</h3>
            <el-tag size="small" :type="item.revokedAt ? 'info' : 'success'">{{ item.revokedAt ? '已撤销' : '有效' }}</el-tag>
          </div>
          <div class="mobile-item-meta">到期：{{ formatInstant(item.expiresAt) }}</div>
          <div class="mobile-item-actions"><el-button type="danger" plain :disabled="!!item.revokedAt" @click="revoke(item)">撤销</el-button></div>
        </article>
      </div>
      <div class="hint">撤销只对新请求生效：已经下载的字节无法收回。</div>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.space-select {
  width: 240px;
}
.gap {
  margin-top: 16px;
}
.hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.id {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.member-heading { display: flex; align-items: start; justify-content: space-between; gap: 10px; }
.member-heading .el-tag { flex: none; }
.link-box {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.link {
  font-size: 12px;
  word-break: break-all;
  flex: 1;
}
@media (max-width: 760px) {
  .space-select { width: 100%; }
  .toolbar-row > .el-button { min-height: 42px; }
  .link-box { align-items: stretch; flex-direction: column; }
  .page :deep(.el-drawer__body) { padding: 16px; }
}
</style>

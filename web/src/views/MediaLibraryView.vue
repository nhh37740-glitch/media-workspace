<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError, media, spaces } from '../api/client'
import { session, rememberSpace } from '../stores/session'
import { describeMediaState, isTaskActive } from '../utils/taskStates'
import { formatBytes, formatDuration, formatInstant } from '../utils/format'
import UploadQueue from '../components/UploadQueue.vue'

const router = useRouter()

const spaceList = ref([])
const spaceId = ref(session.spaceId ?? '')
const loading = ref(false)
const failure = ref('')
const page = reactive({ items: [], page: 1, pageSize: 20, total: 0 })
const query = ref('')

// Whether the upload panel is offered at all. This is a usability decision only: the server
// re-checks the role on every request, and a VIEWER who posted an upload would be refused there.
const canUpload = computed(() => {
  if (session.user?.guest) return false
  const found = spaceList.value.find((space) => space.spaceId === spaceId.value)
  return found?.role === 'OWNER' || found?.role === 'EDITOR'
})

onMounted(async () => {
  try {
    spaceList.value = await spaces.list()
    if (!spaceId.value && spaceList.value.length) {
      spaceId.value = spaceList.value[0].spaceId
    }
    if (spaceId.value) {
      rememberSpace(spaceId.value)
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
    const result = await media.list(spaceId.value, { q: query.value, page: page.page })
    Object.assign(page, result)
  } catch (error) {
    // A permission answer is expected when a space is picked that the account cannot read; the
    // interface reports it rather than pretending the list is empty.
    failure.value =
      error instanceof ApiError && error.status === 404
        ? '没有访问这个空间的权限，或空间已不存在'
        : error.message
  } finally {
    loading.value = false
  }
}

watch(spaceId, async (value) => {
  if (value) {
    rememberSpace(value)
    page.page = 1
    await load()
  }
})

async function createSpace() {
  try {
    const { value } = await ElMessageBox.prompt('新空间名称', '创建空间', {
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空'
    })
    const created = await spaces.create(value.trim())
    spaceList.value.push(created)
    spaceId.value = created.spaceId
    ElMessage.success('空间已创建')
  } catch (error) {
    if (error !== 'cancel' && error?.message) {
      ElMessage.error(`创建失败：${error.message}`)
    }
  }
}

async function refreshOne(mediaId) {
  try {
    const detail = await media.detail(mediaId)
    const index = page.items.findIndex((item) => item.mediaId === mediaId)
    if (index >= 0) {
      page.items.splice(index, 1, {
        ...page.items[index],
        title: detail.title,
        status: detail.status,
        durationMs: detail.durationMs,
        width: detail.width,
        height: detail.height
      })
    }
  } catch {
    // The row is simply left as it was; the next refresh will pick up the change.
  }
}

async function startRename(row) {
  try {
    const { value } = await ElMessageBox.prompt('新的标题', '重命名', {
      inputValue: row.title,
      inputPattern: /\S+/,
      inputErrorMessage: '标题不能为空'
    })
    const detail = await media.detail(row.mediaId)
    await media.rename(row.mediaId, value.trim(), detail.version)
    ElMessage.success('已重命名')
    await refreshOne(row.mediaId)
  } catch (error) {
    if (error === 'cancel') {
      return
    }
    if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') {
      ElMessage.warning('该素材刚被其他操作修改，请刷新后重试')
      await refreshOne(row.mediaId)
      return
    }
    ElMessage.error(`重命名失败：${error.message}`)
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `删除后该素材将不可访问，未完成的处理会被取消。确认删除“${row.title}”？`,
      '删除素材',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await media.remove(row.mediaId)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      ElMessage.error('只有空间所有者可以删除素材')
      return
    }
    ElMessage.error(`删除失败：${error.message}`)
  }
}

function play(row) {
  window.open(media.contentUrl(row.mediaId), '_blank', 'noopener')
}

function openTask(row) {
  if (row.taskId) {
    router.push({ name: 'task-detail', params: { taskId: row.taskId } })
  }
}

async function onUploaded() {
  await load()
}

const hasSpaces = computed(() => spaceList.value.length > 0)
</script>

<template>
  <div class="page">
    <div class="page-intro">
      <div>
        <p class="page-eyebrow">LIBRARY / 素材管理</p>
        <h1 class="page-title">素材库</h1>
        <p class="page-description">{{ session.user?.guest ? '浏览和播放演示视频，查看真实处理任务。' : '集中上传、查找和管理团队影音素材。' }}</p>
      </div>
    </div>
    <el-card class="toolbar">
      <div class="toolbar-row">
        <el-select
          v-model="spaceId"
          placeholder="选择空间"
          class="space-select"
          :disabled="!hasSpaces"
        >
          <el-option
            v-for="space in spaceList"
            :key="space.spaceId"
            :label="`${space.name}（${space.role}）`"
            :value="space.spaceId"
          />
        </el-select>
        <el-input
          v-model="query"
          placeholder="按标题搜索"
          clearable
          class="search"
          @keyup.enter="load"
          @clear="load"
        />
        <el-button @click="load">搜索</el-button>
        <el-button v-if="!session.user?.guest" type="primary" plain @click="createSpace">新建空间</el-button>
      </div>
    </el-card>

    <el-alert v-if="failure" type="error" :closable="false" show-icon :title="failure" class="gap" />

    <el-empty
      v-if="!hasSpaces"
      :description="session.user?.guest ? '暂时没有开放的演示空间，请稍后再试。' : '还没有可用的空间。创建一个空间后即可上传素材。'"
      class="gap"
    />

    <template v-else>
      <UploadQueue v-if="canUpload" :space-id="spaceId" class="gap" @uploaded="onUploaded" />

      <el-card v-loading="loading" class="gap">
        <template #header>
          <div class="header">
            <span>素材</span>
            <span class="count">共 {{ page.total }} 项</span>
          </div>
        </template>

        <el-table :data="page.items" size="default" class="desktop-table">
          <el-table-column label="封面" width="120">
            <template #default="{ row }">
              <img
                v-if="row.status === 'READY'"
                :src="media.posterUrl(row.mediaId)"
                class="poster"
                alt=""
              />
              <div v-else class="poster placeholder">
                {{ row.status === 'PROCESSING' ? '处理中' : '无封面' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="标题" min-width="200">
            <template #default="{ row }">
              <div class="title-cell">
                <span>{{ row.title }}</span>
                <el-button v-if="!session.user?.guest" link type="primary" size="small" @click="startRename(row)">
                  重命名
                </el-button>
              </div>
              <div class="meta">{{ formatInstant(row.createdAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="describeMediaState(row.status).type" size="small">
                {{ describeMediaState(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时长" width="90">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="分辨率" width="110">
            <template #default="{ row }">
              {{ row.width && row.height ? `${row.width}×${row.height}` : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="row.status !== 'READY'"
                @click="play(row)"
              >
                播放
              </el-button>
              <el-button v-if="row.taskId" link @click="openTask(row)">任务详情</el-button>
              <el-button v-if="!session.user?.guest" link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="mobile-list" aria-label="素材列表">
          <el-empty v-if="!page.items.length" description="这个空间还没有素材" />
          <article v-for="row in page.items" :key="row.mediaId" class="mobile-item media-item">
            <img v-if="row.status === 'READY'" :src="media.posterUrl(row.mediaId)" class="poster mobile-poster" alt="" />
            <div v-else class="poster placeholder mobile-poster">{{ row.status === 'PROCESSING' ? '处理中' : '无封面' }}</div>
            <div class="mobile-media-info">
              <h3 class="mobile-item-title">{{ row.title }}</h3>
              <el-tag :type="describeMediaState(row.status).type" size="small">{{ describeMediaState(row.status).label }}</el-tag>
              <div class="mobile-item-meta">{{ formatInstant(row.createdAt) }}</div>
              <div class="mobile-item-meta">{{ formatDuration(row.durationMs) }} · {{ row.width && row.height ? `${row.width}×${row.height}` : '分辨率待生成' }}</div>
            </div>
            <div class="mobile-item-actions">
              <el-button type="primary" plain :disabled="row.status !== 'READY'" @click="play(row)">播放</el-button>
              <el-button v-if="row.taskId" @click="openTask(row)">任务详情</el-button>
              <el-button v-if="!session.user?.guest" @click="startRename(row)">重命名</el-button>
              <el-button v-if="!session.user?.guest" type="danger" plain @click="remove(row)">删除</el-button>
            </div>
          </article>
        </div>

        <el-pagination
          v-if="page.total > page.pageSize"
          class="pager"
          layout="prev, pager, next"
          :current-page="page.page"
          :page-size="page.pageSize"
          :total="page.total"
          @current-change="
            (value) => {
              page.page = value
              load()
            }
          "
        />
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
}
.toolbar-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.space-select {
  width: 240px;
}
.search {
  width: 240px;
}
.gap {
  margin-top: 16px;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.poster {
  width: 96px;
  height: 54px;
  object-fit: cover;
  border-radius: 4px;
  display: block;
}
.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.title-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.media-item { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 12px; }
.mobile-media-info { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 6px; }
.mobile-poster { width: 96px; height: 72px; border-radius: 8px; }
.media-item .mobile-item-actions { grid-column: 1 / -1; margin-top: 0; }
@media (max-width: 420px) {
  .media-item { grid-template-columns: 80px minmax(0, 1fr); }
  .mobile-poster { width: 80px; height: 64px; }
}
</style>

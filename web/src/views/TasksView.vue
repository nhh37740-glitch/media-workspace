<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError, media, newIdempotencyKey, spaces, tasks } from '../api/client'
import { session } from '../stores/session'
import { describeError, describeTaskState, isTaskActive, isTaskRetryable } from '../utils/taskStates'
import { formatInstant, formatRelative } from '../utils/format'

/**
 * The task list.
 *
 * There is no endpoint that lists tasks across a space, so the list is derived from the media of
 * the selected space: each media carries its task id, and the task is read for its live state.
 * That is also why the state shown here can lag by one refresh interval, which the refresh notice
 * states rather than hiding.
 */
const router = useRouter()

const spaceList = ref([])
const spaceId = ref('')
const rows = reactive([])
const loading = ref(false)
const failure = ref('')
const lastRefreshed = ref(null)
let timer = null

const cancelingId = ref(null)
const retryingId = ref(null)

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
  // A polling refresh rather than the event stream: this view shows several tasks at once, and one
  // request per few seconds is cheaper than one stream per row. The detail page, which follows a
  // single task closely, uses the stream.
  timer = setInterval(() => {
    if (!loading.value) {
      load({ quiet: true })
    }
  }, 5000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
  }
})

async function load({ quiet = false } = {}) {
  if (!spaceId.value) {
    return
  }
  if (!quiet) {
    loading.value = true
  }
  failure.value = ''
  try {
    const listing = await media.list(spaceId.value, { pageSize: 100 })
    const withTasks = listing.items.filter((item) => item.taskId)
    const details = await Promise.all(
      withTasks.map(async (item) => {
        try {
          const task = await tasks.get(item.taskId)
          return { media: item, task }
        } catch {
          // A task that cannot be read is left out rather than shown as a broken row.
          return null
        }
      })
    )
    rows.splice(
      0,
      rows.length,
      ...details.filter(Boolean).map(({ media: item, task }) => ({
        mediaId: item.mediaId,
        title: item.title,
        taskId: task.taskId,
        state: task.state,
        progress: task.progress,
        generation: task.generation,
        attempt: task.attempt,
        errorCode: task.errorCode,
        updatedAt: task.updatedAt
      }))
    )
    lastRefreshed.value = new Date().toISOString()
  } catch (error) {
    failure.value = error.message
  } finally {
    loading.value = false
  }
}

async function cancel(row) {
  cancelingId.value = row.taskId
  try {
    await tasks.cancel(row.taskId)
    ElMessage.success('已请求取消，处理进程会在几秒内停止')
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.code === 'STATE_CONFLICT') {
      ElMessage.warning('任务已经是终态，不能取消')
    } else if (error instanceof ApiError && error.status === 403) {
      ElMessage.error('当前角色没有取消任务的权限')
    } else {
      ElMessage.error(`取消失败：${error.message}`)
    }
    await load()
  } finally {
    cancelingId.value = null
  }
}

async function retry(row) {
  retryingId.value = row.taskId
  try {
    // The key is generated once per attempt here, so a double click cannot consume two generations.
    await tasks.retry(row.taskId, newIdempotencyKey())
    ElMessage.success('已创建新的执行代次')
    await load()
  } catch (error) {
    ElMessage.error(`重试失败：${error.message}`)
    await load()
  } finally {
    retryingId.value = null
  }
}

function openDetail(row) {
  router.push({ name: 'task-detail', params: { taskId: row.taskId } })
}

const activeCount = computed(() => rows.filter((row) => isTaskActive(row.state)).length)
</script>

<template>
  <div class="page">
    <div class="page-intro">
      <div>
        <p class="page-eyebrow">PROCESSING / 实时进度</p>
        <h1 class="page-title">处理任务</h1>
        <p class="page-description">查看转码进度，处理失败和重试任务。</p>
      </div>
    </div>
    <el-card class="toolbar">
      <div class="toolbar-row">
        <el-select v-model="spaceId" class="space-select" placeholder="选择空间" @change="load">
          <el-option
            v-for="space in spaceList"
            :key="space.spaceId"
            :label="space.name"
            :value="space.spaceId"
          />
        </el-select>
        <el-button @click="load">立即刷新</el-button>
        <span class="refresh-note">
          进行中 {{ activeCount }} 项 · 每 5 秒自动刷新
          <template v-if="lastRefreshed">· 上次 {{ formatRelative(lastRefreshed) }}</template>
        </span>
      </div>
    </el-card>

    <el-alert v-if="failure" type="error" :closable="false" show-icon :title="failure" class="gap" />

    <el-card v-loading="loading" class="gap">
      <el-table :data="rows" empty-text="这个空间还没有处理任务" class="desktop-table">
        <el-table-column label="素材" min-width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">{{ row.title }}</el-button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="describeTaskState(row.state).type" size="small">
              {{ describeTaskState(row.state).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="row.progress"
              :status="row.state === 'FAILED' ? 'exception' : undefined"
              :stroke-width="12"
              :text-inside="true"
            />
            <div class="hint">执行第 {{ row.generation }} 代 · 第 {{ row.attempt }} 次尝试</div>
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="200">
          <template #default="{ row }">
            <template v-if="describeError(row.errorCode)">
              <div>{{ describeError(row.errorCode).text }}</div>
              <div class="hint">{{ describeError(row.errorCode).code }}</div>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatInstant(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button
              v-if="!session.user?.guest"
              link
              type="primary"
              :disabled="!isTaskActive(row.state)"
              :loading="cancelingId === row.taskId"
              @click="cancel(row)"
            >
              取消
            </el-button>
            <el-button
              v-if="!session.user?.guest"
              link
              type="warning"
              :disabled="!isTaskRetryable(row.state)"
              :loading="retryingId === row.taskId"
              @click="retry(row)"
            >
              重试
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mobile-list" aria-label="处理任务列表">
        <el-empty v-if="!rows.length" description="这个空间还没有处理任务" />
        <article v-for="row in rows" :key="row.taskId" class="mobile-item">
          <div class="task-mobile-heading">
            <h3 class="mobile-item-title">{{ row.title }}</h3>
            <el-tag :type="describeTaskState(row.state).type" size="small">{{ describeTaskState(row.state).label }}</el-tag>
          </div>
          <el-progress :percentage="row.progress" :status="row.state === 'FAILED' ? 'exception' : undefined" :stroke-width="8" class="mobile-progress" />
          <div class="mobile-item-meta">第 {{ row.generation }} 代 · 第 {{ row.attempt }} 次尝试 · {{ formatRelative(row.updatedAt) }}</div>
          <div v-if="describeError(row.errorCode)" class="mobile-error">{{ describeError(row.errorCode).text }}</div>
          <div class="mobile-item-actions">
            <el-button type="primary" plain @click="openDetail(row)">查看详情</el-button>
            <el-button v-if="!session.user?.guest" :disabled="!isTaskActive(row.state)" :loading="cancelingId === row.taskId" @click="cancel(row)">取消</el-button>
            <el-button v-if="!session.user?.guest" type="warning" plain :disabled="!isTaskRetryable(row.state)" :loading="retryingId === row.taskId" @click="retry(row)">重试</el-button>
          </div>
        </article>
      </div>
    </el-card>
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
.refresh-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.gap {
  margin-top: 16px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.task-mobile-heading { display: flex; align-items: start; justify-content: space-between; gap: 10px; }
.task-mobile-heading .el-tag { flex: none; }
.mobile-progress { margin: 14px 0 7px; }
.mobile-error { margin-top: 8px; padding: 9px 11px; border-radius: 8px; background: #fff5f3; color: #a54638; font-size: 12px; }
</style>

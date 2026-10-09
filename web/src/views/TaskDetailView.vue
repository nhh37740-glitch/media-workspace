<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError, media, newIdempotencyKey, tasks } from '../api/client'
import { session } from '../stores/session'
import { openEventStream } from '../api/eventStream'
import {
  describeCancelReason,
  describeError,
  describeTaskState,
  isTaskActive,
  isTaskRetryable
} from '../utils/taskStates'
import { formatInstant, formatRelative } from '../utils/format'

/**
 * One task in detail: state, execution history and the live event stream.
 *
 * The stream is used to keep the numbers moving, and the row is re-read whenever a terminal frame
 * arrives. That ordering matters: a frame is a hint, and treating it as the answer would let a
 * dropped connection look like a finished task.
 */
const route = useRoute()
const router = useRouter()

const taskId = computed(() => route.params.taskId)
const task = ref(null)
const attempts = ref({ items: [], total: 0 })
const mediaDetail = ref(null)
const loading = ref(true)
const failure = ref('')
const streamState = ref('idle')
const snapshotVersion = ref(0)
const detailColumns = ref(3)

function updateColumns() {
  detailColumns.value = window.innerWidth <= 760 ? 1 : window.innerWidth <= 1024 ? 2 : 3
}

let stream = null
let refreshTimer = null

onMounted(async () => {
  updateColumns()
  window.addEventListener('resize', updateColumns)
  await load()
  openStream()
})

onUnmounted(() => {
  window.removeEventListener('resize', updateColumns)
  closeStream()
  if (refreshTimer) {
    clearInterval(refreshTimer)
  }
})

async function load() {
  loading.value = true
  failure.value = ''
  try {
    task.value = await tasks.get(taskId.value)
    attempts.value = await tasks.attempts(taskId.value, { pageSize: 50 })
    if (task.value.mediaId) {
      try {
        mediaDetail.value = await media.detail(task.value.mediaId)
      } catch {
        mediaDetail.value = null
      }
    }
  } catch (error) {
    failure.value =
      error instanceof ApiError && error.status === 404
        ? '任务不存在，或当前账号无权查看'
        : error.message
  } finally {
    loading.value = false
  }
}

/**
 * Opens the event stream.
 *
 * A dropped stream is not a task failure. The interface says the connection was lost and keeps
 * polling, because the authoritative state is one request away and losing the connection changes
 * nothing about the task.
 */
function openStream() {
  closeStream()
  streamState.value = 'connecting'
  stream = openEventStream(
    tasks.eventsUrl(taskId.value),
    (frame) => {
      if (frame.event !== 'snapshot' && frame.event !== 'state') {
        return
      }
      let parsed
      try {
        parsed = JSON.parse(frame.data)
      } catch {
        return // an unparsable frame is ignored; the next poll corrects anything it missed
      }
      // Frames carry the task's row version as the event id, so an older one must not overwrite a
      // newer state. That is what makes reconnection safe.
      if (parsed.version < snapshotVersion.value) {
        return
      }
      snapshotVersion.value = parsed.version
      if (task.value) {
        task.value = {
          ...task.value,
          state: parsed.state,
          progress: parsed.progress,
          version: parsed.version
        }
      }
      if (!isTaskActive(parsed.state)) {
        // Confirm with the authoritative endpoint before showing a terminal state.
        load()
        closeStream()
      }
    },
    (state) => {
      if (state.status === 'open') {
        streamState.value = 'open'
        return
      }
      streamState.value = state.status === 'closed' ? 'lost' : 'unavailable'
      startFallbackPolling()
    }
  )
}

function closeStream() {
  if (stream) {
    stream.close()
    stream = null
  }
}

function startFallbackPolling() {
  if (refreshTimer) {
    return
  }
  refreshTimer = setInterval(() => {
    if (task.value && !isTaskActive(task.value.state)) {
      clearInterval(refreshTimer)
      refreshTimer = null
      return
    }
    load()
  }, 5000)
}

async function cancel() {
  try {
    await tasks.cancel(taskId.value)
    ElMessage.success('已请求取消')
    await load()
  } catch (error) {
    ElMessage.error(`取消失败：${error.message}`)
  }
}

async function retry() {
  try {
    await tasks.retry(taskId.value, newIdempotencyKey())
    ElMessage.success('已创建新的执行代次，等待事件重新入队')
    await load()
    openStream()
  } catch (error) {
    ElMessage.error(`重试失败：${error.message}`)
  }
}

const streamHint = computed(() => {
  switch (streamState.value) {
    case 'open':
      return '事件流已连接'
    case 'connecting':
      return '正在连接事件流…'
    case 'lost':
      return '事件流已断开，正在每 5 秒轮询服务器。断线不代表任务失败'
    case 'unavailable':
      return '浏览器不支持事件流，正在轮询服务器'
    default:
      return ''
  }
})

const errorInfo = computed(() => describeError(task.value?.errorCode))
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-intro">
      <div>
        <p class="page-eyebrow">PROCESSING / 任务追踪</p>
        <h1 class="page-title">任务详情</h1>
        <p class="page-description">状态与执行历史以服务器记录为准。</p>
      </div>
    </div>
    <el-page-header content="返回任务列表" @back="router.back()" />

    <el-alert v-if="failure" type="error" :closable="false" show-icon :title="failure" class="gap" />

    <template v-if="task">
      <el-card class="gap">
        <el-descriptions :column="detailColumns" border>
          <el-descriptions-item label="状态">
            <el-tag :type="describeTaskState(task.state).type">
              {{ describeTaskState(task.state).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="执行代次">第 {{ task.generation }} 代</el-descriptions-item>
          <el-descriptions-item label="本代尝试次数">{{ task.attempt }} / 3</el-descriptions-item>
          <el-descriptions-item label="进度">
            <el-progress :percentage="task.progress" :stroke-width="10" />
          </el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ formatInstant(task.updatedAt) }}（{{ formatRelative(task.updatedAt) }}）
          </el-descriptions-item>
          <el-descriptions-item label="任务编号">
            <code class="id">{{ task.taskId }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="素材编号">
            <code class="id">{{ task.mediaId }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="素材标题">
            {{ mediaDetail?.title ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="素材状态">
            <el-tag v-if="mediaDetail" size="small">
              {{ mediaDetail.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="errorInfo" label="失败原因" :span="detailColumns">
            <div>{{ errorInfo.text }}</div>
            <div class="hint">错误码：{{ errorInfo.code }}</div>
          </el-descriptions-item>
        </el-descriptions>

        <div class="actions">
          <el-button v-if="!session.user?.guest" :disabled="!isTaskActive(task.state)" @click="cancel">取消任务</el-button>
          <el-button v-if="!session.user?.guest" :disabled="!isTaskRetryable(task.state)" type="warning" @click="retry">
            重试
          </el-button>
          <el-button @click="load">刷新</el-button>
          <span class="hint">{{ streamHint }}</span>
        </div>
      </el-card>

      <el-card class="gap">
        <template #header>
          <div class="header">
            <span>执行历史</span>
            <span class="hint">共 {{ attempts.total }} 次</span>
          </div>
        </template>
        <el-table :data="attempts.items" empty-text="还没有执行记录" class="desktop-table">
          <el-table-column label="代次" width="80">
            <template #default="{ row }">第 {{ row.generation }} 代</template>
          </el-table-column>
          <el-table-column label="第几次" width="90">
            <template #default="{ row }">第 {{ row.attempt }} 次</template>
          </el-table-column>
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.state === 'SUCCEEDED' ? 'success' : row.state === 'LOST' ? 'warning' : 'danger'">
                {{ row.state }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="开始" width="180">
            <template #default="{ row }">{{ formatInstant(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="结束" width="180">
            <template #default="{ row }">{{ formatInstant(row.finishedAt) }}</template>
          </el-table-column>
          <el-table-column label="错误摘要" min-width="240">
            <template #default="{ row }">
              <div v-if="row.errorCode" class="hint">错误码：{{ row.errorCode }}</div>
              <div class="summary">{{ row.errorSummary ?? '—' }}</div>
            </template>
          </el-table-column>
        </el-table>
        <div class="mobile-list" aria-label="执行历史">
          <el-empty v-if="!attempts.items.length" description="还没有执行记录" />
          <article v-for="row in attempts.items" :key="`${row.generation}-${row.attempt}`" class="mobile-item">
            <div class="attempt-heading">
              <h3 class="mobile-item-title">第 {{ row.generation }} 代 · 第 {{ row.attempt }} 次</h3>
              <el-tag size="small" :type="row.state === 'SUCCEEDED' ? 'success' : row.state === 'LOST' ? 'warning' : 'danger'">{{ row.state }}</el-tag>
            </div>
            <div class="mobile-item-meta">开始：{{ formatInstant(row.startedAt) }}</div>
            <div class="mobile-item-meta">结束：{{ formatInstant(row.finishedAt) }}</div>
            <div v-if="row.errorCode" class="mobile-item-meta">错误码：{{ row.errorCode }}</div>
            <div v-if="row.errorSummary" class="mobile-item-meta attempt-summary">{{ row.errorSummary }}</div>
          </article>
        </div>
        <div class="hint note">
          执行历史来自服务器的结构化记录。普通账号看不到服务器路径与原始堆栈，这些内容只保留在服务端日志中。
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.gap {
  margin-top: 16px;
}
.actions {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.id {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.summary {
  font-size: 12px;
  word-break: break-word;
}
.note {
  margin-top: 12px;
}
.attempt-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.attempt-heading .el-tag { flex: none; }
.attempt-summary { overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .actions { align-items: stretch; }
  .actions .hint { flex-basis: 100%; }
  .page :deep(.el-descriptions__label) { min-width: 92px; }
  .page :deep(.el-card__body) { min-width: 0; }
}
</style>

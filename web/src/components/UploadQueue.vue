<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadFile, abortUpload } from '../api/uploader'
import { describeUploadState } from '../utils/taskStates'
import { formatBytes } from '../utils/format'

/**
 * The upload queue.
 *
 * The user selects files and presses upload once per batch; everything else - hashing, slicing,
 * sending, retrying a failed chunk, asking for the merge - happens here without further clicks.
 *
 * A file that fails is reported with the reason the server gave and can be retried or removed. A
 * successful upload is reported as "已提交处理", never as "完成": the upload finishing starts a
 * task, and the task is what decides whether the media becomes playable.
 */
const props = defineProps({
  spaceId: { type: String, required: true }
})
const emit = defineEmits(['uploaded'])

const entries = ref([])
const selected = ref([])
const busy = ref(false)
let nextKey = 1

const phaseLabels = {
  queued: '排队中',
  hashing: '计算校验值',
  creating: '创建上传会话',
  uploading: '上传分片',
  finalizing: '请求合并',
  done: '已提交处理',
  failed: '失败',
  aborted: '已取消'
}

const pendingCount = computed(() => entries.value.filter((entry) => entry.phase === 'queued').length)

function addFiles(files) {
  for (const file of files) {
    entries.value.push({
      key: nextKey++,
      file,
      name: file.name,
      size: file.size,
      phase: 'queued',
      percent: 0,
      message: '',
      uploadId: null,
      controller: null
    })
  }
}

function onFileChange(file, fileList) {
  // Element Plus keeps its own list; only the newly added file is taken from it.
  addFiles([file.raw])
}

function removeEntry(entry) {
  entries.value = entries.value.filter((candidate) => candidate.key !== entry.key)
}

function clearFinished() {
  entries.value = entries.value.filter((entry) => entry.phase !== 'done')
}

async function startOne(entry) {
  entry.phase = 'hashing'
  entry.message = ''
  entry.controller = new AbortController()
  try {
    const result = await uploadFile(props.spaceId, entry.file, {
      signal: entry.controller.signal,
      onProgress: (state) => {
        entry.phase = state.phase
        entry.percent = state.percent ?? entry.percent
      }
    })
    entry.uploadId = result.uploadId
    entry.phase = 'done'
    entry.percent = 100
    entry.message = '已提交处理，可在“处理任务”中查看进度'
    emit('uploaded', result)
  } catch (error) {
    if (error.name === 'AbortError') {
      entry.phase = 'aborted'
      entry.message = '已取消'
      return
    }
    entry.phase = 'failed'
    entry.message = error.message || '上传失败'
  } finally {
    entry.controller = null
  }
}

async function startAll() {
  busy.value = true
  try {
    for (const entry of entries.value) {
      if (entry.phase === 'queued') {
        await startOne(entry)
      }
    }
  } finally {
    busy.value = false
  }
}

async function retryOne(entry) {
  if (entry.uploadId) {
    // The session exists, so the merge is asked for again rather than re-uploading the chunks.
    entry.phase = 'finalizing'
    entry.message = ''
    try {
      await uploadFile(props.spaceId, entry.file, { onProgress: () => {} })
      entry.phase = 'done'
      entry.message = '已提交处理'
      emit('uploaded', { uploadId: entry.uploadId, mediaId: null, taskId: null })
    } catch (error) {
      entry.phase = 'failed'
      entry.message = error.message || '仍然失败'
    }
    return
  }
  await startOne(entry)
}

async function cancelOne(entry) {
  if (entry.phase === 'queued' || entry.phase === 'aborted') {
    removeEntry(entry)
    return
  }
  if (entry.controller) {
    entry.controller.abort()
    return
  }
  if (entry.uploadId) {
    try {
      await abortUpload(entry.uploadId)
      entry.phase = 'aborted'
      entry.message = '已终止，配额已释放'
    } catch (error) {
      ElMessage.warning(`终止失败：${error.message}`)
    }
    return
  }
  removeEntry(entry)
}

function statusType(entry) {
  if (entry.phase === 'failed') return 'exception'
  if (entry.phase === 'done') return 'success'
  if (entry.phase === 'aborted') return 'warning'
  return undefined
}

function stateTag(entry) {
  return describeUploadState(
    entry.phase === 'done' ? 'FINALIZING' : entry.phase === 'queued' ? 'OPEN' : null
  )
}
</script>

<template>
  <el-card class="upload-card">
    <template #header>
      <div class="header">
        <div><span class="upload-title">上传素材</span><div class="upload-subtitle">新文件将进入处理队列</div></div>
        <div class="actions">
          <el-button
            size="small"
            :disabled="pendingCount === 0 || busy"
            :loading="busy"
            type="primary"
            @click="startAll"
          >
            开始上传
          </el-button>
          <el-button size="small" :disabled="busy" @click="clearFinished">清除已完成</el-button>
        </div>
      </div>
    </template>

    <el-upload
      drag
      multiple
      :auto-upload="false"
      :show-file-list="false"
      :disabled="busy"
      @change="onFileChange"
    >
      <div class="drop-zone"><span class="drop-icon" aria-hidden="true">↑</span><strong>把视频拖到这里，或点击选择文件</strong><span>选择后可在下方查看每个文件的进度</span></div>
      <template #tip>
        <div class="tip">
          支持 MP4/MOV/MKV。选择后点击“开始上传”，分片、重试与合并由浏览器自动完成。
        </div>
      </template>
    </el-upload>

    <el-table v-if="entries.length" :data="entries" size="small" class="queue desktop-table">
      <el-table-column prop="name" label="文件" min-width="180" show-overflow-tooltip />
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatBytes(row.size) }}</template>
      </el-table-column>
      <el-table-column label="进度" min-width="220">
        <template #default="{ row }">
          <el-progress
            :percentage="row.percent"
            :status="statusType(row)"
            :stroke-width="12"
            :text-inside="true"
          />
          <div class="phase">{{ phaseLabels[row.phase] ?? row.phase }}</div>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="180">
        <template #default="{ row }">
          <span class="message">{{ row.message }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button v-if="row.phase === 'failed'" link type="primary" @click="retryOne(row)">
            重试
          </el-button>
          <el-button
            v-if="row.phase !== 'done'"
            link
            type="danger"
            @click="cancelOne(row)"
          >
            {{ row.phase === 'uploading' ? '停止' : '移除' }}
          </el-button>
          <el-button v-else link @click="removeEntry(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="entries.length" class="mobile-list queue" aria-label="上传队列">
      <article v-for="entry in entries" :key="entry.key" class="mobile-item">
        <h3 class="mobile-item-title">{{ entry.name }}</h3>
        <div class="mobile-item-meta">{{ formatBytes(entry.size) }} · {{ phaseLabels[entry.phase] ?? entry.phase }}</div>
        <el-progress :percentage="entry.percent" :status="statusType(entry)" :stroke-width="8" class="upload-progress" />
        <div v-if="entry.message" class="mobile-item-meta">{{ entry.message }}</div>
        <div class="mobile-item-actions">
          <el-button v-if="entry.phase === 'failed'" type="primary" plain @click="retryOne(entry)">重试</el-button>
          <el-button v-if="entry.phase !== 'done'" type="danger" plain @click="cancelOne(entry)">{{ entry.phase === 'uploading' ? '停止' : '移除' }}</el-button>
          <el-button v-else @click="removeEntry(entry)">移除</el-button>
        </div>
      </article>
    </div>

    <el-alert
      v-if="!entries.length"
      type="info"
      :closable="false"
      title="还没有待上传的文件"
      description="上传完成后素材会先进入处理队列，转码成功后才可播放。"
      class="empty"
    />
  </el-card>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.upload-title { font-size: 16px; font-weight: 720; }
.upload-subtitle { margin-top: 5px; color: #71869a; font-size: 12px; font-weight: 400; }
.actions {
  display: flex;
  gap: 8px;
}
.drop-zone {
  min-height: 150px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 22px;
  color: #587084;
}
.drop-zone strong { color: #284965; font-size: 14px; }
.drop-zone span:last-child { font-size: 12px; }
.drop-icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 10px; background: #e7f4f5; color: #167d91; font-size: 23px; line-height: 1; }
.upload-card :deep(.el-upload-dragger) { border: 1.5px dashed #a8cdd3; border-radius: 12px; background: #f7fbfc; }
.upload-card :deep(.el-upload-dragger:hover) { border-color: #167d91; }
.tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.queue {
  margin-top: 16px;
}
.phase {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.message {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.empty {
  margin-top: 12px;
}
.upload-progress { margin: 12px 0 6px; }
@media (max-width: 760px) {
  .header { align-items: flex-start; gap: 12px; flex-wrap: wrap; }
  .actions { width: 100%; }
  .actions .el-button { flex: 1; min-height: 42px; margin: 0; }
  .drop-zone { min-height: 132px; padding: 14px; text-align: center; }
  .tip { line-height: 1.6; }
}
</style>

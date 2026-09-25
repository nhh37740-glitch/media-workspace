/**
 * Presentation of the server's task and media states.
 *
 * The server sends stable English identifiers; the Chinese text lives here and nowhere else, so a
 * wording change never touches the API. An unknown identifier is shown as-is rather than hidden:
 * a state this build does not know about is information, not something to swallow.
 */

export const TASK_STATES = {
  WAITING_EVENT: { label: '等待事件', type: 'info', active: true },
  QUEUED: { label: '排队中', type: 'info', active: true },
  RUNNING: { label: '处理中', type: 'primary', active: true },
  RETRY_WAIT: { label: '等待重试', type: 'warning', active: true },
  SUCCEEDED: { label: '已完成', type: 'success', active: false },
  FAILED: { label: '失败', type: 'danger', active: false },
  CANCELLED: { label: '已取消', type: 'info', active: false }
}

export const MEDIA_STATES = {
  PROCESSING: { label: '处理中', type: 'primary' },
  READY: { label: '可播放', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' }
}

export const UPLOAD_STATES = {
  OPEN: { label: '上传中', type: 'info', active: true },
  FINALIZING: { label: '合并中', type: 'primary', active: true },
  COMPLETED: { label: '已完成', type: 'success', active: false },
  FAILED: { label: '失败', type: 'danger', active: false },
  EXPIRED: { label: '已过期', type: 'warning', active: false },
  ABORTED: { label: '已终止', type: 'info', active: false }
}

/** Failure codes the user is shown. The code itself is always displayed alongside the text. */
export const ERROR_CODES = {
  INVALID_MEDIA: '文件不是可识别的视频，或已损坏',
  UNSUPPORTED_MEDIA: '文件里没有视频轨道',
  SOURCE_MISSING: '原始文件已不可用',
  PROCESS_START_FAILED: '转码程序无法启动',
  PROCESS_TIMEOUT: '处理超时',
  DISK_FULL: '存储空间不足',
  WORKER_LOST: '处理进程中断，可重试',
  HASH_MISMATCH: '文件校验值不一致',
  OUTPUT_INVALID: '转码结果未通过校验',
  STALE_EXECUTION: '本次执行已被更新的执行取代',
  INTERNAL_ERROR: '服务器内部错误'
}

export const CANCEL_REASONS = {
  USER_REQUEST: '用户取消',
  MEDIA_DELETED: '素材已删除'
}

function describe(table, value, fallbackLabel) {
  if (!value) {
    return { label: fallbackLabel ?? '—', type: 'info', raw: value }
  }
  const entry = table[value]
  return entry ? { ...entry, raw: value } : { label: value, type: 'info', raw: value }
}

export function describeTaskState(state) {
  return describe(TASK_STATES, state)
}

export function describeMediaState(state) {
  return describe(MEDIA_STATES, state)
}

export function describeUploadState(state) {
  return describe(UPLOAD_STATES, state)
}

/** Whether a task in this state can still be cancelled. */
export function isTaskActive(state) {
  return TASK_STATES[state]?.active === true
}

/** Whether a task in this state can be retried. */
export function isTaskRetryable(state) {
  return state === 'FAILED' || state === 'CANCELLED'
}

export function describeError(code) {
  if (!code) {
    return null
  }
  return { code, text: ERROR_CODES[code] ?? '未知错误' }
}

export function describeCancelReason(reason) {
  if (!reason) {
    return null
  }
  return CANCEL_REASONS[reason] ?? reason
}

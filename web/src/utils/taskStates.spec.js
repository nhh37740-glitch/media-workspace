import { describe, expect, it } from 'vitest'
import {
  describeCancelReason,
  describeError,
  describeMediaState,
  describeTaskState,
  describeUploadState,
  isTaskActive,
  isTaskRetryable
} from './taskStates'

describe('task state presentation', () => {
  it('translates every state the contract defines', () => {
    expect(describeTaskState('WAITING_EVENT').label).toBe('等待事件')
    expect(describeTaskState('QUEUED').label).toBe('排队中')
    expect(describeTaskState('RUNNING').label).toBe('处理中')
    expect(describeTaskState('RETRY_WAIT').label).toBe('等待重试')
    expect(describeTaskState('SUCCEEDED').label).toBe('已完成')
    expect(describeTaskState('FAILED').label).toBe('失败')
    expect(describeTaskState('CANCELLED').label).toBe('已取消')
  })

  it('shows an unknown state as itself rather than hiding it', () => {
    // A state a build does not know about is information. Rendering it as "unknown" would hide the
    // fact that the server is ahead of the client.
    const described = describeTaskState('PAUSED_FOR_REVIEW')
    expect(described.label).toBe('PAUSED_FOR_REVIEW')
    expect(described.raw).toBe('PAUSED_FOR_REVIEW')
  })

  it('treats only the four non-terminal states as active', () => {
    expect(isTaskActive('WAITING_EVENT')).toBe(true)
    expect(isTaskActive('QUEUED')).toBe(true)
    expect(isTaskActive('RUNNING')).toBe(true)
    expect(isTaskActive('RETRY_WAIT')).toBe(true)
    expect(isTaskActive('SUCCEEDED')).toBe(false)
    expect(isTaskActive('FAILED')).toBe(false)
    expect(isTaskActive('CANCELLED')).toBe(false)
  })

  it('offers a retry only for the two states the server accepts', () => {
    expect(isTaskRetryable('FAILED')).toBe(true)
    expect(isTaskRetryable('CANCELLED')).toBe(true)
    expect(isTaskRetryable('SUCCEEDED')).toBe(false)
    expect(isTaskRetryable('RUNNING')).toBe(false)
  })

  it('maps media and upload states with their own labels', () => {
    expect(describeMediaState('READY').label).toBe('可播放')
    expect(describeMediaState('PROCESSING').label).toBe('处理中')
    expect(describeUploadState('FINALIZING').label).toBe('合并中')
    expect(describeUploadState('EXPIRED').label).toBe('已过期')
  })

  it('always shows a failure code next to its explanation', () => {
    const described = describeError('HASH_MISMATCH')
    expect(described.code).toBe('HASH_MISMATCH')
    expect(described.text).toContain('校验')
    // An unrecognised code still shows the code, so a report can quote it.
    expect(describeError('SOMETHING_NEW').code).toBe('SOMETHING_NEW')
    expect(describeError(null)).toBeNull()
  })

  it('explains why a task was cancelled', () => {
    expect(describeCancelReason('USER_REQUEST')).toBe('用户取消')
    expect(describeCancelReason('MEDIA_DELETED')).toBe('素材已删除')
  })
})

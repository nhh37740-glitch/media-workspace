<script setup>
import { computed, onMounted, ref } from 'vue'
import { share } from '../api/client'
import { formatDuration } from '../utils/format'

/**
 * The page a share link leads to.
 *
 * The token arrives in the URL fragment and is exchanged once for a session cookie, after which the
 * fragment is cleared with `history.replaceState`. That ordering matters: the fragment is never
 * sent to the server, so the token cannot appear in an access log, and clearing it removes it from
 * the address bar and from the browser's session history.
 *
 * What a visitor can do here is play one media. There is no navigation, no space listing and no
 * media detail: the server does not grant those to a share session, and this page does not pretend
 * otherwise.
 */
const state = ref('loading')
const failure = ref('')
const description = ref(null)
const videoUrl = computed(() => (state.value === 'ready' ? share.contentUrl() : ''))

function readToken() {
  // Both shapes are accepted because the fragment is used by the router as well:
  //   #/share#token=...   and   #token=...
  const hash = window.location.hash
  const match = hash.match(/token=([A-Za-z0-9_-]+)/)
  return match ? match[1] : null
}

function clearTokenFromUrl() {
  const clean = `${window.location.pathname}#/share`
  window.history.replaceState(null, '', clean)
}

onMounted(async () => {
  const token = readToken()
  if (!token) {
    state.value = 'missing'
    return
  }
  try {
    // The exchange requires a CSRF token, which is issued without a session, so a visitor with no
    // account can still complete it.
    description.value = await share.redeem(token)
    clearTokenFromUrl()
    state.value = 'ready'
  } catch (error) {
    clearTokenFromUrl()
    state.value = 'rejected'
    failure.value = error.message
  }
})
</script>

<template>
  <div class="share-page">
    <el-card class="share-card">
      <template #header>
        <div class="title">分享的素材</div>
      </template>

      <el-skeleton v-if="state === 'loading'" :rows="4" animated />

      <el-result
        v-else-if="state === 'missing'"
        icon="warning"
        title="链接不完整"
        sub-title="这个地址里没有分享令牌。请使用创建者发来的完整链接。"
      />

      <el-result
        v-else-if="state === 'rejected'"
        icon="error"
        title="无法访问"
        sub-title="分享可能已过期、已被撤销，或素材已被删除。"
      />

      <template v-else-if="state === 'ready'">
        <div class="meta">
          <div class="media-title">{{ description?.title }}</div>
          <div class="hint">时长 {{ formatDuration(description?.durationMs) }}</div>
          <div class="hint">链接到期：{{ description?.expiresAt }}</div>
        </div>
        <video class="player" :src="videoUrl" controls preload="metadata" />
        <div class="hint note">
          这是一个只读的播放页面。分享不会授予空间列表、原始文件或其他素材的访问权限。
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.share-page {
  display: flex;
  justify-content: center;
  padding: 5vh 16px;
}
.share-card {
  width: 100%;
  max-width: 860px;
}
.title {
  font-weight: 600;
}
.meta {
  margin-bottom: 12px;
}
.media-title {
  font-size: 16px;
  margin-bottom: 4px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.player {
  width: 100%;
  background: #000;
  border-radius: 4px;
}
.note {
  margin-top: 12px;
}
</style>

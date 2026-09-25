<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ensureIdentity, session, signOut } from './stores/session'

const route = useRoute()
const router = useRouter()
const checking = ref(true)
const offline = ref(false)

// The shell is only shown for authenticated pages. The share landing page is deliberately outside
// it: a visitor arriving through a link has no account and must not be shown a workspace menu.
const isPublic = computed(() => route.meta.public === true)
const user = computed(() => session.user)

onMounted(async () => {
  try {
    await ensureIdentity()
  } catch {
    // A failed check is a connection problem, not a signed-out state, and the two are shown
    // differently: one asks the user to retry, the other asks them to sign in.
    offline.value = true
  } finally {
    checking.value = false
  }
  if (!isPublic.value && !session.user) {
    router.replace({ name: 'login' })
  }
})

async function handleSignOut() {
  try {
    await signOut()
    ElMessage.success('已退出登录')
  } finally {
    router.replace({ name: 'login' })
  }
}
</script>

<template>
  <el-container v-if="!checking" class="app-shell">
    <el-header v-if="!isPublic" class="app-header">
      <div class="brand">团队影音素材平台</div>
      <el-menu mode="horizontal" :default-active="route.name" :ellipsis="false" router>
        <el-menu-item index="media" :route="{ name: 'media' }">素材库</el-menu-item>
        <el-menu-item index="tasks" :route="{ name: 'tasks' }">处理任务</el-menu-item>
        <el-menu-item index="workspace" :route="{ name: 'workspace' }">共享空间</el-menu-item>
      </el-menu>
      <div class="account">
        <span v-if="user" class="username">{{ user.username }}</span>
        <el-button link type="primary" @click="handleSignOut">退出</el-button>
      </div>
    </el-header>

    <el-main>
      <el-alert
        v-if="offline"
        type="warning"
        :closable="false"
        show-icon
        title="无法连接服务器"
        description="界面未能确认登录状态。请检查网络或稍后重试；任务进度以服务器状态为准，断线不代表任务失败。"
      />
      <router-view v-else />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}
.brand {
  font-weight: 600;
  font-size: 16px;
  white-space: nowrap;
}
.account {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.username {
  color: var(--el-text-color-secondary);
}
</style>

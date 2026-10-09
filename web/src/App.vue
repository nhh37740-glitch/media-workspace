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
  if (isPublic.value) {
    checking.value = false
    return
  }
  try {
    await ensureIdentity()
  } catch {
    // A failed check is a connection problem, not a signed-out state, and the two are shown
    // differently: one asks the user to retry, the other asks them to sign in.
    offline.value = true
  } finally {
    checking.value = false
  }
  if (!offline.value && !isPublic.value && !session.user) {
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
      <div class="brand"><span class="brand-mark" aria-hidden="true">M</span><span>团队影音素材平台</span></div>
      <el-menu class="primary-nav" mode="horizontal" :default-active="route.name" :ellipsis="false" router aria-label="主导航">
        <el-menu-item index="media" :route="{ name: 'media' }">素材库</el-menu-item>
        <el-menu-item index="tasks" :route="{ name: 'tasks' }">处理任务</el-menu-item>
        <el-menu-item index="workspace" :route="{ name: 'workspace' }">共享空间</el-menu-item>
      </el-menu>
      <div class="account">
        <span v-if="user" class="username">{{ user.guest ? '游客 · 只读' : user.username }}</span>
        <el-button v-if="user?.guest" link type="primary" @click="router.push({ name: 'login' })">账号登录</el-button>
        <el-button class="sign-out" link type="primary" @click="handleSignOut">退出</el-button>
      </div>
    </el-header>

    <el-main>
      <el-alert
        v-if="offline && !isPublic"
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
  min-height: 100dvh;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 32px;
  height: 76px;
  padding: 0 clamp(18px, 4vw, 64px);
  border-bottom: 1px solid #e4eaf1;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 4px 20px rgba(19, 42, 67, .035);
}
.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  font-weight: 750;
  font-size: 17px;
  letter-spacing: -.02em;
  color: #183653;
  white-space: nowrap;
}
.brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 11px;
  background: linear-gradient(145deg, #1c9caa, #1b6388);
  color: white;
  font-size: 20px;
  font-weight: 800;
}
.primary-nav { flex: 1; min-width: 0; border-bottom: 0; background: transparent; }
.account {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.username {
  color: #5d7084;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
}
@media (max-width: 760px) {
  .app-header {
    height: auto;
    min-height: 112px;
    padding: 12px 16px 0;
    gap: 0;
    flex-wrap: wrap;
  }
  .brand { font-size: 15px; max-width: calc(100% - 86px); }
  .brand-mark { width: 30px; height: 30px; border-radius: 9px; }
  .account { margin-left: auto; gap: 4px; }
  .username { display: none; }
  .primary-nav { order: 3; flex: 0 0 100%; }
  .primary-nav :deep(.el-menu-item) { min-width: 0; flex: 1; justify-content: center; height: 56px; padding: 0 8px; font-size: 14px; }
}
</style>

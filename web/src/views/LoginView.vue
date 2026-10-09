<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../api/client'
import { signIn, browseAsGuest } from '../stores/session'

const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const failure = ref('')

async function guest() {
  busy.value = true
  failure.value = ''
  try {
    await browseAsGuest()
    router.replace({ name: 'media' })
  } catch (error) {
    failure.value = error instanceof ApiError && error.status === 503
      ? '游客浏览暂未开放，请使用已有账号登录。'
      : `暂时无法进入游客浏览：${error.message ?? '请稍后重试'}`
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (!username.value || !password.value) {
    failure.value = '请输入用户名和密码'
    return
  }
  busy.value = true
  failure.value = ''
  try {
    await signIn(username.value, password.value)
    ElMessage.success('登录成功')
    router.replace({ name: 'media' })
  } catch (error) {
    // The server does not distinguish an unknown user from a wrong password, and neither does this
    // message: telling them apart would let anyone enumerate accounts.
    failure.value =
      error instanceof ApiError && error.status === 401
        ? '用户名或密码不正确'
        : `登录失败：${error.message ?? '未知错误'}`
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-intro">
      <div class="login-mark" aria-hidden="true">M</div>
      <p class="page-eyebrow">MEDIA WORKSPACE</p>
      <h1>让团队素材，始终井然有序。</h1>
      <p>上传、处理、共享影音内容。所有任务状态和访问权限均由服务器确认。</p>
    </div>
    <el-card class="login-card">
      <template #header>
        <div class="title">浏览演示素材</div>
        <div class="subtitle">游客可查看和播放演示视频；管理素材请使用已有账号登录。</div>
      </template>
      <el-button type="primary" :loading="busy" class="submit guest-entry" @click="guest">游客浏览</el-button>
      <p class="guest-hint">无需账号密码 · 仅限只读浏览</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="username" autocomplete="username" placeholder="owner" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            autocomplete="current-password"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-alert
          v-if="failure"
          type="error"
          :closable="false"
          show-icon
          :title="failure"
          class="failure"
        />
        <el-button :loading="busy" class="submit" @click="submit">账号登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: calc(100dvh - 80px);
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 440px);
  align-items: center;
  gap: clamp(28px, 7vw, 110px);
  max-width: 1080px;
  margin: 0 auto;
}
.login-intro { padding: 24px 0; }
.login-mark { display: grid; place-items: center; width: 58px; height: 58px; margin-bottom: 28px; border-radius: 18px; background: linear-gradient(145deg, #1c9caa, #1b6388); color: white; font-size: 35px; font-weight: 800; box-shadow: 0 14px 30px rgba(24, 113, 137, .2); }
.login-intro h1 { max-width: 550px; margin: 0; color: #183653; font-size: clamp(36px, 4vw, 54px); line-height: 1.16; letter-spacing: -.05em; }
.login-intro > p:last-child { max-width: 450px; margin: 18px 0 0; color: #698095; font-size: 16px; line-height: 1.8; }
.login-card {
  width: 100%;
  box-shadow: 0 24px 56px rgba(22, 60, 91, .1);
}
.title {
  font-size: 23px;
  font-weight: 750;
}
.subtitle {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.submit {
  width: 100%;
  min-height: 44px;
}
.failure {
  margin-bottom: 12px;
}
.guest-hint { margin: 10px 0 22px; color: var(--el-text-color-secondary); font-size: 12px; text-align: center; }
@media (max-width: 760px) {
  .login-page { min-height: 0; grid-template-columns: 1fr; gap: 18px; padding: 20px 0; }
  .login-intro { padding: 4px 8px; }
  .login-mark { width: 46px; height: 46px; margin-bottom: 18px; border-radius: 14px; font-size: 28px; }
  .login-intro h1 { font-size: 30px; }
  .login-intro > p:last-child { margin-top: 10px; font-size: 13px; }
}
</style>

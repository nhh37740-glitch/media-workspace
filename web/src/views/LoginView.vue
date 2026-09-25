<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '../api/client'
import { signIn } from '../stores/session'

const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const failure = ref('')

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
    <el-card class="login-card">
      <template #header>
        <div class="title">团队影音素材平台</div>
        <div class="subtitle">使用已有账号登录，账号由管理员通过命令行创建</div>
      </template>
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
        <el-button type="primary" :loading="busy" class="submit" @click="submit">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  justify-content: center;
  padding-top: 10vh;
}
.login-card {
  width: 380px;
}
.title {
  font-size: 18px;
  font-weight: 600;
}
.subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.submit {
  width: 100%;
}
.failure {
  margin-bottom: 12px;
}
</style>

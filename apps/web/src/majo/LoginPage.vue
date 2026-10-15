<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiRequest } from './api'

const router = useRouter()
const username = ref('')
const password = ref('')
const hasUsers = ref(true)
const loading = ref(false)
const error = ref('')

onMounted(async () => {
  try {
    const status = await apiRequest<{ enabled: boolean; has_users: boolean }>('/auth/status')
    hasUsers.value = status.has_users
    if (!status.enabled) await router.replace('/chat')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not check authentication.'
  }
})

async function submit() {
  loading.value = true
  error.value = ''
  try {
    const result = await apiRequest<{ token: string }>(
      hasUsers.value ? '/auth/login' : '/auth/register',
      {
        method: 'POST',
        body: JSON.stringify({ username: username.value, password: password.value }),
      },
    )
    window.localStorage.setItem('majo_auth_token', result.token)
    const target = new URLSearchParams(window.location.search).get('redirect') || '/chat'
    await router.replace(target.startsWith('/') && !target.startsWith('//') ? target : '/chat')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Authentication failed.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-screen">
    <form class="login-card" @submit.prevent="submit">
      <div class="brand-mark">✳ <span>majo</span></div>
      <h1>{{ hasUsers ? 'Sign in' : 'Create your account' }}</h1>
      <p>Continue to your AI coding workspace.</p>
      <label>Username <input v-model="username" autocomplete="username" required /></label>
      <label
        >Password
        <input
          v-model="password"
          type="password"
          :autocomplete="hasUsers ? 'current-password' : 'new-password'"
          required
      /></label>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <button type="submit" class="primary" :disabled="loading">
        {{ loading ? 'Please wait…' : hasUsers ? 'Sign in' : 'Create account' }}
      </button>
    </form>
  </main>
</template>

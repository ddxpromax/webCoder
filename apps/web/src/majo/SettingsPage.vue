<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'

const language = ref('en')
const accent = ref('#ff7f16')
const accentDark = ref('')
const notice = ref('')
const error = ref('')

async function refresh() {
  try {
    const [lang, theme] = await Promise.all([
      apiRequest<{ language: string }>('/settings/language'),
      apiRequest<{ accent: string; accent_dark?: string }>('/config/theme'),
    ])
    language.value = lang.language
    accent.value = theme.accent || '#ff7f16'
    accentDark.value = theme.accent_dark || ''
    document.documentElement.style.setProperty('--majo-accent', accent.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load settings.'
  }
}

async function save() {
  error.value = ''
  notice.value = ''
  try {
    await Promise.all([
      apiRequest('/settings/language', {
        method: 'PUT',
        body: JSON.stringify({ language: language.value }),
      }),
      apiRequest('/config/theme', {
        method: 'PUT',
        body: JSON.stringify({ accent: accent.value, accent_dark: accentDark.value }),
      }),
    ])
    document.documentElement.style.setProperty('--majo-accent', accent.value)
    notice.value = 'Settings saved.'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save settings.'
  }
}

async function signOut() {
  const token = window.localStorage.getItem('majo_auth_token')
  if (token) {
    try {
      await apiRequest('/auth/revoke-token', {
        method: 'POST',
        body: JSON.stringify({ token }),
      })
    } catch {
      // Remove the local credential even if the server is unavailable.
    }
  }
  window.localStorage.removeItem('majo_auth_token')
  window.location.assign('/login')
}

onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow">PREFERENCES</p>
        <h1>Settings</h1>
      </div>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <div class="settings-card narrow-card">
      <h2>Appearance and language</h2>
      <form class="stacked-form" @submit.prevent="save">
        <label
          >Language
          <select v-model="language">
            <option value="en">English</option>
            <option value="zh">Chinese</option>
            <option value="ja">Japanese</option>
            <option value="ru">Russian</option>
            <option value="pt-BR">Portuguese</option>
            <option value="id">Indonesian</option>
          </select>
        </label>
        <label>Accent color <input v-model="accent" type="color" /></label>
        <label
          >Dark accent color
          <input v-model="accentDark" type="text" placeholder="Optional hex color"
        /></label>
        <button type="submit" class="primary">Save settings</button>
      </form>
    </div>
    <div class="settings-card narrow-card">
      <h2>Environment variables</h2>
      <p>Set named values for workspace integrations.</p>
      <RouterLink to="/settings/environments">Manage variables</RouterLink>
    </div>
    <div class="settings-card narrow-card">
      <h2>Account</h2>
      <button @click="signOut">Sign out</button>
    </div>
  </section>
</template>

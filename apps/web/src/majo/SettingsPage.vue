<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'

const language = ref('en')
const accent = ref('#ff7f16')
const accentDark = ref('')
const notice = ref('')
const error = ref('')
const heartbeat = ref({ enabled: false, every: '6h', target: 'main', timeoutSeconds: 120 })

async function refresh() {
  try {
    const [lang, theme, heartbeatConfig] = await Promise.all([
      apiRequest<{ language: string }>('/settings/language'),
      apiRequest<{ accent: string; accent_dark?: string }>('/config/theme'),
      apiRequest<{ enabled: boolean; every: string; target: string; timeoutSeconds: number }>('/config/heartbeat'),
    ])
    language.value = lang.language
    accent.value = theme.accent || '#ff7f16'
    accentDark.value = theme.accent_dark || ''
    heartbeat.value = heartbeatConfig
    document.documentElement.style.setProperty('--majo-accent', accent.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load settings.'
  }
}

async function saveHeartbeat() {
  error.value = ''; notice.value = ''
  try { await apiRequest('/config/heartbeat', { method: 'PUT', body: JSON.stringify(heartbeat.value) }); notice.value = 'Heartbeat saved.' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not save Heartbeat.' }
}

async function runHeartbeat() {
  error.value = ''; notice.value = ''
  try { const result = await apiRequest<{ started: boolean }>('/config/heartbeat/run', { method: 'POST' }); notice.value = result.started ? 'Heartbeat started.' : 'Enable Heartbeat before running it.' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not start Heartbeat.' }
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
      <h2>Security policies</h2>
      <p>Manage the guard rules applied to tools and workspace files.</p>
      <RouterLink to="/settings/security">Manage security</RouterLink>
    </div>
    <div class="settings-card narrow-card">
      <h2>Voice transcription</h2>
      <p>Configure browser capture, local Whisper, or a model provider.</p>
      <RouterLink to="/settings/voice-transcription">Configure voice transcription</RouterLink>
    </div>
    <div class="settings-card narrow-card"><h2>Debug logs</h2><p>Read recent backend log output for diagnosis.</p><RouterLink to="/settings/debug">Open debug logs</RouterLink></div>
    <div class="settings-card narrow-card">
      <h2>Heartbeat</h2>
      <p>Run instructions from <code>HEARTBEAT.md</code> on a recurring schedule.</p>
      <form class="stacked-form" @submit.prevent="saveHeartbeat">
        <label class="skills-checkbox"><input v-model="heartbeat.enabled" type="checkbox" /> Enable Heartbeat</label>
        <label>Every <input v-model="heartbeat.every" placeholder="6h or 0 */6 * * *" /></label>
        <label>Target <select v-model="heartbeat.target"><option value="main">Main only</option><option value="inbox">Inbox</option></select></label>
        <label>Timeout seconds <input v-model.number="heartbeat.timeoutSeconds" type="number" min="1" max="3600" /></label>
        <div class="skills-editor-actions"><button type="submit" class="primary">Save Heartbeat</button><button type="button" @click="runHeartbeat">Run now</button></div>
      </form>
    </div>
    <div class="settings-card narrow-card">
      <h2>Account</h2>
      <button @click="signOut">Sign out</button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'

type Channel = { enabled: boolean; bot_prefix: string; [key: string]: unknown }
const types = ref<string[]>([])
const channels = ref<Record<string, Channel>>({})
const health = ref<Record<string, string>>({})
const loading = ref(false)
const saving = ref('')
const error = ref('')
const notice = ref('')

async function refresh() {
  loading.value = true; error.value = ''
  try {
    const [nextTypes, nextChannels] = await Promise.all([
      apiRequest<string[]>('/config/channels/types'), apiRequest<Record<string, Channel>>('/config/channels'),
    ])
    types.value = nextTypes; channels.value = nextChannels
    const checks = await Promise.all(nextTypes.map(async (name) => [name, (await apiRequest<{ status: string }>(`/config/channels/${encodeURIComponent(name)}/health`)).status] as const))
    health.value = Object.fromEntries(checks)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not load channels.' }
  finally { loading.value = false }
}

async function save(name: string) {
  saving.value = name; error.value = ''; notice.value = ''
  try { await apiRequest(`/config/channels/${encodeURIComponent(name)}`, { method: 'PUT', body: JSON.stringify(channels.value[name]) }); await refresh(); notice.value = `${name} saved.` }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not save channel.' }
  finally { saving.value = '' }
}

async function restart(name: string) {
  try { await apiRequest(`/config/channels/${encodeURIComponent(name)}/restart`, { method: 'POST' }); await refresh(); notice.value = `${name} restarted.` }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not restart channel.' }
}
function channel(name: string): Channel {
  return channels.value[name] ?? (channels.value[name] = { enabled: false, bot_prefix: '' })
}
onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading"><div><p class="eyebrow">CONTROL</p><h1>Channels</h1></div><button @click="refresh">Refresh</button></header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p><p v-if="notice" class="success-notice">{{ notice }}</p>
    <p v-if="loading">Loading channels…</p>
    <div v-else class="settings-grid">
      <form v-for="name in types" :key="name" class="settings-card stacked-form" @submit.prevent="save(name)">
        <div class="skills-editor-heading"><h2>{{ name === 'console' ? 'Console' : name }}</h2><span class="muted">{{ health[name] || 'unknown' }}</span></div>
        <label class="skills-checkbox"><input v-model="channel(name).enabled" type="checkbox" :disabled="name === 'console'" /> Enable channel</label>
        <label>Bot prefix <input v-model="channel(name).bot_prefix" placeholder="Optional prefix" /></label>
        <div class="skills-editor-actions"><button type="submit" class="primary" :disabled="saving === name">{{ saving === name ? 'Saving…' : 'Save' }}</button><button type="button" @click="restart(name)">Restart</button></div>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest } from './api'
import { parseHeartbeatEvery, serializeHeartbeatEvery, type HeartbeatUnit } from './heartbeat'

interface HeartbeatConfig {
  enabled: boolean
  every: string
  target: 'main' | 'last' | 'inbox'
  timeoutSeconds: number
  active_hours?: { start?: string; end?: string }
  last_run?: { status?: string; started_at?: string; detail?: string }
}

const config = ref<HeartbeatConfig>({ enabled: false, every: '6h', target: 'main', timeoutSeconds: 300 })
const interval = ref(6)
const unit = ref<HeartbeatUnit>('h')
const useActiveHours = ref(false)
const activeStart = ref('08:00')
const activeEnd = ref('22:00')
const loading = ref(false)
const saving = ref(false)
const running = ref(false)
const error = ref('')
const notice = ref('')

function applyConfig(value: HeartbeatConfig) {
  config.value = value
  const every = parseHeartbeatEvery(value.every || '6h')
  interval.value = every.number; unit.value = every.unit
  useActiveHours.value = Boolean(value.active_hours?.start && value.active_hours?.end)
  activeStart.value = value.active_hours?.start || '08:00'
  activeEnd.value = value.active_hours?.end || '22:00'
}

async function refresh() {
  loading.value = true; error.value = ''
  try { applyConfig(await apiRequest<HeartbeatConfig>('/config/heartbeat')) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not load heartbeat settings.' }
  finally { loading.value = false }
}

async function save() {
  if (!Number.isFinite(interval.value) || interval.value < 1) { error.value = 'The interval must be at least 1.'; return }
  if (useActiveHours.value && (!/^\d{2}:\d{2}$/.test(activeStart.value) || !/^\d{2}:\d{2}$/.test(activeEnd.value))) { error.value = 'Active hours must use HH:mm.'; return }
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const next = await apiRequest<HeartbeatConfig>('/config/heartbeat', {
      method: 'PUT', body: JSON.stringify({
        enabled: config.value.enabled,
        every: serializeHeartbeatEvery(interval.value, unit.value),
        target: config.value.target,
        timeoutSeconds: config.value.timeoutSeconds,
        active_hours: useActiveHours.value ? { start: activeStart.value, end: activeEnd.value } : {},
      }),
    })
    applyConfig(next); notice.value = 'Heartbeat settings saved.'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not save heartbeat settings.' }
  finally { saving.value = false }
}

async function runNow() {
  running.value = true; error.value = ''; notice.value = ''
  try {
    const result = await apiRequest<{ started: boolean; last_run?: HeartbeatConfig['last_run'] }>('/config/heartbeat/run', { method: 'POST' })
    if (!result.started) { error.value = 'Enable the heartbeat before running it.'; return }
    notice.value = 'Heartbeat started.'
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not start the heartbeat.' }
  finally { running.value = false }
}

onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page heartbeat-page">
    <header class="settings-heading"><div><p class="eyebrow">CONTROL</p><h1>Heartbeat</h1></div><button :disabled="loading" @click="refresh">Refresh</button></header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p><p v-if="notice" class="success-notice">{{ notice }}</p>
    <div class="settings-grid"><form class="settings-card stacked-form" @submit.prevent="save">
      <h2>Schedule</h2>
      <label class="skills-checkbox"><input v-model="config.enabled" type="checkbox" /> Enable heartbeat</label>
      <label>Run every <span class="heartbeat-interval"><input v-model.number="interval" min="1" type="number" /><select v-model="unit"><option value="m">Minutes</option><option value="h">Hours</option></select></span></label>
      <label>Timeout (seconds)<input v-model.number="config.timeoutSeconds" min="1" max="3600" type="number" /></label>
      <label>Deliver result to<select v-model="config.target"><option value="main">Main chat</option><option value="last">Last active chat</option><option value="inbox">Inbox</option></select></label>
      <label class="skills-checkbox"><input v-model="useActiveHours" type="checkbox" /> Restrict to active hours</label>
      <div v-if="useActiveHours" class="heartbeat-hours"><label>Start<input v-model="activeStart" type="time" /></label><label>End<input v-model="activeEnd" type="time" /></label></div>
      <div class="skills-editor-actions"><button type="submit" class="primary" :disabled="saving">{{ saving ? 'Saving…' : 'Save heartbeat' }}</button><button type="button" :disabled="running || !config.enabled" @click="runNow">{{ running ? 'Starting…' : 'Run now' }}</button></div>
    </form>
    <section class="settings-card"><h2>Last run</h2><p v-if="loading" class="muted">Loading heartbeat status…</p><template v-else><p><strong>Status:</strong> {{ config.last_run?.status || 'never' }}</p><p v-if="config.last_run?.started_at"><strong>Started:</strong> {{ config.last_run.started_at }}</p><p v-if="config.last_run?.detail"><strong>Detail:</strong> {{ config.last_run.detail }}</p><p class="muted">The heartbeat reads <code>HEARTBEAT.md</code> from the active workspace.</p></template></section>
    </div>
  </section>
</template>

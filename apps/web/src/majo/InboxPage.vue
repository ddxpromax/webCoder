<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { apiRequest } from './api'
import { workbench } from './state'
type Event = { id: string; title: string; body: string; source_type: string; status: string; severity: string; payload: Record<string, unknown>; read: boolean; created_at: number }
const events = ref<Event[]>([]); const selected = ref<Event | null>(null); const unread = ref(false); const loading = ref(false); const error = ref('')
const base = () => `/agents/${encodeURIComponent(workbench.agentId)}/console/inbox`
async function refresh() { loading.value = true; try { events.value = (await apiRequest<{events: Event[]}>(`${base()}/events?limit=100&unread_only=${unread.value}`, {}, workbench.agentId)).events } catch (e) { error.value = e instanceof Error ? e.message : 'Could not load Inbox.' } finally { loading.value = false } }
async function open(event: Event) { selected.value = event; if (!event.read) await apiRequest(`${base()}/read`, { method: 'POST', body: JSON.stringify({ event_ids: [event.id] }) }, workbench.agentId); await refresh() }
async function markAll() { await apiRequest(`${base()}/read`, { method: 'POST', body: JSON.stringify({ all: true }) }, workbench.agentId); await refresh() }
async function remove(event: Event) { await apiRequest(`${base()}/events/${event.id}`, { method: 'DELETE' }, workbench.agentId); if (selected.value?.id === event.id) selected.value = null; await refresh() }
const date = (v: number) => new Date(v * 1000).toLocaleString(); watch([() => workbench.agentId, unread], () => void refresh()); onMounted(() => void refresh())
</script>
<template>
  <section class="settings-page"><header class="settings-heading"><div><p class="eyebrow">AUTOMATION</p><h1>Inbox</h1></div><button @click="markAll">Mark all read</button></header><label class="skills-checkbox"><input v-model="unread" type="checkbox" /> Unread only</label><p v-if="error" class="inline-error">{{ error }}</p>
  <div class="inbox-grid"><div class="settings-card inbox-list"><p v-if="loading">Loading…</p><p v-else-if="!events.length">No Inbox events.</p><button v-for="event in events" :key="event.id" class="inbox-item" :class="{ unread: !event.read, selected: selected?.id === event.id }" @click="open(event)"><strong>{{ event.title }}</strong><small>{{ event.source_type }} · {{ date(event.created_at) }}</small></button></div><article class="settings-card inbox-detail"><template v-if="selected"><header><h2>{{ selected.title }}</h2><button @click="remove(selected)">Delete</button></header><p>{{ selected.body }}</p><p><strong>{{ selected.status }}</strong> · {{ selected.severity }}</p><pre>{{ JSON.stringify(selected.payload, null, 2) }}</pre></template><p v-else>Select an Inbox event.</p></article></div></section>
</template>

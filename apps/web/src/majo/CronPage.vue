<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { apiRequest } from './api'
import { workbench } from './state'
type Job = { id: string; name: string; task_type: 'text' | 'agent'; text?: string; schedule: { type: 'cron' | 'once'; cron?: string; run_at?: string; timezone?: string }; request?: { input?: string }; save_result_to_inbox?: boolean }
const jobs = ref<Job[]>([]); const selected = ref<Job | null>(null); const error = ref(''); const busy = ref(false)
const fresh = (): Job => ({ id: '', name: '', task_type: 'agent', schedule: { type: 'cron', cron: '0 9 * * *', timezone: 'UTC' }, request: { input: '' }, save_result_to_inbox: true })
const draft = ref<Job>(fresh()); const base = () => `/agents/${encodeURIComponent(workbench.agentId)}/cron`
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v)) as T
async function refresh() { busy.value = true; try { jobs.value = await apiRequest<Job[]>(`${base()}/jobs`, {}, workbench.agentId) } catch (e) { error.value = e instanceof Error ? e.message : 'Could not load scheduled jobs.' } finally { busy.value = false } }
function choose(job: Job) { selected.value = job; draft.value = clone(job); error.value = '' }
function create() { selected.value = null; draft.value = fresh(); error.value = '' }
async function save() { busy.value = true; error.value = ''; try { const body = clone(draft.value); if (body.task_type === 'text') body.request = undefined; else { body.text = ''; body.request ??= { input: '' } }; const path = selected.value ? `${base()}/jobs/${selected.value.id}` : `${base()}/jobs`; const saved = await apiRequest<Job>(path, { method: selected.value ? 'PUT' : 'POST', body: JSON.stringify(body) }, workbench.agentId); choose(saved); await refresh() } catch (e) { error.value = e instanceof Error ? e.message : 'Could not save scheduled job.' } finally { busy.value = false } }
async function action(path: string) { if (!selected.value) return; try { await apiRequest(`${base()}/jobs/${selected.value.id}${path}`, { method: 'POST' }, workbench.agentId); await refresh() } catch (e) { error.value = e instanceof Error ? e.message : 'Could not update scheduled job.' } }
async function remove() { if (!selected.value) return; await apiRequest(`${base()}/jobs/${selected.value.id}`, { method: 'DELETE' }, workbench.agentId); create(); await refresh() }
watch(() => workbench.agentId, () => { create(); void refresh() }); onMounted(() => void refresh())
</script>
<template>
  <section class="settings-page"><header class="settings-heading"><div><p class="eyebrow">AUTOMATION</p><h1>Scheduled jobs</h1></div><button @click="create">New job</button></header><p v-if="error" class="inline-error">{{ error }}</p>
    <div class="cron-grid"><div class="settings-card cron-list"><p v-if="busy">Loading…</p><button v-for="job in jobs" :key="job.id" class="cron-list-item" :class="{ selected: selected?.id === job.id }" @click="choose(job)"><strong>{{ job.name }}</strong><small>{{ job.schedule.type === 'cron' ? job.schedule.cron : job.schedule.run_at }}</small></button></div>
      <form class="settings-card cron-editor" @submit.prevent="save"><h2>{{ selected ? 'Edit scheduled job' : 'New scheduled job' }}</h2><label>Name <input v-model="draft.name" /></label><label>Task type <select v-model="draft.task_type"><option value="agent">Agent task</option><option value="text">Text message</option></select></label><label v-if="draft.task_type === 'text'">Message <textarea v-model="draft.text" rows="4" /></label><label v-else>Prompt <textarea v-model="draft.request!.input" rows="4" /></label><label>Schedule <select v-model="draft.schedule.type"><option value="cron">Cron expression</option><option value="once">One time</option></select></label><label v-if="draft.schedule.type === 'cron'">Cron expression <input v-model="draft.schedule.cron" /></label><label v-else>Run at <input v-model="draft.schedule.run_at" type="datetime-local" /></label><label>Timezone <input v-model="draft.schedule.timezone" /></label><label class="skills-checkbox"><input v-model="draft.save_result_to_inbox" type="checkbox" /> Save result to Inbox</label><div class="skills-editor-actions"><button class="primary" :disabled="busy">Save job</button><template v-if="selected"><button type="button" @click="action('/run')">Run now</button><button type="button" @click="action('/pause')">Pause</button><button type="button" @click="action('/resume')">Resume</button><button type="button" @click="remove">Delete</button></template></div></form></div>
  </section>
</template>

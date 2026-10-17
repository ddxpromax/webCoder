<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest, getAgentProfile, getAgentRunningConfig, listAgents, updateAgentRunningConfig, type AgentInfo, type AgentProfile, type AgentRunningConfig } from './api'
import { selectAgent, workbench } from './state'

const agents = ref<AgentInfo[]>([])
const newAgentName = ref('')
const newAgentId = ref('')
const selected = ref<AgentProfile | null>(null)
const running = ref<AgentRunningConfig | null>(null)
const description = ref('')
const activeTab = ref('agent')
const loadingConfig = ref(false)
const error = ref('')
const notice = ref('')
type Gate = { type: string; title: string; schema: { properties?: Record<string, { type?: string; default?: unknown }> }; exclusive_group?: string }
type CustomLoop = { id: string; name: string; slash_command: string; description: string; gates: Array<{ type: string; enabled: boolean; params: Record<string, unknown> }> }
const customLoops = ref<CustomLoop[]>([])
const gateCatalog = ref<Gate[]>([])
const editingLoop = ref<CustomLoop | null>(null)

function record(value: unknown): Record<string, any> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : {}
}

function normalizeConfig() {
  if (!running.value) return null
  const config = running.value
  config.loop = record(config.loop)
  config.loop.iteration = record(config.loop.iteration)
  config.loop.goal = record(config.loop.goal)
  config.loop.mission = record(config.loop.mission)
  config.light_context_config = record(config.light_context_config)
  config.light_context_config.context_compact_config = record(config.light_context_config.context_compact_config)
  config.light_context_config.tool_result_pruning_config = record(config.light_context_config.tool_result_pruning_config)
  config.reme_light_memory_config = record(config.reme_light_memory_config)
  config.reme_light_memory_config.auto_memory_search_config = record(config.reme_light_memory_config.auto_memory_search_config)
  config.reme_light_memory_config.embedding_model_config = record(config.reme_light_memory_config.embedding_model_config)
  config.sandbox = record(config.sandbox)
  config.auto_title_config = record(config.auto_title_config)
  return config
}

async function refresh() {
  try { agents.value = (await listAgents()).agents; workbench.agents = agents.value }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not load agents.' }
}

async function open(agent: AgentInfo) {
  error.value = ''; notice.value = ''; loadingConfig.value = true
  try {
    const [profile, config, modes, gates] = await Promise.all([getAgentProfile(agent.id), getAgentRunningConfig(agent.id), apiRequest<CustomLoop[]>('/loops/custom', {}, agent.id), apiRequest<Gate[]>('/loops/gates/catalog', {}, agent.id)])
    selected.value = profile; description.value = profile.description || ''; running.value = config
    customLoops.value = modes; gateCatalog.value = gates; editingLoop.value = null
    normalizeConfig(); activeTab.value = 'agent'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not load agent configuration.' }
  finally { loadingConfig.value = false }
}

function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
function newLoop() { const suffix = customLoops.value.length + 1; editingLoop.value = { id: `custom-loop-${suffix}`, name: `Custom loop ${suffix}`, slash_command: `custom-${suffix}`, description: 'A custom Majo loop mode.', gates: gateCatalog.value.map(gate => ({ type: gate.type, enabled: false, params: Object.fromEntries(Object.entries(gate.schema.properties ?? {}).map(([key, field]) => [key, field.default ?? ''])) })) } }
function editLoop(mode: CustomLoop) { const copy = clone(mode); for (const gate of gateCatalog.value) if (!copy.gates.some(item => item.type === gate.type)) copy.gates.push({ type: gate.type, enabled: false, params: Object.fromEntries(Object.entries(gate.schema.properties ?? {}).map(([key, field]) => [key, field.default ?? ''])) }); editingLoop.value = copy }
function normalizeLoop(mode: CustomLoop): CustomLoop { const properties = new Map(gateCatalog.value.map(gate => [gate.type, gate.schema.properties ?? {}])); return { ...mode, id: mode.id.trim(), name: mode.name.trim(), slash_command: mode.slash_command.trim().replace(/^\//, ''), description: mode.description.trim(), gates: mode.gates.map(gate => ({ ...gate, params: Object.fromEntries(Object.entries(gate.params).map(([key, value]) => { const type = properties.get(gate.type)?.[key]?.type; return [key, (type === 'integer' || type === 'number') && value !== '' ? Number(value) : value] })) })) } }
async function saveCustomLoop() { if (!selected.value || !editingLoop.value) return; const mode = normalizeLoop(editingLoop.value); if (!mode.id || !mode.name || !mode.slash_command) { error.value = 'Loop ID, name, and slash command are required.'; return } error.value = ''; try { const exists = customLoops.value.some(item => item.id === mode.id); await apiRequest(`/loops/custom${exists ? `/${encodeURIComponent(mode.id)}` : ''}`, { method: exists ? 'PUT' : 'POST', body: JSON.stringify(mode) }, selected.value.id); customLoops.value = await apiRequest<CustomLoop[]>('/loops/custom', {}, selected.value.id); editingLoop.value = null; notice.value = 'Custom loop mode saved.' } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not save custom loop mode.' } }
async function duplicateCustomLoop(mode: CustomLoop) { if (!selected.value) return; try { await apiRequest(`/loops/custom/${encodeURIComponent(mode.id)}/duplicate`, { method: 'POST' }, selected.value.id); customLoops.value = await apiRequest<CustomLoop[]>('/loops/custom', {}, selected.value.id); notice.value = 'Custom loop mode duplicated.' } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not duplicate custom loop mode.' } }
async function deleteCustomLoop(mode: CustomLoop) { if (!selected.value || !window.confirm(`Delete custom loop ${mode.name}?`)) return; try { await apiRequest(`/loops/custom/${encodeURIComponent(mode.id)}`, { method: 'DELETE' }, selected.value.id); customLoops.value = customLoops.value.filter(item => item.id !== mode.id); if (editingLoop.value?.id === mode.id) editingLoop.value = null; notice.value = 'Custom loop mode deleted.' } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not delete custom loop mode.' } }

async function create() {
  error.value = ''
  const id = newAgentId.value.trim(), name = newAgentName.value.trim()
  if (!id || !name) return
  try {
    await apiRequest('/agents', { method: 'POST', body: JSON.stringify({ id, name, description: '', backend: 'majo', language: 'en' }) })
    newAgentId.value = ''; newAgentName.value = ''; await refresh()
    const created = agents.value.find((item) => item.id === id); if (created) await open(created)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not create agent.' }
}

async function save() {
  if (!selected.value || !running.value) return
  error.value = ''
  try {
    await Promise.all([
      apiRequest(`/agents/${encodeURIComponent(selected.value.id)}`, { method: 'PUT', body: JSON.stringify({ name: selected.value.name, description: description.value, language: selected.value.language || 'en' }) }),
      updateAgentRunningConfig(selected.value.id, normalizeConfig()!),
    ])
    notice.value = 'Agent configuration saved.'; await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not save agent configuration.' }
}

async function copy(agent: AgentInfo) {
  try { await apiRequest(`/agents/${encodeURIComponent(agent.id)}/copy`, { method: 'POST', body: JSON.stringify({ name: `${agent.name} Copy`, copy_skills: true }) }); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not copy agent.' }
}

onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading"><div><p class="eyebrow">AGENT</p><h1>Agents</h1></div></header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div class="settings-grid">
      <div class="settings-card">
        <h2>Workspaces</h2>
        <div class="data-list"><div v-for="agent in agents" :key="agent.id" class="data-row"><div class="data-row-main"><strong>{{ agent.name }}</strong><small>{{ agent.id }}</small></div><button @click="open(agent)">Configure</button><button @click="copy(agent)">Copy</button><button v-if="workbench.agentId !== agent.id" @click="selectAgent(agent.id)">Use</button></div></div>
        <form class="stacked-form" @submit.prevent="create"><h3>Create agent</h3><label>ID <input v-model="newAgentId" required pattern="[A-Za-z0-9_-]+" placeholder="my-agent" /></label><label>Name <input v-model="newAgentName" required placeholder="My agent" /></label><button type="submit" class="primary">Create</button></form>
      </div>

      <div v-if="selected" class="settings-card agent-config-card">
        <h2>{{ selected.name }}</h2>
        <p v-if="loadingConfig" class="muted">Loading configuration…</p>
        <template v-else-if="running">
          <nav class="agent-config-tabs" aria-label="Agent configuration"><button v-for="tab in [['agent','Agent'],['loop','Agent loop'],['retry','LLM retry'],['rate','Rate limit'],['context','Context'],['memory','Memory'],['approval','Tool execution']]" :key="tab[0]" :class="{ active: activeTab === tab[0] }" type="button" @click="activeTab=tab[0]">{{ tab[1] }}</button></nav>
          <form class="stacked-form" @submit.prevent="save">
            <template v-if="activeTab === 'agent'">
              <label>Name <input v-model="selected.name" required /></label><label>Description <textarea v-model="description" rows="3" /></label><label>Language <select v-model="selected.language"><option value="en">English</option><option value="zh">Chinese</option><option value="ja">Japanese</option><option value="ru">Russian</option><option value="pt-BR">Portuguese (Brazil)</option><option value="id">Indonesian</option></select></label><label>Shell command timeout (seconds) <input v-model.number="running.shell_command_timeout" min="1" max="120" type="number" /></label><label>Shell executable <input v-model="running.shell_command_executable" placeholder="System default" /></label><label>Memory backend <select v-model="running.memory_manager_backend"><option value="remelight">ReMe Light</option><option value="adbpg">ADBPG</option><option value="none">Disabled</option></select></label><label class="skills-checkbox"><input v-model="running.auto_title_config.enabled" type="checkbox" /> Generate chat titles automatically</label><label>Title generation timeout (seconds) <input v-model.number="running.auto_title_config.timeout_seconds" min="1" type="number" /></label><label>Sandbox mode <select v-model="running.sandbox.mode"><option value="off">Off</option><option value="appcontainer">Windows AppContainer</option></select></label><label class="skills-checkbox"><input v-model="running.sandbox.network_allow" type="checkbox" /> Allow network access in AppContainer</label>
            </template>
            <template v-else-if="activeTab === 'loop'">
              <label>Maximum regular iterations <input v-model.number="running.max_iters" min="1" max="500" type="number" /></label><label class="skills-checkbox"><input v-model="running.loop.iteration.enabled" type="checkbox" /> Enable iteration limit</label><label>Iteration limit <input v-model.number="running.loop.iteration.max_iterations" min="1" max="500" type="number" /></label><label>Goal iteration limit <input v-model.number="running.loop.goal.max_iterations" min="1" max="500" type="number" /></label><label>Goal token budget <input v-model.number="running.loop.goal.max_tokens" min="1" type="number" /></label><label>Mission iteration limit <input v-model.number="running.loop.mission.max_iterations" min="1" max="500" type="number" /></label><label>Mission retries per story <input v-model.number="running.loop.mission.max_retries_per_story" min="0" type="number" /></label>
              <section class="custom-loops"><div class="skills-editor-heading"><div><h3>Custom loop modes</h3><p class="muted">Compose the Majo stop gates used by a slash-command loop.</p></div><button type="button" @click="newLoop">New loop</button></div><p v-if="!customLoops.length" class="muted">No custom loop modes.</p><div v-for="mode in customLoops" :key="mode.id" class="skills-item"><span><strong>{{ mode.name }}</strong><small>/{{ mode.slash_command }} · {{ mode.description }}</small></span><span class="skills-editor-actions"><button type="button" @click="editLoop(mode)">Edit</button><button type="button" @click="duplicateCustomLoop(mode)">Duplicate</button><button type="button" @click="deleteCustomLoop(mode)">Delete</button></span></div><div v-if="editingLoop" class="custom-loop-editor"><label>Loop ID <input v-model="editingLoop.id" pattern="[A-Za-z0-9._-]+" /></label><label>Name <input v-model="editingLoop.name" /></label><label>Slash command <input v-model="editingLoop.slash_command" placeholder="review" /></label><label>Description <textarea v-model="editingLoop.description" rows="2" /></label><fieldset><legend>Stop gates</legend><div v-for="gateEntry in editingLoop.gates" :key="gateEntry.type" class="custom-loop-gate"><label class="skills-checkbox"><input v-model="gateEntry.enabled" type="checkbox" /> <strong>{{ gateCatalog.find(item => item.type === gateEntry.type)?.title || gateEntry.type }}</strong></label><template v-for="(field, key) in gateCatalog.find(item => item.type === gateEntry.type)?.schema.properties ?? {}" :key="String(key)"><label>{{ String(key).replaceAll('_', ' ') }}<input v-model="gateEntry.params[String(key)]" :type="field.type === 'integer' || field.type === 'number' ? 'number' : 'text'" /></label></template></div></fieldset><div class="skills-editor-actions"><button type="button" class="primary" @click="saveCustomLoop">Save custom loop</button><button type="button" @click="editingLoop = null">Cancel</button></div></div></section>
            </template>
            <template v-else-if="activeTab === 'retry'">
              <label class="skills-checkbox"><input v-model="running.llm_retry_enabled" type="checkbox" /> Enable LLM retry configuration</label><label>Maximum retries <input v-model.number="running.llm_max_retries" :disabled="!running.llm_retry_enabled" min="1" type="number" /></label><label>Backoff base (seconds) <input v-model.number="running.llm_backoff_base" :disabled="!running.llm_retry_enabled" min="0.1" step="0.1" type="number" /></label><label>Backoff cap (seconds) <input v-model.number="running.llm_backoff_cap" :disabled="!running.llm_retry_enabled" min="0.5" step="0.1" type="number" /></label>
            </template>
            <template v-else-if="activeTab === 'rate'">
              <label>Maximum concurrent LLM calls <input v-model.number="running.llm_max_concurrent" min="1" type="number" /></label><label>Maximum requests per minute <input v-model.number="running.llm_max_qpm" min="0" type="number" /></label><label>Rate-limit pause (seconds) <input v-model.number="running.llm_rate_limit_pause" min="0" step="0.1" type="number" /></label><label>Rate-limit jitter (seconds) <input v-model.number="running.llm_rate_limit_jitter" min="0" step="0.1" type="number" /></label><label>Acquire timeout (seconds) <input v-model.number="running.llm_acquire_timeout" min="1" type="number" /></label>
            </template>
            <template v-else-if="activeTab === 'context'">
              <label>Context manager <select v-model="running.context_manager_backend"><option value="light">Light context</option></select></label><label>History maximum length <input v-model.number="running.history_max_length" min="1" type="number" /></label><label class="skills-checkbox"><input v-model="running.light_context_config.context_compact_config.enabled" type="checkbox" /> Enable context compaction</label><label>Compaction threshold ratio <input v-model.number="running.light_context_config.context_compact_config.compact_threshold_ratio" min="0.1" max="1" step="0.05" type="number" /></label><label>Reserved context ratio <input v-model.number="running.light_context_config.context_compact_config.reserve_threshold_ratio" min="0" max="0.5" step="0.05" type="number" /></label><label class="skills-checkbox"><input v-model="running.light_context_config.tool_result_pruning_config.enabled" type="checkbox" /> Prune old tool results</label><label>Recent tool results to retain <input v-model.number="running.light_context_config.tool_result_pruning_config.pruning_recent_n" min="0" type="number" /></label>
            </template>
            <template v-else-if="activeTab === 'memory'">
              <label class="skills-checkbox"><input v-model="running.reme_light_memory_config.summarize_when_compact" type="checkbox" /> Summarize memory during compaction</label><label class="skills-checkbox"><input v-model="running.reme_light_memory_config.inbox_push_enabled" type="checkbox" /> Push memory to inbox</label><label>Automatic memory interval <input v-model.number="running.reme_light_memory_config.auto_memory_interval" min="1" type="number" /></label><label class="skills-checkbox"><input v-model="running.reme_light_memory_config.auto_memory_search_config.enabled" type="checkbox" /> Search memory automatically</label><label>Automatic search result limit <input v-model.number="running.reme_light_memory_config.auto_memory_search_config.max_results" min="1" max="20" type="number" /></label><label>Embedding backend <select v-model="running.reme_light_memory_config.embedding_model_config.backend"><option value="openai">OpenAI-compatible</option><option value="none">Disabled</option></select></label><label>Embedding model <input v-model="running.reme_light_memory_config.embedding_model_config.model_name" /></label><label>Embedding base URL <input v-model="running.reme_light_memory_config.embedding_model_config.base_url" /></label><label>Embedding dimensions <input v-model.number="running.reme_light_memory_config.embedding_model_config.dimensions" min="1" type="number" /></label>
            </template>
            <template v-else>
              <label>Tool execution level <select v-model="running.approval_level"><option value="AUTO">Auto approve</option><option value="ASK">Ask before guarded tools</option><option value="DENY">Deny guarded tools</option></select></label><p class="muted">This setting controls approval hooks for the next agent turn.</p>
            </template>
            <button class="primary" type="submit">Save agent configuration</button>
          </form>
        </template>
        <p v-if="notice" class="success-notice">{{ notice }}</p>
      </div>
    </div>
  </section>
</template>

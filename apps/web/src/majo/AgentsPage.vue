<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { apiRequest, listAgents, type AgentInfo } from './api'
import { selectAgent, workbench } from './state'

const agents = ref<AgentInfo[]>([])
const newAgentName = ref('')
const newAgentId = ref('')
const selected = ref<AgentInfo | null>(null)
const description = ref('')
const error = ref('')
const notice = ref('')

async function refresh() {
  try {
    agents.value = (await listAgents()).agents
    workbench.agents = agents.value
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load agents.'
  }
}

function open(agent: AgentInfo) {
  selected.value = agent
  description.value = agent.description || ''
  notice.value = ''
}

async function create() {
  error.value = ''
  const id = newAgentId.value.trim()
  const name = newAgentName.value.trim()
  if (!id || !name) return
  try {
    await apiRequest('/agents', {
      method: 'POST',
      body: JSON.stringify({ id, name, description: '', backend: 'majo', language: 'en' }),
    })
    newAgentId.value = ''
    newAgentName.value = ''
    await refresh()
    const created = agents.value.find((agent) => agent.id === id)
    if (created) open(created)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not create agent.'
  }
}

async function save() {
  if (!selected.value) return
  error.value = ''
  try {
    await apiRequest(`/agents/${encodeURIComponent(selected.value.id)}`, {
      method: 'PUT',
      body: JSON.stringify({ name: selected.value.name, description: description.value }),
    })
    notice.value = 'Agent saved.'
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save agent.'
  }
}

async function copy(agent: AgentInfo) {
  error.value = ''
  try {
    await apiRequest(`/agents/${encodeURIComponent(agent.id)}/copy`, {
      method: 'POST',
      body: JSON.stringify({ name: `${agent.name} Copy`, copy_skills: true }),
    })
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not copy agent.'
  }
}

onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow">AGENT</p>
        <h1>Agents</h1>
      </div>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div class="settings-grid">
      <div class="settings-card">
        <h2>Workspaces</h2>
        <div class="data-list">
          <div v-for="agent in agents" :key="agent.id" class="data-row">
            <div class="data-row-main">
              <strong>{{ agent.name }}</strong
              ><small>{{ agent.id }}</small>
            </div>
            <button @click="open(agent)">Configure</button>
            <button @click="copy(agent)">Copy</button>
            <button v-if="workbench.agentId !== agent.id" @click="selectAgent(agent.id)">
              Use
            </button>
          </div>
        </div>
        <form class="stacked-form" @submit.prevent="create">
          <h3>Create agent</h3>
          <label
            >ID
            <input v-model="newAgentId" required pattern="[A-Za-z0-9_-]+" placeholder="my-agent"
          /></label>
          <label>Name <input v-model="newAgentName" required placeholder="My agent" /></label>
          <button type="submit" class="primary">Create</button>
        </form>
      </div>
      <div v-if="selected" class="settings-card">
        <h2>{{ selected.name }}</h2>
        <form class="stacked-form" @submit.prevent="save">
          <label>Name <input v-model="selected.name" required /></label>
          <label>Description <textarea v-model="description" rows="4" /></label>
          <p class="muted">Backend: {{ selected.backend || 'majo' }}</p>
          <button type="submit" class="primary">Save agent</button>
        </form>
        <p v-if="notice" class="success-notice">{{ notice }}</p>
      </div>
    </div>
  </section>
</template>

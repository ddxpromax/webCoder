<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { apiRequest } from './api'
import { workbench } from './state'

interface ToolInfo {
  name: string
  description: string
  icon: string
  enabled: boolean
  async_execution: boolean
  config: Record<string, unknown>
}

const tools = ref<ToolInfo[]>([])
const selected = ref('')
const error = ref('')
const saving = ref(false)

async function refresh() {
  error.value = ''
  try {
    tools.value = await apiRequest<ToolInfo[]>(
      `/agents/${encodeURIComponent(workbench.agentId)}/tools`,
      {},
      workbench.agentId,
    )
    if (!tools.value.some((tool) => tool.name === selected.value)) {
      selected.value = tools.value[0]?.name ?? ''
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load tools.'
  }
}

async function toggle(tool: ToolInfo) {
  saving.value = true
  error.value = ''
  try {
    const updated = await apiRequest<ToolInfo>(
      `/agents/${encodeURIComponent(workbench.agentId)}/tools/${encodeURIComponent(tool.name)}/toggle`,
      { method: 'PATCH' },
      workbench.agentId,
    )
    Object.assign(tool, updated)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not update tool.'
  } finally {
    saving.value = false
  }
}

async function setAsync(tool: ToolInfo, enabled: boolean) {
  saving.value = true
  error.value = ''
  try {
    const updated = await apiRequest<ToolInfo>(
      `/agents/${encodeURIComponent(workbench.agentId)}/tools/${encodeURIComponent(tool.name)}/async-execution`,
      { method: 'PATCH', body: JSON.stringify({ async_execution: enabled }) },
      workbench.agentId,
    )
    Object.assign(tool, updated)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not update tool.'
  } finally {
    saving.value = false
  }
}

watch(() => workbench.agentId, () => void refresh())
onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow">AGENT CONFIGURATION</p>
        <h1>Tools</h1>
      </div>
      <button @click="refresh">Refresh</button>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div class="files-layout">
      <div class="settings-card tool-list">
        <button
          v-for="tool in tools"
          :key="tool.name"
          class="provider-row"
          :class="{ selected: selected === tool.name }"
          @click="selected = tool.name"
        >
          <span>{{ tool.icon }} {{ tool.name }}</span>
          <span :class="tool.enabled ? 'tool-enabled' : 'tool-disabled'">
            {{ tool.enabled ? 'On' : 'Off' }}
          </span>
        </button>
        <p v-if="!tools.length" class="muted">No tools found.</p>
      </div>
      <div v-if="selected" class="settings-card tool-detail">
        <template v-for="tool in tools" :key="tool.name">
          <template v-if="tool.name === selected">
            <h2>{{ tool.icon }} {{ tool.name }}</h2>
            <p class="muted">{{ tool.description }}</p>
            <div class="tool-setting-row">
              <div>
                <strong>Enabled</strong>
                <p class="muted">Allow this agent to use the tool.</p>
              </div>
              <button :disabled="saving" @click="toggle(tool)">
                {{ tool.enabled ? 'Turn off' : 'Turn on' }}
              </button>
            </div>
            <div class="tool-setting-row">
              <div>
                <strong>Background execution</strong>
                <p class="muted">Let the tool run without holding up other work.</p>
              </div>
              <input
                type="checkbox"
                :checked="tool.async_execution"
                :disabled="saving"
                :aria-label="`Background execution for ${tool.name}`"
                @change="setAsync(tool, ($event.target as HTMLInputElement).checked)"
              />
            </div>
          </template>
        </template>
      </div>
    </div>
  </section>
</template>

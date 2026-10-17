<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { apiRequest } from './api'
import MemoryGraphView from './MemoryGraphView.vue'
import { workbench } from './state'

interface WorkspaceFile {
  filename: string
  path: string
  size: number
}
type Section = 'files' | 'memory'
const section = ref<Section>('files')
const entries = ref<WorkspaceFile[]>([])
const selected = ref('')
const content = ref('')
const original = ref('')
const error = ref('')
const notice = ref('')
const showGraph = ref(false)
const graphRevision = ref(0)
const promptFiles = ref<string[]>([])
const uploadInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

function endpoint(name?: string) {
  return `/workspace/${section.value}${name ? `/${name.split('/').map(encodeURIComponent).join('/')}` : ''}`
}

async function refresh() {
  showGraph.value = false
  error.value = ''
  selected.value = ''
  content.value = ''
  original.value = ''
  try {
    entries.value = await apiRequest<WorkspaceFile[]>(endpoint(), {}, workbench.agentId)
    promptFiles.value = await apiRequest<string[]>('/workspace/system-prompt-files', {}, workbench.agentId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load files.'
  }
}

async function togglePromptFile(name: string) {
  const next = promptFiles.value.includes(name) ? promptFiles.value.filter((item) => item !== name) : [...promptFiles.value, name]
  try {
    promptFiles.value = await apiRequest<string[]>('/workspace/system-prompt-files', { method: 'PUT', body: JSON.stringify({ files: next }) }, workbench.agentId)
    notice.value = 'System prompt file configuration updated.'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not update system prompt files.' }
}

async function reindexMemory() {
  error.value = ''
  try {
    const result = await apiRequest<{ status: string }>(
      `/agents/${encodeURIComponent(workbench.agentId)}/memory/reindex`,
      { method: 'POST' },
      workbench.agentId,
    )
    notice.value = result.status === 'completed' ? 'Memory index rebuilt.' : `Memory index: ${result.status}.`
    graphRevision.value++
    entries.value = await apiRequest<WorkspaceFile[]>(endpoint(), {}, workbench.agentId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not rebuild memory index.'
  }
}

async function openGraphFile(path: string) {
  showGraph.value = false
  await nextTick()
  await open(path)
}

async function open(name: string) {
  if (content.value !== original.value && !window.confirm('Discard unsaved changes?')) return
  try {
    const result = await apiRequest<{ content: string }>(endpoint(name), {}, workbench.agentId)
    selected.value = name
    content.value = result.content
    original.value = result.content
    notice.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not open file.'
  }
}

async function save() {
  if (!selected.value) return
  try {
    await apiRequest(
      endpoint(selected.value),
      {
        method: 'PUT',
        body: JSON.stringify({ content: content.value }),
      },
      workbench.agentId,
    )
    original.value = content.value
    notice.value = 'File saved.'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save file.'
  }
}

function downloadWorkspace() {
  window.open(`/api/agents/${encodeURIComponent(workbench.agentId)}/workspace/download`, '_blank')
}

function chooseZip() { uploadInput.value?.click() }

async function uploadWorkspace(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) { error.value = 'Choose a ZIP archive.'; input.value = ''; return }
  uploading.value = true; error.value = ''; notice.value = ''
  try {
    const form = new FormData(); form.append('file', file)
    const result = await apiRequest<{ message: string }>('/workspace/upload', { method: 'POST', body: form }, workbench.agentId)
    notice.value = result.message; await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not import the workspace ZIP.' }
  finally { uploading.value = false; input.value = '' }
}

watch([section, () => workbench.agentId], () => void refresh())
onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow">WORKSPACE</p>
        <h1>Files</h1>
      </div>
      <div class="model-list-header">
        <input ref="uploadInput" class="visually-hidden" type="file" accept=".zip,application/zip" @change="uploadWorkspace" />
        <button :disabled="uploading" @click="chooseZip">{{ uploading ? 'Importing…' : 'Import ZIP' }}</button>
        <button :disabled="uploading" @click="downloadWorkspace">Download ZIP</button>
        <button v-if="section === 'memory'" @click="reindexMemory">Reindex</button>
        <button v-if="section === 'memory'" @click="showGraph = true">Graph</button>
        <button @click="refresh">Refresh</button>
      </div>
    </header>
    <div class="tab-row">
      <button :class="{ selected: section === 'files' }" @click="section = 'files'">
        Workspace notes
      </button>
      <button :class="{ selected: section === 'memory' }" @click="section = 'memory'">
        Memory
      </button>
    </div>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <div v-if="section === 'files'" class="settings-card prompt-files-card">
      <h2>System prompt files</h2><p class="muted">Files loaded into the agent system prompt when present in this workspace.</p>
      <label v-for="name in entries.filter((entry) => ['AGENTS.md', 'SOUL.md', 'PROFILE.md'].includes(entry.filename)).map((entry) => entry.filename)" :key="name" class="skills-checkbox"><input type="checkbox" :checked="promptFiles.includes(name)" @change="togglePromptFile(name)" />{{ name }}</label>
      <p v-if="!entries.some((entry) => ['AGENTS.md', 'SOUL.md', 'PROFILE.md'].includes(entry.filename))" class="muted">No supported system prompt files are present.</p>
    </div>
    <MemoryGraphView
      v-if="section === 'memory' && showGraph"
      :key="graphRevision"
      :agent-id="workbench.agentId"
      @close="showGraph = false"
      @open-file="openGraphFile"
    />
    <div v-else class="files-layout">
      <div class="settings-card">
        <button
          v-for="entry in entries"
          :key="entry.path"
          class="provider-row"
          :class="{ selected: selected === entry.filename }"
          @click="open(entry.filename)"
        >
          {{ entry.filename }}
        </button>
        <p v-if="!entries.length" class="muted">No Markdown files yet.</p>
      </div>
      <div class="settings-card file-editor-card">
        <div class="model-list-header">
          <h2>{{ selected || 'Select a file' }}</h2>
          <button v-if="selected" :disabled="content === original" @click="save">Save</button>
        </div>
        <textarea
          v-if="selected"
          v-model="content"
          :aria-label="`Edit ${selected}`"
          spellcheck="false"
        />
        <p v-else class="muted">Choose a file to read or edit it.</p>
      </div>
    </div>
  </section>
</template>

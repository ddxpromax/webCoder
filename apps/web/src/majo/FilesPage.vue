<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { apiRequest } from './api'
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

function endpoint(name?: string) {
  return `/workspace/${section.value}${name ? `/${encodeURIComponent(name)}` : ''}`
}

async function refresh() {
  error.value = ''
  selected.value = ''
  content.value = ''
  original.value = ''
  try {
    entries.value = await apiRequest<WorkspaceFile[]>(endpoint(), {}, workbench.agentId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load files.'
  }
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
      <button @click="refresh">Refresh</button>
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
    <div class="files-layout">
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

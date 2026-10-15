<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import ChatPage from './ChatPage.vue'
import {
  apiRequest,
  getCodingProject,
  listCodeFiles,
  readCodeFile,
  writeCodeFile,
  type CodeFile,
  type CodingProject,
} from './api'
import { workbench } from './state'

const project = ref<CodingProject | null>(null)
const files = ref<CodeFile[]>([])
const selectedPath = ref('')
const editorText = ref('')
const originalText = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const projectName = ref('')
const projectPickerOpen = ref(false)
const availableProjects = ref<Array<{ path: string; name: string }>>([])

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [current, entries, projects] = await Promise.all([
      getCodingProject(workbench.agentId),
      listCodeFiles(workbench.agentId),
      apiRequest<Array<{ path: string; name: string }>>(
        '/workspace/coding-project/list',
        {},
        workbench.agentId,
      ),
    ])
    project.value = current
    files.value = entries.sort((left, right) => left.path.localeCompare(right.path))
    availableProjects.value = projects
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load project.'
  } finally {
    loading.value = false
  }
}

async function openFile(file: CodeFile) {
  if (!file.path || file.is_dir) return
  if (editorText.value !== originalText.value && !window.confirm('Discard unsaved changes?')) return
  error.value = ''
  try {
    const result = await readCodeFile(file.path, workbench.agentId)
    selectedPath.value = file.path
    editorText.value = result.content
    originalText.value = result.content
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not open file.'
  }
}

async function saveFile() {
  if (!selectedPath.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await writeCodeFile(selectedPath.value, editorText.value, workbench.agentId)
    originalText.value = editorText.value
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save file.'
  } finally {
    saving.value = false
  }
}

async function selectProject(path: string | null) {
  error.value = ''
  try {
    await apiRequest<CodingProject>(
      '/workspace/coding-project',
      { method: 'PUT', body: JSON.stringify({ path }) },
      workbench.agentId,
    )
    selectedPath.value = ''
    editorText.value = ''
    originalText.value = ''
    projectPickerOpen.value = false
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not select project.'
  }
}

async function createProject() {
  const name = projectName.value.trim()
  if (!name) return
  error.value = ''
  try {
    const created = await apiRequest<{ path: string }>(
      '/workspace/coding-project/create',
      { method: 'POST', body: JSON.stringify({ name }) },
      workbench.agentId,
    )
    projectName.value = ''
    await selectProject(created.path)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not create project.'
  }
}

watch(
  () => workbench.agentId,
  () => void refresh(),
)
onMounted(() => void refresh())
</script>

<template>
  <div class="coding-page">
    <aside class="coding-files">
      <div class="coding-files-header">
        <span>EXPLORER</span>
        <button class="icon-button small" title="Refresh files" @click="refresh">↻</button>
      </div>
      <button class="project-selector" @click="projectPickerOpen = !projectPickerOpen">
        <span>▾ {{ project?.name || 'Workspace' }}</span>
      </button>
      <div v-if="projectPickerOpen" class="project-picker">
        <button @click="selectProject(null)">Default workspace</button>
        <button
          v-for="item in availableProjects"
          :key="item.path"
          @click="selectProject(item.path)"
        >
          {{ item.name }}
        </button>
        <form @submit.prevent="createProject">
          <input
            v-model="projectName"
            aria-label="New project name"
            placeholder="New project name"
          />
          <button type="submit">Create</button>
        </form>
      </div>
      <p v-if="loading" class="sidebar-empty">Loading files…</p>
      <div class="file-list">
        <button
          v-for="file in files"
          :key="file.path"
          class="file-row"
          :class="{ selected: selectedPath === file.path }"
          :title="file.path"
          @click="openFile(file)"
        >
          ▤ {{ file.path }}
        </button>
      </div>
    </aside>

    <section class="coding-editor">
      <div class="editor-tabbar">
        <span
          >{{ selectedPath || 'No file open' }}{{ editorText !== originalText ? ' ●' : '' }}</span
        >
        <button
          v-if="selectedPath"
          :disabled="saving || editorText === originalText"
          @click="saveFile"
        >
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
      </div>
      <textarea
        v-if="selectedPath"
        v-model="editorText"
        class="code-editor"
        :aria-label="`Edit ${selectedPath}`"
        spellcheck="false"
        @keydown.meta.s.prevent="saveFile"
        @keydown.ctrl.s.prevent="saveFile"
      />
      <div v-else class="editor-empty">Choose a file to view or edit it.</div>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    </section>

    <aside class="coding-chat"><ChatPage /></aside>
  </div>
</template>

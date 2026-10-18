<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ChatPage from './ChatPage.vue'
import GitPanel from './GitPanel.vue'
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
interface EditorTab { path: string; content: string; original: string }
const tabs = ref<EditorTab[]>([])
const activeTabPath = ref('')
const activeTab = computed(() => tabs.value.find((tab) => tab.path === activeTabPath.value))
const selectedPath = computed(() => activeTab.value?.path || '')
const editorText = computed({
  get: () => activeTab.value?.content || '',
  set: (content: string) => { if (activeTab.value) activeTab.value.content = content },
})
const originalText = computed(() => activeTab.value?.original || '')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const projectName = ref('')
const projectPickerOpen = ref(false)
const codingModeEnabled = ref(false)
const availableProjects = ref<Array<{ path: string; name: string }>>([])
const tabStorageKey = computed(() => `majo:coding-tabs:${workbench.agentId}`)
let refreshVersion = 0

function persistTabs() {
  try { window.localStorage.setItem(tabStorageKey.value, JSON.stringify(tabs.value.map((tab) => tab.path))) }
  catch { /* Editor tabs remain available for the current page. */ }
}

async function restoreTabs(agentId: string, version: number) {
  let paths: string[] = []
  try {
    const raw = JSON.parse(window.localStorage.getItem(`majo:coding-tabs:${agentId}`) || '[]') as unknown
    paths = Array.isArray(raw) ? raw.filter((path): path is string => typeof path === 'string') : []
  } catch { /* A malformed saved value is ignored. */ }
  const restored = await Promise.all(paths.map(async (path) => {
    try {
      const result = await readCodeFile(path, agentId)
      return { path, content: result.content, original: result.content }
    } catch { return null }
  }))
  if (version !== refreshVersion || agentId !== workbench.agentId) return
  tabs.value = restored.filter((tab): tab is EditorTab => tab !== null)
  activeTabPath.value = tabs.value[0]?.path || ''
  persistTabs()
}

async function refresh() {
  const version = ++refreshVersion
  const agentId = workbench.agentId
  loading.value = true
  error.value = ''
  try {
    const [current, entries, projects, mode] = await Promise.all([
      getCodingProject(agentId),
      listCodeFiles(agentId),
      apiRequest<Array<{ path: string; name: string }>>(
        '/workspace/coding-project/list',
        {},
        agentId,
      ),
      apiRequest<{ enabled: boolean }>('/coding-mode', {}, agentId),
    ])
    if (version !== refreshVersion || agentId !== workbench.agentId) return
    project.value = current
    files.value = entries.sort((left, right) => left.path.localeCompare(right.path))
    availableProjects.value = projects
    codingModeEnabled.value = mode.enabled
    if (!tabs.value.length) await restoreTabs(agentId, version)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load project.'
  } finally {
    loading.value = false
  }
}

async function toggleCodingMode() {
  try {
    const result = await apiRequest<{ enabled: boolean }>(
      '/coding-mode',
      { method: 'POST', body: JSON.stringify({ enabled: !codingModeEnabled.value }) },
      workbench.agentId,
    )
    codingModeEnabled.value = result.enabled
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not change Coding Mode.'
  }
}

async function openFile(file: CodeFile) {
  if (!file.path || file.is_dir) return
  const existing = tabs.value.find((tab) => tab.path === file.path)
  if (existing) { activeTabPath.value = existing.path; return }
  error.value = ''
  try {
    const result = await readCodeFile(file.path, workbench.agentId)
    tabs.value.push({ path: file.path, content: result.content, original: result.content })
    activeTabPath.value = file.path
    persistTabs()
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
    if (activeTab.value) activeTab.value.original = editorText.value
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save file.'
  } finally {
    saving.value = false
  }
}

function closeTab(path: string) {
  const index = tabs.value.findIndex((tab) => tab.path === path)
  if (index < 0) return
  const tab = tabs.value[index]
  if (tab && tab.content !== tab.original && !window.confirm(`Close ${path} without saving changes?`)) return
  tabs.value.splice(index, 1)
  if (activeTabPath.value === path) activeTabPath.value = tabs.value[index]?.path || tabs.value[index - 1]?.path || ''
  persistTabs()
}

async function selectProject(path: string | null) {
  error.value = ''
  try {
    await apiRequest<CodingProject>(
      '/workspace/coding-project',
      { method: 'PUT', body: JSON.stringify({ path }) },
      workbench.agentId,
    )
    tabs.value = []
    activeTabPath.value = ''
    persistTabs()
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
  () => { tabs.value = []; activeTabPath.value = ''; void refresh() },
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
      <button class="project-selector" @click="toggleCodingMode">
        Coding Mode: {{ codingModeEnabled ? 'On' : 'Off' }}
      </button>
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
        <div class="editor-tabs">
          <button
            v-for="tab in tabs"
            :key="tab.path"
            class="editor-tab"
            :class="{ active: activeTabPath === tab.path }"
            :title="tab.path"
            @click="activeTabPath = tab.path"
          >
            {{ tab.path.split('/').pop() }}{{ tab.content !== tab.original ? ' ●' : '' }}
            <span title="Close tab" @click.stop="closeTab(tab.path)">×</span>
          </button>
          <span v-if="!tabs.length">No file open</span>
        </div>
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

    <aside class="coding-chat"><GitPanel /><ChatPage :workspace="project?.path" /></aside>
  </div>
</template>

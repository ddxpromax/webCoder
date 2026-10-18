<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ChatPage from './ChatPage.vue'
import GitPanel from './GitPanel.vue'
import MonacoCodeEditor, { disposeMonacoModel, disposeMonacoModels } from './MonacoCodeEditor.vue'
import {
  apiRequest,
  agentHeaders,
  getCodingProject,
  listCodeFiles,
  readCodeFile,
  writeCodeFile,
  type CodeFile,
  type CodingProject,
} from './api'
import { appendCodingComposer, workbench } from './state'
import { subscribeWorkspaceWatch, type WorkspaceFileChange } from './workspaceWatch'

const project = ref<CodingProject | null>(null)
const files = ref<CodeFile[]>([])
interface EditorTab { path: string; content: string; original: string }
interface FileTreeNode { name: string; path: string; directory: boolean; file?: CodeFile; children: FileTreeNode[] }
interface FileTreeRow { name: string; path: string; directory: boolean; file?: CodeFile; depth: number }
const tabs = ref<EditorTab[]>([])
const activeTabPath = ref('')
const externalChanges = ref<Record<string, { content: string | null; change: WorkspaceFileChange['change'] }>>({})
const activeTab = computed(() => tabs.value.find((tab) => tab.path === activeTabPath.value))
const selectedPath = computed(() => activeTab.value?.path || '')
const activeExternalChange = computed(() => selectedPath.value ? externalChanges.value[selectedPath.value] : undefined)
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
const expandedDirectories = ref(new Set<string>())
const previewMode = ref(false)
const binaryPreviewUrl = ref('')
const binaryPreviewLoading = ref(false)
const monacoEditor = ref<{ copySelectionToChat: () => void } | null>(null)
let binaryPreviewObjectUrl = ''
let refreshVersion = 0
let unsubscribeWorkspaceWatch: (() => void) | undefined
let watchKey = ''

const previewKind = computed<'image' | 'pdf' | 'markdown' | 'csv' | 'json' | 'none'>(() => {
  const extension = selectedPath.value.split('.').pop()?.toLowerCase()
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico'].includes(extension || '')) return 'image'
  if (extension === 'pdf') return 'pdf'
  if (['md', 'mdx'].includes(extension || '')) return 'markdown'
  if (extension === 'csv') return 'csv'
  if (['json', 'jsonc'].includes(extension || '')) return 'json'
  return 'none'
})
const previewable = computed(() => previewKind.value !== 'none')
const csvRows = computed(() => editorText.value.trimEnd().split(/\r?\n/).slice(0, 501).map((line) => {
  const cells: string[] = []; let cell = ''; let quoted = false
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index]
    if (char === '"') { if (quoted && line[index + 1] === '"') { cell += '"'; index += 1 } else quoted = !quoted }
    else if (char === ',' && !quoted) { cells.push(cell); cell = '' } else cell += char
  }
  cells.push(cell); return cells
}))
const formattedJson = computed(() => {
  if (previewKind.value !== 'json') return { text: '', error: '' }
  try { return { text: JSON.stringify(JSON.parse(editorText.value), null, 2), error: '' } }
  catch (cause) { return { text: editorText.value, error: cause instanceof Error ? cause.message : 'Invalid JSON.' } }
})

const fileTreeRows = computed<FileTreeRow[]>(() => {
  const roots: FileTreeNode[] = []
  const byPath = new Map<string, FileTreeNode>()
  for (const file of files.value) {
    const pieces = file.path.split('/').filter(Boolean)
    for (let index = 0; index < pieces.length; index += 1) {
      const path = pieces.slice(0, index + 1).join('/')
      if (byPath.has(path)) continue
      const directory = index < pieces.length - 1
      const node: FileTreeNode = { name: pieces[index] || path, path, directory, file: directory ? undefined : file, children: [] }
      byPath.set(path, node)
      const parent = index ? byPath.get(pieces.slice(0, index).join('/')) : undefined
      if (parent) parent.children.push(node)
      else roots.push(node)
    }
  }
  const rows: FileTreeRow[] = []
  const visit = (nodes: FileTreeNode[], depth: number) => {
    nodes.sort((left, right) => Number(right.directory) - Number(left.directory) || left.name.localeCompare(right.name))
    for (const node of nodes) {
      rows.push({ ...node, depth })
      if (node.directory && expandedDirectories.value.has(node.path)) visit(node.children, depth + 1)
    }
  }
  visit(roots, 0)
  return rows
})

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
    startWorkspaceWatch(agentId, current.path)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load project.'
  } finally {
    loading.value = false
  }
}

async function reloadFileList(agentId: string) {
  try {
    files.value = (await listCodeFiles(agentId)).sort((left, right) => left.path.localeCompare(right.path))
  } catch { /* The next explicit refresh reports a persistent filesystem error. */ }
}

async function recordExternalChange(change: WorkspaceFileChange) {
  const tab = tabs.value.find((candidate) => candidate.path === change.path)
  if (!tab || tab.content !== tab.original) return
  if (change.change === 'deleted') {
    externalChanges.value = { ...externalChanges.value, [change.path]: { content: null, change: 'deleted' } }
    return
  }
  try {
    const updated = await readCodeFile(change.path, workbench.agentId)
    if (updated.content !== tab.content) {
      externalChanges.value = { ...externalChanges.value, [change.path]: { content: updated.content, change: change.change } }
    }
  } catch { /* A concurrent rename/delete will be reported by the following watcher event. */ }
}

function handleWorkspaceChanges(changes: WorkspaceFileChange[]) {
  const agentId = workbench.agentId
  if (changes.some((change) => change.change === 'added' || change.change === 'deleted')) void reloadFileList(agentId)
  for (const change of changes) void recordExternalChange(change)
}

function startWorkspaceWatch(agentId: string, projectPath: string) {
  const key = `${agentId}:${projectPath}`
  if (watchKey === key) return
  unsubscribeWorkspaceWatch?.()
  watchKey = key
  unsubscribeWorkspaceWatch = subscribeWorkspaceWatch(agentId, handleWorkspaceChanges)
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
    const extension = file.path.split('.').pop()?.toLowerCase()
    const binary = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico', 'pdf'].includes(extension || '')
    const result = binary ? { content: '' } : await readCodeFile(file.path, workbench.agentId)
    tabs.value.push({ path: file.path, content: result.content, original: result.content })
    activeTabPath.value = file.path
    previewMode.value = binary
    persistTabs()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not open file.'
  }
}

function binaryPreviewPath(path: string) {
  return `/api/workspace/binary-files/${path.split('/').map(encodeURIComponent).join('/')}`
}

async function loadBinaryPreview() {
  if (!previewMode.value || !['image', 'pdf'].includes(previewKind.value) || !selectedPath.value) return
  binaryPreviewLoading.value = true
  if (binaryPreviewObjectUrl) URL.revokeObjectURL(binaryPreviewObjectUrl)
  binaryPreviewObjectUrl = ''; binaryPreviewUrl.value = ''
  try {
    const response = await fetch(binaryPreviewPath(selectedPath.value), { headers: agentHeaders(workbench.agentId) })
    if (!response.ok) throw new Error(`Preview failed (HTTP ${response.status}).`)
    binaryPreviewObjectUrl = URL.createObjectURL(await response.blob())
    binaryPreviewUrl.value = binaryPreviewObjectUrl
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load the file preview.'
  } finally { binaryPreviewLoading.value = false }
}

function selectTreeRow(row: FileTreeRow) {
  if (row.directory) {
    const next = new Set(expandedDirectories.value)
    if (next.has(row.path)) next.delete(row.path)
    else next.add(row.path)
    expandedDirectories.value = next
  } else if (row.file) {
    void openFile(row.file)
  }
}

async function saveFile() {
  if (!selectedPath.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await writeCodeFile(selectedPath.value, editorText.value, workbench.agentId)
    if (activeTab.value) activeTab.value.original = editorText.value
    const next = { ...externalChanges.value }; delete next[selectedPath.value]; externalChanges.value = next
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save file.'
  } finally {
    saving.value = false
  }
}

function acceptExternalChange(path: string) {
  const change = externalChanges.value[path]
  const tab = tabs.value.find((candidate) => candidate.path === path)
  if (tab && change?.content !== null && change?.content !== undefined) {
    tab.content = change.content
    tab.original = change.content
  } else if (change?.content === null) {
    closeTab(path)
  }
  const next = { ...externalChanges.value }; delete next[path]; externalChanges.value = next
}

function keepLocalChange(path: string) {
  const next = { ...externalChanges.value }; delete next[path]; externalChanges.value = next
}

function appendEditorContext(context: string) {
  appendCodingComposer(context)
}

function closeTab(path: string) {
  const index = tabs.value.findIndex((tab) => tab.path === path)
  if (index < 0) return
  const tab = tabs.value[index]
  if (tab && tab.content !== tab.original && !window.confirm(`Close ${path} without saving changes?`)) return
  tabs.value.splice(index, 1)
  disposeMonacoModel(path)
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
    externalChanges.value = {}
    disposeMonacoModels()
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
  () => { tabs.value = []; externalChanges.value = {}; disposeMonacoModels(); activeTabPath.value = ''; watchKey = ''; unsubscribeWorkspaceWatch?.(); void refresh() },
)
watch([selectedPath, previewMode, () => workbench.agentId], () => void loadBinaryPreview())
onMounted(() => void refresh())
onBeforeUnmount(() => { unsubscribeWorkspaceWatch?.(); disposeMonacoModels(); if (binaryPreviewObjectUrl) URL.revokeObjectURL(binaryPreviewObjectUrl) })
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
          v-for="row in fileTreeRows"
          :key="row.path"
          class="file-row"
          :class="{ selected: selectedPath === row.path, directory: row.directory }"
          :style="{ paddingLeft: `${10 + row.depth * 14}px` }"
          :title="row.path"
          @click="selectTreeRow(row)"
        >
          {{ row.directory ? (expandedDirectories.has(row.path) ? '▾' : '▸') : '▤' }} {{ row.name }}
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
        <button v-if="previewable" @click="previewMode = !previewMode">{{ previewMode ? 'Edit' : 'Preview' }}</button>
        <button v-if="selectedPath && !previewMode" title="Copy selection to Chat" @click="monacoEditor?.copySelectionToChat()">Copy to Chat</button>
      </div>
      <div v-if="selectedPath && activeExternalChange" class="external-change" role="status">
        <span>{{ activeExternalChange.change === 'deleted' ? 'This open file was deleted on disk.' : 'This file changed on disk.' }}</span>
        <button @click="acceptExternalChange(selectedPath)">{{ activeExternalChange.change === 'deleted' ? 'Close tab' : 'Load change' }}</button>
        <button @click="keepLocalChange(selectedPath)">Keep current</button>
      </div>
      <div v-if="selectedPath && previewMode" class="file-preview">
        <p v-if="binaryPreviewLoading" class="muted">Loading preview…</p>
        <img v-else-if="previewKind === 'image' && binaryPreviewUrl" :src="binaryPreviewUrl" :alt="selectedPath" />
        <embed v-else-if="previewKind === 'pdf' && binaryPreviewUrl" :src="binaryPreviewUrl" type="application/pdf" :title="selectedPath" />
        <pre v-else-if="previewKind === 'markdown'" class="markdown-preview">{{ editorText }}</pre>
        <div v-else-if="previewKind === 'csv'" class="csv-preview"><table><tbody><tr v-for="(row, index) in csvRows" :key="index"><th v-for="(cell, cellIndex) in (index === 0 ? row : [])" :key="cellIndex">{{ cell }}</th><td v-for="(cell, cellIndex) in (index ? row : [])" :key="cellIndex">{{ cell }}</td></tr></tbody></table></div>
        <div v-else-if="previewKind === 'json'" class="json-preview"><p v-if="formattedJson.error" class="inline-error">{{ formattedJson.error }}</p><pre>{{ formattedJson.text }}</pre></div>
      </div>
      <MonacoCodeEditor
        v-else-if="selectedPath"
        ref="monacoEditor"
        :path="selectedPath"
        :content="editorText"
        @change="editorText = $event"
        @save="saveFile"
        @copy-context="appendEditorContext"
      />
      <div v-else class="editor-empty">Choose a file to view or edit it.</div>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    </section>

    <aside class="coding-chat"><GitPanel /><ChatPage :workspace="project?.path" /></aside>
  </div>
</template>

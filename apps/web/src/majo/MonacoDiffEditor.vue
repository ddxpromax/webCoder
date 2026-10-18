<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'
import { languageForPath } from './editorContext'

const props = defineProps<{ path: string; original: string; modified: string }>()
const host = ref<HTMLElement | null>(null)
let editor: monaco.editor.IStandaloneDiffEditor | undefined
let originalModel: monaco.editor.ITextModel | undefined
let modifiedModel: monaco.editor.ITextModel | undefined

function uri(side: 'original' | 'modified') {
  return monaco.Uri.parse(`inmemory://webcoder-diff/${side}/${props.path.split('/').map(encodeURIComponent).join('/')}`)
}

function setModels() {
  const language = languageForPath(props.path)
  originalModel ??= monaco.editor.createModel(props.original, language, uri('original'))
  modifiedModel ??= monaco.editor.createModel(props.modified, language, uri('modified'))
  monaco.editor.setModelLanguage(originalModel, language)
  monaco.editor.setModelLanguage(modifiedModel, language)
  if (originalModel.getValue() !== props.original) originalModel.setValue(props.original)
  if (modifiedModel.getValue() !== props.modified) modifiedModel.setValue(props.modified)
  editor?.setModel({ original: originalModel, modified: modifiedModel })
}

onMounted(() => {
  if (!host.value) return
  editor = monaco.editor.createDiffEditor(host.value, {
    theme: 'vs-dark', automaticLayout: true, renderSideBySide: false,
    readOnly: true, originalEditable: false, minimap: { enabled: false },
    fontSize: 13, lineNumbers: 'on', scrollBeyondLastLine: false,
    wordWrap: 'off', renderOverviewRuler: false,
  })
  setModels()
})

watch([() => props.path, () => props.original, () => props.modified], setModels)

onBeforeUnmount(() => {
  editor?.dispose()
  originalModel?.dispose()
  modifiedModel?.dispose()
})
</script>

<template><div ref="host" class="monaco-diff-editor" /></template>

<style scoped>.monaco-diff-editor { flex: 1; min-height: 0; }</style>

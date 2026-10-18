<script lang="ts">
import * as monaco from 'monaco-editor'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker'
import CssWorker from 'monaco-editor/esm/vs/language/css/css.worker?worker'
import HtmlWorker from 'monaco-editor/esm/vs/language/html/html.worker?worker'
import TsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker'
import { formatEditorSelectionForChat, languageForPath } from './editorContext'

const models = new Map<string, monaco.editor.ITextModel>()

const monacoEnvironment = self as typeof self & {
  MonacoEnvironment?: { getWorker: (_: string, label: string) => Worker }
}

monacoEnvironment.MonacoEnvironment ??= {
  getWorker(_moduleId, label) {
    if (label === 'json') return new JsonWorker()
    if (label === 'css' || label === 'scss' || label === 'less') return new CssWorker()
    if (label === 'html' || label === 'handlebars' || label === 'razor') return new HtmlWorker()
    if (label === 'typescript' || label === 'javascript') return new TsWorker()
    return new EditorWorker()
  },
}

function modelUri(path: string) {
  return monaco.Uri.parse(`inmemory://webcoder/${path.split('/').map(encodeURIComponent).join('/')}`)
}

function modelFor(path: string, content: string) {
  let model = models.get(path)
  if (!model || model.isDisposed()) {
    model = monaco.editor.createModel(content, languageForPath(path), modelUri(path))
    models.set(path, model)
  }
  return model
}

/** Remove the durable Monaco model after the user closes its Coding tab. */
export function disposeMonacoModel(path: string) {
  const model = models.get(path)
  model?.dispose()
  models.delete(path)
}

/** A project or agent switch must not reuse models for same-named paths. */
export function disposeMonacoModels() {
  for (const model of models.values()) model.dispose()
  models.clear()
}
</script>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{ path: string; content: string }>()
const emit = defineEmits<{ change: [content: string]; save: []; copyContext: [context: string] }>()
const host = ref<HTMLElement | null>(null)
let editor: monaco.editor.IStandaloneCodeEditor | undefined
let contentSubscription: monaco.IDisposable | undefined
let applyingParentValue = false

function selectModel(path: string, content: string) {
  const model = modelFor(path, content)
  editor?.setModel(model)
  monaco.editor.setModelLanguage(model, languageForPath(path))
  if (model.getValue() !== content) {
    applyingParentValue = true
    model.setValue(content)
    applyingParentValue = false
  }
}

function disposeCurrentListener() {
  contentSubscription?.dispose()
  contentSubscription = editor?.onDidChangeModelContent(() => {
    if (!applyingParentValue) emit('change', editor?.getValue() || '')
  })
}

function copySelectionToChat() {
  const model = editor?.getModel()
  const selection = editor?.getSelection()
  if (!model || !selection) return
  if (selection.isEmpty()) {
    emit('copyContext', props.path)
    return
  }
  emit('copyContext', formatEditorSelectionForChat(props.path, model.getValue(), selection))
}

defineExpose({ copySelectionToChat })

onMounted(() => {
  if (!host.value) return
  editor = monaco.editor.create(host.value, {
    model: modelFor(props.path, props.content),
    theme: 'vs-dark', automaticLayout: true, minimap: { enabled: false }, fontSize: 13,
    lineNumbers: 'on', scrollBeyondLastLine: false, wordWrap: 'off', tabSize: 2,
    renderLineHighlight: 'line', suggestOnTriggerCharacters: true,
    acceptSuggestionOnCommitCharacter: true, quickSuggestions: true,
    parameterHints: { enabled: true }, hover: { enabled: true }, gotoLocation: { multiple: 'goto' },
  })
  editor.addAction({
    id: 'webcoder.save-file', label: 'Save file', keybindings: [
      monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS,
    ],
    run: () => emit('save'),
  })
  disposeCurrentListener()
})

watch(() => props.path, (path) => {
  if (!editor) return
  selectModel(path, props.content)
  disposeCurrentListener()
})

watch(() => props.content, (content) => {
  const model = editor?.getModel()
  if (!model || model.getValue() === content) return
  applyingParentValue = true
  model.setValue(content)
  applyingParentValue = false
})

onBeforeUnmount(() => {
  contentSubscription?.dispose()
  editor?.dispose()
})
</script>

<template><div ref="host" class="monaco-code-editor" /></template>

<style scoped>
.monaco-code-editor { flex: 1; min-height: 0; }
</style>

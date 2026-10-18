<script setup lang="ts">
import { computed } from 'vue'
import type { LiveToolCall } from './api'
import { parseToolCallInput, prettyToolCallValue, toolCallTitle } from './toolCallPresentation'

const props = defineProps<{ tool: LiveToolCall }>()
const params = computed(() => parseToolCallInput(props.tool.input))
const title = computed(() => toolCallTitle(props.tool.name, params.value))
const input = computed(() => prettyToolCallValue(params.value && Object.keys(params.value).length ? params.value : props.tool.input))
const result = computed(() => props.tool.result || '')
const isEdit = computed(() => ['edit_file', 'write_file', 'append_file'].includes(props.tool.name))
const oldText = computed(() => typeof params.value.old_text === 'string' ? params.value.old_text : '')
const newText = computed(() => typeof params.value.new_text === 'string'
  ? params.value.new_text : typeof params.value.content === 'string' ? params.value.content : '')
</script>

<template>
  <details class="tool-call-card" :class="tool.status" :open="tool.status === 'calling'">
    <summary>
      <span class="tool-state">{{ tool.status === 'calling' ? '◌' : tool.status === 'error' ? '×' : '✓' }}</span>
      <strong>{{ title }}</strong><small>{{ tool.status === 'calling' ? 'Running…' : tool.status }}</small>
    </summary>
    <div v-if="tool.status === 'error'" class="tool-card-body">
      <strong>Input</strong><pre>{{ input }}</pre>
      <template v-if="result"><strong>Error</strong><pre>{{ result }}</pre></template>
    </div>
    <div v-else-if="isEdit && (oldText || newText)" class="tool-card-diff">
      <div v-for="(line, index) in oldText.split('\n')" :key="`old-${index}`" class="tool-card-del">− {{ line }}</div>
      <div v-for="(line, index) in newText.split('\n')" :key="`new-${index}`" class="tool-card-add">+ {{ line }}</div>
    </div>
    <div v-else class="tool-card-body">
      <template v-if="result"><strong>Output</strong><pre>{{ result }}</pre></template>
      <template v-else><strong>Input</strong><pre>{{ input || 'No input was provided.' }}</pre></template>
    </div>
  </details>
</template>

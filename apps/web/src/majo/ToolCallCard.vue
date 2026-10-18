<script setup lang="ts">
import { computed } from 'vue'
import type { LiveToolCall } from './api'

const props = defineProps<{ tool: LiveToolCall }>()
const title = computed(() => props.tool.name.replaceAll('_', ' '))
const input = computed(() => {
  try { return JSON.stringify(JSON.parse(props.tool.input), null, 2) }
  catch { return props.tool.input }
})
</script>

<template>
  <details class="tool-call-card" :class="tool.status" :open="tool.status === 'calling'">
    <summary><span class="tool-state">{{ tool.status === 'calling' ? '◌' : tool.status === 'error' ? '×' : '✓' }}</span><strong>{{ title }}</strong><small>{{ tool.status === 'calling' ? 'Running…' : tool.status }}</small></summary>
    <pre v-if="input">{{ input }}</pre><p v-else class="muted">No input was provided.</p>
  </details>
</template>

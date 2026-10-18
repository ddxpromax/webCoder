<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import mermaid from 'mermaid'
import { renderMarkdown } from './markdown'

const props = defineProps<{ content: string }>()
const html = computed(() => renderMarkdown(props.content))
const host = ref<HTMLElement | null>(null)
let diagramNumber = 0

mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'dark' })

async function renderMermaid() {
  const blocks = [...(host.value?.querySelectorAll('pre > code.language-mermaid') || [])]
  await Promise.all(blocks.map(async (code) => {
    const chart = code.textContent || ''
    if (!chart.trim()) return
    const pre = code.parentElement
    if (!pre) return
    try {
      const id = `majo-mermaid-${diagramNumber++}`
      const result = await mermaid.render(id, chart)
      const wrapper = document.createElement('div')
      wrapper.className = 'mermaid-diagram'
      wrapper.innerHTML = DOMPurify.sanitize(result.svg, { USE_PROFILES: { svg: true, svgFilters: true } })
      pre.replaceWith(wrapper)
    } catch {
      // Keep the original code block as the readable, copyable fallback.
    }
  }))
}

watch(html, () => void nextTick(renderMermaid))
onMounted(() => void renderMermaid())
</script>

<template><div ref="host" class="markdown-message" v-html="html" /></template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { apiRequest } from './api'

interface MemoryNode {
  id: string
  path: string
  name: string
  description: string
  indexed: boolean
  virtual: boolean
  section: string | null
}
interface MemoryEdge {
  source: string
  target: string
}
interface MemoryGraph {
  version: number
  nodes: MemoryNode[]
  edges: MemoryEdge[]
}
interface PositionedNode {
  id: string
  x: number
  y: number
  vx: number
  vy: number
  data: MemoryNode
}

const props = defineProps<{ agentId: string }>()
const emit = defineEmits<{ openFile: [path: string]; close: [] }>()
const width = 640
const height = 420
const snapshot = ref<MemoryGraph | null>(null)
const selected = ref<MemoryNode | null>(null)
const loading = ref(true)
const error = ref('')
let loadSequence = 0

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  selected.value = null
  try {
    const graph = await apiRequest<MemoryGraph>(
      `/agents/${encodeURIComponent(props.agentId)}/memory/graph`,
      {},
      props.agentId,
    )
    if (sequence === loadSequence) snapshot.value = graph
  } catch (cause) {
    if (sequence === loadSequence) {
      snapshot.value = null
      error.value = cause instanceof Error ? cause.message : 'Could not load memory graph.'
    }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

watch(() => props.agentId, () => void load(), { immediate: true })

const layout = computed(() => {
  if (!snapshot.value) return null
  const nodes = new Map<string, PositionedNode>()
  const count = Math.max(snapshot.value.nodes.length, 1)
  const rootCount = Math.max(
    snapshot.value.nodes.filter((node) => node.id.startsWith('root:') && !node.virtual).length,
    1,
  )
  let rootIndex = 0
  let fileIndex = 0
  for (const node of snapshot.value.nodes) {
    let x: number
    let y: number
    if (node.id.startsWith('root:') && !node.virtual) {
      const angle = (2 * Math.PI * rootIndex) / rootCount - Math.PI / 2
      x = width / 2 + Math.cos(angle) * 110
      y = height / 2 + Math.sin(angle) * 90
      rootIndex++
    } else {
      const angle = (2 * Math.PI * fileIndex) / count + 0.5
      const radius = 170 + ((fileIndex * 37) % 60)
      x = width / 2 + Math.cos(angle) * radius
      y = height / 2 + Math.sin(angle) * (radius * 0.7)
      fileIndex++
    }
    nodes.set(node.id, { id: node.id, x, y, vx: 0, vy: 0, data: node })
  }
  const links = snapshot.value.edges.filter(
    (edge) => nodes.has(edge.source) && nodes.has(edge.target),
  )
  for (let step = 0; step < 60; step++) {
    for (const link of links) {
      const source = nodes.get(link.source)!
      const target = nodes.get(link.target)!
      const dx = target.x - source.x
      const dy = target.y - source.y
      const distance = Math.sqrt(dx * dx + dy * dy) || 1
      const force = (distance - 130) * 0.02
      const fx = (dx / distance) * force
      const fy = (dy / distance) * force
      source.vx += fx
      source.vy += fy
      target.vx -= fx
      target.vy -= fy
    }
    const values = [...nodes.values()]
    for (let i = 0; i < values.length; i++) {
      for (let j = i + 1; j < values.length; j++) {
        const source = values[i]!
        const target = values[j]!
        const dx = target.x - source.x
        const dy = target.y - source.y
        const distanceSquared = dx * dx + dy * dy
        if (distanceSquared < 1 || distanceSquared > 200 * 200) continue
        const distance = Math.sqrt(distanceSquared)
        const force = (2200 / distanceSquared) * 10
        const fx = (dx / distance) * force
        const fy = (dy / distance) * force
        source.vx -= fx
        source.vy -= fy
        target.vx += fx
        target.vy += fy
      }
    }
    for (const node of nodes.values()) {
      if (!node.data.virtual) {
        node.x = Math.min(width - 30, Math.max(30, node.x + node.vx))
        node.y = Math.min(height - 24, Math.max(24, node.y + node.vy))
      }
      node.vx *= 0.85
      node.vy *= 0.85
    }
  }
  return { nodes, links }
})
</script>

<template>
  <div class="settings-card memory-graph">
    <div class="model-list-header">
      <h2>Memory graph</h2>
      <button @click="emit('close')">Back to files</button>
    </div>
    <p v-if="loading" class="muted">Loading memory graph…</p>
    <p v-else-if="error" class="inline-error" role="alert">{{ error }}</p>
    <template v-else-if="layout">
      <p class="muted">{{ layout.nodes.size }} nodes · {{ layout.links.length }} links</p>
      <svg
        class="memory-graph-canvas"
        :viewBox="`0 0 ${width} ${height}`"
        role="img"
        aria-label="Memory graph"
      >
        <line
          v-for="(link, index) in layout.links"
          :key="`${link.source}-${link.target}-${index}`"
          :x1="layout.nodes.get(link.source)?.x"
          :y1="layout.nodes.get(link.source)?.y"
          :x2="layout.nodes.get(link.target)?.x"
          :y2="layout.nodes.get(link.target)?.y"
          class="memory-graph-link"
        />
        <g
          v-for="node in layout.nodes.values()"
          :key="node.id"
          :transform="`translate(${node.x}, ${node.y})`"
          class="memory-graph-node"
          :class="{ selected: selected?.id === node.id, virtual: node.data.virtual }"
          tabindex="0"
          role="button"
          :aria-label="node.data.name"
          @click="selected = node.data"
          @keydown.enter="selected = node.data"
        >
          <circle :r="node.id.startsWith('root:') ? 9 : 6" />
          <text>{{ node.data.name }}</text>
        </g>
      </svg>
      <div v-if="selected" class="memory-graph-detail">
        <strong>{{ selected.name }}</strong>
        <p v-if="selected.description">{{ selected.description }}</p>
        <button v-if="selected.indexed" @click="emit('openFile', selected.path)">
          Open memory file
        </button>
      </div>
    </template>
  </div>
</template>

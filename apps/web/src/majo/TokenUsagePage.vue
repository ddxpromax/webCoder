<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'

interface UsageRecord {
  date: string
  provider_id: string
  model: string
  prompt_tokens: number
  completion_tokens: number
  call_count: number
}

interface AgentUsage {
  agent_id: string
  input_tokens: number
  output_tokens: number
  turns: number
  duration_ms: number
}

function localDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const endDate = ref(localDate(new Date()))
const startDate = ref(localDate(new Date(Date.now() - 30 * 24 * 60 * 60 * 1000)))
const records = ref<UsageRecord[]>([])
const agents = ref<AgentUsage[]>([])
const loading = ref(false)
const error = ref('')
let requestNumber = 0

async function refresh() {
  const currentRequest = ++requestNumber
  loading.value = true
  error.value = ''
  try {
    if (startDate.value > endDate.value) throw new Error('Start date must be before end date.')
    const dates = new URLSearchParams({ start_date: startDate.value, end_date: endDate.value })
    const [details, agentResponse] = await Promise.all([
      apiRequest<UsageRecord[]>(`/token-usage/details?${dates}`),
      apiRequest<{ agents: AgentUsage[] }>(
        `/token-usage/agents?start_date=${encodeURIComponent(startDate.value)}`,
      ),
    ])
    if (currentRequest !== requestNumber) return
    records.value = details
    agents.value = agentResponse.agents
  } catch (cause) {
    if (currentRequest !== requestNumber) return
    error.value = cause instanceof Error ? cause.message : 'Could not load token usage.'
  } finally {
    if (currentRequest === requestNumber) loading.value = false
  }
}

const inputTokens = computed(() => records.value.reduce((sum, row) => sum + row.prompt_tokens, 0))
const outputTokens = computed(() => records.value.reduce((sum, row) => sum + row.completion_tokens, 0))
const calls = computed(() => records.value.reduce((sum, row) => sum + row.call_count, 0))
const byModel = computed(() => {
  const totals = new Map<string, { input: number; output: number; calls: number }>()
  for (const row of records.value) {
    const key = row.provider_id ? `${row.provider_id}:${row.model}` : row.model
    const value = totals.get(key) ?? { input: 0, output: 0, calls: 0 }
    value.input += row.prompt_tokens
    value.output += row.completion_tokens
    value.calls += row.call_count
    totals.set(key, value)
  }
  return [...totals].sort((left, right) =>
    right[1].input + right[1].output - left[1].input - left[1].output,
  )
})
const byDate = computed(() => {
  const totals = new Map<string, { input: number; output: number; calls: number }>()
  for (const row of records.value) {
    const value = totals.get(row.date) ?? { input: 0, output: 0, calls: 0 }
    value.input += row.prompt_tokens
    value.output += row.completion_tokens
    value.calls += row.call_count
    totals.set(row.date, value)
  }
  return [...totals].sort((left, right) => left[0].localeCompare(right[0]))
})
const maxDailyTokens = computed(() =>
  Math.max(1, ...byDate.value.map(([, value]) => value.input + value.output)),
)
const number = (value: number) => new Intl.NumberFormat('en-US').format(value)

onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page usage-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow">SETTINGS</p>
        <h1>Token usage</h1>
      </div>
    </header>
    <form class="usage-filter" @submit.prevent="refresh">
      <label>From <input v-model="startDate" type="date" :max="endDate" /></label>
      <label>To <input v-model="endDate" type="date" :min="startDate" /></label>
      <button type="submit" :disabled="loading">{{ loading ? 'Loading…' : 'Apply' }}</button>
    </form>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <div class="usage-summary">
      <div><span>Calls</span><strong>{{ number(calls) }}</strong></div>
      <div><span>Input tokens</span><strong>{{ number(inputTokens) }}</strong></div>
      <div><span>Output tokens</span><strong>{{ number(outputTokens) }}</strong></div>
      <div><span>Total tokens</span><strong>{{ number(inputTokens + outputTokens) }}</strong></div>
    </div>
    <div v-if="!loading && records.length === 0" class="settings-card">
      No token usage data in the selected period.
    </div>
    <template v-else>
      <div class="settings-card">
        <h2>Daily trend</h2>
        <div class="usage-chart" role="img" aria-label="Daily input and output token totals">
          <div v-for="[day, value] in byDate" :key="day" class="usage-day" :title="`${day}: ${number(value.input + value.output)} tokens`">
            <div class="usage-bar" :style="{ height: `${Math.max(2, ((value.input + value.output) / maxDailyTokens) * 100)}%` }">
              <div class="usage-bar-output" :style="{ height: `${value.input + value.output ? (value.output / (value.input + value.output)) * 100 : 0}%` }" />
            </div>
            <small>{{ day.slice(5) }}</small>
          </div>
        </div>
        <p class="usage-legend">■ Input tokens &nbsp; <span>■ Output tokens</span></p>
      </div>
      <div class="settings-card">
        <h2>By model</h2>
        <div class="usage-table-wrap">
          <table class="usage-table">
            <thead><tr><th>Model</th><th>Input</th><th>Output</th><th>Calls</th></tr></thead>
            <tbody>
              <tr v-for="[name, value] in byModel" :key="name">
                <td>{{ name }}</td><td>{{ number(value.input) }}</td>
                <td>{{ number(value.output) }}</td><td>{{ number(value.calls) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="settings-card">
        <h2>By agent</h2>
        <div class="usage-table-wrap">
          <table class="usage-table">
            <thead><tr><th>Agent</th><th>Input</th><th>Output</th><th>Turns</th></tr></thead>
            <tbody>
              <tr v-for="agent in agents" :key="agent.agent_id">
                <td>{{ agent.agent_id }}</td><td>{{ number(agent.input_tokens) }}</td>
                <td>{{ number(agent.output_tokens) }}</td><td>{{ number(agent.turns) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { apiRequest } from './api'

interface Daily { date: string; chats: number; user_messages: number; assistant_messages: number; tool_calls: number; total_messages: number }
interface Channel { channel: string; session_count: number; user_messages: number; assistant_messages: number; total_messages: number }
interface Stats { total_active_sessions: number; total_messages: number; total_user_messages: number; total_assistant_messages: number; total_tool_calls: number; total_prompt_tokens: number; total_completion_tokens: number; total_llm_calls: number; by_date: Daily[]; channel_stats: Channel[] }
const iso = (date: Date) => date.toISOString().slice(0, 10)
const endDate = ref(iso(new Date()))
const startDate = ref(iso(new Date(Date.now() - 7 * 86400000)))
const stats = ref<Stats | null>(null)
const error = ref('')
const loading = ref(false)
const totalTokens = computed(() => (stats.value?.total_prompt_tokens ?? 0) + (stats.value?.total_completion_tokens ?? 0))
const maxMessages = computed(() => Math.max(1, ...(stats.value?.by_date.map(item => item.total_messages) ?? [0])))
const number = (value: number) => new Intl.NumberFormat('en-US').format(value)
async function refresh() {
  if (startDate.value > endDate.value) { error.value = 'Start date must be before end date.'; return }
  loading.value = true; error.value = ''
  try { stats.value = await apiRequest<Stats>(`/agent-stats?${new URLSearchParams({ start_date: startDate.value, end_date: endDate.value })}`) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not load agent statistics.' }
  finally { loading.value = false }
}
onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page usage-page">
    <header class="settings-heading"><div><p class="eyebrow">SETTINGS</p><h1>Agent statistics</h1></div></header>
    <form class="usage-filter" @submit.prevent="refresh"><label>From <input v-model="startDate" type="date" :max="endDate" /></label><label>To <input v-model="endDate" type="date" :min="startDate" /></label><button type="submit" :disabled="loading">{{ loading ? 'Loading…' : 'Apply' }}</button></form>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <template v-if="stats">
      <div class="usage-summary"><div><span>Active sessions</span><strong>{{ number(stats.total_active_sessions) }}</strong></div><div><span>Messages</span><strong>{{ number(stats.total_messages) }}</strong></div><div><span>Tool calls</span><strong>{{ number(stats.total_tool_calls) }}</strong></div><div><span>Tokens</span><strong>{{ number(totalTokens) }}</strong></div></div>
      <div class="settings-card"><h2>Daily activity</h2><div class="usage-chart" role="img" aria-label="Daily conversation activity"><div v-for="day in stats.by_date" :key="day.date" class="usage-day" :title="`${day.date}: ${day.total_messages} messages`"><div class="usage-bar" :style="{ height: `${Math.max(2, day.total_messages / maxMessages * 100)}%` }" /><small>{{ day.date.slice(5) }}</small></div></div><p class="usage-legend">Messages per day</p></div>
      <div class="settings-card"><h2>By channel</h2><div class="usage-table-wrap"><table class="usage-table"><thead><tr><th>Channel</th><th>Sessions</th><th>User</th><th>Assistant</th><th>Total</th></tr></thead><tbody><tr v-for="channel in stats.channel_stats" :key="channel.channel"><td>{{ channel.channel }}</td><td>{{ number(channel.session_count) }}</td><td>{{ number(channel.user_messages) }}</td><td>{{ number(channel.assistant_messages) }}</td><td>{{ number(channel.total_messages) }}</td></tr><tr v-if="!stats.channel_stats.length"><td colspan="5">No conversation data in the selected period.</td></tr></tbody></table></div></div>
    </template>
  </section>
</template>

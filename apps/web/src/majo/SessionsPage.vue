<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { apiRequest, type ChatSpec } from './api'
import { workbench } from './state'

const sessions = ref<ChatSpec[]>([])
const query = ref('')
const showArchived = ref(false)
const loading = ref(false)
const error = ref('')
const filtered = computed(() =>
  sessions.value.filter((chat) =>
    chat.name.toLowerCase().includes(query.value.trim().toLowerCase()),
  ),
)

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    sessions.value = await apiRequest<ChatSpec[]>(
      `/chats?archived=${showArchived.value}`,
      {},
      workbench.agentId,
    )
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load sessions.'
  } finally {
    loading.value = false
  }
}

async function toggleArchive(chat: ChatSpec) {
  try {
    await apiRequest(
      `/chats/${encodeURIComponent(chat.id)}/${chat.archived ? 'unarchive' : 'archive'}`,
      { method: 'POST' },
      workbench.agentId,
    )
    await refresh()
    window.dispatchEvent(new Event('majo-chats-changed'))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not update session.'
  }
}

async function remove(chat: ChatSpec) {
  if (!window.confirm(`Delete “${chat.name}”? This cannot be undone.`)) return
  try {
    await apiRequest(
      `/chats/${encodeURIComponent(chat.id)}`,
      { method: 'DELETE' },
      workbench.agentId,
    )
    await refresh()
    window.dispatchEvent(new Event('majo-chats-changed'))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not delete session.'
  }
}

onMounted(() => void refresh())
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow">CONTROL</p>
        <h1>Sessions</h1>
      </div>
      <button @click="refresh">Refresh</button>
    </header>
    <div class="settings-toolbar">
      <input v-model="query" aria-label="Search sessions" placeholder="Search sessions" />
      <label
        ><input v-model="showArchived" type="checkbox" @change="refresh" /> Show archived</label
      >
    </div>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="loading" class="muted">Loading sessions…</p>
    <div v-else class="data-list">
      <div v-for="chat in filtered" :key="chat.id" class="data-row">
        <div class="data-row-main">
          <strong>{{ chat.name }}</strong
          ><small>{{ chat.status }} · {{ chat.updated_at }}</small>
        </div>
        <RouterLink :to="`/chat/${encodeURIComponent(chat.id)}`">Open</RouterLink>
        <button @click="toggleArchive(chat)">{{ chat.archived ? 'Unarchive' : 'Archive' }}</button>
        <button class="danger" @click="remove(chat)">Delete</button>
      </div>
      <p v-if="!filtered.length" class="muted">No sessions found.</p>
    </div>
  </section>
</template>

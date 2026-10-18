<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { archiveChat, deleteChat, getChat, listAgents, listChats, messageText, updateChat } from './api'
import { selectAgent, workbench } from './state'
import { pluginRoutes } from './pluginRuntime'

const route = useRoute()
const router = useRouter()
const sidebarOpen = ref(true)
const showArchived = ref(false)
const search = ref('')
const searching = ref(false)
const contentMatches = ref<Set<string> | null>(null)
let searchVersion = 0
const archivedChats = ref<typeof workbench.chats>([])
const visibleChats = computed(() => {
  const query = search.value.trim().toLowerCase()
  const source = showArchived.value ? archivedChats.value : workbench.chats
  return source.filter((chat) => !query || (showArchived.value
    ? chat.name.toLowerCase().includes(query)
    : contentMatches.value?.has(chat.id)))
    .sort((left, right) => Number(Boolean(right.pinned)) - Number(Boolean(left.pinned)))
})
const nav = [
  { label: 'Chat', to: '/chat', icon: '◈' },
  { label: 'Coding', to: '/coding', icon: '⌘' },
  { label: 'Sessions', to: '/sessions', icon: '▣' },
  { label: 'Files', to: '/files', icon: '▤' },
  { label: 'Agents', to: '/agents', icon: '◉' },
  { label: 'Apps', to: '/apps', icon: '▰' },
  { label: 'Channels', to: '/channels', icon: '◌' },
  { label: 'Models', to: '/models', icon: '◇' },
  { label: 'Tools', to: '/tools', icon: '⌁' },
  { label: 'Skills', to: '/skills', icon: '✦' },
  { label: 'Skill Pool', to: '/skill-pool', icon: '◫' },
  { label: 'MCP', to: '/mcp', icon: '⌘' },
  { label: 'Scheduled jobs', to: '/cron', icon: '◷' },
  { label: 'Heartbeat', to: '/heartbeat', icon: '♥' },
  { label: 'Inbox', to: '/inbox', icon: '✉' },
  { label: 'Backups', to: '/backups', icon: '▧' },
  { label: 'Checkpoints', to: '/checkpoints', icon: '◫' },
]

async function refresh() {
  workbench.loading = true
  try {
    const [agents, chats, archived] = await Promise.all([listAgents(), listChats(workbench.agentId), listChats(workbench.agentId, true)])
    workbench.agents = agents.agents
    workbench.chats = chats
    archivedChats.value = archived
    workbench.error = ''
  } catch (cause) {
    workbench.error = cause instanceof Error ? cause.message : 'Could not load workspace.'
  } finally {
    workbench.loading = false
  }
}

async function searchContents() {
  const version = ++searchVersion
  const query = search.value.trim().toLowerCase()
  if (!query) { contentMatches.value = null; searching.value = false; return }
  searching.value = true
  const matches = new Set<string>()
  for (const chat of workbench.chats) {
    if (version !== searchVersion) return
    if (chat.name.toLowerCase().includes(query)) { matches.add(chat.id); continue }
    try {
      const history = await getChat(chat.id, workbench.agentId)
      if (history.messages.some((message) => messageText(message.content).toLowerCase().includes(query))) matches.add(chat.id)
    } catch { /* A deleted or inaccessible chat is skipped. */ }
  }
  if (version === searchVersion) { contentMatches.value = matches; searching.value = false }
}

async function togglePinned(id: string, pinned: boolean) {
  try { await updateChat(id, workbench.agentId, { pinned: !pinned }); await refresh() }
  catch (cause) { workbench.error = cause instanceof Error ? cause.message : 'Could not update the chat.' }
}

async function toggleArchived(id: string, archived: boolean) {
  try {
    await archiveChat(id, workbench.agentId, !archived); await refresh()
    if (route.params.id === id) await router.push('/chat')
  } catch (cause) { workbench.error = cause instanceof Error ? cause.message : 'Could not update the chat.' }
}

function changeAgent(event: Event) {
  selectAgent((event.target as HTMLSelectElement).value)
  void router.push('/chat')
}

async function removeChat(id: string) {
  try {
    await deleteChat(id, workbench.agentId)
    workbench.chats = workbench.chats.filter((chat) => chat.id !== id)
    if (route.params.id === id) await router.push('/chat')
  } catch (cause) {
    workbench.error = cause instanceof Error ? cause.message : 'Could not delete chat.'
  }
}

watch(
  () => workbench.agentId,
  () => void refresh(),
)
watch(search, () => void searchContents())
onMounted(() => {
  void refresh()
  window.addEventListener('majo-chats-changed', refresh)
})
onBeforeUnmount(() => window.removeEventListener('majo-chats-changed', refresh))
</script>

<template>
  <div class="workbench-shell">
    <header class="topbar">
      <div class="topbar-brand">
        <button class="menu-toggle" aria-label="Toggle sidebar" @click="sidebarOpen = !sidebarOpen">
          ☰
        </button>
        <RouterLink to="/chat" class="brand-mark">✳ <span>majo</span></RouterLink>
      </div>
      <div class="topbar-actions">
        <select :value="workbench.agentId" aria-label="Select agent" @change="changeAgent">
          <option v-for="agent in workbench.agents" :key="agent.id" :value="agent.id">
            {{ agent.name }}
          </option>
        </select>
        <span class="online-dot" title="Local workspace" />
      </div>
    </header>

    <div class="workbench-body">
      <aside v-if="sidebarOpen" class="sidebar">
        <nav aria-label="Main navigation">
          <RouterLink v-for="item in nav" :key="item.to" :to="item.to" class="nav-item">
            <span class="nav-icon">{{ item.icon }}</span
            >{{ item.label }}
          </RouterLink>
          <RouterLink v-for="plugin in pluginRoutes" :key="plugin.path" :to="plugin.path" class="nav-item">
            <span class="nav-icon">{{ plugin.icon || '◇' }}</span>{{ plugin.label }}
          </RouterLink>
        </nav>
        <div class="sidebar-section-title">{{ showArchived ? 'ARCHIVED CHATS' : 'RECENT CHATS' }}</div>
        <div class="chat-history-controls"><input v-model="search" aria-label="Search chats" placeholder="Search chats" /><button @click="showArchived = !showArchived">{{ showArchived ? 'Recent' : 'Archived' }}</button></div>
        <p v-if="searching && !showArchived" class="sidebar-empty">Searching conversation history…</p>
        <div class="session-list">
          <div v-for="chat in visibleChats" :key="chat.id" class="session-row">
            <RouterLink
              :to="`/chat/${encodeURIComponent(chat.id)}`"
              class="session-link"
              :title="chat.name"
            >
              {{ chat.name || 'New chat' }}
            </RouterLink>
            <button class="session-delete" :aria-label="`${chat.pinned ? 'Unpin' : 'Pin'} ${chat.name}`" :title="chat.pinned ? 'Unpin' : 'Pin'" @click="togglePinned(chat.id, Boolean(chat.pinned))">{{ chat.pinned ? '★' : '☆' }}</button>
            <button class="session-delete" :aria-label="`${showArchived ? 'Restore' : 'Archive'} ${chat.name}`" :title="showArchived ? 'Restore' : 'Archive'" @click="toggleArchived(chat.id, showArchived)">{{ showArchived ? '↩' : '▱' }}</button>
            <button
              class="session-delete"
              :aria-label="`Delete ${chat.name}`"
              title="Delete chat"
              @click="removeChat(chat.id)"
            >
              ×
            </button>
          </div>
          <p v-if="!visibleChats.length && !workbench.loading" class="sidebar-empty">
            No chats yet
          </p>
        </div>
        <div class="sidebar-bottom">
          <RouterLink to="/token-usage" class="nav-item"
            ><span class="nav-icon">▥</span>Token usage</RouterLink
          >
          <RouterLink to="/agent-stats" class="nav-item"
            ><span class="nav-icon">▦</span>Agent statistics</RouterLink
          >
          <RouterLink to="/settings" class="nav-item"
            ><span class="nav-icon">⚙</span>Settings</RouterLink
          >
        </div>
      </aside>
      <main class="main-panel">
        <p v-if="workbench.error" class="workspace-error" role="alert">{{ workbench.error }}</p>
        <RouterView />
      </main>
    </div>
  </div>
</template>

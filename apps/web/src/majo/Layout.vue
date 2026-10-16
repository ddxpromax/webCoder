<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { deleteChat, listAgents, listChats } from './api'
import { selectAgent, workbench } from './state'

const route = useRoute()
const router = useRouter()
const sidebarOpen = ref(true)
const nav = [
  { label: 'Chat', to: '/chat', icon: '◈' },
  { label: 'Coding', to: '/coding', icon: '⌘' },
  { label: 'Sessions', to: '/sessions', icon: '▣' },
  { label: 'Files', to: '/files', icon: '▤' },
  { label: 'Agents', to: '/agents', icon: '◉' },
  { label: 'Apps', to: '/apps', icon: '▰' },
  { label: 'Models', to: '/models', icon: '◇' },
  { label: 'Tools', to: '/tools', icon: '⌁' },
  { label: 'Skills', to: '/skills', icon: '✦' },
  { label: 'MCP', to: '/mcp', icon: '⌘' },
  { label: 'Scheduled jobs', to: '/cron', icon: '◷' },
  { label: 'Inbox', to: '/inbox', icon: '✉' },
  { label: 'Backups', to: '/backups', icon: '▧' },
  { label: 'Checkpoints', to: '/checkpoints', icon: '◫' },
]

async function refresh() {
  workbench.loading = true
  try {
    const [agents, chats] = await Promise.all([listAgents(), listChats(workbench.agentId)])
    workbench.agents = agents.agents
    workbench.chats = chats
    workbench.error = ''
  } catch (cause) {
    workbench.error = cause instanceof Error ? cause.message : 'Could not load workspace.'
  } finally {
    workbench.loading = false
  }
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
        </nav>
        <div class="sidebar-section-title">RECENT CHATS</div>
        <div class="session-list">
          <div v-for="chat in workbench.chats" :key="chat.id" class="session-row">
            <RouterLink
              :to="`/chat/${encodeURIComponent(chat.id)}`"
              class="session-link"
              :title="chat.name"
            >
              {{ chat.name || 'New chat' }}
            </RouterLink>
            <button
              class="session-delete"
              :aria-label="`Delete ${chat.name}`"
              title="Delete chat"
              @click="removeChat(chat.id)"
            >
              ×
            </button>
          </div>
          <p v-if="!workbench.chats.length && !workbench.loading" class="sidebar-empty">
            No chats yet
          </p>
        </div>
        <div class="sidebar-bottom">
          <RouterLink to="/token-usage" class="nav-item"
            ><span class="nav-icon">▥</span>Token usage</RouterLink
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

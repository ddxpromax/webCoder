import { createRouter, createWebHistory } from 'vue-router'
import WorkbenchLayout from '@/majo/Layout.vue'
import ChatPage from '@/majo/ChatPage.vue'
import CodingPage from '@/majo/CodingPage.vue'
import SessionsPage from '@/majo/SessionsPage.vue'
import FilesPage from '@/majo/FilesPage.vue'
import AgentsPage from '@/majo/AgentsPage.vue'
import ModelsPage from '@/majo/ModelsPage.vue'
import SettingsPage from '@/majo/SettingsPage.vue'
import EnvironmentsPage from '@/majo/EnvironmentsPage.vue'
import McpPage from '@/majo/McpPage.vue'
import ToolsPage from '@/majo/ToolsPage.vue'
import TokenUsagePage from '@/majo/TokenUsagePage.vue'
import SkillsPage from '@/majo/SkillsPage.vue'
import LoginPage from '@/majo/LoginPage.vue'
import CronPage from '@/majo/CronPage.vue'
import InboxPage from '@/majo/InboxPage.vue'
import BackupsPage from '@/majo/BackupsPage.vue'
import CheckpointsPage from '@/majo/CheckpointsPage.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/login', component: LoginPage },
    {
      path: '/',
      component: WorkbenchLayout,
      children: [
        { path: 'chat/:id?', component: ChatPage },
        { path: 'coding/:id?', component: CodingPage },
        { path: 'sessions', component: SessionsPage },
        { path: 'files', component: FilesPage },
        { path: 'agents', component: AgentsPage },
        { path: 'models', component: ModelsPage },
        { path: 'settings', component: SettingsPage },
        { path: 'settings/environments', component: EnvironmentsPage },
        { path: 'mcp', component: McpPage },
        { path: 'tools', component: ToolsPage },
        { path: 'token-usage', component: TokenUsagePage },
        { path: 'skills', component: SkillsPage },
        { path: 'cron', component: CronPage },
        { path: 'inbox', component: InboxPage },
        { path: 'backups', component: BackupsPage },
        { path: 'checkpoints', component: CheckpointsPage },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  try {
    const statusResponse = await fetch('/api/auth/status')
    if (!statusResponse.ok) return true
    const status = (await statusResponse.json()) as { enabled: boolean }
    if (!status.enabled) return to.path === '/login' ? '/chat' : true
    const token = window.localStorage.getItem('majo_auth_token')
    if (!token)
      return to.path === '/login' ? true : `/login?redirect=${encodeURIComponent(to.fullPath)}`
    const verifiedResponse = await fetch('/api/auth/verify', {
      headers: { Authorization: `Bearer ${token}` },
    })
    const verified =
      verifiedResponse.ok && ((await verifiedResponse.json()) as { valid: boolean }).valid
    if (!verified) {
      window.localStorage.removeItem('majo_auth_token')
      return to.path === '/login' ? true : `/login?redirect=${encodeURIComponent(to.fullPath)}`
    }
    return to.path === '/login' ? '/chat' : true
  } catch {
    return true
  }
})

export default router

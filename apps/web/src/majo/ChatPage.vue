<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createChat,
  decideApproval,
  getChat,
  getLoopStatus,
  listLoopModes,
  listApprovals,
  messageText,
  stopChat,
  streamChat,
  type ApprovalRequest,
  type ChatMessage,
  type LoopMode,
} from './api'
import { workbench } from './state'

const props = defineProps<{ workspace?: string }>()

const route = useRoute()
const router = useRouter()
const chatId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const messages = ref<ChatMessage[]>([])
const prompt = ref('')
const liveText = ref('')
const liveReasoning = ref('')
const error = ref('')
const loading = ref(false)
const sending = ref(false)
const approvals = ref<ApprovalRequest[]>([])
const loopModes = ref<LoopMode[]>([])
const selectedLoop = ref('')
const activeLoop = ref('')
const transcript = ref<HTMLElement | null>(null)
let turnController: AbortController | null = null
let approvalsTimer: ReturnType<typeof setInterval> | null = null
let requestVersion = 0

async function scrollToBottom() {
  await nextTick()
  transcript.value?.scrollTo({ top: transcript.value.scrollHeight, behavior: 'smooth' })
}

async function loadChat(id: string) {
  const version = ++requestVersion
  if (!id) {
    messages.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await getChat(id, workbench.agentId)
    if (version === requestVersion) {
      messages.value = result.messages
      await scrollToBottom()
    }
  } catch (cause) {
    if (version === requestVersion) {
      error.value = cause instanceof Error ? cause.message : 'Could not load chat.'
    }
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

async function refreshApprovals() {
  if (!sending.value || !chatId.value) return
  const chat = workbench.chats.find((item) => item.id === chatId.value)
  if (!chat) return
  try {
    const result = await listApprovals(chat.session_id)
    approvals.value = result.pending_approvals
  } catch {
    // Polling can recover on the next interval.
  }
}

async function loadLoopModes() {
  try { loopModes.value = await listLoopModes(workbench.agentId) }
  catch { loopModes.value = [] }
}

async function refreshLoopStatus(sessionId: string) {
  try {
    const status = await getLoopStatus(workbench.agentId, sessionId)
    activeLoop.value = status.state === 'running' && status.mode ? status.mode.name : ''
  } catch { activeLoop.value = '' }
}

async function answerApproval(item: ApprovalRequest, approved: boolean) {
  try {
    await decideApproval(item, approved)
    approvals.value = approvals.value.filter((entry) => entry.request_id !== item.request_id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not respond to approval.'
  }
}

async function submit() {
  const text = prompt.value.trim()
  if (!text || sending.value) return
  sending.value = true
  error.value = ''
  liveText.value = ''
  liveReasoning.value = ''
  prompt.value = ''
  const controller = new AbortController()
  turnController = controller

  try {
    let id = chatId.value
    if (!id) {
      const chat = await createChat(workbench.agentId)
      id = chat.id
      workbench.chats = [chat, ...workbench.chats]
      const section = route.path.startsWith('/coding') ? 'coding' : 'chat'
      await router.replace(`/${section}/${encodeURIComponent(id)}`)
    }
    const chat = workbench.chats.find((item) => item.id === id)
    const sessionId = chat?.session_id || `console:${id}`
    const mode = loopModes.value.find((item) => item.id === selectedLoop.value)
    const outgoing = mode ? `/${mode.slash_command} ${text}` : text
    messages.value.push({
      id: `local-${Date.now()}`,
      role: 'user',
      content: [{ type: 'text', text: outgoing }],
    })
    await scrollToBottom()
    await streamChat(
      { prompt: outgoing, sessionId, agentId: workbench.agentId, workspace: props.workspace },
      {
        onText: (delta) => {
          liveText.value += delta
          void scrollToBottom()
        },
        onReasoning: (delta) => {
          liveReasoning.value += delta
          void scrollToBottom()
        },
        onDone: () => { activeLoop.value = '' },
      },
      controller.signal,
    )
    await loadChat(id)
    window.dispatchEvent(new Event('majo-chats-changed'))
  } catch (cause) {
    if (!controller.signal.aborted) {
      error.value = cause instanceof Error ? cause.message : 'The agent turn failed.'
    }
    if (chatId.value) await loadChat(chatId.value)
  } finally {
    liveText.value = ''
    liveReasoning.value = ''
    approvals.value = []
    sending.value = false
    if (turnController === controller) turnController = null
  }
}

async function stop() {
  if (!sending.value || !chatId.value) return
  const controller = turnController
  try {
    await stopChat(chatId.value, workbench.agentId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not stop the turn.'
  } finally {
    controller?.abort()
    await loadChat(chatId.value)
  }
}

watch(
  [chatId, () => workbench.agentId],
  ([id]) => {
    if (!sending.value) void loadChat(id)
  },
  { immediate: true },
)

watch(sending, (active) => {
  if (approvalsTimer) clearInterval(approvalsTimer)
  approvalsTimer = active ? setInterval(() => void refreshApprovals(), 1500) : null
})

watch([() => workbench.agentId, chatId], () => void loadLoopModes(), { immediate: true })
watch(sending, (active) => {
  if (!active) return
  const chat = workbench.chats.find((item) => item.id === chatId.value)
  if (chat?.session_id) void refreshLoopStatus(chat.session_id)
})

onMounted(() => void loadLoopModes())

onBeforeUnmount(() => {
  if (approvalsTimer) clearInterval(approvalsTimer)
  turnController?.abort()
})
</script>

<template>
  <section class="chat-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">WORKSPACE</p>
        <h1>
          {{
            chatId ? workbench.chats.find((chat) => chat.id === chatId)?.name || 'Chat' : 'New chat'
          }}
        </h1>
      </div>
      <button class="icon-button" title="New chat" @click="router.push('/chat')">＋</button>
    </header>

    <div ref="transcript" class="chat-transcript" aria-live="polite">
      <div v-if="loading" class="empty-state">Loading conversation…</div>
      <div v-else-if="!messages.length && !sending" class="welcome">
        <div class="welcome-symbol">✳</div>
        <h2>What can I help you build?</h2>
        <p>Ask a question, explore a codebase, or start a coding task.</p>
      </div>
      <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
        <div class="message-label">
          {{ message.role === 'user' ? 'You' : message.type === 'reasoning' ? 'Thinking' : 'Majo' }}
        </div>
        <div class="message-body">{{ messageText(message.content) }}</div>
      </article>
      <article v-if="liveReasoning" class="message reasoning">
        <div class="message-label">Thinking</div>
        <div class="message-body">{{ liveReasoning }}</div>
      </article>
      <article v-if="liveText" class="message assistant">
        <div class="message-label">Majo</div>
        <div class="message-body">{{ liveText }}</div>
      </article>
      <div v-if="sending && !liveText" class="turn-indicator">{{ activeLoop ? `${activeLoop} loop is working…` : 'Agent is working…' }}</div>
    </div>

    <div v-if="approvals.length" class="approval-stack">
      <div v-for="approval in approvals" :key="approval.request_id" class="approval-card">
        <strong>Approval required</strong>
        <span>{{ approval.tool_display_name || approval.tool_name }}</span>
        <div>
          <button @click="answerApproval(approval, false)">Deny</button>
          <button class="primary" @click="answerApproval(approval, true)">Approve</button>
        </div>
      </div>
    </div>

    <div class="composer-wrap">
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <form class="composer" @submit.prevent="submit">
        <textarea
          v-model="prompt"
          aria-label="Message"
          placeholder="Ask anything, or describe a coding task…"
          rows="3"
          :disabled="sending"
          @keydown.enter.exact.prevent="submit"
        />
        <div class="composer-footer">
          <label class="loop-mode"><span>Mode</span><select v-model="selectedLoop" :disabled="sending"><option value="">Standard</option><option v-for="mode in loopModes.filter((item) => item.id !== 'default')" :key="mode.id" :value="mode.id">{{ mode.name }}</option></select></label>
          <span>{{
            workbench.agents.find((agent) => agent.id === workbench.agentId)?.name || 'Majo'
          }}</span>
          <button v-if="sending" type="button" class="send-button" @click="stop">Stop</button>
          <button v-else type="submit" class="send-button" :disabled="!prompt.trim()">
            Send ↑
          </button>
        </div>
      </form>
      <p class="composer-hint">AI can make mistakes. Review changes before using them.</p>
    </div>
  </section>
</template>

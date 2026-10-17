<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createChat,
  apiRequest,
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
const recording = ref(false)
const transcribing = ref(false)
interface ChatModel {
  id: string
  name?: string
  hidden?: boolean
  is_free?: boolean
}

interface ChatModelProvider {
  id: string
  name: string
  api_key?: string
  require_api_key?: boolean
  is_custom?: boolean
  is_local?: boolean
  is_free_tier?: boolean
  models?: ChatModel[]
  extra_models?: ChatModel[]
}

interface ActiveModelInfo {
  active_llm: { provider_id: string; model: string } | null
  effective_max_input_length?: number | null
}

const modelProviders = ref<ChatModelProvider[]>([])
const activeModel = ref<ActiveModelInfo>({ active_llm: null })
const modelMenuOpen = ref(false)
const modelSearch = ref('')
const modelLoading = ref(false)
const modelSwitching = ref(false)
const activeModelName = computed(() => {
  const slot = activeModel.value.active_llm
  if (!slot) return 'Select model'
  const provider = modelProviders.value.find((item) => item.id === slot.provider_id)
  const model = [...(provider?.models || []), ...(provider?.extra_models || [])]
    .find((item) => item.id === slot.model)
  return model?.name || model?.id || slot.model
})
const visibleModelProviders = computed(() => {
  const query = modelSearch.value.trim().toLowerCase()
  return modelProviders.value
    .map((provider) => ({
      ...provider,
      models: [...(provider.models || []), ...(provider.extra_models || [])]
        .filter((model) => !model.hidden)
        .filter((model) => !query || provider.name.toLowerCase().includes(query)
          || (model.name || model.id).toLowerCase().includes(query)),
    }))
    .filter((provider) => {
      const isConfigured = provider.require_api_key === false || Boolean(provider.api_key)
        || Boolean(provider.is_custom) || Boolean(provider.is_local) || Boolean(provider.is_free_tier)
      return isConfigured && provider.models.length > 0
    })
})
let turnController: AbortController | null = null
let approvalsTimer: ReturnType<typeof setInterval> | null = null
let requestVersion = 0
let recorder: MediaRecorder | null = null
let recordingStream: MediaStream | null = null
let recordingTimer: number | null = null

async function loadModels() {
  modelLoading.value = true
  try {
    const [providers, selected] = await Promise.all([
      apiRequest<ChatModelProvider[]>('/models'),
      apiRequest<ActiveModelInfo>(
        `/models/active?scope=effective&agent_id=${encodeURIComponent(workbench.agentId)}`,
        {},
        workbench.agentId,
      ),
    ])
    modelProviders.value = providers
    activeModel.value = selected
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load models.'
  } finally {
    modelLoading.value = false
  }
}

async function selectModel(providerId: string, modelId: string) {
  if (modelSwitching.value) return
  modelSwitching.value = true
  error.value = ''
  try {
    activeModel.value = await apiRequest<ActiveModelInfo>('/models/active', {
      method: 'PUT',
      body: JSON.stringify({
        provider_id: providerId,
        model: modelId,
        scope: 'agent',
        agent_id: workbench.agentId,
      }),
    }, workbench.agentId)
    modelMenuOpen.value = false
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not switch the model.'
  } finally {
    modelSwitching.value = false
  }
}

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

async function toggleRecording() {
  if (recording.value) { recorder?.stop(); return }
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
    error.value = 'Voice recording is not supported by this browser.'; return
  }
  try {
    recordingStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const mime = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : 'audio/mp4'
    const chunks: Blob[] = []
    recorder = new MediaRecorder(recordingStream, { mimeType: mime })
    recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data) }
    recorder.onstop = async () => {
      if (recordingTimer) window.clearTimeout(recordingTimer)
      recordingTimer = null; recording.value = false
      recordingStream?.getTracks().forEach((track) => track.stop()); recordingStream = null
      const form = new FormData(); form.append('file', new Blob(chunks, { type: mime }), 'recording.webm')
      transcribing.value = true
      try {
        const result = await apiRequest<{ text: string }>('/workspace/transcribe', { method: 'POST', body: form }, workbench.agentId)
        if (result.text) prompt.value = prompt.value ? `${prompt.value} ${result.text}` : result.text
        else error.value = 'No transcription was returned. Configure a transcription provider in Settings.'
      } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not transcribe recording.' }
      finally { transcribing.value = false; recorder = null }
    }
    recorder.start(); recording.value = true
    recordingTimer = window.setTimeout(() => recorder?.stop(), 5 * 60 * 1000)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Could not access the microphone.' }
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
watch(() => workbench.agentId, () => void loadModels(), { immediate: true })
watch(sending, (active) => {
  if (!active) return
  const chat = workbench.chats.find((item) => item.id === chatId.value)
  if (chat?.session_id) void refreshLoopStatus(chat.session_id)
})

onMounted(() => void loadLoopModes())

onBeforeUnmount(() => {
  if (approvalsTimer) clearInterval(approvalsTimer)
  turnController?.abort()
  if (recording.value) recorder?.stop()
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
      <div class="chat-heading-actions">
        <div class="model-selector">
          <button
            class="model-selector-trigger"
            type="button"
            :aria-expanded="modelMenuOpen"
            aria-haspopup="listbox"
            :disabled="modelLoading || modelSwitching"
            @click="modelMenuOpen = !modelMenuOpen"
          >
            {{ modelSwitching ? 'Switching model…' : activeModelName }} <span>⌄</span>
          </button>
          <section v-if="modelMenuOpen" class="model-selector-panel" role="listbox" aria-label="Select model">
            <input v-model="modelSearch" aria-label="Search models" placeholder="Search models" />
            <p v-if="modelLoading" class="muted">Loading models…</p>
            <p v-else-if="!visibleModelProviders.length" class="muted">No configured models are available.</p>
            <div v-for="provider in visibleModelProviders" :key="provider.id" class="model-provider-group">
              <strong>{{ provider.name }}</strong>
              <button
                v-for="model in provider.models"
                :key="model.id"
                type="button"
                class="model-option"
                :class="{ active: activeModel.active_llm?.provider_id === provider.id && activeModel.active_llm.model === model.id }"
                :disabled="modelSwitching"
                @click="selectModel(provider.id, model.id)"
              >
                <span>{{ model.name || model.id }}</span>
                <small v-if="model.is_free">Free</small>
                <small v-if="activeModel.active_llm?.provider_id === provider.id && activeModel.active_llm.model === model.id">Active</small>
              </button>
            </div>
          </section>
        </div>
        <button class="icon-button" title="New chat" @click="router.push('/chat')">＋</button>
      </div>
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
          <button type="button" :disabled="sending || transcribing" @click="toggleRecording">{{ transcribing ? 'Transcribing…' : recording ? 'Stop recording' : 'Record voice' }}</button>
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

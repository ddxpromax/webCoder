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
  messageAttachments,
  messageText,
  stopChat,
  streamChat,
  uploadChatFile,
  type ChatAttachment,
  type ApprovalRequest,
  type ChatMessage,
  type LoopMode,
} from './api'
import { codingComposer, workbench } from './state'
import MarkdownMessage from './MarkdownMessage.vue'
import {
  MAX_QUEUE_SIZE,
  createQueueItem,
  queueStorageKey,
  readQueue,
  withQueueSendLock,
  writeQueue,
  type QueueItem,
} from './messageQueue'

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
const attachments = ref<ChatAttachment[]>([])
const uploading = ref(false)
const attachmentInput = ref<HTMLInputElement | null>(null)
const queuedTurns = ref<QueueItem[]>([])
const drainingQueue = ref(false)
const lastTurnSucceeded = ref(false)
const queueKey = computed(() => queueStorageKey(workbench.agentId, chatId.value || 'new'))
const wideMode = ref(typeof window !== 'undefined' && window.localStorage?.getItem('majo_wide_mode') === 'true')
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

async function addAttachments(event: Event) {
  const input = event.target as HTMLInputElement
  const files = [...(input.files || [])]
  input.value = ''
  if (!files.length || uploading.value) return
  uploading.value = true
  error.value = ''
  try {
    for (const file of files) {
      if (file.size > 100 * 1024 * 1024) {
        throw new Error(`${file.name} exceeds the 100 MB attachment limit.`)
      }
      const uploaded = await uploadChatFile(file, workbench.agentId)
      attachments.value.push({
        name: uploaded.file_name || file.name,
        url: uploaded.stored_name || uploaded.url,
        type: file.type || 'application/octet-stream',
        size: uploaded.size || file.size,
      })
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not upload the attachment.'
  } finally {
    uploading.value = false
  }
}

function removeAttachment(index: number) {
  attachments.value.splice(index, 1)
}

function attachmentPreviewUrl(attachment: ChatAttachment) {
  if (attachment.url.startsWith('http://') || attachment.url.startsWith('https://')) return attachment.url
  return `/api/files/preview/${encodeURIComponent(attachment.url.split('/').pop() || attachment.url)}`
}

function isImageAttachment(attachment: ChatAttachment) {
  return attachment.type === 'image' || attachment.type.startsWith('image/')
}

function persistQueue() {
  writeQueue(queueKey.value, queuedTurns.value)
}

function loadQueue() {
  queuedTurns.value = readQueue(queueKey.value)
}

function queueDraft() {
  const text = prompt.value.trim()
  if ((!text && !attachments.value.length) || uploading.value) return
  if (queuedTurns.value.length >= MAX_QUEUE_SIZE) {
    error.value = `The message queue is full (${MAX_QUEUE_SIZE} items).`
    return
  }
  queuedTurns.value.push(createQueueItem(
    text || 'Please analyze the attached file(s).',
    [...attachments.value],
    selectedLoop.value,
  ))
  persistQueue()
  prompt.value = ''
  attachments.value = []
  error.value = ''
}

function removeQueuedTurn(id: string) {
  queuedTurns.value = queuedTurns.value.filter((item) => item.id !== id)
  persistQueue()
}

function retryQueuedTurn(id: string) {
  const item = queuedTurns.value.find((entry) => entry.id === id)
  if (!item) return
  item.status = 'pending'
  item.error = undefined
  persistQueue()
  void drainQueue()
}

function onComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  if (event.ctrlKey || event.metaKey || sending.value || queuedTurns.value.length) {
    queueDraft()
    if (!sending.value) void drainQueue()
    return
  }
  void submit()
}

async function drainQueue() {
  if (!chatId.value || sending.value || drainingQueue.value || !queuedTurns.value.length) return
  await withQueueSendLock(queueKey.value, async () => {
    if (sending.value || drainingQueue.value) return
    drainingQueue.value = true
    try {
      while (!sending.value && queuedTurns.value.length) {
        const next = queuedTurns.value[0]
        if (!next) break
        if (next.status === 'failed') break
        next.status = 'sending'
        next.error = undefined
        persistQueue()
        prompt.value = next.text
        attachments.value = [...next.attachments]
        selectedLoop.value = next.loopMode
        await submit()
        if (lastTurnSucceeded.value) {
          queuedTurns.value.shift()
        } else {
          next.status = 'failed'
          next.error = error.value || 'The turn did not complete.'
          break
        }
        persistQueue()
      }
    } finally {
      drainingQueue.value = false
      persistQueue()
    }
  })
}

function syncQueueFromStorage(event: StorageEvent) {
  if (event.key === queueKey.value) loadQueue()
}

function toggleWideMode() {
  wideMode.value = !wideMode.value
  try {
    if (wideMode.value) window.localStorage.setItem('majo_wide_mode', 'true')
    else window.localStorage.removeItem('majo_wide_mode')
  } catch {
    // The view remains usable when browser storage is unavailable.
  }
}

async function submit() {
  const text = prompt.value.trim()
  if ((!text && !attachments.value.length) || uploading.value) return
  if (sending.value) {
    queueDraft()
    return
  }
  if (queuedTurns.value.length && !drainingQueue.value) {
    queueDraft()
    void drainQueue()
    return
  }
  sending.value = true
  lastTurnSucceeded.value = false
  error.value = ''
  liveText.value = ''
  liveReasoning.value = ''
  const outgoingAttachments = [...attachments.value]
  const outgoingPrompt = text || 'Please analyze the attached file(s).'
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
    const outgoing = mode ? `/${mode.slash_command} ${outgoingPrompt}` : outgoingPrompt
    messages.value.push({
      id: `local-${Date.now()}`,
      role: 'user',
      content: [
        { type: 'text', text: outgoing },
        ...outgoingAttachments.map((attachment) => attachment.type.startsWith('image/')
          ? { type: 'image', image_url: attachment.url, name: attachment.name }
          : { type: 'file', file_url: attachment.url, name: attachment.name }),
      ],
    })
    await scrollToBottom()
    await streamChat(
      { prompt: outgoing, sessionId, agentId: workbench.agentId, workspace: props.workspace, attachments: outgoingAttachments },
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
    lastTurnSucceeded.value = true
    attachments.value = []
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
    if (queuedTurns.value.length && !drainingQueue.value) void drainQueue()
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
watch(() => codingComposer.revision, () => {
  if (!props.workspace || !codingComposer.text) return
  prompt.value = prompt.value ? `${prompt.value}\n${codingComposer.text}` : codingComposer.text
  codingComposer.text = ''
})
watch(() => workbench.agentId, () => void loadModels(), { immediate: true })
watch(queueKey, () => loadQueue(), { immediate: true })
watch([() => queuedTurns.value.length, sending, chatId], ([count, active, id]) => {
  if (count && !active && id) void drainQueue()
}, { immediate: true })
watch(sending, (active) => {
  if (!active) return
  const chat = workbench.chats.find((item) => item.id === chatId.value)
  if (chat?.session_id) void refreshLoopStatus(chat.session_id)
})

onMounted(() => {
  void loadLoopModes()
  window.addEventListener('storage', syncQueueFromStorage)
})

onBeforeUnmount(() => {
  window.removeEventListener('storage', syncQueueFromStorage)
  if (approvalsTimer) clearInterval(approvalsTimer)
  turnController?.abort()
  if (recording.value) recorder?.stop()
})
</script>

<template>
  <section class="chat-page" :class="{ 'wide-mode': wideMode }">
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
        <button class="icon-button" :title="wideMode ? 'Use normal width' : 'Use wide width'" @click="toggleWideMode">{{ wideMode ? '↔' : '↗' }}</button>
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
        <MarkdownMessage class="message-body" :content="messageText(message.content)" />
        <div v-if="messageAttachments(message.content).length" class="message-attachments">
          <a v-for="attachment in messageAttachments(message.content)" :key="attachment.url" :href="attachmentPreviewUrl(attachment)" target="_blank" rel="noopener">
            <img v-if="isImageAttachment(attachment)" :src="attachmentPreviewUrl(attachment)" :alt="attachment.name" />
            <span v-else>Attachment: {{ attachment.name }}</span>
          </a>
        </div>
      </article>
      <article v-if="liveReasoning" class="message reasoning">
        <div class="message-label">Thinking</div>
        <MarkdownMessage class="message-body" :content="liveReasoning" />
      </article>
      <article v-if="liveText" class="message assistant">
        <div class="message-label">Majo</div>
        <MarkdownMessage class="message-body" :content="liveText" />
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
      <div v-if="attachments.length" class="pending-attachments" aria-label="Attachments">
        <span v-for="(attachment, index) in attachments" :key="`${attachment.url}-${index}`">{{ attachment.name }} <button type="button" aria-label="Remove attachment" :disabled="sending" @click="removeAttachment(index)">×</button></span>
      </div>
      <form class="composer" @submit.prevent="submit">
        <textarea
          v-model="prompt"
          aria-label="Message"
          placeholder="Ask anything, or describe a coding task…"
          rows="3"
          :disabled="uploading"
          @keydown="onComposerKeydown"
        />
        <div class="composer-footer">
          <label class="loop-mode"><span>Mode</span><select v-model="selectedLoop" :disabled="sending"><option value="">Standard</option><option v-for="mode in loopModes.filter((item) => item.id !== 'default')" :key="mode.id" :value="mode.id">{{ mode.name }}</option></select></label>
          <input ref="attachmentInput" class="visually-hidden" type="file" multiple @change="addAttachments" />
          <button type="button" :disabled="uploading" @click="attachmentInput?.click()">{{ uploading ? 'Uploading…' : 'Attach files' }}</button>
          <button type="button" :disabled="sending || transcribing" @click="toggleRecording">{{ transcribing ? 'Transcribing…' : recording ? 'Stop recording' : 'Record voice' }}</button>
          <span>{{
            workbench.agents.find((agent) => agent.id === workbench.agentId)?.name || 'Majo'
          }}</span>
          <button v-if="sending" type="button" class="send-button" @click="stop">Stop</button>
          <button v-else type="submit" class="send-button" :disabled="(!prompt.trim() && !attachments.length) || uploading">
            {{ queuedTurns.length ? 'Queue ↑' : 'Send ↑' }}
          </button>
        </div>
      </form>
      <section v-if="queuedTurns.length" class="turn-queue" aria-label="Message queue">
        <header><strong>Queued messages</strong><span>{{ queuedTurns.length }}/{{ MAX_QUEUE_SIZE }}</span></header>
        <div v-for="item in queuedTurns" :key="item.id" class="queued-turn" :class="item.status">
          <span>{{ item.text }}</span><small v-if="item.attachments.length">{{ item.attachments.length }} attachment{{ item.attachments.length === 1 ? '' : 's' }}</small>
          <em>{{ item.status === 'sending' ? 'Sending…' : item.status === 'failed' ? 'Failed' : 'Pending' }}</em>
          <button v-if="item.status === 'failed'" type="button" @click="retryQueuedTurn(item.id)">Retry</button>
          <button type="button" :disabled="item.status === 'sending'" @click="removeQueuedTurn(item.id)">Remove</button>
        </div>
      </section>
      <p class="composer-hint">AI can make mistakes. Review changes before using them.</p>
    </div>
  </section>
</template>

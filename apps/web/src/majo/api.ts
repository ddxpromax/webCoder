import { EventSourceParserStream } from 'eventsource-parser/stream'

export interface AgentInfo {
  id: string
  name: string
  description?: string
  backend?: string
}

export interface ChatSpec {
  id: string
  session_id: string
  name: string
  status: string
  pinned?: boolean
  archived?: boolean
  updated_at: string
}

export interface ChatMessage {
  id: string
  role: string
  type?: string
  content: unknown
  status?: string
  created_at?: string
}

export interface ChatHistory {
  messages: ChatMessage[]
  status: string
}

export interface ApprovalRequest {
  request_id: string
  session_id: string
  root_session_id: string
  tool_name: string
  tool_display_name: string
  severity: string
}

export interface CodingProject {
  path: string
  name: string
  is_workspace_default: boolean
  exists?: boolean
}

export interface CodeFile {
  path: string
  name: string
  type?: string
  is_dir?: boolean
  children?: CodeFile[]
}

function authHeaders(): Record<string, string> {
  const token =
    typeof window === 'undefined' ? null : window.localStorage?.getItem('majo_auth_token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export function agentHeaders(agentId: string): Record<string, string> {
  return { ...authHeaders(), 'X-Agent-Id': agentId }
}

export async function apiRequest<T>(
  path: string,
  options: RequestInit = {},
  agentId?: string,
): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...authHeaders(),
      ...(agentId ? { 'X-Agent-Id': agentId } : {}),
      ...(options.body && !(options.body instanceof FormData)
        ? { 'Content-Type': 'application/json' }
        : {}),
      ...options.headers,
    },
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail ?? body?.message ?? `Request failed (HTTP ${response.status}).`)
  }
  return response.json() as Promise<T>
}

export function listAgents() {
  return apiRequest<{ agents: AgentInfo[] }>('/agents')
}

export function listChats(agentId: string) {
  return apiRequest<ChatSpec[]>('/chats?archived=false', {}, agentId)
}

export function createChat(agentId: string) {
  return apiRequest<ChatSpec>('/chats', { method: 'POST' }, agentId)
}

export function getChat(chatId: string, agentId: string) {
  return apiRequest<ChatHistory>(`/chats/${encodeURIComponent(chatId)}`, {}, agentId)
}

export function deleteChat(chatId: string, agentId: string) {
  return apiRequest<{ deleted: boolean }>(
    `/chats/${encodeURIComponent(chatId)}`,
    { method: 'DELETE' },
    agentId,
  )
}

export function stopChat(chatId: string, agentId: string) {
  return apiRequest<{ stopped: boolean }>(
    `/console/chat/stop?chat_id=${encodeURIComponent(chatId)}`,
    { method: 'POST' },
    agentId,
  )
}

export function listApprovals(sessionId: string) {
  return apiRequest<{ pending_approvals: ApprovalRequest[] }>(
    `/approval/list?session_id=${encodeURIComponent(sessionId)}`,
  )
}

export function decideApproval(request: ApprovalRequest, approved: boolean) {
  return apiRequest<{ success: boolean }>(approved ? '/approval/approve' : '/approval/deny', {
    method: 'POST',
    body: JSON.stringify({
      request_id: request.request_id,
      session_id: request.root_session_id,
    }),
  })
}

export function getCodingProject(agentId: string) {
  return apiRequest<CodingProject>('/workspace/coding-project', {}, agentId)
}

export function listCodeFiles(agentId: string) {
  return apiRequest<CodeFile[]>('/workspace/code-files', {}, agentId)
}

export function readCodeFile(path: string, agentId: string) {
  return apiRequest<{ content: string; path?: string }>(
    `/workspace/code-files/${path.split('/').map(encodeURIComponent).join('/')}`,
    {},
    agentId,
  )
}

export function writeCodeFile(path: string, content: string, agentId: string) {
  return apiRequest<unknown>(
    `/workspace/code-files/${path.split('/').map(encodeURIComponent).join('/')}`,
    { method: 'PUT', body: JSON.stringify({ content }) },
    agentId,
  )
}

export function messageText(content: unknown): string {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .filter(
      (part): part is { type: string; text?: string } =>
        typeof part === 'object' && part !== null && part.type === 'text',
    )
    .map((part) => part.text ?? '')
    .join('\n')
}

export async function streamChat(
  input: {
    prompt: string
    sessionId: string
    agentId: string
    workspace?: string
  },
  handlers: {
    onText: (text: string) => void
    onReasoning: (text: string) => void
    onDone: () => void
  },
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/console/chat', {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      ...agentHeaders(input.agentId),
    },
    body: JSON.stringify({
      input: [{ role: 'user', content: [{ type: 'text', text: input.prompt }] }],
      session_id: input.sessionId,
      user_id: 'default',
      channel: 'console',
      ...(input.workspace ? { workspace: input.workspace } : {}),
    }),
    signal,
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail ?? `Chat failed (HTTP ${response.status}).`)
  }
  if (!response.body) throw new Error('The server returned an empty stream.')

  const events = response.body.pipeThrough(new TextDecoderStream()).pipeThrough(
    new EventSourceParserStream({
      maxBufferSize: 1024 * 1024,
      onError: 'terminate',
    }),
  )
  let completed = false
  const messageTypes = new Map<string, string>()
  for await (const event of events) {
    let payload: Record<string, unknown>
    try {
      payload = JSON.parse(event.data) as Record<string, unknown>
    } catch {
      throw new Error('The server returned an invalid chat event.')
    }
    if (payload.type === 'error') {
      throw new Error(String(payload.content ?? 'Agent turn failed.'))
    }
    if (payload.object === 'message' && typeof payload.id === 'string') {
      messageTypes.set(payload.id, String(payload.type ?? 'message'))
    }
    if (payload.object === 'content' && payload.delta === true) {
      const receiver =
        messageTypes.get(String(payload.msg_id)) === 'reasoning'
          ? handlers.onReasoning
          : handlers.onText
      receiver(String(payload.text ?? ''))
    }
    if (payload.object === 'response' && payload.status === 'completed') {
      completed = true
      handlers.onDone()
    }
  }
  if (!completed) throw new Error('The agent stream ended before completion.')
}

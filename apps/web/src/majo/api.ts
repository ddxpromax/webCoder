import { EventSourceParserStream } from 'eventsource-parser/stream'

export interface AgentInfo {
  id: string
  name: string
  description?: string
  backend?: string
}

export interface AgentProfile extends AgentInfo {
  language?: string
  workspace_dir?: string
  active_model?: Record<string, unknown>
}

/** Full Majo execution configuration persisted for an agent. */
export type AgentRunningConfig = Record<string, any> & {
  approval_level?: string
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

export interface LoopMode {
  id: string
  name: string
  slash_command: string
  description: string
  source: string
}

export interface LoopStatus {
  state: string
  mode: LoopMode | null
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

export interface GitChange {
  path: string
  status: string
  staged: boolean
}

export interface GitStatus {
  branch: string
  changes: GitChange[]
  ahead: number
  behind: number
}

export interface GitBranch {
  name: string
  current: boolean
  remote: boolean
}

export interface GitCommit {
  hash: string
  author: string
  date: string
  message: string
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

/** Consume Majo's JSON-over-SSE skill optimization stream. */
export async function streamSkillOptimization(
  skillPath: string,
  content: string,
  agentId: string,
  onChunk: (text: string) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api${skillPath}/ai/optimize/stream`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      ...agentHeaders(agentId),
    },
    body: JSON.stringify({ content, language: 'en' }),
    signal,
  })
  if (!response.ok) {
    throw new Error(`Could not optimize skill (HTTP ${response.status}).`)
  }
  if (!response.body) {
    throw new Error('The optimization stream was not available.')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() ?? ''
    for (const rawLine of lines) {
      const line = rawLine.trim()
      if (!line.startsWith('data:')) continue
      const payload = JSON.parse(line.substring(5).trim()) as {
        text?: string; done?: boolean; error?: string
      }
      if (payload.error) throw new Error(payload.error)
      if (payload.text) onChunk(payload.text)
      if (payload.done) return
    }
  }
}

export function listAgents() {
  return apiRequest<{ agents: AgentInfo[] }>('/agents')
}

export function getAgentProfile(agentId: string) {
  return apiRequest<AgentProfile>(`/agents/${encodeURIComponent(agentId)}`)
}

export function getAgentRunningConfig(agentId: string) {
  return apiRequest<AgentRunningConfig>('/workspace/running-config', {}, agentId)
}

export function updateAgentRunningConfig(agentId: string, config: AgentRunningConfig) {
  return apiRequest<AgentRunningConfig>(
    '/workspace/running-config',
    { method: 'PUT', body: JSON.stringify(config) },
    agentId,
  )
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

export function listLoopModes(agentId: string) {
  return apiRequest<LoopMode[]>('/loops', {}, agentId)
}

export function getLoopStatus(agentId: string, sessionId: string) {
  return apiRequest<LoopStatus>(`/loops/status?session_id=${encodeURIComponent(sessionId)}`, {}, agentId)
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

export function gitStatus(agentId: string) {
  return apiRequest<GitStatus>('/workspace/git/status', {}, agentId)
}

export function gitBranches(agentId: string) {
  return apiRequest<GitBranch[]>('/workspace/git/branches', {}, agentId)
}

export function gitCheckout(agentId: string, branch: string, create = false) {
  return apiRequest<{ branch: string }>('/workspace/git/checkout', {
    method: 'POST', body: JSON.stringify({ branch, create }),
  }, agentId)
}

export function gitDiff(agentId: string, path: string, staged: boolean, untracked: boolean) {
  const query = new URLSearchParams({ path })
  if (staged) query.set('staged', 'true')
  if (untracked) query.set('untracked', 'true')
  return apiRequest<{ diff: string }>(`/workspace/git/diff?${query}`, {}, agentId)
}

export function gitChangeStage(agentId: string, paths: string[], staged: boolean) {
  return apiRequest<{ staged?: string[]; unstaged?: string[] }>(
    `/workspace/git/${staged ? 'stage' : 'unstage'}`,
    { method: 'POST', body: JSON.stringify({ paths }) }, agentId,
  )
}

export function gitCommit(agentId: string, message: string) {
  return apiRequest<{ committed: boolean; output: string }>('/workspace/git/commit', {
    method: 'POST', body: JSON.stringify({ message }),
  }, agentId)
}

export function gitDiscard(agentId: string, paths: string[]) {
  return apiRequest<{ discarded: string[] }>('/workspace/git/discard', {
    method: 'POST', body: JSON.stringify({ paths }),
  }, agentId)
}

export function gitLog(agentId: string, limit = 20) {
  return apiRequest<GitCommit[]>(`/workspace/git/log?limit=${limit}`, {}, agentId)
}

export function gitCommitDiff(agentId: string, hash: string) {
  return apiRequest<{ diff: string; hash: string }>(
    `/workspace/git/commit-diff?commit_hash=${encodeURIComponent(hash)}`, {}, agentId,
  )
}

export function gitRevert(agentId: string, hash: string) {
  return apiRequest<{ reverted: string; output: string }>('/workspace/git/revert', {
    method: 'POST', body: JSON.stringify({ commit_hash: hash }),
  }, agentId)
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

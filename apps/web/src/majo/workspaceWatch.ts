import { agentHeaders } from './api'

/** Majo's JSON-over-SSE workspace change protocol. */
export interface WorkspaceFileChange {
  change: 'added' | 'modified' | 'deleted'
  path: string
}

type WorkspaceWatchListener = (changes: WorkspaceFileChange[]) => void

interface WatchConnection {
  controller: AbortController
  listeners: Set<WorkspaceWatchListener>
  running: boolean
}

const connections = new Map<string, WatchConnection>()

function pause(milliseconds: number) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds))
}

function emit(connection: WatchConnection, changes: WorkspaceFileChange[]) {
  for (const listener of connection.listeners) {
    try { listener(changes) } catch { /* A page listener cannot terminate the shared stream. */ }
  }
}

export function parseWorkspaceWatchPayload(raw: string): WorkspaceFileChange[] {
  try {
    const payload = JSON.parse(raw) as { type?: unknown; events?: unknown }
    if (payload.type !== 'file_change' || !Array.isArray(payload.events)) return []
    return payload.events.filter((event): event is WorkspaceFileChange => {
      if (!event || typeof event !== 'object') return false
      const candidate = event as Record<string, unknown>
      return typeof candidate.path === 'string'
        && (candidate.change === 'added' || candidate.change === 'modified' || candidate.change === 'deleted')
    })
  } catch { return [] }
}

async function run(agentId: string, connection: WatchConnection) {
  let retryDelay = 1_000
  while (!connection.controller.signal.aborted) {
    try {
      const response = await fetch('/api/workspace/watch', {
        method: 'GET', headers: { Accept: 'text/event-stream', ...agentHeaders(agentId) }, signal: connection.controller.signal,
      })
      if (!response.ok || !response.body) throw new Error(`Workspace watch failed (${response.status}).`)
      retryDelay = 1_000
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (!connection.controller.signal.aborted) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() || ''
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const changes = parseWorkspaceWatchPayload(line.slice(5).trim())
          if (changes.length) emit(connection, changes)
        }
      }
    } catch (cause) {
      if (connection.controller.signal.aborted) break
      if (cause instanceof DOMException && cause.name === 'AbortError') break
    }
    if (!connection.controller.signal.aborted) {
      await pause(retryDelay)
      retryDelay = Math.min(retryDelay * 2, 30_000)
    }
  }
  connection.running = false
  if (!connection.listeners.size && connections.get(agentId) === connection) {
    connections.delete(agentId)
  }
}

/**
 * Subscribe to the single workspace watcher for an agent. The connection is
 * closed only after the last Coding view unsubscribes.
 */
export function subscribeWorkspaceWatch(agentId: string, listener: WorkspaceWatchListener) {
  let connection = connections.get(agentId)
  if (!connection) {
    connection = { controller: new AbortController(), listeners: new Set(), running: true }
    connections.set(agentId, connection)
    void run(agentId, connection)
  }
  connection.listeners.add(listener)
  return () => {
    const current = connections.get(agentId)
    if (!current) return
    current.listeners.delete(listener)
    if (!current.listeners.size) {
      current.controller.abort()
      connections.delete(agentId)
    }
  }
}

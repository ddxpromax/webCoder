import type { ChatAttachment } from './api'

export type QueueItemStatus = 'pending' | 'sending' | 'failed'

export interface QueueItem {
  id: string
  text: string
  attachments: ChatAttachment[]
  loopMode: string
  status: QueueItemStatus
  error?: string
  createdAt: number
}

export const MAX_QUEUE_SIZE = 50
const STORAGE_PREFIX = 'majo:message-queue:'

export function queueStorageKey(agentId: string, chatId: string) {
  return `${STORAGE_PREFIX}${agentId}:${chatId}`
}

export function readQueue(key: string): QueueItem[] {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(key) || '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter((item): item is QueueItem => Boolean(item)
      && typeof item === 'object' && typeof item.id === 'string'
      && typeof item.text === 'string' && Array.isArray(item.attachments))
  } catch {
    return []
  }
}

export function writeQueue(key: string, items: QueueItem[]) {
  try {
    if (items.length) window.localStorage.setItem(key, JSON.stringify(items))
    else window.localStorage.removeItem(key)
  } catch {
    // A full or unavailable localStorage should not interrupt an active turn.
  }
}

let sequence = 0
export function createQueueItem(
  text: string,
  attachments: ChatAttachment[],
  loopMode: string,
): QueueItem {
  sequence += 1
  return {
    id: `mq-${Date.now().toString(36)}-${sequence.toString(36)}`,
    text,
    attachments,
    loopMode,
    status: 'pending',
    createdAt: Date.now(),
  }
}

type LockManager = {
  request<T>(name: string, options: { ifAvailable: boolean }, callback: (lock: unknown) => Promise<T> | T): Promise<T | null>
}

/** Majo's per-session Web Lock prevents two browser tabs draining one queue. */
export async function withQueueSendLock<T>(key: string, action: () => Promise<T>): Promise<T | null> {
  const locks = (navigator as Navigator & { locks?: LockManager }).locks
  if (!locks) return action()
  try {
    return await locks.request(`majo:queue-send:${key}`, { ifAvailable: true }, async (lock) => {
      if (!lock) return null
      return action()
    })
  } catch {
    return null
  }
}

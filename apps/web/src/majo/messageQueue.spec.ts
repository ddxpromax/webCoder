import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createQueueItem, queueStorageKey, readQueue, writeQueue } from './messageQueue'

describe('Majo message queue persistence', () => {
  const key = queueStorageKey('agent-a', 'chat-a')
  const values = new Map<string, string>()

  beforeEach(() => {
    const storage: Storage = {
      get length() { return values.size },
      clear: () => values.clear(),
      getItem: (item) => values.get(item) ?? null,
      key: (index) => [...values.keys()][index] ?? null,
      removeItem: (item) => { values.delete(item) },
      setItem: (item, value) => { values.set(item, value) },
    }
    Object.defineProperty(window, 'localStorage', { configurable: true, value: storage })
  })

  afterEach(() => values.clear())

  it('persists attachment URLs without retaining their binary payload', () => {
    const item = createQueueItem('Review this image', [{
      name: 'screen.png', url: 'stored-screen.png', type: 'image/png', size: 42,
    }], 'goal')
    writeQueue(key, [item])

    expect(readQueue(key)).toEqual([item])
    expect(window.localStorage.getItem(key)).toContain('stored-screen.png')
    expect(window.localStorage.getItem(key)).not.toContain('base64')
  })

  it('removes the storage entry when the queue is drained', () => {
    writeQueue(key, [createQueueItem('Follow up', [], '')])
    writeQueue(key, [])
    expect(readQueue(key)).toEqual([])
  })
})

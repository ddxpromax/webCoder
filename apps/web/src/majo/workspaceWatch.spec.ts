import { describe, expect, it } from 'vitest'
import { parseWorkspaceWatchPayload } from './workspaceWatch'

describe('Majo workspace watch payloads', () => {
  it('accepts only recognized file change events', () => {
    expect(parseWorkspaceWatchPayload(JSON.stringify({
      type: 'file_change',
      events: [
        { change: 'added', path: 'src/App.vue' },
        { change: 'modified', path: 'src/api.ts' },
        { change: 'deleted', path: 'old.txt' },
        { change: 'unknown', path: 'ignored.txt' },
      ],
    }))).toEqual([
      { change: 'added', path: 'src/App.vue' },
      { change: 'modified', path: 'src/api.ts' },
      { change: 'deleted', path: 'old.txt' },
    ])
  })

  it('ignores malformed or unrelated SSE data', () => {
    expect(parseWorkspaceWatchPayload('invalid')).toEqual([])
    expect(parseWorkspaceWatchPayload('{"type":"heartbeat"}')).toEqual([])
  })
})

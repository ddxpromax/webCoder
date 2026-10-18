import { afterEach, describe, expect, it, vi } from 'vitest'
import { messageAttachments, messageText, streamChat } from './api'

afterEach(() => vi.unstubAllGlobals())

function streamResponse(events: Record<string, unknown>[]): Response {
  const encoder = new TextEncoder()
  const source = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const event of events) {
        controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`))
      }
      controller.close()
    },
  })

  return new Response(source, {
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

describe('Majo console stream', () => {
  it('routes reasoning and answer deltas to separate handlers', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      streamResponse([
        { object: 'message', id: 'thinking', type: 'reasoning', status: 'in_progress' },
        { object: 'content', msg_id: 'thinking', delta: true, text: 'Let me check.' },
        { object: 'message', id: 'answer', type: 'message', status: 'in_progress' },
        { object: 'content', msg_id: 'answer', delta: true, text: 'Done.' },
        { object: 'response', status: 'completed' },
      ]),
    )
    vi.stubGlobal('fetch', fetchMock)
    const onText = vi.fn()
    const onReasoning = vi.fn()
    const onDone = vi.fn()

    await streamChat(
      { prompt: 'Fix it', sessionId: 'console:chat-1', agentId: 'default' },
      { onText, onReasoning, onDone },
      new AbortController().signal,
    )

    expect(onReasoning).toHaveBeenCalledExactlyOnceWith('Let me check.')
    expect(onText).toHaveBeenCalledExactlyOnceWith('Done.')
    expect(onDone).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/console/chat',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          input: [{ role: 'user', content: [{ type: 'text', text: 'Fix it' }] }],
          session_id: 'console:chat-1',
          user_id: 'default',
          channel: 'console',
        }),
      }),
    )
  })

  it('rejects server errors and incomplete streams', async () => {
    const handlers = { onText: vi.fn(), onReasoning: vi.fn(), onDone: vi.fn() }
    const input = { prompt: 'Fix it', sessionId: 's', agentId: 'default' }
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(streamResponse([{ type: 'error', content: 'Model unavailable' }]))
        .mockResolvedValueOnce(
          streamResponse([{ object: 'message', id: 'answer', type: 'message' }]),
        ),
    )

    await expect(streamChat(input, handlers, new AbortController().signal)).rejects.toThrow(
      'Model unavailable',
    )
    await expect(streamChat(input, handlers, new AbortController().signal)).rejects.toThrow(
      'before completion',
    )
  })

  it('serializes queued attachment URLs as Majo input content blocks', async () => {
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([
      { object: 'response', status: 'completed' },
    ]))
    vi.stubGlobal('fetch', fetchMock)

    await streamChat(
      {
        prompt: 'Inspect this', sessionId: 's', agentId: 'default',
        attachments: [{ name: 'screen.png', url: 'stored-screen.png', type: 'image/png' }],
      },
      { onText: vi.fn(), onReasoning: vi.fn(), onDone: vi.fn() },
      new AbortController().signal,
    )

    expect(fetchMock).toHaveBeenCalledWith('/api/console/chat', expect.objectContaining({
      body: expect.stringContaining('"image_url":"stored-screen.png"'),
    }))
  })
})

it('extracts text from persisted content arrays', () => {
  expect(
    messageText([
      { type: 'text', text: 'first' },
      { type: 'image', image_url: '/image.png' },
      { type: 'text', text: 'second' },
    ]),
  ).toBe('first\nsecond')
})

it('extracts persisted file and image references', () => {
  expect(messageAttachments([
    { type: 'image', image_url: 'image.png', name: 'Image' },
    { type: 'file', file_url: 'notes.txt', name: 'Notes' },
  ])).toEqual([
    { type: 'image', url: 'image.png', name: 'Image' },
    { type: 'file', url: 'notes.txt', name: 'Notes' },
  ])
})

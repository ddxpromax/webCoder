import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'

import App from '../App.vue'
import { streamExplanation } from '@/api/explanations'
import { getSystemInfo } from '@/api/system'
import {
  createConversation,
  getRecentMessages,
} from '@/api/conversation'

vi.mock('@/api/system', () => ({
  getSystemInfo: vi.fn<typeof getSystemInfo>(),
}))

vi.mock('@/api/conversation', () => ({
  createConversation: vi.fn<typeof createConversation>(),
  getRecentMessages: vi.fn<typeof getRecentMessages>(),
}))

vi.mock('@/api/explanations', () => ({
  streamExplanation: vi.fn<typeof streamExplanation>(),
}))

enableAutoUnmount(afterEach)

const getSystemInfoMock = vi.mocked(getSystemInfo)
const createConversationMock = vi.mocked(createConversation)
const streamExplanationMock = vi.mocked(streamExplanation)
const getRecentMessagesMock = vi.mocked(getRecentMessages)

const conversation = {
  id: '00000000-0000-0000-0000-000000000001',
  title: 'New conversation',
  createdAt: '2026-10-07T00:00:00Z',
  updatedAt: '2026-10-07T00:00:00Z',
}

beforeEach(() => {
  getSystemInfoMock
    .mockReset()
    .mockResolvedValue({ applicationName: 'webcoder-api'})

  createConversationMock
    .mockReset()
    .mockResolvedValue(conversation)

  streamExplanationMock.mockReset()

  getRecentMessagesMock
    .mockReset()
    .mockResolvedValue({
      conversationId: conversation.id,
      messages: [],
      nextCursor: null,
    })
})

describe('service connection', () => {
  it('shows a loading state and then the service name', async () => {
    const wrapper = mount(App)
    await nextTick()

    expect(wrapper.get('[role="status"]').text()).toContain('connecting')
    expect(wrapper.get('button').element.disabled).toBe(true)

    await flushPromises()

    expect(wrapper.text()).toContain('connected: webcoder-api')
    expect(wrapper.get('button').element.disabled).toBe(false)
  })

  it('shows a connection error and allows retrying', async () => {
    getSystemInfoMock
      .mockRejectedValueOnce(new Error('Backend is temporarily unavailable.'))
      .mockResolvedValueOnce({ applicationName: 'webcoder-api' })

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('Backend is temporarily unavailable.')

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('connected: webcoder-api')
    expect(getSystemInfoMock).toHaveBeenCalledTimes(2)
  })
})

describe('code explanation stream', () => {
  it('creates a conversation and streams tokens using its UUID', async () => {
    getRecentMessagesMock.mockResolvedValue({
      conversationId: conversation.id,
      messages: [
        {
          id: 1,
          conversationId: conversation.id,
          role: 'user',
          content: 'print(1)',
          language: 'python',
          status: 'completed',
          createdAt: '2026-10-08T00:00:00Z',
        },
        {
          id: 2,
          conversationId: conversation.id,
          role: 'assistant',
          content: 'Mock explanation for python code.',
          language: 'python',
          status: 'completed',
          createdAt: '2026-10-08T00:00:01Z',
        },
      ],
      nextCursor: null,
    })

    streamExplanationMock.mockImplementation(
      async (_conversationId, _input, { onToken }) => {
        onToken('Mock ')
        onToken('explanation for python code.')
      },
    )

    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#code-input').setValue('print(1)')
    await wrapper.get('#explanation-form').trigger('submit')
    await flushPromises()

    expect(createConversationMock).toHaveBeenCalledTimes(1)
    expect(createConversationMock).toHaveBeenCalledWith(
      expect.any(AbortSignal),
    )

    expect(streamExplanationMock).toHaveBeenCalledWith(
      conversation.id,
      {
        code: 'print(1)',
        language: 'python',
      },
      expect.objectContaining({
        signal: expect.any(AbortSignal),
        onToken: expect.any(Function),
      }),
    )

    expect(wrapper.get('.conversation-history').text()).toContain(
      'Mock explanation for python code.',
    )
  })

  it('reuses the created conversation for later prompts', async () => {
    streamExplanationMock.mockImplementation(
      async (_conversationId, _input, { onToken }) => {
        onToken('Explanation.')
      },
    )

    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#code-input').setValue('print(1)')
    await wrapper.get('#explanation-form').trigger('submit')
    await flushPromises()

    await wrapper.get('#code-input').setValue('print(2)')
    await wrapper.get('#explanation-form').trigger('submit')
    await flushPromises()

    expect(createConversationMock).toHaveBeenCalledTimes(1)
    expect(streamExplanationMock).toHaveBeenCalledTimes(2)
    expect(streamExplanationMock).toHaveBeenNthCalledWith(
      1,
      conversation.id,
      {
        code: 'print(1)',
        language: 'python',
      },
      expect.objectContaining({
        signal: expect.any(AbortSignal),
        onToken: expect.any(Function),
      }),
    )
    expect(streamExplanationMock).toHaveBeenNthCalledWith(
      2,
      conversation.id,
      {
        code: 'print(2)',
        language: 'python',
      },
      expect.objectContaining({
        signal: expect.any(AbortSignal),
        onToken: expect.any(Function),
      }),
    )
  })

  it('rejects empty code without creating a conversation or calling the stream API', async () => {
    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#explanation-form').trigger('submit')

    expect(wrapper.get('[role="alert"]').text()).toBe('Enter the code you want explained.')
    expect(createConversationMock).not.toHaveBeenCalled()
    expect(streamExplanationMock).not.toHaveBeenCalled()
  })

  it('shows an error when the stream request fails', async () => {
    streamExplanationMock.mockRejectedValue(
      new Error('The AI service could not process the request.'),
    )

    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#code-input').setValue('print(1)')
    await wrapper.get('#explanation-form').trigger('submit')
    await flushPromises()

    expect(createConversationMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role="alert"]').text()).toBe('The AI service could not process the request.')
  })

  it('aborts the active stream when Stop is clicked', async () => {
    let requestSignal: AbortSignal | undefined

    getRecentMessagesMock.mockResolvedValue({
      conversationId: conversation.id,
      messages: [
        {
          id: 1,
          conversationId: conversation.id,
          role: 'assistant',
          content: 'Partial response',
          language: 'python',
          status: 'cancelled',
          createdAt: '2026-10-08T00:00:00Z',
        },
      ],
      nextCursor: null,
    })

    streamExplanationMock.mockImplementation(
      async (_conversation, _input, { onToken, signal }) => {
        if (!signal) {
          throw new Error('Expected an abort signal.')
        }

        requestSignal = signal
        onToken('Partial response')

        await new Promise<void>((_resolve, reject) => {
          signal.addEventListener(
            'abort',
            () => reject(new DOMException('The operation was aborted.', 'AbortError')),
            { once: true },
          )
        })
      },
    )

    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#code-input').setValue('print(1)')
    await wrapper.get('#explanation-form').trigger('submit')
    await flushPromises()

    expect(createConversationMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.editor-actions button[type="button"]').text()).toBe("Stop")
    expect(requestSignal?.aborted).toBe(false)

    await wrapper.get('.editor-actions button[type="button"]').trigger('click')
    await flushPromises()

    expect(requestSignal?.aborted).toBe(true)
    expect(wrapper.get('[role="status"]').text()).toBe('Generation stopped.')
    expect(wrapper.get('.conversation-history').text()).toContain(
      'cancelled',
    )
    expect(wrapper.get('.conversation-history').text()).toContain(
      'Partial response',
    )
  })
})

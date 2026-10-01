import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'

import App from '../App.vue'
import { streamExplanation } from '@/api/explanations'
import { getSystemInfo } from '@/api/system'

vi.mock('@/api/system', () => ({
  getSystemInfo: vi.fn<typeof getSystemInfo>(),
}))

vi.mock('@/api/explanations', () => ({
  streamExplanation: vi.fn<typeof streamExplanation>(),
}))

enableAutoUnmount(afterEach)

const getSystemInfoMock = vi.mocked(getSystemInfo)
const streamExplanationMock = vi.mocked(streamExplanation)

beforeEach(() => {
  getSystemInfoMock
    .mockReset()
    .mockResolvedValue({ applicationName: 'webcoder-api' })

  streamExplanationMock.mockReset()
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

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Backend is temporarily unavailable.',
    )

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('connected: webcoder-api')
    expect(getSystemInfoMock).toHaveBeenCalledTimes(2)
  })
})

describe('code explanation stream', () => {
  it('appends streamed tokens to the explanation', async () => {
    streamExplanationMock.mockImplementation(
      async (_input, { onToken }) => {
        onToken('Mock ')
        onToken('explanation for python code.')
      },
    )

    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#code-input').setValue('print(1)')
    await wrapper.get('#explanation-form').trigger('submit')
    await flushPromises()

    expect(streamExplanationMock).toHaveBeenCalledWith(
      {
        code: 'print(1)',
        language: 'python',
      },
      expect.objectContaining({
        signal: expect.any(AbortSignal),
        onToken: expect.any(Function),
      }),
    )

    expect(wrapper.get('.result').text()).toContain(
      'Mock explanation for python code.',
    )
  })

  it('rejects empty code without calling the API', async () => {
    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#explanation-form').trigger('submit')

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Enter the code you want explained.',
    )
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

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'The AI service could not process the request.',
    )
  })

  it('aborts the active stream when Stop is clicked', async () => {
    let requestSignal: AbortSignal | undefined

    streamExplanationMock.mockImplementation(
      async (_input, { onToken, signal }) => {
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

    expect(wrapper.get('.editor-actions button[type="button"]').text()).toBe(
      'Stop',
    )
    expect(requestSignal?.aborted).toBe(false)

    await wrapper.get('.editor-actions button[type="button"]').trigger('click')
    await flushPromises()

    expect(requestSignal?.aborted).toBe(true)
    expect(wrapper.get('[role="status"]').text()).toBe('Generation stopped.')
    expect(wrapper.get('.result').text()).toContain('Partial response')
  })
})
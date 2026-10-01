import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'

import App from '../App.vue'
import { getSystemInfo } from '@/api/system'
import { explainCode } from '@/api/explanations'

vi.mock('@/api/system', () => ({
  getSystemInfo: vi.fn<typeof getSystemInfo>(),
}))

vi.mock('@/api/explanations', () => ({
  explainCode: vi.fn<typeof explainCode>(),
}))

enableAutoUnmount(afterEach)

const getSystemInfoMock = vi.mocked(getSystemInfo)
const explainCodeMock = vi.mocked(explainCode)

beforeEach(() => {
  getSystemInfoMock
    .mockReset()
    .mockResolvedValue({ applicationName: 'webcoder-api'})
  explainCodeMock.mockReset()
})

describe('service connect', () => {
  it('show waiting while request, and service name after success', async () => {
    getSystemInfoMock.mockResolvedValue({
      applicationName: 'webcoder-api',
    })

    const wrapper = mount(App)
    await nextTick()

    expect(wrapper.get('[role="status"]').text()).toContain('connecting')
    expect(wrapper.get('button').element.disabled).toBe(true)

    await flushPromises()

    expect(wrapper.text()).toContain('connected: webcoder-api')
    expect(wrapper.get('button').element.disabled).toBe(false)
  })

  it('show error while fail, and allow test again', async () => {
    getSystemInfoMock
      .mockRejectedValueOnce(new Error('backend not available temporarily'))
      .mockResolvedValueOnce({
        applicationName: 'webcoder-api',
      })

      const wrapper = mount(App)
      await flushPromises()

      expect(wrapper.get('[role="alert"]').text()).toBe('backend not available temporarily')

      await wrapper.get('button').trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('connected: webcoder-api')
      expect(getSystemInfoMock).toHaveBeenCalledTimes(2)
  })

  it('submit code and render the mock explanation', async() => {
    explainCodeMock.mockResolvedValue({
      mode: 'mock',
      explanation: 'Mock explanation for python code.',
    })

    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#code-input').setValue('print(1)')
    await wrapper.get('#explanation-form').trigger('submit')
    await flushPromises()

    expect(explainCodeMock).toHaveBeenCalledWith({
      code: 'print(1)',
      language: 'python',
    })
    expect(wrapper.get('.result').text()).toContain(
      'Mock explanation for python code.',
    )
  })

  it('rejects empty code without calling the API', async () => 
  {
    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#explanation-form').trigger('submit')

    expect(wrapper.get('[role="alert"]').text()).toContain('Enter')
    expect(explainCodeMock).not.toHaveBeenCalled()
  })

  it('shows the backend error when explanation fails', async () => {
    explainCodeMock.mockRejectedValue(new Error('Upstream AI service timed out.'))

    const wrapper = mount(App)
    await flushPromises()

    await wrapper.get('#code-input').setValue('print(1)')
    await wrapper.get('#explanation-form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Upstream AI service timed out.',
    )
  })
})

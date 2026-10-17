import { afterEach, describe, expect, it } from 'vitest'
import { installPluginHost, pluginRoutes } from './pluginRuntime'

describe('Majo Vue plugin runtime', () => {
  const pluginId = 'runtime-test-plugin'
  afterEach(() => window.Majo?.plugins?.removePlugin(pluginId))

  it('registers plugin routes under the isolated plugin namespace', () => {
    installPluginHost()
    window.Majo?.plugins?.registerRoute({ pluginId, path: `/plugin/${pluginId}/home`, label: 'Runtime test', render: () => {} })
    expect(pluginRoutes.find(route => route.pluginId === pluginId)?.path).toBe(`/plugin/${pluginId}/home`)
  })
})

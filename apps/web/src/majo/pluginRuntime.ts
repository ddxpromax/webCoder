import { reactive } from 'vue'
import { workbench } from './state'

export type PluginRoute = {
  pluginId: string
  path: string
  label: string
  icon?: string
  render: (element: HTMLElement, context: PluginContext) => void | (() => void)
}

export type PluginContext = {
  agentId: string
  api: (path: string, options?: RequestInit) => Promise<Response>
  navigate: (path: string) => void
}

const routes = reactive<PluginRoute[]>([])
let loadingPlugin = ''

type MajoGlobal = { plugins?: { registerRoute: typeof registerRoute; removePlugin: typeof removePlugin }; host?: { selectedAgentId: () => string; fetch: PluginContext['api'] } }
declare global { interface Window { Majo?: MajoGlobal } }

function normalizePath(path: string) {
  const normalized = path.startsWith('/') ? path : `/${path}`
  if (!normalized.startsWith('/plugin/')) throw new Error('Plugin routes must be scoped under /plugin/.')
  return normalized
}

function registerRoute(route: Omit<PluginRoute, 'pluginId'> & { pluginId?: string }) {
  const pluginId = route.pluginId || loadingPlugin
  if (!pluginId) throw new Error('Plugin route registration requires a plugin id.')
  const normalized: PluginRoute = { ...route, pluginId, path: normalizePath(route.path) }
  const existing = routes.findIndex(item => item.path === normalized.path)
  if (existing >= 0) routes.splice(existing, 1, normalized)
  else routes.push(normalized)
}

function removePlugin(pluginId: string) {
  for (let index = routes.length - 1; index >= 0; index -= 1) {
    const route = routes[index]
    if (route && route.pluginId === pluginId) routes.splice(index, 1)
  }
}

function syncInstalled(pluginIds: Set<string>) {
  for (const route of [...routes]) if (!pluginIds.has(route.pluginId)) removePlugin(route.pluginId)
}

export const pluginRoutes = routes

export function beginPluginLoad(pluginId: string) { loadingPlugin = pluginId }
export function endPluginLoad(pluginId: string) { if (loadingPlugin === pluginId) loadingPlugin = '' }
export function syncPluginRoutes(pluginIds: Set<string>) { syncInstalled(pluginIds) }

export function installPluginHost() {
  const global = window
  global.Majo ??= {}
  global.Majo.plugins = { registerRoute, removePlugin }
  global.Majo.host = {
    selectedAgentId: () => workbench.agentId,
    fetch: (path, options = {}) => fetch(`/api${path}`, { ...options, headers: { ...(options.headers ?? {}), 'X-Agent-Id': workbench.agentId } }),
  }
}

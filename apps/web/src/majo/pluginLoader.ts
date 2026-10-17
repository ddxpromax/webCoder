import { beginPluginLoad, endPluginLoad, syncPluginRoutes } from './pluginRuntime'

type FrontendPlugin = { id: string; version: string; frontend_entry?: string }
const loadedVersions = new Map<string, string>()

function headers(): Record<string, string> {
  const token = window.localStorage.getItem('majo_auth_token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function loadScript(plugin: FrontendPlugin) {
  const entry = plugin.frontend_entry
  if (!entry) return
  const url = `/api/frontend_plugin/${encodeURIComponent(plugin.id)}/files/${entry.split('/').map(encodeURIComponent).join('/')}`
  const response = await fetch(url, { headers: headers() })
  if (!response.ok) throw new Error(`Plugin entry returned HTTP ${response.status}.`)
  const blobUrl = URL.createObjectURL(new Blob([await response.text()], { type: 'application/javascript' }))
  beginPluginLoad(plugin.id)
  try { await import(/* @vite-ignore */ blobUrl) }
  finally { endPluginLoad(plugin.id); URL.revokeObjectURL(blobUrl) }
}

/** Loads each installed Majo frontend entry once per version; one failure never blocks another plugin. */
export async function loadFrontendPlugins() {
  let plugins: FrontendPlugin[]
  try {
    const response = await fetch('/api/frontend_plugin', { headers: headers() })
    if (!response.ok) return { loaded: 0, failed: [] as string[] }
    plugins = await response.json() as FrontendPlugin[]
  } catch { return { loaded: 0, failed: [] as string[] } }
  const browserPlugins = plugins.filter(plugin => plugin.frontend_entry)
  syncPluginRoutes(new Set(browserPlugins.map(plugin => plugin.id)))
  for (const [id] of loadedVersions) if (!browserPlugins.some(plugin => plugin.id === id)) loadedVersions.delete(id)
  const failed: string[] = []; let loaded = 0
  for (const plugin of browserPlugins) {
    if (loadedVersions.get(plugin.id) === plugin.version) continue
    try { await loadScript(plugin); loadedVersions.set(plugin.id, plugin.version); loaded += 1 }
    catch (cause) { failed.push(`${plugin.id}: ${cause instanceof Error ? cause.message : 'Could not load plugin.'}`) }
  }
  return { loaded, failed }
}

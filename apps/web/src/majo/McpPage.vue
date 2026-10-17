<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { apiRequest } from './api'

interface McpClient {
  key: string
  name: string
  description: string
  enabled: boolean
  transport: 'stdio' | 'streamable_http' | 'sse'
  url: string
  headers: Record<string, string>
  command: string
  args: string[]
  env: Record<string, string>
  cwd: string
  http_timeout: number | null
  tools: string[] | null
  access_summary?: { default_effect: string; overrides_count: number }
  oauth_status?: {
    authorized: boolean
    expires_at: number
    scope: string
    client_id: string
  }
}

interface McpTool {
  name: string
  description: string
  enabled: boolean
}

interface AccessRule {
  source_type: string
  source_value: string
  subject_type: 'all' | 'user'
  subject_value: string
  effect: 'allow' | 'ask' | 'deny'
}

interface ToolDefault {
  tool_name: string
  effect: 'allow' | 'ask' | 'deny'
}

interface ToolOverride extends AccessRule {
  tool_name: string
}

interface AccessPolicy {
  default_effect: 'allow' | 'ask' | 'deny'
  client_overrides: AccessRule[]
  tool_defaults: ToolDefault[]
  tool_overrides: ToolOverride[]
  unmanaged_rules_count: number
}

const clients = ref<McpClient[]>([])
const selected = ref<McpClient | null>(null)
const tools = ref<McpTool[]>([])
const loading = ref(true)
const toolsLoading = ref(false)
const error = ref('')
const notice = ref('')
const editing = ref(false)
const creating = ref(false)
const form = ref({
  key: '',
  name: '',
  description: '',
  enabled: true,
  transport: 'stdio' as McpClient['transport'],
  command: '',
  args: '',
  cwd: '',
  url: '',
  headers: '',
  env: '',
  http_timeout: '',
})
const policy = ref<AccessPolicy | null>(null)
const oauthBusy = ref(false)
const oauthPolling = ref<number | null>(null)
const oauthForm = ref({ client_id: '', scope: '', auth_endpoint: '', token_endpoint: '' })

function linesToRecord(text: string) {
  const record: Record<string, string> = {}
  for (const line of text.split('\n')) {
    const separator = line.indexOf('=')
    if (separator < 1 || !line.trim()) continue
    record[line.slice(0, separator).trim()] = line.slice(separator + 1)
  }
  return record
}

function recordToLines(values: Record<string, string>) {
  return Object.entries(values).map(([key, value]) => `${key}=${value}`).join('\n')
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    clients.value = await apiRequest<McpClient[]>('/mcp')
    if (selected.value) {
      selected.value = clients.value.find((client) => client.key === selected.value?.key) ?? null
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load MCP clients.'
  } finally {
    loading.value = false
  }
}

async function select(client: McpClient) {
  selected.value = client
  editing.value = false
  creating.value = false
  tools.value = []
  policy.value = null
  oauthForm.value = {
    client_id: client.oauth_status?.client_id ?? '',
    scope: client.oauth_status?.scope ?? '',
    auth_endpoint: '', token_endpoint: '',
  }
  toolsLoading.value = true
  try {
    policy.value = await apiRequest<AccessPolicy>(
      `/mcp/policy/${encodeURIComponent(client.key)}`,
    )
    tools.value = await apiRequest<McpTool[]>(`/mcp/tools/${encodeURIComponent(client.key)}`)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load MCP tools.'
  } finally {
    toolsLoading.value = false
  }
}

function clearOAuthPolling() {
  if (oauthPolling.value !== null) window.clearInterval(oauthPolling.value)
  oauthPolling.value = null
}

async function refreshOAuthStatus() {
  const client = selected.value
  if (!client) return
  try {
    const status = await apiRequest<McpClient['oauth_status']>(
      `/mcp/oauth/status/${encodeURIComponent(client.key)}`,
    )
    client.oauth_status = status
    const index = clients.value.findIndex((item) => item.key === client.key)
    const listed = index >= 0 ? clients.value[index] : undefined
    if (listed) clients.value[index] = { ...listed, oauth_status: status }
    if (status?.authorized) clearOAuthPolling()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not load OAuth status.'
    clearOAuthPolling()
  }
}

async function authorizeOAuth() {
  const client = selected.value
  if (!client) return
  oauthBusy.value = true
  error.value = ''
  try {
    const response = await apiRequest<{ auth_url: string }>(
      `/mcp/oauth/start/${encodeURIComponent(client.key)}`,
      {
        method: 'POST',
        body: JSON.stringify({
          client_id: oauthForm.value.client_id,
          scope: oauthForm.value.scope,
          auth_endpoint: oauthForm.value.auth_endpoint,
          token_endpoint: oauthForm.value.token_endpoint,
        }),
      },
    )
    window.open(response.auth_url, 'mcp-oauth', 'popup,width=600,height=700')
    clearOAuthPolling()
    oauthPolling.value = window.setInterval(() => void refreshOAuthStatus(), 2_000)
    notice.value = 'Complete authorization in the opened window.'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not start OAuth authorization.'
  } finally {
    oauthBusy.value = false
  }
}

async function revokeOAuth() {
  const client = selected.value
  if (!client) return
  oauthBusy.value = true
  error.value = ''
  try {
    await apiRequest(`/mcp/oauth/${encodeURIComponent(client.key)}`, { method: 'DELETE' })
    await refreshOAuthStatus()
    notice.value = 'OAuth tokens cleared.'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not clear OAuth tokens.'
  } finally {
    oauthBusy.value = false
  }
}

function startCreate() {
  selected.value = null
  creating.value = true
  editing.value = true
  form.value = {
    key: '', name: '', description: '', enabled: true, transport: 'stdio',
    command: '', args: '', cwd: '', url: '', headers: '', env: '', http_timeout: '',
  }
}

function startEdit(client: McpClient) {
  selected.value = client
  creating.value = false
  editing.value = true
  form.value = {
    key: client.key,
    name: client.name,
    description: client.description,
    enabled: client.enabled,
    transport: client.transport,
    command: client.command,
    args: client.args.join('\n'),
    cwd: client.cwd,
    url: client.url,
    headers: recordToLines(client.headers),
    env: recordToLines(client.env),
    http_timeout: client.http_timeout?.toString() ?? '',
  }
}

async function saveClient() {
  error.value = ''
  notice.value = ''
  if (!form.value.key.trim()) {
    error.value = 'A client key is required.'
    return
  }
  const data = {
    name: form.value.name,
    description: form.value.description,
    enabled: form.value.enabled,
    transport: form.value.transport,
    command: form.value.command,
    args: form.value.args.split('\n').map((arg) => arg.trim()).filter(Boolean),
    cwd: form.value.cwd,
    url: form.value.url,
    headers: linesToRecord(form.value.headers),
    env: linesToRecord(form.value.env),
    http_timeout: form.value.http_timeout ? Number(form.value.http_timeout) : null,
  }
  try {
    const key = form.value.key.trim()
    const saved = creating.value
      ? await apiRequest<McpClient>('/mcp', {
          method: 'POST', body: JSON.stringify({ client_key: key, client: data }),
        })
      : await apiRequest<McpClient>(`/mcp/${encodeURIComponent(key)}`, {
          method: 'PUT', body: JSON.stringify(data),
        })
    editing.value = false
    creating.value = false
    notice.value = 'MCP client saved.'
    await refresh()
    await select(saved)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save MCP client.'
  }
}

async function toggle(client: McpClient) {
  try {
    await apiRequest(`/mcp/toggle/${encodeURIComponent(client.key)}`, { method: 'PATCH' })
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not update MCP client.'
  }
}

async function remove(client: McpClient) {
  if (!window.confirm(`Delete MCP client "${client.name}"?`)) return
  try {
    await apiRequest(`/mcp/${encodeURIComponent(client.key)}`, { method: 'DELETE' })
    selected.value = null
    tools.value = []
    notice.value = 'MCP client deleted.'
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not delete MCP client.'
  }
}

async function toggleTool(tool: McpTool) {
  const client = selected.value
  if (!client) return
  const enabled = new Set(tools.value.filter((item) => item.enabled).map((item) => item.name))
  if (enabled.has(tool.name)) enabled.delete(tool.name)
  else enabled.add(tool.name)
  try {
    tools.value = await apiRequest<McpTool[]>(`/mcp/tools/${encodeURIComponent(client.key)}`, {
      method: 'PUT', body: JSON.stringify({ tools: [...enabled] }),
    })
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not update tool access.'
  }
}

async function savePolicy() {
  const client = selected.value
  if (!client || !policy.value) return
  try {
    policy.value = await apiRequest<AccessPolicy>(`/mcp/policy/${encodeURIComponent(client.key)}`, {
      method: 'PUT',
      body: JSON.stringify(policy.value),
    })
    notice.value = 'MCP access policy saved.'
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Could not save MCP policy.'
  }
}

function addClientRule() {
  policy.value?.client_overrides.push({
    source_type: 'channel', source_value: 'console',
    subject_type: 'all', subject_value: '', effect: 'ask',
  })
}

function addToolDefault() {
  policy.value?.tool_defaults.push({ tool_name: '', effect: 'ask' })
}

function addToolOverride() {
  policy.value?.tool_overrides.push({
    tool_name: '', source_type: 'channel', source_value: 'console',
    subject_type: 'all', subject_value: '', effect: 'ask',
  })
}

onMounted(() => void refresh())
onBeforeUnmount(clearOAuthPolling)
</script>

<template>
  <section class="settings-page">
    <header class="settings-heading">
      <div>
        <p class="eyebrow">INTEGRATIONS</p>
        <h1>MCP servers</h1>
        <p>Connect tool servers to the agent runtime.</p>
      </div>
      <button class="primary" @click="startCreate">Add MCP server</button>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-notice">{{ notice }}</p>
    <div class="settings-grid">
      <div class="settings-card">
        <h2>Clients</h2>
        <p v-if="loading">Loading…</p>
        <p v-else-if="!clients.length">No MCP servers configured.</p>
        <button
          v-for="client in clients" :key="client.key" type="button"
          class="provider-row" :class="{ selected: selected?.key === client.key }"
          @click="select(client)"
        >
          {{ client.name }} <span class="muted">{{ client.enabled ? 'Enabled' : 'Disabled' }}</span>
        </button>
      </div>

      <div v-if="editing" class="settings-card">
        <h2>{{ creating ? 'Add MCP server' : 'Edit MCP server' }}</h2>
        <form class="stacked-form" @submit.prevent="saveClient">
          <label>Client key <input v-model="form.key" :disabled="!creating" placeholder="my_server" /></label>
          <label>Display name <input v-model="form.name" placeholder="My server" /></label>
          <label>Description <input v-model="form.description" /></label>
          <label>Transport
            <select v-model="form.transport">
              <option value="stdio">stdio</option>
              <option value="streamable_http">Streamable HTTP</option>
              <option value="sse">SSE</option>
            </select>
          </label>
          <template v-if="form.transport === 'stdio'">
            <label>Command <input v-model="form.command" placeholder="node" /></label>
            <label>Arguments (one per line) <textarea v-model="form.args" rows="4" /></label>
            <label>Working directory <input v-model="form.cwd" placeholder="Optional" /></label>
            <label>Environment (NAME=value per line) <textarea v-model="form.env" rows="4" /></label>
          </template>
          <template v-else>
            <label>Server URL <input v-model="form.url" type="url" placeholder="https://example.com/mcp" /></label>
            <label>Headers (NAME=value per line) <textarea v-model="form.headers" rows="4" /></label>
            <label>HTTP timeout (seconds) <input v-model="form.http_timeout" type="number" min="1" /></label>
          </template>
          <label><input v-model="form.enabled" type="checkbox" /> Enabled</label>
          <div class="environment-actions">
            <button type="submit" class="primary">Save client</button>
            <button type="button" @click="editing = false">Cancel</button>
          </div>
        </form>
      </div>

      <div v-else-if="selected" class="settings-card">
        <h2>{{ selected.name }}</h2>
        <p>{{ selected.description || selected.key }}</p>
        <p class="muted">{{ selected.transport }} · {{ selected.url || selected.command }}</p>
        <div class="environment-actions">
          <button @click="startEdit(selected)">Edit</button>
          <button @click="toggle(selected)">{{ selected.enabled ? 'Disable' : 'Enable' }}</button>
          <button @click="remove(selected)">Delete</button>
        </div>
        <template v-if="selected.transport !== 'stdio'">
          <h3>OAuth authorization</h3>
          <p class="muted">
            {{ selected.oauth_status?.authorized ? 'Authorized for this MCP server.' : 'Authorize a remote MCP server with OAuth 2.1 and PKCE.' }}
          </p>
          <p v-if="selected.oauth_status?.authorized" class="success-notice">
            Authorized{{ selected.oauth_status.expires_at ? ` until ${new Date(selected.oauth_status.expires_at * 1000).toLocaleString()}` : '' }}.
          </p>
          <details class="oauth-details">
            <summary>Advanced OAuth options</summary>
            <div class="stacked-form oauth-form">
              <label>Client ID <input v-model="oauthForm.client_id" autocomplete="off" /></label>
              <label>Scope <input v-model="oauthForm.scope" placeholder="Optional" /></label>
              <label>Authorization endpoint <input v-model="oauthForm.auth_endpoint" type="url" placeholder="Auto-discover when empty" /></label>
              <label>Token endpoint <input v-model="oauthForm.token_endpoint" type="url" placeholder="Auto-discover when empty" /></label>
            </div>
          </details>
          <div class="environment-actions">
            <button class="primary" :disabled="oauthBusy" @click="authorizeOAuth">
              {{ selected.oauth_status?.authorized ? 'Reauthorize' : 'Authorize' }}
            </button>
            <button v-if="selected.oauth_status?.authorized" :disabled="oauthBusy" @click="revokeOAuth">Revoke</button>
          </div>
        </template>
        <h3>Available tools</h3>
        <p v-if="toolsLoading">Discovering server tools…</p>
        <p v-else-if="!tools.length">No tools found.</p>
        <div v-for="tool in tools" :key="tool.name" class="data-row">
          <label><input :checked="tool.enabled" type="checkbox" @change="toggleTool(tool)" />
            {{ tool.name }}
          </label>
          <small>{{ tool.description }}</small>
        </div>
        <template v-if="policy">
          <h3>Default access</h3>
          <select v-model="policy.default_effect">
            <option value="allow">Allow</option>
            <option value="ask">Ask</option>
            <option value="deny">Deny</option>
          </select>
          <h3>Client access rules</h3>
          <div v-for="(rule, index) in policy.client_overrides" :key="`client-${index}`" class="mcp-rule-row">
            <input v-model="rule.source_value" placeholder="Channel" aria-label="Channel" />
            <select v-model="rule.subject_type" aria-label="Subject type">
              <option value="all">All users</option><option value="user">User</option>
            </select>
            <input v-if="rule.subject_type === 'user'" v-model="rule.subject_value" placeholder="User ID" />
            <select v-model="rule.effect" aria-label="Effect">
              <option value="allow">Allow</option><option value="ask">Ask</option><option value="deny">Deny</option>
            </select>
            <button @click="policy.client_overrides.splice(index, 1)">Remove</button>
          </div>
          <button @click="addClientRule">Add client rule</button>
          <h3>Tool defaults</h3>
          <div v-for="(rule, index) in policy.tool_defaults" :key="`default-${index}`" class="mcp-rule-row">
            <input v-model="rule.tool_name" placeholder="Tool name" aria-label="Tool name" />
            <select v-model="rule.effect" aria-label="Effect">
              <option value="allow">Allow</option><option value="ask">Ask</option><option value="deny">Deny</option>
            </select>
            <button @click="policy.tool_defaults.splice(index, 1)">Remove</button>
          </div>
          <button @click="addToolDefault">Add tool default</button>
          <h3>Tool access rules</h3>
          <div v-for="(rule, index) in policy.tool_overrides" :key="`tool-${index}`" class="mcp-rule-row">
            <input v-model="rule.tool_name" placeholder="Tool name" aria-label="Tool name" />
            <input v-model="rule.source_value" placeholder="Channel" aria-label="Channel" />
            <select v-model="rule.subject_type" aria-label="Subject type">
              <option value="all">All users</option><option value="user">User</option>
            </select>
            <input v-if="rule.subject_type === 'user'" v-model="rule.subject_value" placeholder="User ID" />
            <select v-model="rule.effect" aria-label="Effect">
              <option value="allow">Allow</option><option value="ask">Ask</option><option value="deny">Deny</option>
            </select>
            <button @click="policy.tool_overrides.splice(index, 1)">Remove</button>
          </div>
          <button @click="addToolOverride">Add tool rule</button>
          <div class="environment-actions"><button class="primary" @click="savePolicy">Save policy</button></div>
        </template>
      </div>
    </div>
  </section>
</template>

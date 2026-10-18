export interface ToolCallParams { [key: string]: unknown }

export function parseToolCallInput(input: string): ToolCallParams {
  try {
    const parsed: unknown = JSON.parse(input)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as ToolCallParams : {}
  } catch { return {} }
}

function text(params: ToolCallParams, ...keys: string[]) {
  for (const key of keys) if (typeof params[key] === 'string' && params[key]) return params[key] as string
  return ''
}

function shortPath(value: string) {
  const parts = value.replaceAll('\\', '/').split('/').filter(Boolean)
  return parts.at(-1) || value
}

export function toolCallTitle(name: string, params: ToolCallParams) {
  const path = text(params, 'file_path', 'path', 'file', 'target_path')
  const file = path ? shortPath(path) : ''
  const command = text(params, 'command', 'cmd')
  const query = text(params, 'query', 'pattern', 'search')
  const agent = text(params, 'agent_id', 'agent', 'task')
  switch (name) {
    case 'read_file': return file ? `Read ${file}` : 'Read file'
    case 'write_file': return file ? `Write ${file}` : 'Write file'
    case 'edit_file': return file ? `Edit ${file}` : 'Edit file'
    case 'append_file': return file ? `Append ${file}` : 'Append file'
    case 'grep_search': return query ? `Search for ${query}` : 'Search files'
    case 'glob_search': return query ? `Find ${query}` : 'Find files'
    case 'memory_search': return query ? `Search memory: ${query}` : 'Search memory'
    case 'execute_shell_command': case 'shell': case 'bash': case 'terminal': case 'run_command':
      return command ? `Run ${command}` : 'Run command'
    case 'list_agents': return 'List agents'
    case 'chat_with_agent': case 'submit_to_agent': case 'check_agent_task':
      return agent ? `Agent: ${agent}` : name.replaceAll('_', ' ')
    case 'delegate_external_agent': return 'Delegate external agent'
    case 'browser_use': case 'browser_navigate': case 'navigate': return 'Use browser'
    case 'view_image': return file ? `View ${file}` : 'View image'
    case 'view_video': return file ? `View ${file}` : 'View video'
    case 'send_file_to_user': return file ? `Send ${file}` : 'Send file'
    case 'get_current_time': return 'Get current time'
    case 'set_user_timezone': return 'Set timezone'
    case 'get_token_usage': return 'Get token usage'
    case 'materialize_skill': return 'Materialize skill'
    default: return name.replaceAll('_', ' ')
  }
}

export function prettyToolCallValue(value: unknown) {
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

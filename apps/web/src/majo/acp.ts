export function parseAcpArgs(value: string) {
  return value.split('\n').map((part) => part.trim()).filter(Boolean)
}

export function parseAcpEnv(value: string) {
  const env: Record<string, string> = {}
  for (const line of value.split('\n').map((part) => part.trim()).filter(Boolean)) {
    const separator = line.indexOf('=')
    if (separator <= 0) throw new Error(`Invalid environment entry: ${line}`)
    env[line.slice(0, separator).trim()] = line.slice(separator + 1).trim()
  }
  return env
}

export function stringifyAcpEnv(env: Record<string, string> = {}) {
  return Object.entries(env).map(([key, value]) => `${key}=${value}`).join('\n')
}

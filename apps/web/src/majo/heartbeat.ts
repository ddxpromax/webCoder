export type HeartbeatUnit = 'm' | 'h'

export function parseHeartbeatEvery(value: string): { number: number; unit: HeartbeatUnit } {
  const match = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/i.exec(value.trim())
  if (!match) return { number: 6, unit: 'h' }
  const minutes = Number(match[1] || 0) * 60 + Number(match[2] || 0) + Math.round(Number(match[3] || 0) / 60)
  if (minutes <= 0) return { number: 6, unit: 'h' }
  return minutes >= 60 && minutes % 60 === 0 ? { number: minutes / 60, unit: 'h' } : { number: minutes, unit: 'm' }
}

export function serializeHeartbeatEvery(number: number, unit: HeartbeatUnit) {
  return `${Math.max(1, Math.floor(number || 1))}${unit}`
}

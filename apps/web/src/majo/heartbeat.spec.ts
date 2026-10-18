import { describe, expect, it } from 'vitest'
import { parseHeartbeatEvery, serializeHeartbeatEvery } from './heartbeat'

describe('Majo heartbeat interval', () => {
  it('round-trips hourly and minute schedules', () => {
    expect(parseHeartbeatEvery('6h')).toEqual({ number: 6, unit: 'h' })
    expect(parseHeartbeatEvery('90m')).toEqual({ number: 90, unit: 'm' })
    expect(serializeHeartbeatEvery(6, 'h')).toBe('6h')
  })
  it('uses the Majo default for malformed schedules', () => expect(parseHeartbeatEvery('invalid')).toEqual({ number: 6, unit: 'h' }))
})

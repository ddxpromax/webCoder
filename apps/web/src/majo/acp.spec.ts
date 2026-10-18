import { describe, expect, it } from 'vitest'
import { parseAcpArgs, parseAcpEnv, stringifyAcpEnv } from './acp'

describe('Majo ACP form values', () => {
  it('serializes command arguments and environment entries', () => {
    expect(parseAcpArgs('-y\n@zed/acp\n')).toEqual(['-y', '@zed/acp'])
    expect(parseAcpEnv('API_KEY=secret\nMODE=dev')).toEqual({ API_KEY: 'secret', MODE: 'dev' })
    expect(stringifyAcpEnv({ MODE: 'dev' })).toBe('MODE=dev')
  })
  it('rejects malformed environment entries', () => expect(() => parseAcpEnv('MISSING')).toThrow('Invalid environment entry'))
})

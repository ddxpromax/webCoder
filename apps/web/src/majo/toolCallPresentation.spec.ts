import { describe, expect, it } from 'vitest'
import { parseToolCallInput, toolCallTitle } from './toolCallPresentation'

describe('Majo tool call presentation', () => {
  it('renders tool-specific titles from structured inputs', () => {
    expect(toolCallTitle('read_file', parseToolCallInput('{"path":"src/App.vue"}'))).toBe('Read App.vue')
    expect(toolCallTitle('shell', parseToolCallInput('{"command":"npm test"}'))).toBe('Run npm test')
  })

  it('falls back safely when tool input is not JSON', () => {
    expect(parseToolCallInput('not-json')).toEqual({})
    expect(toolCallTitle('memory_search', {})).toBe('Search memory')
  })
})

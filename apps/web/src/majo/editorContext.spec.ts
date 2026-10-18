import { describe, expect, it } from 'vitest'
import { formatEditorSelectionForChat } from './editorContext'

describe('Majo editor chat context', () => {
  const source = 'first line\nsecond line\nthird line'

  it('uses the path for a whole-file selection', () => {
    expect(formatEditorSelectionForChat('src/App.ts', source, {
      startLineNumber: 1, startColumn: 1, endLineNumber: 3, endColumn: 11,
    })).toBe('src/App.ts')
  })

  it('uses a line range for complete selected lines', () => {
    expect(formatEditorSelectionForChat('src/App.ts', source, {
      startLineNumber: 2, startColumn: 1, endLineNumber: 3, endColumn: 1,
    })).toBe('src/App.ts:2')
  })

  it('includes a fenced code block for a partial selection', () => {
    expect(formatEditorSelectionForChat('src/App.ts', source, {
      startLineNumber: 2, startColumn: 4, endLineNumber: 2, endColumn: 8,
    })).toBe('src/App.ts:2\n```typescript\nond lin\n```')
  })
})

import { describe, expect, it } from 'vitest'
import { keepDiffHunk, undoDiffHunk } from './diffHunks'

const hunk = { originalStartLineNumber: 2, originalEndLineNumber: 2, modifiedStartLineNumber: 2, modifiedEndLineNumber: 2 }
describe('Majo hunk review operations', () => {
  it('keeps one changed hunk in the original baseline', () => expect(keepDiffHunk('one\ntwo\nthree', 'one\nTWO\nthree', hunk)).toBe('one\nTWO\nthree'))
  it('undoes one changed hunk in the disk version', () => expect(undoDiffHunk('one\ntwo\nthree', 'one\nTWO\nthree', hunk)).toBe('one\ntwo\nthree'))
  it('keeps and undoes an inserted hunk', () => {
    const inserted = { originalStartLineNumber: 2, originalEndLineNumber: 1, modifiedStartLineNumber: 2, modifiedEndLineNumber: 2 }
    expect(keepDiffHunk('one\nthree', 'one\ntwo\nthree', inserted)).toBe('one\ntwo\nthree')
    expect(undoDiffHunk('one\nthree', 'one\ntwo\nthree', inserted)).toBe('one\nthree')
  })
  it('keeps and undoes a deleted hunk', () => {
    const deleted = { originalStartLineNumber: 2, originalEndLineNumber: 2, modifiedStartLineNumber: 2, modifiedEndLineNumber: 1 }
    expect(keepDiffHunk('one\ntwo\nthree', 'one\nthree', deleted)).toBe('one\nthree')
    expect(undoDiffHunk('one\ntwo\nthree', 'one\nthree', deleted)).toBe('one\ntwo\nthree')
  })
})

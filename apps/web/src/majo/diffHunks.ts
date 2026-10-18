export interface DiffHunk {
  originalStartLineNumber: number
  originalEndLineNumber: number
  modifiedStartLineNumber: number
  modifiedEndLineNumber: number
}

function range(start: number, end: number) {
  if (end === 0 || end < start) return { start: Math.max(0, start - 1), end: Math.max(0, start - 1) }
  return { start: start - 1, end }
}

export function keepDiffHunk(original: string, modified: string, hunk: DiffHunk) {
  const before = original.split('\n'); const after = modified.split('\n')
  const left = range(hunk.originalStartLineNumber, hunk.originalEndLineNumber)
  const right = range(hunk.modifiedStartLineNumber, hunk.modifiedEndLineNumber)
  return [...before.slice(0, left.start), ...after.slice(right.start, right.end), ...before.slice(left.end)].join('\n')
}

export function undoDiffHunk(original: string, modified: string, hunk: DiffHunk) {
  const before = original.split('\n'); const after = modified.split('\n')
  const left = range(hunk.originalStartLineNumber, hunk.originalEndLineNumber)
  const right = range(hunk.modifiedStartLineNumber, hunk.modifiedEndLineNumber)
  return [...after.slice(0, right.start), ...before.slice(left.start, left.end), ...after.slice(right.end)].join('\n')
}

export interface EditorSelection {
  startLineNumber: number
  startColumn: number
  endLineNumber: number
  endColumn: number
}

export function languageForPath(path: string) {
  const extension = path.split('.').pop()?.toLowerCase()
  const languages: Record<string, string> = {
    java: 'java', js: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
    vue: 'html', html: 'html', htm: 'html', css: 'css', scss: 'scss', less: 'less',
    json: 'json', jsonc: 'json', md: 'markdown', mdx: 'markdown', yaml: 'yaml', yml: 'yaml',
    xml: 'xml', sql: 'sql', py: 'python', go: 'go', rs: 'rust', c: 'c', h: 'c', cpp: 'cpp',
    hpp: 'cpp', cs: 'csharp', sh: 'shell', bash: 'shell', zsh: 'shell', dockerfile: 'dockerfile',
  }
  return languages[extension || ''] || 'plaintext'
}

function withoutTrailingNewlines(value: string) { return value.replace(/\n+$/, '') }

/** Majo's compact path, line-range, or fenced-selection chat context. */
export function formatEditorSelectionForChat(
  path: string,
  source: string,
  selection: EditorSelection,
) {
  const lines = source.split('\n')
  let endLine = selection.endLineNumber
  let endColumn = selection.endColumn
  if (endLine > selection.startLineNumber && endColumn === 1) {
    endLine -= 1
    endColumn = (lines[endLine - 1] || '').length + 1
  }
  const selectedLines = lines.slice(selection.startLineNumber - 1, endLine)
  if (selectedLines.length) {
    selectedLines[0] = selectedLines[0]?.slice(selection.startColumn - 1) || ''
    selectedLines[selectedLines.length - 1] = (selectedLines[selectedLines.length - 1] || '')
      .slice(0, endColumn - 1)
  }
  const selected = selectedLines.join('\n')
  if (withoutTrailingNewlines(selected) === withoutTrailingNewlines(source)) return path
  const completeLines = lines.slice(selection.startLineNumber - 1, endLine).join('\n')
  const range = selection.startLineNumber === endLine
    ? `${selection.startLineNumber}` : `${selection.startLineNumber}-${endLine}`
  if (withoutTrailingNewlines(selected) === completeLines) return `${path}:${range}`
  return `${path}:${range}\n\`\`\`${languageForPath(path)}\n${selected}\n\`\`\``
}

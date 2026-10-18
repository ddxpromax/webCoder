import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

describe('Majo-compatible Markdown rendering', () => {
  it('renders GFM content and opens links safely', () => {
    const html = renderMarkdown('## Hello\n\n| A | B |\n| - | - |\n| 1 | 2 |\n\n[Docs](https://example.com)')
    expect(html).toContain('<h2>Hello</h2>')
    expect(html).toContain('<table>')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('removes unsafe model-supplied HTML', () => {
    const html = renderMarkdown('<img src=x onerror="alert(1)"><script>alert(1)</script>')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('<script')
  })
})

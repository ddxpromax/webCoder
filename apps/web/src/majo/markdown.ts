import DOMPurify from 'dompurify'
import { marked } from 'marked'

/** Render model-supplied Markdown with the same safe external-link policy as Majo. */
export function renderMarkdown(source: string) {
  const unsafeHtml = marked.parse(source, { gfm: true, breaks: true }) as string
  const template = document.createElement('template')
  template.innerHTML = DOMPurify.sanitize(unsafeHtml, { USE_PROFILES: { html: true } })
  template.content.querySelectorAll('a').forEach((link) => {
    link.target = '_blank'
    link.rel = 'noopener noreferrer'
  })
  return template.innerHTML
}

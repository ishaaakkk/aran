import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { DOCS_BASE_URL, DOCS_PAGES, docsUrl } from './docsLinks'

describe('docsUrl', () => {
  it("points at a page under the site's /docs route", () => {
    expect(docsUrl('programmatic-access/jdbc', 'https://docs.example'))
      .toBe('https://docs.example/docs/programmatic-access/jdbc')
  })

  it('defaults to an absolute https base with no trailing slash', () => {
    expect(DOCS_BASE_URL).toMatch(/^https:\/\/[^/]+(\/.*[^/])?$/)
  })

  // A moved or renamed docs page must fail here, not as a 404 a user clicks into.
  it.each(DOCS_PAGES)('%s exists in docs-site/docs', (page) => {
    expect(existsSync(join(process.cwd(), '..', 'docs-site', 'docs', `${page}.md`))).toBe(true)
  })
})

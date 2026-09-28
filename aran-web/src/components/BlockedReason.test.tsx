import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { BlockedReason } from './BlockedReason'

/**
 * Second review of UA2: the reason Register was disabled mounted already holding its text, with
 * role="status" — a live region screen readers often do not announce, because nothing inside it
 * changed. It is now always mounted, empty when nothing blocks.
 */
describe('BlockedReason', () => {
  it('is mounted as an empty status when nothing blocks the action', () => {
    expect(renderToStaticMarkup(<BlockedReason id="r" reason={null} />))
      .toMatch(/^<span id="r" role="status"[^>]*><\/span>$/)
  })

  it('holds the reason when something does', () => {
    expect(renderToStaticMarkup(<BlockedReason id="r" reason="Create a project first." />))
      .toMatch(/^<span id="r" role="status"[^>]*>Create a project first\.<\/span>$/)
  })

  it('is what both Register buttons use, unconditionally', () => {
    for (const [file, id] of [['DatasetsListView', 'datasets-register-reason'], ['RepositoriesListView', 'repos-register-reason']]) {
      const source = readFileSync(new URL(`../views/${file}.tsx`, import.meta.url), 'utf8')
      expect(source, file).toContain(`<BlockedReason id="${id}" reason={registerBlockedReason}`)
      expect(source, file).not.toContain('{registerBlockedReason && (')
    }
  })
})

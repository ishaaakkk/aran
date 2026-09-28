import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { LiveStatus } from './LiveStatus'

/**
 * Third review of UA2 (MINOR): the wrapper save's notes, the unconfirmed-promote note and the
 * member notice each mounted already holding their text — regions screen readers often do not
 * announce. Each is now a LiveStatus mounted empty, whose text changes.
 */
describe('LiveStatus', () => {
  it('is mounted as an empty, visually hidden status when there is nothing to say', () => {
    expect(renderToStaticMarkup(<LiveStatus testId="s" className="note">{null}</LiveStatus>))
      .toBe('<p role="status" data-testid="s" class="sr-only"></p>')
  })

  it('holds its message, styled, when there is one', () => {
    expect(renderToStaticMarkup(<LiveStatus id="x" testId="s" className="note">Saved.</LiveStatus>))
      .toBe('<p id="x" role="status" data-testid="s" class="note">Saved.</p>')
  })

  it('is what the outcome notes use, unconditionally', () => {
    const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')
    expect(read('./datasets/DatasetWrapperTab.tsx')).toContain('<LiveStatus id="dataset-wrapper-status" testId="dataset-wrapper-status"')
    expect(read('./alignments/CompilePanel.tsx')).toContain('<LiveStatus testId="align-promote-status"')
    // The Users panel's member-create notice (UA2) was not carried into the merged panel (UA6's);
    // the server's 409 sentence stands in for it until it is re-ported (register, Tier A merge).
    expect(read('./alignments/CompilePanel.tsx')).not.toContain('{promoteCheck && (\n            <div\n              role="status"')
  })
})

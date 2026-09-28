import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { DatasetFailureNote } from './DatasetFailureNote'

/**
 * A failed dataset row says why and offers a Retry (UI audit J2-F5), in text a person can read:
 * the reason used `text-rose-300` at 9.5 px, ~1.9:1 on the default light skin's white rows. The
 * testids are the e2e contract `DatasetsPage.expectFailed` drives.
 */

const PALE = /\btext-(rose|red|pink|violet|purple)-(50|100|200|300|400)\b/

describe('DatasetFailureNote', () => {
  it('shows the reason and the Retry under their e2e testids, in the error token', () => {
    const html = renderToStaticMarkup(
      <DatasetFailureNote name="two_arrays.json" reason="two arrays of primitives: tags, ids"
        retryLabel="Retry bootstrap" onRetry={() => {}} />,
    )
    expect(html).toContain('data-testid="datasets-failure-two_arrays.json"')
    expect(html).toContain('two arrays of primitives: tags, ids')
    expect(html).toContain('data-testid="datasets-retry-two_arrays.json"')
    expect(html).toContain('Retry bootstrap')
    expect(html).toContain('text-error')
    expect(html).not.toMatch(PALE)
  })

  it('renders no empty reason and no Retry it cannot run', () => {
    const html = renderToStaticMarkup(<DatasetFailureNote name="x.csv" reason={null} retryLabel={null} />)
    expect(html).toBe('')
  })
})

import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ResultPanel } from './WorkbenchPanels'
import type { QueryRunResult } from '../../services/types'

/**
 * The result pane's lifecycle (UI audit rules R3/R4). Rendered with `renderToStaticMarkup` — the
 * project has no DOM environment — so these assert what is on screen for each state, not clicks.
 */

const RESULT: QueryRunResult = {
  headers: ['title'], headerNames: ['title'],
  rows: [['Federated Query Processing']] as QueryRunResult['rows'],
  rowCount: 1000, offset: 0, pageSize: 1000, hasMore: true, truncated: true, elapsedMs: 12,
}

const noop = () => {}

describe('ResultPanel — a result is bound to the run that produced it (J5-F1)', () => {
  it('says so when the editor changed since the run, and names what the actions will use', () => {
    const html = renderToStaticMarkup(
      <ResultPanel result={RESULT} stale ranText="SELECT Paper.title FROM Paper" onRunCurrent={noop} onPage={noop} />,
    )
    expect(html).toContain('data-testid="oql-stale"')
    expect(html).toContain('Editor changed since this run')
    expect(html).toContain('paging, export and save use the query that produced this result')
    expect(html).toContain('data-state="stale"')
    // The grid is still the answer to the run — dimmed, not hidden.
    expect(html).toContain('data-testid="oql-results"')
  })

  it('shows no banner while the editor still holds the run text', () => {
    const html = renderToStaticMarkup(<ResultPanel result={RESULT} />)
    expect(html).not.toContain('data-testid="oql-stale"')
    expect(html).toContain('data-state="ok"')
  })
})

describe('ResultPanel — failures and waiting (R4, J5-F11)', () => {
  it('a failure replaces the result: the old grid is never shown under a new error', () => {
    const html = renderToStaticMarkup(<ResultPanel result={RESULT} error="Unknown class: Papr" />)
    expect(html).toContain('data-testid="oql-error"')
    expect(html).not.toContain('data-testid="oql-results"')
    expect(html).not.toContain('data-testid="oql-save-product"')
    expect(html).toContain('data-state="error"')
  })

  it('a running query shows neither the previous result nor its actions', () => {
    const html = renderToStaticMarkup(
      <ResultPanel result={RESULT} running onSaveAsProduct={noop} onExportAll={noop} />,
    )
    expect(html).not.toContain('data-testid="oql-results"')
    expect(html).not.toContain('data-testid="oql-export"')
    expect(html).not.toContain('data-testid="oql-save-product"')
    expect(html).toContain('data-state="running"')
  })

  it('stopping the wait says the server may still be working, and shows no result', () => {
    const html = renderToStaticMarkup(<ResultPanel result={null} stopped />)
    expect(html).toContain('data-testid="oql-stopped"')
    expect(html).toContain('Stopped waiting')
    expect(html).toContain('may still be finishing this query on the server')
    expect(html).not.toContain('data-testid="oql-results"')
    expect(html).toContain('data-state="stopped"')
  })
})

describe('ResultPanel — export says what it did (J5-F14)', () => {
  it("shows the finished export's row count", () => {
    const html = renderToStaticMarkup(
      <ResultPanel result={RESULT} onExportAll={noop}
        exportNotice={{ tone: 'ok', text: "Downloaded 100 rows as CSV. Your query's LIMIT 100 applied — remove it to export every row." }} />,
    )
    expect(html).toContain('data-testid="oql-export-notice"')
    expect(html).toContain('Downloaded 100 rows as CSV.')
    expect(html).toContain('LIMIT 100 applied')
  })

  it('an export failure is a notice, not a failure that hides the result it was exporting', () => {
    const html = renderToStaticMarkup(
      <ResultPanel result={RESULT} onExportAll={noop} exportNotice={{ tone: 'error', text: 'Export failed: timeout' }} />,
    )
    expect(html).toContain('Export failed: timeout')
    expect(html).toContain('data-testid="oql-results"')
    expect(html).toContain('data-state="ok"')
  })
})

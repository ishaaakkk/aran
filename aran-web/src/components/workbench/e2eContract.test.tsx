import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { CatalogBrowser, ResultPanel } from './WorkbenchPanels'
import { PlanTree } from './PlanTree'
import { ResultTable } from '../ResultTable'
import type { QueryRunResult } from '../../services/types'

/**
 * The test ids the Playwright suite drives the workbench through.
 *
 * <p>The browser suite needs Docker and cannot run here, so a UI refactor can rename or bury a
 * selector and every unit test stays green — `playwright test --list` only *collects* specs, it
 * never resolves a locator. That is exactly what happened when the OQL page was de-cluttered:
 * the check was a grep run from the wrong directory, where "no matches" and "no such file" look
 * identical.
 *
 * <p>These render the real components and assert the ids are still emitted. Not a substitute for
 * running the suite — it cannot catch a control that moved behind a click, only one that stopped
 * being rendered — but it makes the contract explicit and fails in CI rather than an hour into a
 * Docker session.
 *
 * <p>Sourced from `e2e/pages/WorkbenchPage.ts`. Adding a workbench selector there means adding it
 * here.
 */

const CLASSES = [
  { name: 'Int_acm', iri: 'http://ex.org/Int_acm', fields: [{ name: 'title', type: 'string' }], sources: [] },
  { name: 'Int_researchers', iri: 'http://ex.org/Int_researchers', fields: [{ name: 'full_name', type: 'string' }], sources: [] },
] as never

const EDGES = [
  { name: 'hasAuthor', from: 'Int_acm', to: 'Int_researchers', kind: 'join' },
] as never

const RESULT: QueryRunResult = {
  headers: ['title'], headerNames: ['title'],
  rows: [['Federated Query Processing']] as QueryRunResult['rows'],
  rowCount: 1, truncated: false, elapsedMs: 12,
}

describe('workbench e2e selector contract', () => {
  it('the catalog still exposes one testid per class, keyed on the class name', () => {
    // `WorkbenchPage.catalogClassNames()` reads the NAME back out of the attribute rather than
    // the button's text, because the button also renders badges and counts with no separators.
    // So the `catalog-class-${name}` shape is load-bearing, not just the prefix.
    const html = renderToStaticMarkup(
      <CatalogBrowser
        catalogData={{ classes: CLASSES, edges: EDGES, classByName: {} } as never}
        onSelect={() => {}} onAddClass={() => {}}
      />,
    )
    expect(html).toContain('data-testid="catalog-class-Int_acm"')
    expect(html).toContain('data-testid="catalog-class-Int_researchers"')
  })

  it('the catalog still exposes relationships with the from/to attributes the suite reads', () => {
    // B4's whole claim is observable only here — `catalogRelationships()` reads data-from and
    // data-to off the element, since the rendered row interleaves them with arrows.
    const html = renderToStaticMarkup(
      <CatalogBrowser
        catalogData={{ classes: CLASSES, edges: EDGES, classByName: {} } as never}
        onSelect={() => {}} onAddClass={() => {}}
      />,
    )
    expect(html).toContain('data-testid="catalog-rel-hasAuthor"')
    expect(html).toMatch(/data-from="Int_acm"/)
    expect(html).toMatch(/data-to="Int_researchers"/)
  })

  it('a successful run still renders the results table the suite reads rows from', () => {
    // `rows()` and `column()` both scope `tbody tr` / `thead th` inside this id.
    const html = renderToStaticMarkup(<ResultTable result={RESULT} />)
    expect(html).toContain('data-testid="oql-results"')
    expect(html).toContain('<tbody')
    expect(html).toContain('<thead')
  })

  it('a failed run still renders the error banner run() waits on', () => {
    // `run()` settles on results OR error and never neither; losing this id would hang every
    // failing-query spec to its full timeout instead of failing.
    const html = renderToStaticMarkup(
      <ResultPanel result={null} error="no active global schema" />,
    )
    expect(html).toContain('data-testid="oql-error"')
  })

  it('the result panel still renders its table inside the result tab', () => {
    const html = renderToStaticMarkup(<ResultPanel result={RESULT} />)
    expect(html).toContain('data-testid="oql-results"')
  })

  it('the export control is present, and export-all is behind it', () => {
    // Documents a real consequence of folding four verbs into one menu: `oql-export-all` is no
    // longer rendered until the export menu is opened. No spec drives it today — if one ever
    // does, it has to click `oql-export` first, and this test is where that is written down.
    const html = renderToStaticMarkup(
      <ResultPanel result={RESULT} onExportAll={() => {}} />,
    )
    expect(html).toContain('data-testid="oql-export"')
    expect(html).not.toContain('data-testid="oql-export-all"')
  })

  it('withholding the OQL-only callbacks drops their controls — how SQL mode hides them', () => {
    // `/api/query/export` takes `oql`, and a data product is materialised by `materialize-oql`.
    // Neither can back a hand-written SQL statement, so the workbench passes `undefined` for both
    // in SQL mode. That relies on ResultPanel treating the callbacks as the feature flag rather
    // than rendering a button that fails on click — which is what this pins.
    const html = renderToStaticMarkup(<ResultPanel result={RESULT} />)
    expect(html).toContain('data-testid="oql-export"')
    expect(html).not.toContain('data-testid="oql-save-product"')
    expect(html).not.toContain('data-testid="oql-export-all"')
  })

  it('the profiler toggle is present — the only way a spec can reach the plan tree', () => {
    // `WorkbenchPage.planNodes()` clicks this before reading anything: the profiler pane
    // (and with it the whole plan tree) is behind `showProfiler`, which starts false. The
    // V32/V34 live-validation specs read the optimized plan's leaf through it.
    const html = renderToStaticMarkup(<ResultPanel result={RESULT} />)
    expect(html).toContain('data-testid="oql-profiler"')
    // ...and the tree really is absent until it is clicked, which is why planNodes() clicks.
    expect(html).not.toContain('data-testid="oql-plan-tree"')
  })

  it('the plan tree exposes one node testid per plan operator, with kind and detail on attributes', () => {
    // `planNodes()` reads `data-kind`/`data-detail` off the element rather than its text: the
    // rendered head interleaves the kind chip, the detail, an access chip and a toggle glyph
    // with no separators that survive innerText — same reason as `catalog-class-*`.
    const html = renderToStaticMarkup(
      <PlanTree
        logicalPlan=""
        optimizedPlan="EnumerableProject(...)"
        planShape={JSON.stringify({
          kind: 'PROJECT',
          detail: 'title',
          inputs: [{ kind: 'SCAN', detail: 'u_acm_Int_acm', inputs: [] }],
        })}
      />,
    )
    expect(html).toContain('data-testid="oql-plan-tree"')
    expect(html.match(/data-testid="plan-node"/g)).toHaveLength(2)
    expect(html).toContain('data-kind="PROJECT"')
    expect(html).toContain('data-kind="SCAN"')
    expect(html).toContain('data-detail="u_acm_Int_acm"')
  })

  it("a scan's access mode is its own attribute, so data-detail is always just the table", () => {
    // `WorkbenchPage.planScans()` compares a leaf against `u_`/`int_`/`src_` — it must never
    // have to strip a ", access=…" suffix, and the day one is added to a scan that had none
    // this is what keeps that comparison intact.
    const html = renderToStaticMarkup(
      <PlanTree
        logicalPlan=""
        optimizedPlan="EnumerableTableScan(...)"
        planShape={JSON.stringify({ kind: 'SCAN', detail: 'u_acm_Int_acm, access=FEDERATED', inputs: [] })}
      />,
    )
    expect(html).toContain('data-detail="u_acm_Int_acm"')
    expect(html).toContain('data-access="FEDERATED"')
  })
})

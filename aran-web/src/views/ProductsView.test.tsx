import { readFileSync } from 'node:fs'
import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { AccessBadge, DeleteProductDialog, ProductDetail, ProductRow, ProductsEmptyState, ProductsView } from './ProductsView'
import type { DataProduct, Job, QueryRunResult } from '../services/types'
import { connectionDetails } from '../lib/productDoors'

/**
 * The access-mode badge and the materialize/refresh affordance (Phase 5): a virtual product
 * must be recognizable at a glance, and must not offer an action the backend rejects outright
 * (it has no materialize step — see DataProductService.materialize). `accessMode` is optional
 * on the wire only for a payload predating Phase 5, so "absent" needs to behave exactly like
 * an explicit MATERIALIZED, never like VIRTUAL and never a crash.
 *
 * No jsdom in this project; `renderToStaticMarkup` is enough for output assertions (see
 * DatasetWrapperTab.test.tsx for the same idiom).
 *
 * These tests are also where the testids `aran-web/e2e/pages/ProductsPage.ts` drives are pinned, so
 * a rename here goes red in vitest instead of only in a Playwright run that needs Docker (the same
 * job `components/workbench/e2eContract.test.tsx` does for the workbench). `product-preview` is the
 * one exception and deliberately not pinned: it lives in `ProductsView`'s detail panel, and that
 * component fetches over the network on mount, which `renderToStaticMarkup` cannot wait on — the
 * reason `SaveAsProductDialog` was extracted in the first place. Only the v37 spec covers it.
 */

function product(overrides: Partial<DataProduct> = {}): DataProduct {
  return {
    id: 'prod-1',
    projectId: 'proj-1',
    name: 'papers',
    description: null,
    oql: 'FIND Paper.title',
    schemaVersionId: 'sv-1',
    accessMode: 'MATERIALIZED',
    status: 'READY',
    snapshotId: 'snap-1',
    rowCount: 42,
    materializedAt: '2026-08-01T10:00:00Z',
    lastError: null,
    createdBy: null,
    createdAt: '2026-07-01T09:00:00Z',
    ...overrides,
  }
}

/** Constructed by hand, not via `product()`, so `accessMode` is genuinely absent from the
 *  object rather than set-then-deleted — the exact shape of a payload or fixture written
 *  before this field existed (types.ts declares it optional for exactly this reason). */
const legacyProduct: DataProduct = {
  id: 'prod-legacy',
  projectId: 'proj-1',
  name: 'legacy_view',
  description: null,
  oql: 'FIND Paper.title',
  schemaVersionId: 'sv-1',
  status: 'READY',
  snapshotId: 'snap-1',
  rowCount: 7,
  materializedAt: '2026-01-01T00:00:00Z',
  lastError: null,
  createdBy: null,
  createdAt: '2026-01-01T00:00:00Z',
}

const noop = () => {}

function renderRow(p: DataProduct) {
  return renderToStaticMarkup(
    <table>
      <tbody>
        <ProductRow
          product={p}
          versionLabel="v1"
          onOpenDetail={noop}
          onUseWithAgents={noop}
          onMaterialize={noop}
          onJobDone={noop}
        />
      </tbody>
    </table>,
  )
}

describe('AccessBadge', () => {
  it('renders MATERIALIZED and VIRTUAL distinctly', () => {
    const materialized = renderToStaticMarkup(<AccessBadge mode="MATERIALIZED" />)
    const virtual = renderToStaticMarkup(<AccessBadge mode="VIRTUAL" />)

    expect(materialized).toContain('Snapshot')
    expect(virtual).toContain('Virtual')
    expect(materialized).not.toBe(virtual)
  })
})

describe('ProductRow', () => {
  it('a MATERIALIZED product is unchanged: badge and materialize action both present', () => {
    const html = renderRow(product({ accessMode: 'MATERIALIZED' }))

    expect(html).toContain('Snapshot')
    expect(html).toContain('materialize-papers')
    expect(html).toContain('use-with-agents-papers')
    expect(html).toContain('product-row-papers')
    expect(html).not.toContain('recomputed on read')
  })

  it('a VIRTUAL product renders the VIRTUAL badge and hides the materialize/refresh affordance', () => {
    const html = renderRow(product({ accessMode: 'VIRTUAL', snapshotId: null, materializedAt: null }))

    expect(html).toContain('Virtual')
    expect(html).not.toContain('materialize-papers')
    // Honest freshness text, not the MATERIALIZED-flavored "never" (F-style bug: a product
    // that can never materialize must not be described as if it merely hasn't yet) — and not
    // "always fresh" either (J6-F2): it is as fresh as the sources the active schema points at.
    expect(html).toContain('recomputed on read')
    expect(html).not.toContain('always fresh')
    expect(html).not.toContain('>never<')
    // Unrelated to materialization — must stay offered.
    expect(html).toContain('use-with-agents-papers')
    // Name-keyed row id: `ProductsPage.row()` scopes the badge and the refresh-button ABSENCE to
    // one product, which is the only way "VIRTUAL has no refresh button and MATERIALIZED does" can
    // be asserted in a single render.
    expect(html).toContain('product-row-papers')
  })

  it('opens from the keyboard: the name is a real button, not only a clickable row (J6-F8)', () => {
    const html = renderRow(product())

    expect(html).toMatch(/<button[^>]*data-testid="product-open-papers"[^>]*>papers<\/button>/)
  })

  it('a legacy product with no accessMode in its fixture is treated as MATERIALIZED: no crash, not virtual', () => {
    const html = renderRow(legacyProduct)

    expect(html).toContain('Snapshot')
    expect(html).not.toContain('Virtual')
    expect(html).toContain('materialize-legacy_view')
    expect(html).toContain('product-row-legacy_view')
    expect(html).not.toContain('recomputed on read')
  })
})

/**
 * UI audit J6-F7 and register F5 for products: the row's job comes from the jobs store (found by
 * product id, so it survives navigation and covers a cube's star-table jobs), MATERIALIZING is
 * only true while a job is live, and a failure stays on the row until dismissed.
 */
describe('ProductRow and its materialize job', () => {
  function materializeJob(overrides: Partial<Job> = {}): Job {
    return {
      id: 'job-m', projectId: 'proj-1', jobType: 'product:materialize', status: 'RUNNING',
      parameters: { productId: 'prod-1' }, progressStage: null, progressPercent: null, result: null,
      errorCode: null, errorMessage: null, createdBy: null, createdAt: '2026-09-25T11:00:00Z',
      startedAt: '2026-09-25T11:00:01Z', finishedAt: null, subject: null, ...overrides,
    }
  }

  function renderWithJob(p: DataProduct, job: Job | null) {
    return renderToStaticMarkup(
      <table><tbody>
        <ProductRow product={p} versionLabel="v1" job={job} onOpenDetail={noop}
          onUseWithAgents={noop} onMaterialize={noop} onJobDone={noop} onJobDismiss={noop} />
      </tbody></table>,
    )
  }

  const refreshDisabled = (html: string) =>
    /<button[^>]*disabled=""[^>]*data-testid="materialize-papers"/.test(html)

  it('a live job shows its card and disables refresh', () => {
    const html = renderWithJob(product({ status: 'MATERIALIZING' }), materializeJob())
    expect(html).toContain('job-card')
    expect(refreshDisabled(html)).toBe(true)
  })

  it('MATERIALIZING with no live job does not keep refresh disabled forever', () => {
    const html = renderWithJob(product({ status: 'MATERIALIZING' }), null)
    expect(refreshDisabled(html)).toBe(false)
  })

  it('a stranded MATERIALIZING row whose job failed reads FAILED and keeps the failed card', () => {
    const html = renderWithJob(product({ status: 'MATERIALIZING' }),
      materializeJob({ status: 'FAILED', errorMessage: 'lake full', finishedAt: '2026-09-25T11:01:00Z' }))
    expect(html).toMatch(/>FAILED</)
    expect(html).not.toMatch(/>MATERIALIZING</)
    expect(html).toContain('job-card')
    expect(refreshDisabled(html)).toBe(false)
  })

  it('a FAILED product with no card still says why, under the row, in the error token', () => {
    const html = renderWithJob(product({ status: 'FAILED', lastError: 'Materialization was interrupted by a restart' }), null)
    expect(html).toContain('product-error-papers')
    expect(html).toContain('Materialization was interrupted by a restart')
    // text-rose-300 on the default light skin's white rows read at ~1.9:1 (WCAG AA wants 4.5:1).
    const errorRow = html.slice(html.indexOf('product-error-papers') - 120)
    expect(errorRow).toContain('text-error')
    expect(errorRow).not.toMatch(/\btext-(rose|red|pink)-(50|100|200|300|400)\b/)
  })

  it('refresh stays disabled while the page says the product is busy, and says why', () => {
    // The page passes busy for a MATERIALIZING row whose run it cannot yet see end
    // (materializeBusy) — a cube's star table, or a run started before a reload.
    const html = renderToStaticMarkup(
      <table><tbody>
        <ProductRow product={product({ status: 'MATERIALIZING' })} versionLabel="v1" job={null} busy
          onOpenDetail={noop} onUseWithAgents={noop} onMaterialize={noop} />
      </tbody></table>,
    )
    expect(refreshDisabled(html)).toBe(true)
    expect(html).toContain('waiting for its job to say it has finished')
  })
})

/**
 * The cube surface is a DEPLOYMENT capability, not a build-time one: the same bundle is served
 * by every deployment, so the server says whether the feature exists (`GET /api/me` →
 * `features.cubes`, from `aran.cubes.enabled`) and this page renders off that answer. With it
 * off, `CubeController` is not even a bean — every cube route is a 404 — so a button and a panel
 * that can only produce 404s must not be on the screen.
 *
 * Two halves, because they fail differently, and `lib/features.test.ts` owns the other one:
 * `cubesEnabledFrom` is how the payload is READ (an absent `features` has to read as off), and
 * this file is what is RENDERED off it. The view is driven by its prop here rather than by the
 * auth store, because a zustand hook returns the store's INITIAL state under
 * `renderToStaticMarkup` — a store write would silently never arrive, and the "on" case would
 * pass for the wrong reason.
 *
 * The request half needs no assertion of its own: it is the same fact as the panel.
 * `api.listCubes` is called only from `CubesSection`'s effect, and `CubesSection` is exactly
 * what is absent below — there is no second guard that could drift from this one.
 */
/**
 * J6-F22: an empty product list offered no way forward, and a list that failed to load looked the
 * same as an empty one (the error banner sat above "No data products yet"). A load failure is not
 * emptiness; an empty list names its next step and links to it.
 */
describe('ProductsEmptyState (J6-F22)', () => {
  function renderEmpty(error: string | null) {
    return renderToStaticMarkup(
      <MemoryRouter>
        <ProductsEmptyState error={error} onRetry={noop} />
      </MemoryRouter>,
    )
  }

  it('a failed load says so, with Retry, and never claims the list is empty', () => {
    const html = renderEmpty('request failed: 500')
    expect(html).toContain('role="alert"')
    expect(html).toContain('Couldn’t load data products')
    expect(html).toContain('request failed: 500')
    expect(html).toContain('products-retry')
    expect(html).not.toContain('No data products yet')
  })

  it('an empty list links to the workbench, where products are made', () => {
    const html = renderEmpty(null)
    expect(html).toContain('No data products yet')
    expect(html).toContain('href="/oql"')
    expect(html).toContain('Open the workbench')
  })
})

describe('the cube surface, behind aran.cubes.enabled', () => {
  it('off: neither the "shape as cube" button nor the Cubes panel is rendered', () => {
    // The view reads `?product=` (the post-save deep link), so it needs a router context.
    const html = renderToStaticMarkup(<MemoryRouter><ProductsView cubesEnabled={false} /></MemoryRouter>)

    expect(html).not.toContain('shape-as-cube-open')
    expect(html).not.toContain('shape as cube')
    expect(html).not.toContain('list its cubes')
    expect(html).not.toContain('cubes-list')
    // The rest of the page is untouched — this hides one feature, it does not gate the view.
    expect(html).toContain('Data products')
    expect(html).toContain('list its data products')
  })

  it('on: both are back', () => {
    const html = renderToStaticMarkup(<MemoryRouter><ProductsView cubesEnabled /></MemoryRouter>)

    expect(html).toContain('shape-as-cube-open')
    expect(html).toContain('shape as cube')
    expect(html).toContain('list its cubes')
  })
})

const PREVIEW: QueryRunResult = {
  headers: ['http://x/title'],
  headerNames: ['title'],
  rows: [['RDF Graphs']],
  rowCount: 1,
  truncated: false,
  hasMore: false,
  elapsedMs: 3,
}

function renderDetail(p: DataProduct, overrides: Partial<Parameters<typeof ProductDetail>[0]> = {}) {
  return renderToStaticMarkup(
    <ProductDetail
      product={p}
      pinLabel="v2"
      versionNumberById={{ 'sv-1': 2, 'sv-3': 3 }}
      connection={connectionDetails('https://aran.example')}
      connectionIsDevGuess={false}
      onManageTokens={noop}
      preview={null}
      previewLoading={false}
      previewError={null}
      onRunPreview={noop}
      snapshotCopied={false}
      onCopySnapshot={noop}
      downloading={false}
      downloadError={null}
      onDownloadAll={noop}
      onUseWithAgents={noop}
      onDelete={noop}
      onClose={noop}
      {...overrides}
    />,
  )
}

describe('ProductDetail', () => {
  it('is a focusable, named region the view can scroll to and focus (J6-F8)', () => {
    const html = renderDetail(product())
    expect(html).toContain('data-testid="product-detail"')
    expect(html).toContain('tabindex="-1"')
    expect(html).toContain('aria-label="Data product papers"')
  })

  // ProductsView fetches on mount, which renderToStaticMarkup cannot drive, so the wiring is read
  // off its source: the only preview on open goes through previewsOnOpen (whose cases
  // lib/productDoors.test.ts pins), and the only other load is the explicit live-preview button.
  it('opening a product previews only through previewsOnOpen; a live run needs the button (J6-F19)', () => {
    const source = readFileSync(new URL('./ProductsView.tsx', import.meta.url), 'utf8')
    expect(source).toMatch(/function openDetail\(product: DataProduct\) \{[^}]*if \(previewsOnOpen\(product\)\) loadPreview\(product\);/)
    const calls = source.match(/\bloadPreview\(/g) ?? []
    expect(calls, 'the definition, openDetail\'s gated call and onRunPreview — nothing else loads a preview').toHaveLength(3)
    expect(source).toContain('onRunPreview={() => loadPreview(detail)}')
  })

  it('does not run a VIRTUAL product live on open — it offers an explicit live preview (J6-F19)', () => {
    const html = renderDetail(product({ accessMode: 'VIRTUAL', snapshotId: null, materializedAt: null }))
    expect(html).toContain('data-testid="product-preview-run"')
    expect(html).toMatch(/queries its sources/)
    expect(html).not.toContain('data-testid="product-preview"')
  })

  it('shows a loaded preview, says how much of it is shown, and offers this page as CSV', () => {
    const html = renderDetail(product(), { preview: PREVIEW })
    expect(html).toContain('data-testid="product-preview"')
    expect(html).toContain('data-testid="product-preview-scope"')
    expect(html).toMatch(/1 row shown/)
    expect(html).not.toMatch(/<button[^>]*data-testid="product-download-page"[^>]*disabled=""/)
  })

  it('says when the preview is only the first page', () => {
    const html = renderDetail(product(), { preview: { ...PREVIEW, hasMore: true } })
    expect(html).toMatch(/the first 100/)
  })

  it('offers every row of a built MATERIALIZED product as its parquet snapshot, and none of an unbuilt one (J6-F4)', () => {
    expect(renderDetail(product())).toContain('all rows · parquet')
    const unbuilt = renderDetail(product({ status: 'DRAFT', snapshotId: null, materializedAt: null }))
    expect(unbuilt).toMatch(/<button[^>]*data-testid="product-download-all"[^>]*disabled=""/)
  })

  it('names the table JDBC and Iceberg serve it as (J6-F4)', () => {
    const html = renderDetail(product({ tableName: 'papers_1a2b3c4d' }))
    expect(html).toContain('data-testid="product-connect"')
    expect(html).toContain('SELECT * FROM products.papers_1a2b3c4d')
  })

  it('gives everything a client needs to connect, each copyable, with the guide and the token (J6-F4)', () => {
    const html = renderDetail(product({ tableName: 'papers_1a2b3c4d' }))
    // JDBC: the URL on this deployment, the driver and its version, and the guide.
    expect(html).toContain('jdbc:avatica:remote:url=https://aran.example/api/jdbc;serialization=json')
    expect(html).toContain('org.apache.calcite.avatica.remote.Driver')
    expect(html).toContain('avatica-core 1.27.0')
    expect(html).toMatch(/href="[^"]*\/docs\/programmatic-access\/jdbc"[^>]*target="_blank"/)
    // Iceberg: the catalog URI the table name resolves against, its namespace, and the guide.
    expect(html).toContain('>https://aran.example/api/iceberg<')
    expect(html).toContain('data-testid="product-connect-iceberg-table"')
    expect(html).toMatch(/href="[^"]*\/docs\/programmatic-access\/iceberg"/)
    // The token every door takes, and one gesture to make one.
    expect(html).toContain('data-testid="product-connect-tokens"')
    for (const label of ['the SQL statement', 'the JDBC URL', 'the Iceberg table', 'the Iceberg catalog URI']) {
      expect(html).toContain(`aria-label="Copy ${label}"`)
    }
    expect(html).not.toContain('product-connect-dev-note')
  })

  it('says why Iceberg does not serve a VIRTUAL product, and offers no catalog URI for it', () => {
    const html = renderDetail(product({ accessMode: 'VIRTUAL', snapshotId: null, materializedAt: null }))
    expect(html).toContain('data-testid="product-connect-iceberg-unserved"')
    expect(html).not.toContain('product-connect-iceberg-uri')
    // JDBC still serves it.
    expect(html).toContain('data-testid="product-connect-jdbc-url"')
  })

  it('does not name an Iceberg table or offer the parquet the server does not serve (built before V29)', () => {
    const html = renderDetail(product({ servedOverIceberg: false }))
    expect(html).toContain('data-testid="product-connect-iceberg-unserved"')
    expect(html).not.toContain('product-connect-iceberg-table')
    expect(html).toMatch(/<button[^>]*data-testid="product-download-all"[^>]*disabled=""[^>]*title="Built before builds recorded their columns/)
  })

  it('warns when the URLs were guessed for a page on the Vite dev server', () => {
    expect(renderDetail(product(), { connectionIsDevGuess: true })).toContain('data-testid="product-connect-dev-note"')
  })

  it('shows what its rows mean — the version its last build ran under (UI-OD12)', () => {
    const html = renderDetail(product({ schemaVersionId: 'sv-1', builtAgainstVersionId: 'sv-3' }))
    expect(html).toContain('data-testid="product-built-against"')
    expect(html).toContain('built against v3 · defined against v2')
  })

  it('disables delete for a cube member and says why, instead of refusing after a confirm (J6-F17)', () => {
    const html = renderDetail(product({ memberOfCube: 'sales' }))
    expect(html).toMatch(/<button[^>]*data-testid="product-delete"[^>]*disabled=""/)
    expect(html).toContain('data-testid="product-cube-member"')
    expect(html).toContain('sales')
  })

  it('renders a failed build as an alert on readable tokens, not light rose on pink (J6-F13 in passing)', () => {
    const html = renderDetail(product({ status: 'FAILED', lastError: 'boom' }))
    expect(html).toMatch(/role="alert"[^>]*>boom/)
    expect(html).not.toContain('text-rose-300')
  })
})

describe('DeleteProductDialog (J6-F17)', () => {
  it('names what stops resolving and asks for the name of a readable product', () => {
    const html = renderToStaticMarkup(
      <DeleteProductDialog product={product({ tableName: 'papers' })} deleting={false} error={null} onCancel={noop} onConfirm={noop} />,
    )
    expect(html).toContain('role="dialog"')
    expect(html).toContain('data-testid="product-delete-impact"')
    expect(html).toContain('SELECT * FROM products.papers')
    expect(html).toContain('data-testid="product-delete-confirm-input"')
    expect(html).toMatch(/<button[^>]*data-testid="product-delete-confirm"[^>]*disabled=""/)
  })

  it('does not ask a DRAFT nobody can read to be typed back', () => {
    const html = renderToStaticMarkup(
      <DeleteProductDialog
        product={product({ status: 'DRAFT', snapshotId: null, materializedAt: null })}
        deleting={false} error={null} onCancel={noop} onConfirm={noop}
      />,
    )
    expect(html).not.toContain('product-delete-confirm-input')
    expect(html).not.toMatch(/<button[^>]*data-testid="product-delete-confirm"[^>]*disabled=""/)
  })
})

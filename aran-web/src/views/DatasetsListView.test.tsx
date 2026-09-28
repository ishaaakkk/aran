import { describe, it, expect, vi, beforeAll } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import type { ReactNode } from 'react'
import type { AggregatedDataset } from '@/components/datasets/types'

/**
 * One discover-constraints entry point. The legacy row-level LIMA modal wrote
 * `Dataset.denialConstraints`, which no part of the engine ever read — enforcement reads
 * `model_constraint` — so a user who ran it got a success toast and no enforcement. It was
 * removed on 2026-08-26; this pins that the row action cannot come back unnoticed.
 *
 * The view's row actions live inside a `renderCell.actions` callback that only runs for rows
 * DataTable actually renders, and this view's rows arrive from an effect `renderToStaticMarkup`
 * never runs. Stubbing DataTable to invoke that callback with a fixture row is what keeps the
 * assertion from passing vacuously against an empty table.
 */

const ROW: AggregatedDataset = {
  id: 'ds-1',
  repositoryId: 'repo-1',
  name: 'papers.csv',
  description: 'ACM papers',
  snapshotId: 'snap-1',
  bootstrapStatus: 'READY',
  accessMode: 'SNAPSHOT',
  idx: '01',
  projectName: 'ACM',
  projectColor: '#8b5cf6',
  repoName: 'local',
  repoType: 'LOCAL_FILE',
  projectId: 'proj-1',
}

/** The row the stubbed DataTable renders; a test may swap it before rendering. */
let rowUnderTest: AggregatedDataset = ROW

vi.mock('@/components/DataTable', () => ({
  DataTable: ({ renderCell }: {
    renderCell?: Record<string, (row: AggregatedDataset) => ReactNode>
  }) => (
    <div>
      {renderCell?.name?.(rowUnderTest)}
      {renderCell?.actions?.(rowUnderTest)}
      {renderCell?.accessMode?.(rowUnderTest)}
    </div>
  ),
}))

beforeAll(() => {
  // No jsdom in this project; the view reads the theme class off <html> during render.
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { documentElement: { classList: { contains: () => false } } },
  })
})

describe('DatasetsListView row actions', () => {
  it('offers no legacy denial-constraint discovery action', async () => {
    const { DatasetsListView } = await import('./DatasetsListView')
    // The view is routed now (/datasets/:datasetId drives the detail), so it reads
    // router hooks and must render inside a router.
    const { MemoryRouter } = await import('react-router-dom')
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/datasets']}>
        <DatasetsListView />
      </MemoryRouter>,
    )

    // The surviving row actions still render, so an empty/blank render cannot fake this pass.
    expect(html).toContain('datasets-graph-papers.csv')
    // The profile action opens the tab, which reads the stored profile. It used to pick its label
    // off `Dataset.columns`, a field the backend never sent, so it always read "Run profiling" —
    // this line pinned that dead branch (J2-F13, S14-2).
    expect(html).toContain('Open column profile')
    expect(html).not.toContain('Run profiling')

    expect(html).not.toContain('Discover Denial Constraints (LIMA)')
  })

  it('says per row how the dataset is read (J2-F3)', async () => {
    const { DatasetsListView } = await import('./DatasetsListView')
    const { MemoryRouter } = await import('react-router-dom')
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/datasets']}>
        <DatasetsListView />
      </MemoryRouter>,
    )
    expect(html).toContain('data-testid="datasets-access-papers.csv"')
    expect(html).toContain('Snapshot')
  })

  it('puts "Review changes" beside a drifted row, not behind a hover title (J2-F16)', async () => {
    const { DatasetsListView } = await import('./DatasetsListView')
    const { MemoryRouter } = await import('react-router-dom')
    rowUnderTest = { ...ROW, bootstrapStatus: 'DRIFTED' }
    try {
      const html = renderToStaticMarkup(
        <MemoryRouter initialEntries={['/datasets']}>
          <DatasetsListView />
        </MemoryRouter>,
      )
      expect(html).toContain('data-testid="datasets-review-drift-papers.csv"')
      expect(html).toContain('Review changes')
      // The theme's warning token (#B45309 on light), not orange-400 (~2.2:1 on white).
      expect(html).toMatch(/data-testid="datasets-review-drift-papers\.csv"[^>]*class="[^"]*\btext-warning\b/)
      expect(html).not.toMatch(/data-testid="datasets-review-drift-papers\.csv"[^>]*text-orange-400/)
      // The badge keeps its own text: d1-schema-drift reads it by testid.
      expect(html).toMatch(/data-testid="datasets-status-papers\.csv"[^>]*>\s*SCHEMA DRIFT\s*</)
    } finally {
      rowUnderTest = ROW
    }
  })

  it('offers no review on a row that has not drifted', async () => {
    const { DatasetsListView } = await import('./DatasetsListView')
    const { MemoryRouter } = await import('react-router-dom')
    const html = renderToStaticMarkup(
      <MemoryRouter initialEntries={['/datasets']}>
        <DatasetsListView />
      </MemoryRouter>,
    )
    expect(html).not.toContain('datasets-review-drift-')
  })
})

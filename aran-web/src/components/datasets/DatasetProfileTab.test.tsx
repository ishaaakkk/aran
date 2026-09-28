import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { DatasetProfileTab } from './DatasetProfileTab'
import type { ProfileLoad } from './profileModel'
import type { ProfileGroup, StoredColumnProfile } from '@/services/types'

function column(name: string, overrides: Partial<StoredColumnProfile> = {}): StoredColumnProfile {
  return {
    columnName: name, columnIndex: 0, sourceType: 'BIGINT',
    cardinality: 40, entropy: 5.1, uniqueness: 1, incompleteness: 0,
    frequencyAvg: 1, frequencyMin: 1, frequencyMax: 1, frequencySd: 0, frequencyIqr: 0,
    valPctMin: 0.025, valPctMax: 0.025, valPctStd: 0, constancy: 0.025,
    lenMaxWord: 6, lenMinWord: 6, lenAvgWord: 6,
    numberWords: 40, wordsCntMax: 1, wordsCntMin: 1, wordsCntAvg: 1, wordsCntSd: 0,
    firstWord: 'acm001', lastWord: 'acm040', emptyColumn: false, binaryColumn: false,
    commonValues: ['acm001'], commonValuesSoundex: [],
    ...overrides,
  }
}

const SNAPSHOT: ProfileGroup = { subject: 'SNAPSHOT', classIri: null, keyEvidence: true, columns: [column('paper_id'), column('year')] }
const HARVESTED: ProfileGroup = { subject: 'HARVESTED', classIri: null, keyEvidence: false, columns: [column('paper_id', { entropy: null })] }

function render(load: ProfileLoad | null, extra: { running?: boolean; runErrors?: Record<string, string> | null; hasSnapshot?: boolean; activity?: React.ReactNode } = {}) {
  return renderToStaticMarkup(
    <DatasetProfileTab
      datasetId="ds-1"
      load={load}
      running={extra.running ?? false}
      runErrors={extra.runErrors ?? null}
      hasSnapshot={extra.hasSnapshot ?? true}
      onRunProfiling={() => {}}
      onRetry={() => {}}
      activity={extra.activity}
    />,
  )
}

describe('DatasetProfileTab — stored evidence (J3-F1)', () => {
  it('shows the profile bootstrap already stored, with no invitation to re-run it from scratch', () => {
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [SNAPSHOT] })
    expect(html).toContain('data-testid="dataset-profile-metrics"')
    expect(html).toContain('Cardinality count')
    expect(html).toContain('paper_id')
    expect(html).toContain('Refresh profile')
    expect(html).not.toContain('does not have a cached column profile')
    expect(html).not.toContain('data-testid="dataset-run-profiling"')
  })

  it('names each generation and says which ones are not key evidence', () => {
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [SNAPSHOT, HARVESTED] })
    expect(html).toContain('Snapshot')
    expect(html).toContain('Catalog statistics')
    expect(html).toContain('not key evidence')
  })

  it('draws the key-evidence chip in the success token, legible on the light theme too', () => {
    // emerald-400 on white is ~2.5:1 at 9px; the token's light value (#1F8A5B) passes AA.
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [SNAPSHOT] })
    expect(html).toMatch(/class="[^"]*\btext-success\b[^"]*"[^>]*>key evidence</)
    expect(html).not.toMatch(/text-emerald-400[^"]*"[^>]*>key evidence</)
  })

  it('offers a first run only when nothing is stored', () => {
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [] })
    expect(html).toContain('data-testid="dataset-run-profiling"')
    expect(html).toContain('No stored profile')
  })

  it('does not blame the profiling service for a profile bootstrap may still be writing', () => {
    // Bootstrap sets READY before its auto-profiling runs, so a just-ready dataset has no
    // profile yet for an ordinary reason; the copy used to call that an outage.
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [] })
    expect(html).toContain('profiling may still be running')
    expect(html).not.toContain('usually means the profiling service was unreachable')
    expect(html).toContain('data-testid="dataset-profile-recheck"')
  })

  it('picks a generation with toggle buttons, not radios that ignore the arrow keys', () => {
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [SNAPSHOT, HARVESTED] })
    expect(html).not.toContain('role="radio')
    expect(html).toContain('aria-label="Which stored profile to show"')
    expect(html).toMatch(/aria-pressed="true" data-testid="dataset-profile-group-SNAPSHOT"/)
    expect(html).toMatch(/aria-pressed="false" data-testid="dataset-profile-group-HARVESTED"/)
  })

  it('renders a failed read as a failure with a retry, never as "no profile"', () => {
    const html = render({ datasetId: 'ds-1', status: 'error', message: 'Gateway timeout' })
    expect(html).toContain('Gateway timeout')
    expect(html).toContain('Retry')
    expect(html).not.toContain('data-testid="dataset-run-profiling"')
    expect(html).not.toContain('No stored profile')
  })
})

describe('DatasetProfileTab — one dataset at a time (J3-F8)', () => {
  it("never renders another dataset's profile, whatever arrived last", () => {
    const html = render({ datasetId: 'ds-OTHER', status: 'ready', groups: [SNAPSHOT] })
    expect(html).not.toContain('paper_id')
    expect(html).not.toContain('Cardinality count')
  })
})

describe('DatasetProfileTab — partial failures (J3-F9)', () => {
  it('names the columns the last run could not profile', () => {
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [SNAPSHOT] },
      { runErrors: { title: 'Conversion Error: cannot cast' } })
    expect(html).toContain('title')
    expect(html).toContain('Conversion Error: cannot cast')
  })

  it('renders a missing metric as a dash instead of crashing the page', () => {
    const broken = column('venue', { frequencyAvg: null as unknown as number, lenAvgWord: undefined as unknown as number })
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [{ ...SNAPSHOT, columns: [broken] }] })
    expect(html).toContain('venue')
    expect(html).toContain('—')
  })
})

describe('DatasetProfileTab plain language (J3-F19 — carried from the A1 truth pass)', () => {
  it('says what profiling is doing, not which operator it invokes', () => {
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [] }, { running: true })
    expect(html).not.toContain('aran invoke')
    expect(html).not.toContain('CTE')
    expect(html).toContain('role="status"')
  })

  it('renders the profiling job card above the evidence when one is given (J3-F7)', () => {
    const html = render({ datasetId: 'ds-1', status: 'ready', groups: [SNAPSHOT] }, { activity: <span>job card</span> })
    expect(html).toContain('data-testid="dataset-profile-job"')
    expect(html).toContain('job card')
  })
})

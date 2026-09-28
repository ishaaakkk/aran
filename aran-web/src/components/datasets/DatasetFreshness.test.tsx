import { describe, it, expect } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { DatasetFreshness } from './DatasetFreshness'
import { MIN_REFRESH_INTERVAL_SECONDS } from '../../services/types'
import type { DatasetAccessMode, DatasetRefreshPolicy } from '../../services/types'

/**
 * What this panel says about how old the data is.
 *
 * Freshness is the one thing here a user cannot check any other way, so a wrong answer is not a
 * cosmetic bug — it is the panel confidently lying about data someone is about to make a decision
 * on. The two ways it can: rendering "never refreshed" as "0 seconds old", and offering an
 * interval the backend refuses.
 *
 * No jsdom in this project; `renderToStaticMarkup` is enough for output assertions.
 */

function policy(overrides: Partial<DatasetRefreshPolicy> = {}): DatasetRefreshPolicy {
  return {
    datasetId: 'ds-1',
    sourceTable: 'orders',
    policy: 'MANUAL',
    intervalSeconds: null,
    lastRefreshAt: null,
    nextRunAt: null,
    stalenessSeconds: null,
    lastError: null,
    ...overrides,
  }
}

const noop = async () => {}

function render(p: DatasetRefreshPolicy | null, accessMode: DatasetAccessMode = 'SNAPSHOT'): string {
  return renderToStaticMarkup(
    <DatasetFreshness policy={p} accessMode={accessMode} onRefreshNow={noop} onSetPolicy={noop} />,
  )
}

describe('DatasetFreshness', () => {
  it('says an uploaded dataset has no upstream, rather than showing a dead control', () => {
    // Absence of a policy is the signal for "the file IS the source". Rendering a disabled
    // schedule instead would leave a user hunting for why the refresh button does nothing.
    const html = render(null)

    expect(html).toContain('No upstream')
    expect(html).not.toContain('dataset-refresh-now')
    expect(html).not.toContain('dataset-refresh-policy-select')
  })

  it('says a live dataset is read live, never that its data "was uploaded" (J2-F3)', () => {
    // A table on a federated repository has no refresh policy either — there is nothing to copy —
    // and the no-upstream copy told its user they were looking at a static upload while every
    // query read production.
    const html = render(null, 'LIVE')

    expect(html).toContain('data-testid="dataset-freshness-live"')
    expect(html).toContain('Read live')
    expect(html).not.toContain('No upstream')
    expect(html).not.toContain('uploaded')
  })

  it('distinguishes never-refreshed from refreshed-just-now', () => {
    // null and 0 are different facts and the whole reason stalenessSeconds is nullable. A UI that
    // defaults the missing number to 0 reports stale data as current.
    expect(render(policy({ stalenessSeconds: null }))).toContain('never refreshed')

    const justNow = render(policy({ stalenessSeconds: 0, lastRefreshAt: '2026-08-15T10:00:00Z' }))
    expect(justNow).not.toContain('never refreshed')
    expect(justNow).toContain('less than a minute')
  })

  it('rounds staleness down to a coarse unit', () => {
    expect(render(policy({ stalenessSeconds: 90_000 }))).toContain('1 day')
    expect(render(policy({ stalenessSeconds: 7_200 }))).toContain('2 hours')
    expect(render(policy({ stalenessSeconds: 3_599 }))).toContain('59 minutes')
  })

  it('offers no interval the backend would refuse', () => {
    // The floor exists because an extract copies the whole table; an option below it would be a
    // control that always errors.
    const html = render(policy())
    const offered = [...html.matchAll(/<option value="(\d+)"/g)].map((m) => Number(m[1]))

    expect(offered.length).toBeGreaterThan(0)
    expect(Math.min(...offered)).toBeGreaterThanOrEqual(MIN_REFRESH_INTERVAL_SECONDS)
  })

  it('preselects the interval currently in force, so the control reflects the truth', () => {
    const html = render(policy({ policy: 'EVERY', intervalSeconds: 3_600, nextRunAt: '2026-08-15T11:00:00Z' }))

    expect(html).toContain('value="3600" selected')
    expect(html).toContain('next run')
  })

  it('says a refresh is running instead of offering a second one (J2-F11)', () => {
    // Two concurrent refreshes re-materialize and re-bootstrap the same dataset, and the drift
    // unwind reads its prior snapshot from a race.
    const html = renderToStaticMarkup(
      <DatasetFreshness policy={policy()} onRefreshNow={noop} onSetPolicy={noop} busyLabel="Refreshing…" />,
    )
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*data-testid="dataset-refresh-now"/)
    expect(html).toContain('Refreshing…')
    expect(html).not.toContain('Refresh now')

    const idle = render(policy())
    expect(idle).not.toMatch(/<button[^>]*disabled=""[^>]*data-testid="dataset-refresh-now"/)
    expect(idle).toContain('Refresh now')
  })

  it('surfaces a failing schedule, which is invisible everywhere else', () => {
    // A schedule quietly failing for a week is the worst state this feature has. Job rows get
    // pruned, so the row's lastError is the only place it survives.
    const html = render(policy({
      policy: 'EVERY',
      intervalSeconds: 3_600,
      nextRunAt: '2026-08-15T11:00:00Z',
      lastRefreshAt: '2026-08-08T10:00:00Z',
      stalenessSeconds: 604_800,
      lastError: 'connection refused',
    }))

    expect(html).toContain('connection refused')
    expect(html).toContain('7 days')
  })
})

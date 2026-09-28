import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import type { Job } from '../services/types'
import { retryFor, type RetryApi } from '../lib/jobs'

/**
 * What a job card says (UI audit A6): the stage the job reported under its operational title, a
 * duration measured from the server's `startedAt`, and — for a failure — its code, its reason and
 * a way to act on it, kept on screen. No jsdom: the pure `JobCardView` over one job state.
 */

vi.mock('../services/api', () => ({
  api: { jobEvents: () => () => {}, listJobs: async () => [] },
}))

const PALE = /\btext-(rose|red|pink|violet|purple)-(50|100|200|300|400)\b/

const ops = {
  refreshDataset: async () => ({ jobId: 'n' }),
} as unknown as RetryApi

function job(overrides: Partial<Job> = {}): Job {
  return {
    id: 'job-1', projectId: 'p', jobType: 'dataset:refresh', status: 'RUNNING',
    parameters: { datasetId: 'd' }, progressStage: null, progressPercent: null, result: null,
    errorCode: null, errorMessage: null, createdBy: null, createdAt: '2026-09-25T11:00:00Z',
    startedAt: '2026-09-25T11:00:05Z', finishedAt: null,
    subject: { kind: 'dataset', id: 'd', name: 'orders', repositoryId: 'r' }, ...overrides,
  }
}

async function render(j: Job | null, props: Record<string, unknown> = {}) {
  const { JobCardView } = await import('./JobCard')
  return renderToStaticMarkup(<JobCardView job={j} now={Date.parse('2026-09-25T11:00:47Z')} retry={null} {...props} />)
}

describe('JobCardView', () => {
  it('shows the stage the job reported, under its operational title, with its running time', async () => {
    const html = await render(job({ progressStage: 'copying orders' }))
    expect(html).toContain('aran refresh orders')
    expect(html).toContain('copying orders')
    expect(html).toContain('42 s')
    expect(html).toContain('data-job-status="RUNNING"')
  })

  it('says a queued job is queued rather than inventing a stage', async () => {
    expect(await render(job({ status: 'PENDING', startedAt: null }))).toContain('queued — waiting to start')
  })

  it('keeps a failure on screen with its code, its reason, Retry and Dismiss', async () => {
    const failed = job({ status: 'FAILED', errorCode: 'REFRESH_BLOCKED_BY_DRIFT',
      errorMessage: 'blocked by schema drift', finishedAt: '2026-09-25T11:01:00Z' })
    const html = await render(failed, { retry: retryFor(failed, ops), onDismiss: () => {} })
    expect(html).toContain('REFRESH_BLOCKED_BY_DRIFT')
    expect(html).toContain('blocked by schema drift')
    expect(html).toContain('Retry refresh')
    expect(html).toContain('job-card-dismiss')
    expect(html).toContain('role="alert"')
    expect(html).toContain('55 s')
  })

  it('never offers a cancel — nothing can stop a running job (D7)', async () => {
    const html = await render(job({ status: 'FAILED', errorMessage: 'x' }), { onDismiss: () => {} })
    expect(html).not.toContain('job-card-retry')
    expect(html.toLowerCase()).not.toContain('cancel')
    expect((await render(job())).toLowerCase()).not.toContain('cancel')
  })

  it('draws a failure with the skin\u2019s error tokens, never pale palette text (WCAG AA)', async () => {
    // text-rose-300/-200 on the default light skin read at ~1.9:1 and ~1.4:1 (J6-F13's pattern);
    // tokenContrast.test.ts holds destructive-foreground on destructive to 4.5:1 in both skins.
    const failed = job({ status: 'FAILED', errorCode: 'X', errorMessage: 'lake down', finishedAt: '2026-09-25T11:01:00Z' })
    const html = await render(failed, { retry: retryFor(failed, ops), retryError: 'Could not start', onDismiss: () => {} })
    expect(html).toContain('bg-destructive text-destructive-foreground')
    // From the failure box on: the header's StatusDot label is J8-F11's (package D2), not this one.
    expect(html.slice(html.indexOf('job-card-error'))).not.toMatch(PALE)
  })

  it('announces the stage, not the ticking clock', async () => {
    // A live region around a duration that changes every second makes a screen reader read
    // "3 s, 4 s, 5 s…" for as long as the job runs.
    const html = await render(job({ progressStage: 'copying orders' }))
    const live = html.match(/<[^>]*aria-live="polite"[^>]*>([^<]*)</)
    expect(live?.[1]).toBe('copying orders')
    expect(html.match(/aria-live/g)).toHaveLength(1)
  })

  it('keeps an explicit title', async () => {
    expect(await render(job(), { title: 'aran bootstrap acm.csv' })).toContain('aran bootstrap acm.csv')
  })
})

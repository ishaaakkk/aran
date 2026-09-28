import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import type { Job } from '../services/types'

/**
 * The jobs drawer (UI audit J8-F7): running, failed and recent jobs with kind, object, start,
 * duration, stage or failure reason, and a Retry where one exists — and a failed list said as
 * such, never shown as "no jobs". No jsdom: the pure `JobsDrawerView`.
 */

vi.mock('../services/api', () => ({
  api: { jobEvents: () => () => {}, listJobs: async () => [], bootstrapDataset: async () => ({ jobId: 'n' }) },
}))

const PALE = /\btext-(rose|red|pink|violet|purple)-(50|100|200|300|400)\b/

const NOW = Date.parse('2026-09-25T12:00:00Z')

function job(overrides: Partial<Job> = {}): Job {
  return {
    id: 'job-1', projectId: 'p1', jobType: 'dataset:bootstrap', status: 'RUNNING',
    parameters: { datasetId: 'd1' }, progressStage: null, progressPercent: null, result: null,
    errorCode: null, errorMessage: null, createdBy: null, createdAt: '2026-09-25T11:50:00Z',
    startedAt: '2026-09-25T11:55:00Z', finishedAt: null,
    subject: { kind: 'dataset', id: 'd1', name: 'researchers.json', repositoryId: 'r1' }, ...overrides,
  }
}

async function render(props: Record<string, unknown>) {
  const { JobsDrawerView } = await import('./JobsDrawer')
  const noop = () => {}
  return renderToStaticMarkup(
    <JobsDrawerView jobs={[]} loadState="ready" loadError={null} now={NOW} projectNames={{ p1: 'Bibliography' }}
      retrying={null} retryErrors={{}} onRefresh={noop} onClose={noop} onOpen={noop} onRetry={noop}
      {...props} />,
  )
}

describe('JobsDrawerView', () => {
  it('lists a running job by kind and object, with its stage, start and running time', async () => {
    const html = await render({ jobs: [job({ progressStage: 'extracting schema' })] })
    expect(html).toContain('jobs-running')
    expect(html).toContain('Bootstrap')
    expect(html).toContain('researchers.json')
    expect(html).toContain('extracting schema')
    expect(html).toContain('started 5 min ago')
    expect(html).toContain('ran 5 min 0 s')
  })

  it('lists a failure with its code, its reason and a Retry of the same operation', async () => {
    const html = await render({ jobs: [job({
      id: 'job-f', status: 'FAILED', errorCode: 'BOOTSTRAP_FAILED',
      errorMessage: 'two arrays of primitives: tags, ids', finishedAt: '2026-09-25T11:56:00Z',
    })] })
    expect(html).toContain('jobs-failed')
    expect(html).toContain('BOOTSTRAP_FAILED')
    expect(html).toContain('two arrays of primitives: tags, ids')
    expect(html).toContain('jobs-retry-job-f')
    expect(html).toContain('Retry bootstrap')
  })

  it('names a rebuild by its project', async () => {
    const html = await render({ jobs: [job({
      jobType: 'model:compile', status: 'SUCCEEDED', parameters: { projectId: 'p1' }, subject: null,
      finishedAt: '2026-09-25T11:57:00Z',
    })] })
    expect(html).toContain('jobs-recent')
    expect(html).toContain('Rebuild')
    expect(html).toContain('Bibliography')
  })

  it('says a failed list failed — it does not render as "no jobs"', async () => {
    const html = await render({ loadState: 'error', loadError: 'request failed: 503' })
    expect(html).toContain('jobs-drawer-error')
    expect(html).toContain('request failed: 503')
    expect(html).not.toContain('jobs-drawer-empty')
  })

  it('draws failures and a failed list with the skin\u2019s tokens, never pale palette text (WCAG AA)', async () => {
    const html = await render({
      loadState: 'error', loadError: 'request failed: 503',
      jobs: [job({ id: 'job-f', status: 'FAILED', errorCode: 'BOOTSTRAP_FAILED', errorMessage: 'lake down',
        finishedAt: '2026-09-25T11:56:00Z' })],
      retryErrors: { 'job-f': 'Could not start the job again.' },
    })
    expect(html).toContain('bg-destructive text-destructive-foreground')
    // The list's load error, and each failed job's reason, Retry and retry error. The StatusDot
    // label beside a job's name is J8-F11's (package D2), not this one.
    expect(html.slice(html.indexOf('jobs-drawer-error'), html.indexOf('<section'))).not.toMatch(PALE)
    const failure = html.slice(html.indexOf('text-[11px] text-error'))
    expect(failure).toContain('BOOTSTRAP_FAILED')
    expect(failure).not.toMatch(PALE)
  })

  it('says there are no jobs only when the list loaded and was empty', async () => {
    expect(await render({})).toContain('jobs-drawer-empty')
    expect(await render({ loadState: 'loading' })).toContain('Loading jobs')
  })

  it('offers no cancel, and says why', async () => {
    const html = await render({ jobs: [job()] })
    expect(html).toContain('a running job cannot be cancelled')
    expect(html).not.toMatch(/<button[^>]*>[^<]*cancel/i)
  })
})

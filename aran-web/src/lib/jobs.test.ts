import { describe, expect, it, vi } from 'vitest'
import {
  formatDuration,
  jobDurationMs,
  jobHref,
  jobKind,
  jobObjectLabel,
  latestJob,
  isDatasetJob,
  isProductJob,
  isProfilingJob,
  jobSections,
  unseenFailures,
  resolveDatasetStatus,
  resolveProductStatus,
  materializeBusy,
  datasetBusyLabel,
  shouldReattach,
  retryFor,
  type RetryApi,
} from './jobs'
import type { Job } from '@/services/types'

/**
 * Jobs you can find (UI audit A6: J8-F7, J2-F5, J2-F24, J6-F7, register F5). A job row stores ids
 * and a type string; everything a person needs — what ran, on what, for how long, why it failed,
 * and how to run it again — is derived here, once, for the drawer and for every object's card.
 */

const NOW = Date.parse('2026-09-25T12:00:00Z')

function job(overrides: Partial<Job> = {}): Job {
  return {
    id: 'job-1', projectId: 'proj-1', jobType: 'dataset:bootstrap', status: 'RUNNING',
    parameters: { datasetId: 'ds-1' }, progressStage: null, progressPercent: null, result: null,
    errorCode: null, errorMessage: null, createdBy: null,
    createdAt: '2026-09-25T11:58:00Z', startedAt: '2026-09-25T11:58:10Z', finishedAt: null,
    subject: { kind: 'dataset', id: 'ds-1', name: 'acm.csv', repositoryId: 'repo-1' },
    ...overrides,
  }
}

describe('jobKind', () => {
  it('names every job type the backend submits, in the words the UI uses', () => {
    expect(jobKind('dataset:bootstrap').label).toBe('Bootstrap')
    expect(jobKind('operator:bootstrapping:bootstrap').label).toBe('Bootstrap')
    expect(jobKind('dataset:import').label).toBe('Import')
    expect(jobKind('dataset:refresh').label).toBe('Refresh')
    expect(jobKind('product:materialize').label).toBe('Materialize')
    expect(jobKind('model:compile').label).toBe('Rebuild')
    expect(jobKind('model:incremental-integrate').label).toBe('Add source')
    expect(jobKind('model:discover-constraints').label).toBe('Discover constraints')
    expect(jobKind('operator:profiling:profile').label).toBe('Profile')
    expect(jobKind('operator:join-discovery:discover').label).toBe('Discover joins')
  })

  it('keeps the operational `aran …` command titles, and a readable fallback', () => {
    expect(jobKind('model:compile').command).toBe('aran rebuild')
    expect(jobKind('operator:mystery:frob').label).toBe('mystery · frob')
    expect(jobKind('something-new').label).toBe('something-new')
  })
})

describe('jobObjectLabel', () => {
  it('uses the subject the server named', () => {
    expect(jobObjectLabel(job())).toBe('acm.csv')
  })

  it('falls back to the table a job read, then to a short id — never to nothing', () => {
    expect(jobObjectLabel(job({ subject: { kind: 'dataset', id: 'ds-1', name: null, repositoryId: null },
      parameters: { datasetId: 'ds-1', tableName: 'orders' } }))).toBe('orders')
    expect(jobObjectLabel(job({ subject: { kind: 'dataset', id: 'abcdef12-3456', name: null, repositoryId: null },
      parameters: { datasetId: 'abcdef12-3456' } }))).toBe('deleted dataset abcdef12')
    expect(jobObjectLabel(job({ subject: null, projectId: null, parameters: {} }))).toBe('—')
  })

  it('names a project job by the project list when the server could not', () => {
    expect(jobObjectLabel(
      job({ jobType: 'model:compile', subject: null, parameters: { projectId: 'proj-1' } }),
      { 'proj-1': 'Bibliography' },
    )).toBe('Bibliography')
  })
})

describe('durations', () => {
  it('measures from startedAt — a fact the server recorded, not when this page loaded', () => {
    expect(jobDurationMs(job(), NOW)).toBe(110_000)
    expect(jobDurationMs(job({ finishedAt: '2026-09-25T11:58:52Z', status: 'SUCCEEDED' }), NOW)).toBe(42_000)
  })

  it('has no duration for a job that never started', () => {
    expect(jobDurationMs(job({ startedAt: null, status: 'PENDING' }), NOW)).toBeNull()
  })

  it('formats seconds, minutes and hours', () => {
    expect(formatDuration(900)).toBe('< 1 s')
    expect(formatDuration(42_000)).toBe('42 s')
    expect(formatDuration(185_000)).toBe('3 min 5 s')
    expect(formatDuration(3_720_000)).toBe('1 h 2 min')
  })
})

describe('jobHref', () => {
  it('links a job to the page of the thing it ran on', () => {
    expect(jobHref(job())).toBe('/datasets/ds-1')
    expect(jobHref(job({ jobType: 'product:materialize', parameters: { productId: 'p-1' },
      subject: { kind: 'product', id: 'p-1', name: 'top', repositoryId: null } }))).toBe('/products')
    expect(jobHref(job({ jobType: 'model:compile', parameters: { projectId: 'proj-1' },
      subject: { kind: 'project', id: 'proj-1', name: 'Bib', repositoryId: null } }))).toBe('/alignments')
  })
})

describe('matching a job to its object', () => {
  const bootstrapOld = job({ id: 'a', createdAt: '2026-09-25T10:00:00Z', status: 'FAILED' })
  const refreshNew = job({ id: 'b', jobType: 'dataset:refresh', createdAt: '2026-09-25T11:00:00Z' })
  const profile = job({ id: 'c', jobType: 'operator:profiling:profile', createdAt: '2026-09-25T11:30:00Z' })
  const otherDataset = job({ id: 'd', parameters: { datasetId: 'ds-2' }, createdAt: '2026-09-25T11:40:00Z',
    subject: { kind: 'dataset', id: 'ds-2', name: 'x', repositoryId: null } })

  it('finds the newest job that changes a dataset\'s status — not its profiling runs', () => {
    const all = [bootstrapOld, refreshNew, profile, otherDataset]
    expect(latestJob(all, (j) => isDatasetJob(j, 'ds-1'))?.id).toBe('b')
    expect(latestJob(all, (j) => isProfilingJob(j, 'ds-1'))?.id).toBe('c')
    expect(latestJob(all, (j) => isDatasetJob(j, 'ds-9'))).toBeNull()
  })

  it('finds a product\'s materialize job', () => {
    const m = job({ jobType: 'product:materialize', parameters: { productId: 'p-1' } })
    expect(isProductJob(m, 'p-1')).toBe(true)
    expect(isProductJob(m, 'p-2')).toBe(false)
  })
})

describe('resolveDatasetStatus — register F5, the badge stuck in a transitional state', () => {
  it('a PENDING row whose job failed is Failed, with the job\'s reason', () => {
    const r = resolveDatasetStatus('PENDING', job({
      status: 'FAILED', errorCode: 'BOOTSTRAP_FAILED', errorMessage: 'two array fields: tags, ids',
    }))
    expect(r.status).toBe('FAILED')
    expect(r.reason).toBe('two array fields: tags, ids')
  })

  it('a PENDING row with a live job is still pending, and says what stage it is in', () => {
    const r = resolveDatasetStatus('PENDING', job({ progressStage: 'extracting schema' }))
    expect(r.status).toBe('PENDING')
    expect(r.stage).toBe('extracting schema')
    expect(r.live).toBe(true)
  })

  it('a FAILED row carries the last job\'s reason — the thing J2-F5 said was shown nowhere', () => {
    const r = resolveDatasetStatus('FAILED', job({ status: 'FAILED', errorMessage: 'lake unreachable' }))
    expect(r.reason).toBe('lake unreachable')
  })

  it('a FAILED row being retried reads as bootstrapping again, not as failed', () => {
    const r = resolveDatasetStatus('FAILED', job({ jobType: 'operator:bootstrapping:bootstrap', progressStage: 'writing source graph' }))
    expect(r).toEqual({ status: 'PENDING', reason: null, stage: 'writing source graph', live: true })
  })

  it('a job that is not the row\'s own change leaves the row alone', () => {
    expect(resolveDatasetStatus('READY', job({ status: 'FAILED', errorMessage: 'x' })).status).toBe('READY')
    expect(resolveDatasetStatus('PENDING', null)).toEqual({ status: 'PENDING', reason: null, stage: null, live: false })
  })
})

describe('resolveProductStatus — J6-F7', () => {
  it('MATERIALIZING whose job failed is FAILED with the job\'s reason', () => {
    const r = resolveProductStatus('MATERIALIZING', job({
      jobType: 'product:materialize', status: 'FAILED', errorMessage: 'lake full',
    }))
    expect(r).toEqual({ status: 'FAILED', reason: 'lake full', live: false })
  })

  it('MATERIALIZING with no live job is not "in progress" — refresh must not stay disabled', () => {
    expect(resolveProductStatus('MATERIALIZING', null).live).toBe(false)
    expect(resolveProductStatus('MATERIALIZING', job({ jobType: 'product:materialize' })).live).toBe(true)
  })
})

describe('datasetBusyLabel — what the dataset\'s refresh control says while a job runs', () => {
  it('names the running job, and is null once nothing runs', () => {
    expect(datasetBusyLabel(job({ jobType: 'dataset:refresh' }))).toBe('Refreshing…')
    expect(datasetBusyLabel(job({ jobType: 'dataset:import', status: 'PENDING' }))).toBe('Importing…')
    expect(datasetBusyLabel(job({ jobType: 'operator:bootstrapping:bootstrap' }))).toBe('Bootstrapping…')
    expect(datasetBusyLabel(job({ jobType: 'dataset:refresh', status: 'SUCCEEDED' }))).toBeNull()
    expect(datasetBusyLabel(null)).toBeNull()
  })
})

describe('shouldReattach — the alignments panel follows the rebuild that is running', () => {
  const rebuild = (id: string, status: Job['status']) => job({ id, jobType: 'model:compile', status })

  it('picks up a running rebuild when it holds none', () => {
    expect(shouldReattach(undefined, null, rebuild('r2', 'RUNNING'))).toBe(true)
  })

  it('yields a finished rebuild to a newer one — a retry started from the jobs drawer', () => {
    expect(shouldReattach(rebuild('r1', 'FAILED'), 'r1', rebuild('r2', 'RUNNING'))).toBe(true)
  })

  it('keeps what it holds while that still runs, or before the store has seen it', () => {
    expect(shouldReattach(rebuild('r1', 'RUNNING'), 'r1', rebuild('r2', 'RUNNING'))).toBe(false)
    expect(shouldReattach(undefined, 'r1', rebuild('r2', 'RUNNING'))).toBe(false)
    expect(shouldReattach(rebuild('r2', 'RUNNING'), 'r2', rebuild('r2', 'RUNNING'))).toBe(false)
    expect(shouldReattach(rebuild('r1', 'FAILED'), 'r1', null)).toBe(false)
  })
})

describe('materializeBusy — no second materialization on a guess', () => {
  const run = (status: Job['status']) => job({ jobType: 'product:materialize', status, parameters: { productId: 'p1' } })

  it('holds refresh while the job runs', () => {
    expect(materializeBusy('READY', run('RUNNING'), true)).toBe(true)
  })

  it('holds a MATERIALIZING row until the jobs list has loaded and shows how its run ended', () => {
    // A cube's star tables and a materialize started before a reload are MATERIALIZING rows whose
    // job the page may not know yet; the server does not refuse a second, concurrent run.
    expect(materializeBusy('MATERIALIZING', null, false)).toBe(true)
    expect(materializeBusy('MATERIALIZING', null, true)).toBe(true)
    expect(materializeBusy('MATERIALIZING', run('SUCCEEDED'), false)).toBe(true)
  })

  it('lets go once the run is known to have ended, even if the row still reads MATERIALIZING', () => {
    expect(materializeBusy('MATERIALIZING', run('SUCCEEDED'), true)).toBe(false)
    expect(materializeBusy('MATERIALIZING', run('FAILED'), true)).toBe(false)
    expect(materializeBusy('READY', null, false)).toBe(false)
  })
})

describe('retryFor — the same operation, re-invoked', () => {
  const calls: string[] = []
  const ops: RetryApi = {
    bootstrapDataset: vi.fn(async (d) => { calls.push(`bootstrap ${d}`); return { jobId: 'n' } }),
    refreshDataset: vi.fn(async (p, r, d) => { calls.push(`refresh ${p}/${r}/${d}`); return { jobId: 'n' } }),
    materializeProduct: vi.fn(async (p, id) => { calls.push(`materialize ${p}/${id}`); return { jobId: 'n' } }),
    compileModel: vi.fn(async (p) => { calls.push(`compile ${p}`); return { jobId: 'n' } }),
    incrementalIntegrate: vi.fn(async (p, d) => { calls.push(`incremental ${p}/${d}`); return { jobId: 'n' } }),
    discoverConstraints: vi.fn(async (p, b) => { calls.push(`discover ${p}/${b.datasetId}/${b.classIri}`); return { jobId: 'n' } }),
    submitOperator: vi.fn(async (o, b) => { calls.push(`operator ${o}:${b.operation}:${JSON.stringify(b.parameters)}`); return { jobId: 'n' } }),
  }

  it('is offered only for a failed or cancelled job', () => {
    expect(retryFor(job({ status: 'RUNNING' }), ops)).toBeNull()
    expect(retryFor(job({ status: 'SUCCEEDED' }), ops)).toBeNull()
    expect(retryFor(job({ status: 'FAILED' }), ops)).not.toBeNull()
  })

  it('re-runs each kind through its own route', async () => {
    calls.length = 0
    await retryFor(job({ status: 'FAILED' }), ops)!.run()
    await retryFor(job({ status: 'FAILED', jobType: 'dataset:import' }), ops)!.run()
    await retryFor(job({ status: 'FAILED', jobType: 'product:materialize', parameters: { productId: 'p-1' },
      subject: { kind: 'product', id: 'p-1', name: 'x', repositoryId: null } }), ops)!.run()
    await retryFor(job({ status: 'FAILED', jobType: 'model:compile', parameters: { projectId: 'proj-1' } }), ops)!.run()
    await retryFor(job({ status: 'FAILED', jobType: 'model:incremental-integrate',
      parameters: { projectId: 'proj-1', datasetId: 'ds-1' } }), ops)!.run()
    await retryFor(job({ status: 'FAILED', jobType: 'model:discover-constraints',
      parameters: { projectId: 'proj-1', datasetId: 'ds-1', classIri: 'urn:c' } }), ops)!.run()
    await retryFor(job({ status: 'FAILED', jobType: 'operator:profiling:profile',
      parameters: { datasetId: 'ds-1', snapshotId: 's' } }), ops)!.run()
    expect(calls).toEqual([
      'bootstrap ds-1',
      'refresh proj-1/repo-1/ds-1',
      'materialize proj-1/p-1',
      'compile proj-1',
      'incremental proj-1/ds-1',
      'discover proj-1/ds-1/urn:c',
      'operator profiling:profile:{"datasetId":"ds-1","snapshotId":"s"}',
    ])
  })

  it('keeps the operator parameters exactly, so the retry is the same invocation', async () => {
    calls.length = 0
    await retryFor(job({ status: 'CANCELLED', jobType: 'operator:join-discovery:discover',
      parameters: { a: '1', b: '2' } }), ops)!.run()
    expect(calls).toEqual(['operator join-discovery:discover:{"a":"1","b":"2"}'])
  })

  it('offers nothing it cannot run: an import whose repository is unknown, or a job without a project', () => {
    expect(retryFor(job({ status: 'FAILED', jobType: 'dataset:refresh',
      subject: { kind: 'dataset', id: 'ds-1', name: null, repositoryId: null } }), ops)).toBeNull()
    expect(retryFor(job({ status: 'FAILED', jobType: 'model:compile', projectId: null, parameters: {} }), ops)).toBeNull()
  })
})

describe('jobSections — the drawer\'s three lists', () => {
  it('splits running, failed and finished, newest first', () => {
    const s = jobSections([
      job({ id: 'old-ok', status: 'SUCCEEDED', createdAt: '2026-09-25T09:00:00Z' }),
      job({ id: 'new-run', status: 'RUNNING', createdAt: '2026-09-25T11:00:00Z' }),
      job({ id: 'queued', status: 'PENDING', createdAt: '2026-09-25T11:30:00Z' }),
      job({ id: 'boom', status: 'FAILED', createdAt: '2026-09-25T10:00:00Z' }),
      job({ id: 'new-ok', status: 'SUCCEEDED', createdAt: '2026-09-25T10:30:00Z' }),
    ])
    expect(s.running.map((j) => j.id)).toEqual(['queued', 'new-run'])
    expect(s.failed.map((j) => j.id)).toEqual(['boom'])
    expect(s.recent.map((j) => j.id)).toEqual(['new-ok', 'old-ok'])
  })

  it('counts failures that ended since the drawer was last opened', () => {
    const seen = Date.parse('2026-09-25T10:00:00Z')
    expect(unseenFailures([
      job({ status: 'FAILED', finishedAt: '2026-09-25T09:59:00Z' }),
      job({ status: 'FAILED', finishedAt: '2026-09-25T10:01:00Z' }),
      job({ status: 'SUCCEEDED', finishedAt: '2026-09-25T10:02:00Z' }),
    ], seen)).toBe(1)
  })
})

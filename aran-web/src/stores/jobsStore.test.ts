import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Job } from '@/services/types'

/**
 * The client's one copy of each job (UI audit A6). What these pin: a list and a stream never
 * regress a job's state, cards share one stream per job, and a failed list says why instead of
 * becoming an empty list.
 */

const jobEvents = vi.fn()
const listJobs = vi.fn()

vi.mock('@/services/api', () => ({
  api: {
    jobEvents: (...args: unknown[]) => jobEvents(...args),
    listJobs: (...args: unknown[]) => listJobs(...args),
  },
}))

function job(overrides: Partial<Job> = {}): Job {
  return {
    id: 'job-1', projectId: 'p', jobType: 'dataset:refresh', status: 'RUNNING', parameters: {},
    progressStage: null, progressPercent: null, result: null, errorCode: null, errorMessage: null,
    createdBy: null, createdAt: '2026-09-25T11:00:00Z', startedAt: null, finishedAt: null,
    subject: null, ...overrides,
  }
}

beforeEach(async () => {
  jobEvents.mockReset()
  listJobs.mockReset()
  const { useJobsStore } = await import('./jobsStore')
  useJobsStore.setState({ jobs: {}, loadState: 'idle', loadError: null, loadedAt: null, dismissed: {} })
})

describe('mergeJob', () => {
  it('never lets a late event turn a finished job back into a running one', async () => {
    const { mergeJob } = await import('./jobsStore')
    const done = job({ status: 'FAILED', errorMessage: 'x' })
    expect(mergeJob(done, job({ status: 'RUNNING' }))).toBe(done)
  })

  it('keeps the subject the list named when the stream sends none', async () => {
    const { mergeJob } = await import('./jobsStore')
    const subject = { kind: 'dataset' as const, id: 'd', name: 'orders', repositoryId: 'r' }
    expect(mergeJob(job({ subject }), job({ status: 'SUCCEEDED', subject: null })).subject).toEqual(subject)
  })
})

describe('refresh', () => {
  it('merges the listed jobs', async () => {
    const { useJobsStore } = await import('./jobsStore')
    listJobs.mockResolvedValue([job({ id: 'a' }), job({ id: 'b', status: 'FAILED' })])

    await useJobsStore.getState().refresh()

    expect(Object.keys(useJobsStore.getState().jobs).sort()).toEqual(['a', 'b'])
    expect(useJobsStore.getState().loadState).toBe('ready')
    expect(listJobs).toHaveBeenCalledWith({ limit: 100 })
  })

  it('records why a list failed, and keeps the jobs it already knew', async () => {
    const { useJobsStore } = await import('./jobsStore')
    useJobsStore.getState().upsert(job({ id: 'known' }))
    listJobs.mockRejectedValue(new Error('request failed: 503'))

    await useJobsStore.getState().refresh()

    expect(useJobsStore.getState().loadState).toBe('error')
    expect(useJobsStore.getState().loadError).toBe('request failed: 503')
    expect(useJobsStore.getState().jobs.known).toBeDefined()
  })
})

describe('follow', () => {
  it('shares one stream between every card showing a job, and closes it with the last', async () => {
    const { useJobsStore } = await import('./jobsStore')
    const stop = vi.fn()
    jobEvents.mockReturnValue(stop)

    const releaseA = useJobsStore.getState().follow('job-1')
    const releaseB = useJobsStore.getState().follow('job-1')
    expect(jobEvents).toHaveBeenCalledOnce()

    releaseA()
    expect(stop).not.toHaveBeenCalled()
    releaseB()
    expect(stop).toHaveBeenCalledOnce()
  })

  it('merges each streamed update into the store', async () => {
    const { useJobsStore } = await import('./jobsStore')
    jobEvents.mockImplementation((_id: string, onUpdate: (j: Job) => void) => {
      onUpdate(job({ progressStage: 'extracting schema' }))
      return () => {}
    })

    useJobsStore.getState().follow('job-1')

    expect(useJobsStore.getState().jobs['job-1'].progressStage).toBe('extracting schema')
  })

  it('does not open a stream for a job already known to be over', async () => {
    const { useJobsStore } = await import('./jobsStore')
    useJobsStore.getState().upsert(job({ status: 'SUCCEEDED' }))

    useJobsStore.getState().follow('job-1')

    expect(jobEvents).not.toHaveBeenCalled()
  })
})

describe('started', () => {
  it('calls back once when a started job ends, whether the stream or the list says so', async () => {
    const { useJobsStore } = await import('./jobsStore')
    jobEvents.mockReturnValue(() => {})
    listJobs.mockResolvedValue([])
    const settled = vi.fn()

    useJobsStore.getState().started('job-1', settled)
    useJobsStore.getState().upsert(job({ status: 'RUNNING' }))
    expect(settled).not.toHaveBeenCalled()

    listJobs.mockResolvedValue([job({ status: 'SUCCEEDED' })])
    await useJobsStore.getState().refresh()
    useJobsStore.getState().upsert(job({ status: 'SUCCEEDED' }))

    expect(settled).toHaveBeenCalledOnce()
    expect(settled.mock.calls[0][0].status).toBe('SUCCEEDED')
  })

  it('remembers the job as started here, and resolves once the list holds it', async () => {
    // A page hears the end of a job this tab started even when it first sees it finished
    // (settledSince), and a button awaiting the start finds the job in the store, with no gap in
    // which it could be clicked again (J2-F11's "Refreshing…").
    const { useJobsStore, wasStartedHere } = await import('./jobsStore')
    jobEvents.mockReturnValue(() => {})
    listJobs.mockResolvedValue([job({ id: 'job-7', status: 'PENDING' })])

    expect(wasStartedHere('job-7')).toBe(false)
    await useJobsStore.getState().started('job-7')

    expect(wasStartedHere('job-7')).toBe(true)
    expect(useJobsStore.getState().jobs['job-7'].status).toBe('PENDING')
  })
})

describe('dismiss', () => {
  it('remembers an acknowledged failure', async () => {
    const { useJobsStore } = await import('./jobsStore')
    useJobsStore.getState().dismiss('job-9')
    expect(useJobsStore.getState().dismissed['job-9']).toBe(true)
  })
})

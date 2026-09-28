import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The job routes the jobs drawer and the re-attaching cards depend on (UI audit A6): listing,
 * the job id handed out before a job ends (J3-F7), and a polling fallback that stops and says so
 * when a job cannot be read, instead of polling forever (J3-F7's "bound the poll fallback").
 */

vi.mock('./keycloak', () => ({
  ensureFreshToken: async () => 'token',
  tokenParsed: () => undefined,
}))

/** An EventSource that fails at once, forcing the polling fallback. */
class FailingEventSource {
  onerror: (() => void) | null = null
  constructor(readonly url: string) {
    queueMicrotask(() => this.onerror?.())
  }
  addEventListener() {}
  close() {}
}

const fetchMock = vi.fn()

beforeEach(() => {
  vi.useFakeTimers()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('EventSource', FailingEventSource)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const settle = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve()
}

describe('api.listJobs', () => {
  it('asks for the newest jobs, optionally for one project', async () => {
    fetchMock.mockImplementation(async () => new Response('[]', { status: 200 }))
    const { api } = await import('./api')

    await api.listJobs({ limit: 50 })
    await api.listJobs({ projectId: 'p-1', limit: 100 })
    await api.listJobs()

    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      '/api/jobs?limit=50',
      '/api/jobs?projectId=p-1&limit=100',
      '/api/jobs',
    ])
  })
})

describe('api.jobEvents polling fallback', () => {
  it('reports a job it cannot read as lost after a bounded number of tries, then stops', async () => {
    fetchMock.mockImplementation(async () => new Response('{"detail":"job x not found"}', { status: 404 }))
    const { api, JOB_POLL_INTERVAL_MS, JOB_POLL_MAX_FAILURES } = await import('./api')
    const updates: unknown[] = []
    const lost = vi.fn()

    api.jobEvents('job-x', (j) => updates.push(j), lost)
    await settle()
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS * (JOB_POLL_MAX_FAILURES - 1))
    expect(lost).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS * 2)
    expect(lost).toHaveBeenCalledOnce()
    expect(lost.mock.calls[0][0]).toContain('Lost track of job job-x')

    const callsWhenLost = fetchMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS * 10)
    expect(fetchMock.mock.calls.length).toBe(callsWhenLost)
    expect(updates).toEqual([])
  })

  it('keeps following a slow job whose reads succeed — there is no deadline on the work', async () => {
    const running = { id: 'job-s', status: 'RUNNING', parameters: {} }
    fetchMock.mockImplementation(async () => new Response(JSON.stringify(running), { status: 200 }))
    const { api, JOB_POLL_INTERVAL_MS, JOB_POLL_MAX_FAILURES } = await import('./api')
    const lost = vi.fn()

    const stop = api.jobEvents('job-s', () => {}, lost)
    await settle()
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS * JOB_POLL_MAX_FAILURES * 3)

    expect(lost).not.toHaveBeenCalled()
    stop()
  })
})

describe('api.runOperatorJob', () => {
  it('hands out the job id as soon as the job exists', async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url.endsWith('/submit')
        ? new Response(JSON.stringify({ jobId: 'job-p' }), { status: 202 })
        : new Response(JSON.stringify({ id: 'job-p', status: 'RUNNING', parameters: {} }), { status: 200 }))
    const { api } = await import('./api')
    const submitted = vi.fn()

    void api.runOperatorJob('profiling', { operation: 'profile', parameters: { datasetId: 'd' } },
      { onSubmitted: submitted })
    await settle()

    expect(submitted).toHaveBeenCalledWith('job-p')
  })

  it('resolves as failed when the job is lost, rather than never', async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url.endsWith('/submit')
        ? new Response(JSON.stringify({ jobId: 'job-l' }), { status: 202 })
        : new Response('{}', { status: 500 }))
    const { api, JOB_POLL_INTERVAL_MS, JOB_POLL_MAX_FAILURES } = await import('./api')

    const outcome = api.runOperatorJob('profiling', { operation: 'profile', parameters: {} })
    await settle()
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS * (JOB_POLL_MAX_FAILURES + 1))

    await expect(outcome).resolves.toMatchObject({ success: false, errorCode: 'JOB_LOST' })
  })
})

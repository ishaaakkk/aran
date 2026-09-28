import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * FRONTEND-2: profiling and join discovery rode `/invoke` under `request`'s 5 s client abort,
 * so a 6 s server-side run finished and persisted while the UI said "API request timed out".
 * They now run as jobs. Every other call takes its deadline from one table (UI audit J8-F3),
 * and a failure carries what the user and support need to act on it (J8-F4).
 */

vi.mock('./keycloak', () => ({
  ensureFreshToken: async () => 'token',
  tokenParsed: () => undefined,
}))

/** A stand-in for the browser's EventSource that the test drives by hand. */
class FakeEventSource {
  static last: FakeEventSource | null = null
  readonly listeners: Record<string, (event: MessageEvent) => void> = {}
  onerror: (() => void) | null = null
  constructor(readonly url: string) {
    FakeEventSource.last = this
  }
  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners[type] = listener
  }
  close() {}
  emitJob(job: Record<string, unknown>) {
    this.listeners.job?.({ data: JSON.stringify(job) } as MessageEvent)
  }
}

const job = (status: string, extra: Record<string, unknown> = {}) => ({
  id: 'job-1', projectId: null, jobType: 'operator:profiling:profile', status,
  parameters: {}, progressStage: null, progressPercent: null, result: null,
  errorCode: null, errorMessage: null, createdBy: null, createdAt: '', startedAt: null,
  finishedAt: null, ...extra,
})

const fetchMock = vi.fn()

beforeEach(() => {
  vi.useFakeTimers()
  FakeEventSource.last = null
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('EventSource', FakeEventSource)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

/** Resolve pending microtasks so the async chain reaches the next await. */
const settle = async () => {
  for (let i = 0; i < 50; i++) await Promise.resolve()
}

describe('api.runOperatorJob', () => {
  it('submits the invocation as a job and resolves with its result, however long it runs', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ jobId: 'job-1' }), { status: 202 }))
    const { api } = await import('./api')

    const outcome = api.runOperatorJob('profiling', {
      operation: 'profile', parameters: { datasetId: 'ds-1' },
    })
    await settle()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/operators/profiling/submit')

    // Longer than the 5 s client abort that used to drop the result.
    await vi.advanceTimersByTimeAsync(6_000)
    FakeEventSource.last!.emitJob(job('RUNNING'))
    FakeEventSource.last!.emitJob(job('SUCCEEDED', { result: { profile: '{"columns":[]}' } }))

    await expect(outcome).resolves.toEqual({ success: true, data: { profile: '{"columns":[]}' } })
    expect(FakeEventSource.last!.url).toContain('/api/jobs/job-1/events')
  })

  it('resolves a failed job as a failed result carrying its code and message', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ jobId: 'job-1' }), { status: 202 }))
    const { api } = await import('./api')

    const outcome = api.runOperatorJob('join-discovery', {
      operation: 'discover', parameters: { datasetIdA: 'a', datasetIdB: 'b' },
    })
    await settle()
    FakeEventSource.last!.emitJob(job('FAILED', {
      errorCode: 'FREYJA_UNAVAILABLE', errorMessage: 'the sidecar did not answer',
    }))

    await expect(outcome).resolves.toEqual({
      success: false, errorCode: 'FREYJA_UNAVAILABLE', errorMessage: 'the sidecar did not answer',
    })
  })
})

describe('the two data-sized operations the UI starts', () => {
  it('go through the job path, not the 5 s synchronous invoke', async () => {
    const { readFileSync } = await import('node:fs')
    const source = (view: string) => readFileSync(new URL(`../views/${view}`, import.meta.url), 'utf8')

    expect(source('DatasetsListView.tsx')).toContain("api.runOperatorJob('profiling'")
    expect(source('DatasetsListView.tsx')).not.toContain("invokeOperator('profiling'")
    expect(source('AlignmentsView.tsx')).toContain("api.runOperatorJob('join-discovery'")
    expect(source('AlignmentsView.tsx')).not.toContain("invokeOperator('join-discovery'")
  })
})

/** A fetch that never answers until the request is aborted — the shape of a slow server. */
const hangUntilAborted = () =>
  fetchMock.mockImplementation((_url: string, init: RequestInit) => new Promise((_, reject) => {
    init.signal?.addEventListener('abort', () =>
      reject(Object.assign(new Error('aborted'), { name: 'AbortError' })))
  }))

/** Records how `promise` settled, so a test can assert it is still pending at a given time. */
const track = <T,>(promise: Promise<T>) => {
  const state: { settled: boolean; error?: unknown } = { settled: false }
  promise.then(() => { state.settled = true }, (e: unknown) => { state.settled = true; state.error = e })
  return state
}

type Api = typeof import('./api').api
type ApiErrorT = InstanceType<typeof import('./api').ApiError>

describe('one deadline table (UI audit J8-F3, J1-F21, J2-F10, J4-F11, J6-F6, J7-F7)', () => {
  it('names every tier in one place', async () => {
    const { DEADLINES } = await import('./api')
    expect(DEADLINES).toEqual({ read: 15_000, write: 30_000, catalog: 30_000, long: 150_000 })
  })

  it('an ordinary read waits 15 s, not the old 5 s, and never claims the service is offline', async () => {
    hangUntilAborted()
    const { api } = await import('./api')

    const outcome = track(api.listProjects())
    await vi.advanceTimersByTimeAsync(5_000)
    expect(outcome.settled, 'still waiting at the old 5 s default').toBe(false)
    await vi.advanceTimersByTimeAsync(10_000)
    const err = outcome.error as ApiErrorT
    expect(err.kind).toBe('timeout')
    expect(err.mayStillBeRunning).toBe(false)
    expect(err.message).toContain('15 s')
    expect(err.message).not.toMatch(/offline/i)
  })

  it('a write that outlives its deadline says it may still be working on the server', async () => {
    hangUntilAborted()
    const { api } = await import('./api')

    const outcome = track(api.createApiToken({ name: 't', scope: 'READ', expiryDays: null }))
    await vi.advanceTimersByTimeAsync(29_000)
    expect(outcome.settled, 'a write waits the write deadline, not the read one').toBe(false)
    await vi.advanceTimersByTimeAsync(1_000)
    const err = outcome.error as ApiErrorT
    expect(err.kind).toBe('timeout')
    expect(err.mayStillBeRunning).toBe(true)
    expect(err.message).toContain('still working on the server')
    expect(err.message).toMatch(/check the result before trying again/i)
    expect(err.message).not.toMatch(/offline/i)
  })

  it.each<[string, (a: Api) => Promise<unknown>]>([
    ['a wrapper save (verify runs before it answers)', (a: Api) => a.setModelWrapper('p', 'd', 'c', 'SELECT 1')],
    ['a product create (plans the OQL)', (a: Api) => a.createProduct('p', { name: 'n', oql: 'SELECT A.x FROM A' })],
    ['a model promote', (a: Api) => a.promoteModelRun('p', 'r')],
    ['a live connection test', (a: Api) => a.testConnection('p', {} as never)],
    ['a dataset preview', (a: Api) => a.previewDatasetById('p', 'r', 'd')],
    ['a rollback', (a: Api) => a.rollbackSchemaVersion('v')],
  ])('%s gets the long deadline: alive at 60 s, given up at 150 s', async (_name, call) => {
    hangUntilAborted()
    const { api } = await import('./api')

    const outcome = track(call(api))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(outcome.settled).toBe(false)
    await vi.advanceTimersByTimeAsync(90_000)
    expect(outcome.settled).toBe(true)
  })

  it('the catalog and the view DDL get 30 s', async () => {
    hangUntilAborted()
    const { api } = await import('./api')

    const catalog = track(api.queryEngineCatalog())
    const ddl = track(api.explainQuery())
    await vi.advanceTimersByTimeAsync(15_000)
    expect(catalog.settled || ddl.settled).toBe(false)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(catalog.settled && ddl.settled).toBe(true)
  })

  it('Explain plans what a run plans, so it gets a run\'s deadline — not less than the 60 s it had', async () => {
    hangUntilAborted()
    const { api } = await import('./api')

    const explain = track(api.rewriteOql('SELECT A.x FROM A'))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(explain.settled, 'alive at the 60 s the call had before the table').toBe(false)
    await vi.advanceTimersByTimeAsync(90_000)
    expect(explain.settled).toBe(true)
  })

  it.each<[string, (a: Api) => Promise<unknown>, number]>([
    ['the query catalog', (a: Api) => a.queryEngineCatalog(), 30_000],
    ['an Explain', (a: Api) => a.rewriteOql('SELECT A.x FROM A'), 150_000],
    ['an OQL run', (a: Api) => a.runOql('SELECT A.x FROM A'), 150_000],
    ['a SQL run', (a: Api) => a.runQuery('SELECT 1'), 150_000],
    ['a connection test', (a: Api) => a.testConnection('p', {} as never), 150_000],
    ['a wrapper preview', (a: Api) => a.previewWrapper('p', 'r', 'd', 'SELECT 1'), 150_000],
  ])('%s is a POST that changes nothing: its timeout reads as a read\'s, safe to retry', async (_name, call, deadline) => {
    hangUntilAborted()
    const { api } = await import('./api')

    const outcome = track(call(api))
    await vi.advanceTimersByTimeAsync(deadline)
    const err = outcome.error as ApiErrorT
    expect(err.kind).toBe('timeout')
    expect(err.mayStillBeRunning).toBe(false)
    expect(err.message).not.toContain('still working on the server')
    expect(err.message).not.toMatch(/check the result/i)
  })

  it('a caller\'s own cancel still reads "query cancelled" (the workbench compares it)', async () => {
    hangUntilAborted()
    const { api } = await import('./api')
    const ctrl = new AbortController()

    const outcome = track(api.runOql('SELECT A.x FROM A', { signal: ctrl.signal }))
    await settle()
    ctrl.abort()
    await settle()
    expect((outcome.error as ApiErrorT).message).toBe('query cancelled')
    expect((outcome.error as ApiErrorT).kind).toBe('cancelled')
  })
})

describe('ApiError carries what support needs (J8-F4, J5-F15)', () => {
  const problem = (status: number, body: Record<string, unknown>, headers: Record<string, string> = {}) =>
    new Response(JSON.stringify(body), {
      status, headers: { 'Content-Type': 'application/problem+json', ...headers },
    })
  const sentHeaders = (call = 0) => (fetchMock.mock.calls[call][1] as RequestInit).headers as Record<string, string>

  it('sends a correlation id and keeps the one the server echoes', async () => {
    fetchMock.mockResolvedValue(problem(500,
      { title: 'Unexpected error', detail: 'An internal server error occurred.', status: 500 },
      { 'X-Correlation-ID': 'corr-from-server' }))
    const { api } = await import('./api')

    const err = await api.listProjects().catch((e: unknown) => e) as ApiErrorT
    expect(sentHeaders()['X-Correlation-ID']).toMatch(/^[0-9a-f-]{16,}$/)
    expect(err.correlationId).toBe('corr-from-server')
    expect(err.kind).toBe('server')
  })

  it('a request that never got an answer still has the id it sent, so the server log can be searched', async () => {
    hangUntilAborted()
    const { api } = await import('./api')

    const outcome = track(api.listProjects())
    await vi.advanceTimersByTimeAsync(15_000)
    expect(sentHeaders()['X-Correlation-ID']).toBeTruthy()
    expect((outcome.error as ApiErrorT).correlationId).toBe(sentHeaders()['X-Correlation-ID'])
  })

  it('carries errorCode from a ProblemDetail, and a 409 is a conflict', async () => {
    fetchMock.mockResolvedValue(problem(409,
      { title: 'Conflict', detail: "A data product named 'p' already exists in this project.", status: 409, errorCode: 'NAME_TAKEN' }))
    const { api } = await import('./api')

    const err = await api.createProduct('proj', { name: 'p', oql: 'x' }).catch((e: unknown) => e) as ApiErrorT
    expect(err.status).toBe(409)
    expect(err.kind).toBe('conflict')
    expect(err.errorCode).toBe('NAME_TAKEN')
    expect(err.message).toBe("A data product named 'p' already exists in this project.")
  })

  it('a query-engine refusal keeps its operator message, its errorCode and its correlation id', async () => {
    fetchMock.mockResolvedValue(problem(422,
      { success: false, errorCode: 'NO_SCHEMA', errorMessage: 'No active global schema — promote one first.' },
      { 'X-Correlation-ID': 'corr-422' }))
    const { api } = await import('./api')

    const err = await api.runOql('SELECT A.x FROM A').catch((e: unknown) => e) as ApiErrorT
    expect(err.message).toBe('No active global schema — promote one first.')
    expect(err.errorCode).toBe('NO_SCHEMA')
    expect(err.correlationId).toBe('corr-422')
    expect(err.kind).toBe('invalid')
  })

  it('a 401 or 403 with no body reads as a sentence, not "request failed: 401"', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 401 }))
    fetchMock.mockResolvedValueOnce(new Response('', { status: 403 }))
    const { api } = await import('./api')

    const unauth = await api.me().catch((e: unknown) => e) as ApiErrorT
    const forbidden = await api.listProjects().catch((e: unknown) => e) as ApiErrorT
    expect(unauth.kind).toBe('unauthenticated')
    expect(unauth.message).not.toContain('request failed')
    expect(forbidden.kind).toBe('forbidden')
    expect(forbidden.message).not.toContain('request failed')
  })

  it('an unreachable server is a network error, not a timeout', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const { api, ApiError } = await import('./api')

    const err = await api.listProjects().catch((e: unknown) => e) as ApiErrorT
    expect(err).toBeInstanceOf(ApiError)
    expect(err.kind).toBe('network')
    expect(err.message).toMatch(/could not reach aran/i)
  })

  it('the upload path shares the contract: kind, errorCode and correlation id', async () => {
    fetchMock.mockResolvedValue(problem(409,
      { title: 'Conflict', detail: "A dataset named 'acm.csv' already exists in the repository 'files'.", status: 409, errorCode: 'NAME_TAKEN' },
      { 'X-Correlation-ID': 'corr-upload' }))
    const { api } = await import('./api')

    const err = await api.createDatasetWithFile('p', 'r', { name: 'acm.csv', description: '' },
      new File(['id\n1\n'], 'acm.csv')).catch((e: unknown) => e) as ApiErrorT
    expect(err.kind).toBe('conflict')
    expect(err.errorCode).toBe('NAME_TAKEN')
    expect(err.correlationId).toBe('corr-upload')
  })
})

describe('Explain passes the planner\'s account through (J5-F12/J8-F9 pass-through)', () => {
  it('rewriteOql returns planning, placement and columns instead of dropping them', async () => {
    const planning = JSON.stringify({ productions: 10, budget: 5, elapsedMillis: 812, degradation: 'BEST_SO_FAR', tripped: 'PRODUCTION_BUDGET', productionsByRule: {} })
    const placement = JSON.stringify([{ datasetId: 'd', chosen: 'FEDERATED', liveCost: 1, snapshotCost: 2, effectiveRows: 3, fullRows: 4, reason: 'fresh copy unavailable' }])
    const columns = JSON.stringify([{ name: 'title', typeName: 'VARCHAR', jdbcType: 12 }])
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      success: true, data: { sql: 'SELECT 1', logicalPlan: 'L', optimizedPlan: 'O', planning, placement, columns },
    }), { status: 200 }))
    const { api } = await import('./api')

    const res = await api.rewriteOql('SELECT A.x FROM A')
    expect(res.planning).toBe(planning)
    expect(res.placement).toBe(placement)
    expect(res.columns).toBe(columns)
  })
})

describe('errorMessage quotes the reference only where it helps (J8-F4)', () => {
  it('appends the reference to a server error and a timeout, never to a domain refusal or a cancel', async () => {
    const { ApiError } = await import('./api')
    const { errorMessage, errorReference } = await import('@/lib/errors')

    const server = new ApiError(500, null, 'An internal server error occurred.', { correlationId: 'abc-123' })
    const timeout = new ApiError(0, null, 'This is still working on the server.', { kind: 'timeout', correlationId: 'def-456', mayStillBeRunning: true })
    const refusal = new ApiError(422, null, 'No active global schema — promote one first.', { correlationId: 'ghi-789' })
    const cancelled = new ApiError(0, null, 'query cancelled', { kind: 'cancelled', correlationId: 'jkl' })

    expect(errorMessage(server)).toBe('An internal server error occurred. Reference: abc-123')
    expect(errorMessage(timeout)).toBe('This is still working on the server. Reference: def-456')
    expect(errorMessage(refusal)).toBe('No active global schema — promote one first.')
    expect(errorMessage(cancelled)).toBe('query cancelled')
    expect(errorReference(server)).toBe('abc-123')
    expect(errorReference(refusal)).toBeNull()
  })
})

describe('api.exportOql — the row count it reports', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.stubGlobal('document', { createElement: () => ({ click() {} }) })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  })

  const exported = (headers: Record<string, string>) =>
    new Response('a\n1\n', { status: 200, headers: { 'Content-Type': 'text/csv', ...headers } })

  it('reads X-Aran-Row-Count', async () => {
    fetchMock.mockResolvedValue(exported({ 'X-Aran-Row-Count': '50213' }))
    const { api } = await import('./api')
    await expect(api.exportOql('SELECT A.x FROM A')).resolves.toBe(50213)
  })

  it('is unknown, not 0, when the header is missing or garbled', async () => {
    const { api } = await import('./api')
    fetchMock.mockResolvedValue(exported({}))
    await expect(api.exportOql('SELECT A.x FROM A')).resolves.toBeNull()
    fetchMock.mockResolvedValue(exported({ 'X-Aran-Row-Count': 'n/a' }))
    await expect(api.exportOql('SELECT A.x FROM A')).resolves.toBeNull()
  })
})

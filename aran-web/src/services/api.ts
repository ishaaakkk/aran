import { ensureFreshToken } from './keycloak'
import type { ApiTokenSummary, CreatedApiToken, TenantMember, ProjectMember, CreatedTenantMember, RemovedTenantMember, LicenseSummary, Me, Project, ProblemDetail, DataRepository, RepositoryInput, ConnectionTestResult, RelationalEngineInfo, SingerTapInfo, OperatorResult, Dataset, DatasetCreated, DatasetPreview, DatasetProfiles, DatasetRefreshPolicy, DashboardSummary, GraphNode, GraphEdge, QueryCatalog, QueryRunResult, OqlRewriteResult, Job, IntegrationModelBundle, ModelSource, ModelMapping, ModelConstraint, MappingKind, MappingStatus, SchemaVersion, SchemaDiff, DataProduct, ProductAccessMode, CubeDto, CubeEvidence, CreateCubeRequest, DriftReport, DriftRepairResult, TenantOverview, AdminTokenSummary, AuditEntry, StorageReport, SystemStatus } from './types'
import { DEMO_PASSTHROUGH, DemoReadOnlyError, isDemoUser, resolveDemoRequest } from './demo/demoInterceptor'

/**
 * REST API client for the aran-application backend.
 *
 * The tenant a request acts within is resolved server-side from the caller's
 * `membership` row, not from anything the client sends and not from a token
 * claim — see `IdentityService.soleMembership`. The frontend therefore does NOT send a
 * tenant header; one would be ignored.
 *
 * There is no tenant switcher because a user belongs to exactly one tenant
 * (enforced by `uq_membership_user`). Someone needing two tenants holds two
 * Aran users, as with Google accounts. This is a product decision, not a
 * missing Keycloak feature — do not add a switcher without reading that design
 * note first.
 */

/** How often `jobEvents` polls when the event stream is unavailable. */
export const JOB_POLL_INTERVAL_MS = 1500
/** Consecutive failed polls (≈ one minute) after which `jobEvents` reports the job lost. */
export const JOB_POLL_MAX_FAILURES = 40

/**
 * Every client-side deadline, in one place (UI audit J8-F3).
 *
 * The browser gives up waiting at these; the server does not stop working. So a deadline is a
 * promise about the page, never about the operation — which is why a write that outlives its
 * deadline says so ("still working on the server") instead of guessing that the service is
 * offline (the 5 s default this replaces made a 6 s wrapper save read as an outage while the
 * server committed it).
 *
 * - `read`    — a list, a row, a summary.
 * - `write`   — a mutation that commits in a round trip (a mapping, a token, a member).
 * - `catalog` — walking the whole schema without planning a query: the query catalog, the view
 *               DDL listing, a version diff, a schema graph.
 * - `long`    — anything whose duration is the source's or the data's, or the planner's: a live
 *               connection test, a scan, a preview, a wrapper verify, a promote, a product create,
 *               a query run, and Explain — it plans exactly what a run plans, so it gets a run's
 *               deadline (the 60 s it had before this table was not a considered number either).
 *
 * Which timeout sentence a call gets is decided by whether it CHANGES anything, not by its HTTP
 * method: the query engine's reads are POSTs to `/invoke`, and a timed-out catalog load or query
 * run has no "result" to check — it is safe to simply retry (`readOnly`).
 *
 * Jobs have no deadline at all — their row is the outcome (`runOperatorJob`). An upload and an
 * export have none either: they are as long as the bytes, and the user asked for exactly that.
 */
export const DEADLINES = {
  read: 15_000,
  write: 30_000,
  catalog: 30_000,
  long: 150_000,
} as const

export type Deadline = keyof typeof DEADLINES

/**
 * What went wrong, coarsely — what a view branches on (a sign-in prompt, a retry, a
 * "check before retrying"), where `status` is what the wire said. `timeout`, `network` and
 * `cancelled` never reached a status.
 */
export type ApiErrorKind =
  | 'network'
  | 'timeout'
  | 'cancelled'
  | 'unauthenticated'
  | 'forbidden'
  | 'licence'
  | 'not-found'
  | 'conflict'
  | 'invalid'
  | 'server'

export function kindForStatus(status: number): ApiErrorKind {
  if (status === 401) return 'unauthenticated'
  if (status === 402) return 'licence'
  if (status === 403) return 'forbidden'
  if (status === 404) return 'not-found'
  if (status === 409) return 'conflict'
  if (status >= 500) return 'server'
  return 'invalid'
}

class ApiError extends Error {
  readonly kind: ApiErrorKind
  /** The server's machine-readable code (a ProblemDetail's or an operator result's), if any. */
  readonly errorCode: string | null
  /**
   * The `X-Correlation-ID` of the request — echoed by the server, or the one this client sent
   * when no answer came back. Every server log line for the request carries it, so it is the
   * reference a user quotes (J8-F4, `troubleshooting.md`).
   */
  readonly correlationId: string | null
  /** A write that outlived its deadline: the server may still complete it, so re-read before retrying. */
  readonly mayStillBeRunning: boolean

  constructor(
    public readonly status: number,
    public readonly problem: ProblemDetail | null,
    message: string,
    opts: { kind?: ApiErrorKind; correlationId?: string | null; mayStillBeRunning?: boolean } = {},
  ) {
    super(message)
    this.name = 'ApiError'
    this.kind = opts.kind ?? kindForStatus(status)
    this.errorCode = problem?.errorCode ?? null
    this.correlationId = opts.correlationId ?? null
    this.mayStillBeRunning = opts.mayStillBeRunning ?? false
  }

  /** The same failure with a better sentence — everything a view branches on is kept. */
  withMessage(message: string): ApiError {
    return new ApiError(this.status, this.problem, message, {
      kind: this.kind, correlationId: this.correlationId, mayStillBeRunning: this.mayStillBeRunning,
    })
  }
}

const CORRELATION_HEADER = 'X-Correlation-ID'

/**
 * A fresh id per request, sent as `X-Correlation-ID`: `CorrelationIdFilter` adopts an inbound
 * id, so a request that times out in the browser can still be found in the server log by the
 * reference the page shows.
 */
function newCorrelationId(): string {
  const c = globalThis.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return Array.from({ length: 4 }, () => Math.floor(Math.random() * 0x1_0000_0000).toString(16).padStart(8, '0')).join('-')
}

/** The sentence for a status that came back with no usable body. */
function fallbackMessage(status: number): string {
  switch (kindForStatus(status)) {
    case 'unauthenticated':
      return 'Aran did not accept your sign-in — your session may have ended. Sign in again.'
    case 'forbidden':
      return 'You do not have permission to do this.'
    case 'not-found':
      return 'Not found — it may have been removed.'
    case 'server':
      return `Aran hit an unexpected error (HTTP ${status}).`
    default:
      return `The request was refused (HTTP ${status}).`
  }
}

/** One reading of a non-2xx response, shared by `request` and the raw-fetch paths (upload, export, YAML). */
async function errorFromResponse(response: Response, sentCorrelationId: string | null): Promise<ApiError> {
  let problem: ProblemDetail | null = null
  try {
    problem = (await response.json()) as ProblemDetail
  } catch {
    // Not a problem+json document; the status alone has to speak.
  }
  return new ApiError(
    response.status,
    problem,
    problem?.detail ?? fallbackMessage(response.status),
    { correlationId: response.headers.get(CORRELATION_HEADER) ?? sentCorrelationId },
  )
}

/**
 * `ensureFreshToken`, with an ended session reported as the ApiError every view already handles
 * (kind `unauthenticated`) rather than a bare error or — as it used to — a navigation away.
 * Matched by name, not class: the keycloak module is mocked wholesale in tests.
 */
async function freshToken(): Promise<string | undefined> {
  try {
    return await ensureFreshToken()
  } catch (e: unknown) {
    if (e instanceof Error && e.name === 'SessionEndedError') {
      throw new ApiError(401, null, e.message, { kind: 'unauthenticated' })
    }
    throw e
  }
}

function networkError(correlationId: string): ApiError {
  return new ApiError(0, null,
    'Could not reach Aran — check your connection. If it keeps happening, the service may be down.',
    { kind: 'network', correlationId })
}

async function request<T>(
  path: string,
  options: {
    method?: string
    body?: unknown
    deadline?: Deadline
    signal?: AbortSignal
    /** A POST that changes nothing (the query engine's reads): a timeout reads as a read's. */
    readOnly?: boolean
  } = {},
): Promise<T> {
  const token = await freshToken()

  // The `demo` Keycloak user has no seeded tenant in Postgres — every request would
  // 401/404 against a real backend — so its whole traffic is answered from an
  // in-memory scenario instead of ever reaching `fetch`. See demo/demoInterceptor.ts.
  if (isDemoUser()) {
    const mocked = resolveDemoRequest(path, options)
    if (mocked !== DEMO_PASSTHROUGH) return mocked as T
  }

  const method = options.method ?? 'GET'
  const correlationId = newCorrelationId()
  const headers: Record<string, string> = {
    Authorization: `Bearer ${token ?? ''}`,
    [CORRELATION_HEADER]: correlationId,
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const changesNothing = options.readOnly ?? method === 'GET'
  const deadlineMs = DEADLINES[options.deadline ?? (changesNothing ? 'read' : 'write')]
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), deadlineMs)

  // Wire external signal into our controller so either can cancel
  if (options.signal) {
    if (options.signal.aborted) controller.abort()
    else options.signal.addEventListener('abort', () => controller.abort(), { once: true })
  }

  const aborted = (): ApiError => {
    if (options.signal?.aborted) {
      return new ApiError(0, null, 'query cancelled', { kind: 'cancelled', correlationId })
    }
    const seconds = Math.round(deadlineMs / 1000)
    // A read that timed out changed nothing; a write may still land. Saying "offline" about
    // either made users retry a write the server had already committed (J8-F3).
    return changesNothing
      ? new ApiError(0, null,
          `Aran did not answer within ${seconds} s. It may be busy — try again in a moment.`,
          { kind: 'timeout', correlationId })
      : new ApiError(0, null,
          `This is still working on the server — the page stopped waiting after ${seconds} s. `
            + 'Check the result before trying again.',
          { kind: 'timeout', correlationId, mayStillBeRunning: true })
  }
  const isAbort = (error: unknown) => error instanceof Error && error.name === 'AbortError'

  try {
    let response: Response
    try {
      response = await fetch(`/api${path}`, {
        method,
        headers,
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
        signal: controller.signal,
      })
    } catch (error: unknown) {
      throw isAbort(error) ? aborted() : networkError(correlationId)
    }

    if (!response.ok) {
      throw await errorFromResponse(response, correlationId)
    }
    if (response.status === 204) {
      return undefined as T
    }
    try {
      return (await response.json()) as T
    } catch (error: unknown) {
      if (isAbort(error)) throw aborted()
      throw error
    }
  } finally {
    clearTimeout(timeoutId)
  }
}

/** `X-Aran-Row-Count` as a number, or null when it is absent or not a count — never a made-up 0. */
function rowCountHeader(response: Response): number | null {
  const value = response.headers.get('X-Aran-Row-Count')?.trim()
  return value && /^\d+$/.test(value) ? Number(value) : null
}

/** The server's suggested filename, so an export is named consistently everywhere. */
function filenameFrom(response: Response): string | null {
  const disposition = response.headers.get('Content-Disposition')
  const match = disposition?.match(/filename="?([^"]+)"?/)
  return match ? match[1] : null
}

/**
 * POST /api/operators/query-engine/invoke for a given operation, returning the
 * raw response envelope. A rejected invocation answers 422 with an
 * OperatorResult body ({success:false, errorCode, errorMessage}) which the
 * request helper parks in `problem`; we rethrow with that operator message as
 * the ApiError message so the UI shows "No active global schema…" etc. rather
 * than a generic 422. Callers pull the payload out of the returned `data`.
 */
async function invokeQueryEngine<T>(
  operation: string,
  parameters: Record<string, string>,
  opts: { deadline: Deadline; signal?: AbortSignal },
): Promise<T> {
  try {
    return await request<T>('/operators/query-engine/invoke', {
      method: 'POST',
      body: { operation, parameters },
      deadline: opts.deadline,
      signal: opts.signal,
      // Every operation the SPA sends here reads: catalog, explain, rewrite-oql, run, run-oql.
      readOnly: true,
    })
  } catch (e) {
    if (e instanceof ApiError && e.problem?.errorMessage) {
      throw e.withMessage(e.problem.errorMessage)
    }
    throw e
  }
}

export const api = {
  /** GET /api/me — current user + the tenants they can act within. */
  me(): Promise<Me> {
    return request<Me>('/me')
  },

  /** GET /api/projects — projects visible to the user in the JWT's tenant. */
  listProjects(): Promise<Project[]> {
    return request<Project[]>('/projects')
  },

  /** POST /api/projects — create a project; caller becomes creator + first member. */
  createProject(name: string, description: string, color?: string, privacy?: string): Promise<Project> {
    return request<Project>('/projects', {
      method: 'POST',
      body: { name, description, color, privacy },
    })
  },

  /** GET /api/projects/{id}/members — who can see the project (visibility is membership). */
  listProjectMembers(projectId: string): Promise<ProjectMember[]> {
    return request<ProjectMember[]>(`/projects/${projectId}/members`)
  },

  /** POST /api/projects/{id}/members — owner-only; the user must belong to the tenant. */
  addProjectMember(projectId: string, userId: string): Promise<ProjectMember> {
    return request<ProjectMember>(`/projects/${projectId}/members`, { method: 'POST', body: { userId } })
  },

  /** DELETE /api/projects/{id}/members/{userId} — owner-only; the last member cannot be removed. */
  removeProjectMember(projectId: string, userId: string): Promise<void> {
    return request<void>(`/projects/${projectId}/members/${userId}`, { method: 'DELETE' })
  },

  /** GET /api/tenants/{id}/license — entitlements + current usage. */
  getLicense(tenantId: string): Promise<LicenseSummary> {
    return request<LicenseSummary>(`/tenants/${tenantId}/license`)
  },

  /**
   * GET /api/relational-engines — which database engines this build can federate or extract
   * from, and which ports to prefill. Static for a given build.
   */
  listRelationalEngines(): Promise<RelationalEngineInfo[]> {
    return request<RelationalEngineInfo[]>('/relational-engines')
  },

  /** GET /api/projects/{projectId}/repositories — list registered repositories. */
  listRepositories(projectId: string): Promise<DataRepository[]> {
    return request<DataRepository[]>(`/projects/${projectId}/repositories`)
  },

  /** POST /api/projects/{projectId}/repositories — register a repository. */
  createRepository(projectId: string, repo: RepositoryInput): Promise<DataRepository> {
    return request<DataRepository>(`/projects/${projectId}/repositories`, {
      method: 'POST',
      body: repo,
    })
  },

  /** PUT /api/projects/{projectId}/repositories/{repositoryId} — update an existing repository. */
  updateRepository(projectId: string, repoId: string, repo: RepositoryInput): Promise<DataRepository> {
    return request<DataRepository>(`/projects/${projectId}/repositories/${repoId}`, {
      method: 'PUT',
      body: repo,
    })
  },

  /** DELETE /api/projects/{projectId}/repositories/{repositoryId} — remove a repository. */
  deleteRepository(projectId: string, repoId: string): Promise<void> {
    return request<void>(`/projects/${projectId}/repositories/${repoId}`, {
      method: 'DELETE',
    })
  },

  /**
   * POST /api/projects/{projectId}/repositories/test-connection — test settings that are not
   * saved yet (the register dialog). A masked credential is refused, never dialed: a SAVED
   * repository is tested with `testStoredConnection`. 60 s rather than the default: a first test
   * of an engine downloads its JDBC driver (J2-F19).
   */
  testConnection(projectId: string, repo: RepositoryInput): Promise<ConnectionTestResult> {
    return request<ConnectionTestResult>(`/projects/${projectId}/repositories/test-connection`, {
      method: 'POST',
      body: repo,
      deadline: 'long',
      readOnly: true,
    })
  },

  /**
   * POST /api/projects/{projectId}/repositories/{repoId}/test-connection — test the STORED
   * repository with its stored credentials (UI audit J2-F1). Without `edits`: the repository as
   * saved, recorded server-side as its last check. With `edits` (the edit dialog's form, masked
   * fields and all): those settings, each masked credential taken from storage — never sent as
   * a password. A kept credential aimed at a new host is refused (400), as a save would be.
   */
  testStoredConnection(projectId: string, repoId: string, edits?: RepositoryInput): Promise<ConnectionTestResult> {
    return request<ConnectionTestResult>(`/projects/${projectId}/repositories/${repoId}/test-connection`, {
      method: 'POST',
      body: edits,
      deadline: 'long',
      readOnly: true,
    })
  },

  /** GET /api/projects/{projectId}/repositories/{repositoryId}/datasets — list registered datasets. */
  listDatasets(projectId: string, repoId: string): Promise<Dataset[]> {
    return request<Dataset[]>(`/projects/${projectId}/repositories/${repoId}/datasets`)
  },

  /** POST /api/projects/{projectId}/repositories/{repositoryId}/datasets — register a dataset. */
  /**
   * POST /api/projects/{projectId}/repositories/{repositoryId}/datasets — register a dataset with
   * no file. A table a federated repository reads live comes back `PENDING` with the id of its
   * bootstrap job, as an upload does; anything else has `bootstrapJobId: null`.
   */
  createDataset(projectId: string, repoId: string, dataset: { name: string; description: string }): Promise<DatasetCreated> {
    return request<DatasetCreated>(`/projects/${projectId}/repositories/${repoId}/datasets`, {
      method: 'POST',
      body: dataset,
      deadline: 'long',
    })
  },

  /**
   * POST /api/projects/{projectId}/repositories/{repositoryId}/datasets — register a dataset with file.
   *
   * The dataset comes back `PENDING`: bootstrapping runs as a job, and
   * `bootstrapJobId` is how to watch it (`jobEvents`) rather than guess.
   */
  async createDatasetWithFile(
    projectId: string,
    repoId: string,
    dataset: { name: string; description: string },
    file: File
  ): Promise<DatasetCreated> {
    // Uploads bytes over a raw `fetch`, bypassing `request()` (and with it the demo-user
    // interceptor) entirely — the one write in this file that needed its own guard rather
    // than inheriting one.
    if (isDemoUser()) throw new DemoReadOnlyError()

    const token = await freshToken()
    const formData = new FormData()
    formData.append('dataset', JSON.stringify(dataset))
    formData.append('file', file)

    const correlationId = newCorrelationId()
    const headers: Record<string, string> = {
      Authorization: `Bearer ${token ?? ''}`,
      [CORRELATION_HEADER]: correlationId,
    }

    // No deadline: an upload is as long as the file.
    let response: Response
    try {
      response = await fetch(`/api/projects/${projectId}/repositories/${repoId}/datasets`, {
        method: 'POST',
        headers,
        body: formData,
      })
    } catch {
      throw networkError(correlationId)
    }

    if (!response.ok) {
      throw await errorFromResponse(response, correlationId)
    }

    return (await response.json()) as DatasetCreated
  },

  /** PUT /api/projects/{projectId}/repositories/{repositoryId}/datasets/{datasetId} — update dataset definition. */
  updateDataset(projectId: string, repoId: string, datasetId: string, dataset: { name: string; description: string }): Promise<Dataset> {
    return request<Dataset>(`/projects/${projectId}/repositories/${repoId}/datasets/${datasetId}`, {
      method: 'PUT',
      body: dataset,
    })
  },

  /** DELETE /api/projects/{projectId}/repositories/{repositoryId}/datasets/{datasetId} — delete a dataset. */
  deleteDataset(projectId: string, repoId: string, datasetId: string): Promise<void> {
    return request<void>(`/projects/${projectId}/repositories/${repoId}/datasets/${datasetId}`, {
      method: 'DELETE',
    })
  },

  /** GET /api/dashboard/summary — real counts and the last 15 audit rows for the tenant. */
  getDashboardSummary(): Promise<DashboardSummary> {
    // Walks every project, repository and dataset the caller can see (J1-F21).
    return request<DashboardSummary>('/dashboard/summary', { deadline: 'catalog' })
  },

  /** GET /api/projects/{projectId}/repositories/{repositoryId}/scan — scan available tables/files from the source. */
  scanDatasets(projectId: string, repoId: string): Promise<string[]> {
    return request<string[]>(`/projects/${projectId}/repositories/${repoId}/scan`, { deadline: 'long' })
  },

  /**
   * POST /api/projects/{projectId}/repositories/{repoId}/datasets/import — import a
   * PostgreSQL table as a durable parquet snapshot in the lake, then bootstrap it.
   *
   * A snapshot copy, distinct from live federation. Comes back `PENDING`; watch
   * `bootstrapJobId` (`jobEvents`) — it covers both the lake import and the bootstrap.
   */
  importDatasetFromTable(projectId: string, repoId: string, tableName: string): Promise<DatasetCreated> {
    return request<DatasetCreated>(`/projects/${projectId}/repositories/${repoId}/datasets/import`, {
      method: 'POST',
      body: { tableName },
      deadline: 'long',
    })
  },

  /**
   * GET /api/singer-taps — the Singer taps this deployment allows.
   *
   * An empty array means the feature is off, which is the default: the form must say so rather
   * than render an empty dropdown. Carries key NAMES only, never a command or a value.
   */
  listSingerTaps(): Promise<SingerTapInfo[]> {
    return request<SingerTapInfo[]>('/singer-taps')
  },

  /**
   * POST /api/projects/{projectId}/repositories/{repoId}/datasets/import-stream — run one of a
   * tap's streams and land its records in the lake, then bootstrap them.
   *
   * Comes back `PENDING`; watch `bootstrapJobId` (`jobEvents`) — it covers the tap run, the
   * upload and the bootstrap.
   */
  importDatasetFromStream(projectId: string, repoId: string, stream: string): Promise<DatasetCreated> {
    return request<DatasetCreated>(`/projects/${projectId}/repositories/${repoId}/datasets/import-stream`, {
      method: 'POST',
      body: { stream },
      deadline: 'long',
    })
  },

  /**
   * POST .../datasets/import-iceberg — copy one table of an Iceberg REST catalog into the lake
   * as a parquet snapshot, then bootstrap it (Gap 2). The federated alternative goes through the
   * plain dataset registration with the table identifier as the dataset name.
   */
  importDatasetFromIcebergTable(projectId: string, repoId: string, tableIdentifier: string): Promise<DatasetCreated> {
    return request<DatasetCreated>(`/projects/${projectId}/repositories/${repoId}/datasets/import-iceberg`, {
      method: 'POST',
      body: { tableIdentifier },
      deadline: 'long',
    })
  },

  /**
   * GET .../datasets/refresh-policies — every refresh policy under a repository.
   *
   * Datasets with no upstream are simply absent from the list; the caller decides what to render
   * for them. One request per repository rather than per dataset, because the datasets view lists
   * them together.
   */
  listRefreshPolicies(projectId: string, repoId: string): Promise<DatasetRefreshPolicy[]> {
    return request<DatasetRefreshPolicy[]>(
      `/projects/${projectId}/repositories/${repoId}/datasets/refresh-policies`)
  },

  /** PUT .../datasets/{datasetId}/refresh-policy — MANUAL, or EVERY with an interval. */
  setRefreshPolicy(
    projectId: string,
    repoId: string,
    datasetId: string,
    policy: 'MANUAL' | 'EVERY',
    intervalSeconds?: number,
  ): Promise<DatasetRefreshPolicy> {
    return request<DatasetRefreshPolicy>(
      `/projects/${projectId}/repositories/${repoId}/datasets/${datasetId}/refresh-policy`,
      { method: 'PUT', body: { policy, intervalSeconds: intervalSeconds ?? null } })
  },

  /**
   * POST .../datasets/{datasetId}/refresh — re-copy the dataset from its upstream now.
   *
   * Returns the job doing the work: the copy and the bootstrap that follows it take a while, and
   * the dataset keeps serving its previous snapshot until the new one lands.
   */
  refreshDataset(projectId: string, repoId: string, datasetId: string): Promise<{ jobId: string }> {
    return request<{ jobId: string }>(
      `/projects/${projectId}/repositories/${repoId}/datasets/${datasetId}/refresh`,
      { method: 'POST' })
  },

  /** GET /api/projects/{projectId}/repositories/{repoId}/datasets/{datasetId}/preview — retrieve the original snapshot preview of a registered dataset */
  previewDatasetById(projectId: string, repoId: string, datasetId: string): Promise<DatasetPreview> {
    return request<DatasetPreview>(`/projects/${projectId}/repositories/${repoId}/datasets/${datasetId}/preview`, { deadline: 'long' })
  },

  /**
   * GET …/datasets/{datasetId}/profiles — the column profiles already stored for a dataset, one
   * group per generation (J3-F1). Reads the registry only; running profiling is `runOperatorJob`.
   */
  listDatasetProfiles(projectId: string, repoId: string, datasetId: string): Promise<DatasetProfiles> {
    return request<DatasetProfiles>(`/projects/${projectId}/repositories/${repoId}/datasets/${datasetId}/profiles`)
  },

  /** POST /api/projects/{projectId}/repositories/{repoId}/datasets/{datasetId}/preview-wrapper — preview and validate wrapper SQL query */
  previewWrapper(projectId: string, repoId: string, datasetId: string, wrapper: string): Promise<DatasetPreview> {
    return request<DatasetPreview>(`/projects/${projectId}/repositories/${repoId}/datasets/${datasetId}/preview-wrapper`, {
      method: 'POST',
      body: { wrapper },
      deadline: 'long',
      readOnly: true,
    })
  },

  /**
   * POST /api/operators/{operatorId}/submit — run the same invocation as a job.
   *
   * Returns as soon as the job row exists (202); the work continues server-side.
   * Watch it with `jobEvents`, or poll `getJob`.
   */
  submitOperator(
    operatorId: string,
    payload: { operation: string; parameters: Record<string, string> },
  ): Promise<{ jobId: string }> {
    return request<{ jobId: string }>(`/operators/${operatorId}/submit`, {
      method: 'POST',
      body: payload,
    })
  },

  /** GET /api/jobs/{jobId} — the job's current state, whole. */
  getJob(jobId: string): Promise<Job> {
    return request<Job>(`/jobs/${jobId}`)
  },

  /**
   * GET /api/jobs — the newest jobs the caller may see (their projects' and unscoped ones),
   * newest first, each with its named `subject`. What the jobs drawer lists, and how a page
   * re-attaches to a job it started before a navigation or a reload (UI audit J8-F7). The server
   * caps `limit` at 100.
   */
  listJobs(options: { projectId?: string; limit?: number } = {}): Promise<Job[]> {
    const query = new URLSearchParams()
    if (options.projectId) query.set('projectId', options.projectId)
    if (options.limit) query.set('limit', String(options.limit))
    const qs = query.toString()
    return request<Job[]>(`/jobs${qs ? `?${qs}` : ''}`)
  },

  /**
   * Watch a job until it reaches a terminal state. Returns an unsubscribe function.
   *
   * Prefers SSE. `EventSource` cannot send an Authorization header, so the token
   * rides as a query parameter on this one route (the backend's
   * `SseAccessTokenFilter` turns it back into the header before the JWT filter
   * sees it). If the stream cannot be established — proxy that buffers SSE,
   * connection dropped — this falls back to polling `getJob`, which is not a
   * degradation: the job row is the source of truth and the events are only a
   * push of what it already says.
   *
   * The polling fallback gives up after {@link JOB_POLL_MAX_FAILURES} consecutive failed reads —
   * about a minute of a job that cannot be read at all (deleted, forbidden, backend gone) — and
   * says so through `onLost`, instead of polling forever while its caller waits (J3-F7). A slow
   * job whose reads succeed is followed for as long as it runs: there is no client deadline on
   * the work, only on losing sight of it.
   */
  jobEvents(jobId: string, onUpdate: (job: Job) => void, onLost?: (reason: string) => void): () => void {
    // No job-creating call ever returns a jobId for the demo user (see
    // demoInterceptor.ts's isBlockedWrite) — bootstrap, compile, incremental-integrate
    // and materialize are all rejected before a job exists, so this is never reached
    // for them. Left un-special-cased on purpose: there is nothing to intercept.
    let cancelled = false
    let source: EventSource | null = null
    let pollTimer: ReturnType<typeof setInterval> | null = null

    const stop = () => {
      cancelled = true
      source?.close()
      source = null
      if (pollTimer) clearInterval(pollTimer)
      pollTimer = null
    }

    const isTerminal = (job: Job) =>
      job.status === 'SUCCEEDED' || job.status === 'FAILED' || job.status === 'CANCELLED'

    const startPolling = () => {
      if (cancelled || pollTimer) return
      let failures = 0
      pollTimer = setInterval(() => {
        void api
          .getJob(jobId)
          .then((job) => {
            if (cancelled) return
            failures = 0
            onUpdate(job)
            if (isTerminal(job)) stop()
          })
          .catch((err: unknown) => {
            // Transient failures are retried on the next tick; a job that stays unreadable is
            // reported rather than polled forever.
            failures += 1
            if (cancelled || failures < JOB_POLL_MAX_FAILURES) return
            stop()
            const why = err instanceof Error && err.message ? err.message : 'no answer'
            onLost?.(`Lost track of job ${jobId} (${why}). It may still be running — see Jobs.`)
          })
      }, JOB_POLL_INTERVAL_MS)
    }

    void (async () => {
      let token: string | undefined
      try {
        token = await ensureFreshToken()
      } catch {
        // The session ended; polling keeps asking (and failing quietly) until the user signs in.
        startPolling()
        return
      }
      if (cancelled) return

      source = new EventSource(
        `/api/jobs/${jobId}/events?access_token=${encodeURIComponent(token ?? '')}`,
      )

      source.addEventListener('job', (event) => {
        if (cancelled) return
        const job = JSON.parse((event as MessageEvent).data) as Job
        onUpdate(job)
        if (isTerminal(job)) stop()
      })

      source.onerror = () => {
        if (cancelled) return
        source?.close()
        source = null
        startPolling()
      }
    })()

    return stop
  },

  /**
   * Run an operator invocation as a job and resolve with its outcome, shaped like
   * `invokeOperator`'s so a caller can swap one for the other.
   *
   * For operations whose duration depends on the data — profiling a dataset, discovering
   * joins between two. Through `/invoke` they rode the old 5 s client abort (FRONTEND-2):
   * a 6 s profiling run finished and persisted server-side while the UI reported "API request
   * timed out". A job has no client-side deadline; the job row is the outcome, and it reaches
   * a terminal state even across a backend restart (`OrphanedJobSweep`).
   *
   * The demo account never creates a job (the interceptor rejects every submit) and answers
   * its read-only operations synchronously instead, so it keeps the `/invoke` route.
   *
   * `onSubmitted` hands the caller the job id as soon as the job exists, so a page can show the
   * job's own stages in a JobCard and find it again after a navigation (J3-F7) — the promise
   * alone said nothing until the end.
   */
  runOperatorJob(
    operatorId: string,
    payload: { operation: string; parameters: Record<string, string> },
    options: { onSubmitted?: (jobId: string) => void } = {},
  ): Promise<OperatorResult> {
    if (isDemoUser()) return api.invokeOperator(operatorId, payload)
    return api.submitOperator(operatorId, payload).then(
      ({ jobId }) =>
        new Promise<OperatorResult>((resolve) => {
          options.onSubmitted?.(jobId)
          api.jobEvents(jobId, (job) => {
            if (job.status === 'SUCCEEDED') {
              resolve({ success: true, data: job.result ?? {} })
            } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
              resolve({
                success: false,
                errorCode: job.errorCode ?? undefined,
                errorMessage: job.errorMessage ?? `the ${operatorId} job was ${job.status.toLowerCase()}`,
              })
            }
          }, (reason) => resolve({ success: false, errorCode: 'JOB_LOST', errorMessage: reason }))
        }),
    )
  },

  /** POST /api/operators/{operatorId}/invoke — invoke a registered operator.
   *  Returns the OperatorResult body for both success (200) and failure (422) so
   *  callers can branch on res.success without catching exceptions. */
  async invokeOperator(operatorId: string, payload: { operation: string; parameters: Record<string, string> }): Promise<OperatorResult> {
    try {
      return await request<OperatorResult>(`/operators/${operatorId}/invoke`, {
        method: 'POST',
        body: payload,
        deadline: 'long',
      })
    } catch (e) {
      // A rejected invocation answers 422 with an OperatorResult body, which the
      // request helper parked in `problem`. Hand it back as the result it already is.
      if (e instanceof ApiError && e.problem) {
        return {
          success: false,
          errorCode: e.problem.errorCode,
          errorMessage: e.problem.errorMessage ?? e.problem.detail,
        }
      }
      throw e
    }
  },

  /**
   * POST /api/operators/query-engine/invoke {operation: "catalog"} — the derived GAV
   * mapping catalog for the active global schema (integrated classes → source views →
   * column maps, plus join/object properties). `empty` is true when no schema is promoted.
   */
  async queryEngineCatalog(): Promise<QueryCatalog> {
    const res = await invokeQueryEngine<{ data?: { catalog?: string } }>('catalog', {}, { deadline: 'catalog' })
    const raw = res?.data?.catalog
    if (!raw) {
      return { empty: true, schemaVersionId: null, versionNumber: null, classes: [], joinProperties: [], objectProperties: [] }
    }
    return JSON.parse(raw) as QueryCatalog
  },

  /**
   * POST /api/operators/query-engine/invoke {operation: "cube-evidence"} — the ACTIVE catalog's
   * join edges with their trust tiers, for the tier chip on accepted joins (audit J4-F17). The
   * operation is read-only and not gated by `aran.cubes.enabled` (the cubes routes are); an
   * empty active schema answers `{ classes: [], edges: [] }`.
   */
  async queryEngineEvidence(): Promise<CubeEvidence> {
    const res = await invokeQueryEngine<{ data?: { evidence?: string } }>('cube-evidence', {}, { deadline: 'catalog' })
    const raw = res?.data?.evidence
    return raw ? (JSON.parse(raw) as CubeEvidence) : { classes: [], edges: [] }
  },

  /**
   * POST /api/operators/query-engine/invoke {operation: "explain"} — the GAV unfolding as
   * DuckDB DDL: the src_*, w_*_* and int_* view definitions the engine materialises. This is
   * exactly what `runQuery` executes against.
   */
  async explainQuery(): Promise<string[]> {
    const res = await invokeQueryEngine<{ data?: { ddl?: string } }>('explain', {}, { deadline: 'catalog' })
    const raw = res?.data?.ddl
    return raw ? (JSON.parse(raw) as string[]) : []
  },

  /**
   * POST /api/operators/query-engine/invoke {operation: "run"} — execute a read-only SQL
   * statement over the materialised int_ views and return the rows. Throws ApiError(422) with
   * the operator's errorMessage when the schema is missing or the SQL is rejected.
   *
   * Carries the same `signal`/`offset`/timeout contract as `runOql`: the workbench drives both
   * from one pager and one cancel button, and a raw-SQL run is no cheaper than an OQL one.
   */
  async runQuery(
    sql: string,
    opts?: { signal?: AbortSignal; offset?: number },
  ): Promise<QueryRunResult> {
    const params: Record<string, string> = { sql }
    if (typeof opts?.offset === 'number' && Number.isFinite(opts.offset) && opts.offset > 0) {
      params.offset = String(opts.offset)
    }
    const res = await invokeQueryEngine<{ data?: { result?: string } }>(
      'run', params, { deadline: 'long', signal: opts?.signal },
    )
    const raw = res?.data?.result
    if (!raw) throw new ApiError(500, null, 'query-engine returned no result payload')
    return JSON.parse(raw) as QueryRunResult
  },

  // ── API tokens ───────────────────────────────────────────────────────────

  /** GET /api/tokens — the caller's own tokens, live and dead. */
  listApiTokens(): Promise<ApiTokenSummary[]> {
    return request<ApiTokenSummary[]>('/tokens')
  },

  /**
   * POST /api/tokens — mint a token.
   *
   * The response carries the secret. It is the only time it exists anywhere: the server stores a
   * hash, so a token that is not copied now must be replaced, not recovered.
   */
  createApiToken(body: { name: string; scope: 'READ' | 'FULL'; expiryDays: number | null }): Promise<CreatedApiToken> {
    return request<CreatedApiToken>('/tokens', { method: 'POST', body })
  },

  // ── Tenant members (OWNER only) ─────────────────────────────────────────
  //
  // Every route acts on the caller's own tenant; none takes a tenant id, because there is
  // no case where an owner administers a tenant other than theirs. Authorisation is the
  // OWNER role on the caller's membership, enforced server-side in TenantMemberService.

  // ── Tenant administration (read-only) ──────────────────────────────────────

  /** @param days window every rate is measured over; the server clamps to [1, 365]. */
  tenantOverview(days = 30): Promise<TenantOverview> {
    return request<TenantOverview>(`/tenant/admin/overview?days=${days}`)
  },

  adminTokens(): Promise<AdminTokenSummary[]> {
    return request<AdminTokenSummary[]>('/tenant/admin/tokens')
  },

  /** DELETE /api/tenant/admin/tokens/{id} — an owner revokes one member's token; audited, idempotent. */
  revokeTenantToken(tokenId: string): Promise<void> {
    return request<void>(`/tenant/admin/tokens/${tokenId}`, { method: 'DELETE' })
  },

  /** Live storage for the caller's tenant. Slower than the other admin reads — it calls the lake. */
  adminStorage(): Promise<StorageReport> {
    return request<StorageReport>('/tenant/admin/storage', { deadline: 'catalog' })
  },

  /** Version and component health. Probes four external services, so allow for their timeouts. */
  adminSystem(): Promise<SystemStatus> {
    return request<SystemStatus>('/tenant/admin/system', { deadline: 'catalog' })
  },

  adminActivity(limit = 50): Promise<AuditEntry[]> {
    return request<AuditEntry[]>(`/tenant/admin/activity?limit=${limit}`)
  },

  listTenantMembers(): Promise<TenantMember[]> {
    return request<TenantMember[]>('/tenant/members')
  },

  /** The temporary password in the response is the only time it exists. */
  createTenantMember(body: { email: string; displayName: string; role: 'OWNER' | 'MEMBER' }): Promise<CreatedTenantMember> {
    return request<CreatedTenantMember>('/tenant/members', { method: 'POST', body })
  },

  changeTenantMemberRole(userId: string, role: 'OWNER' | 'MEMBER'): Promise<TenantMember> {
    return request<TenantMember>(`/tenant/members/${userId}/role`, { method: 'PUT', body: { role } })
  },

  /** Admin-side half of "I forgot my password". Returns a new temporary one, once. */
  resetTenantMemberPassword(userId: string): Promise<CreatedTenantMember> {
    return request<CreatedTenantMember>(`/tenant/members/${userId}/password`, { method: 'POST' })
  },

  /**
   * Removing the membership is what ends access — immediately. The server also revokes the
   * member's API tokens and drops their project access (UI-OD9), first handing any project only
   * they belonged to to the caller; disabling their login is hygiene it does afterwards. Re-adding
   * the same email reinstates the account with a new temporary password.
   */
  removeTenantMember(userId: string): Promise<RemovedTenantMember> {
    return request<RemovedTenantMember>(`/tenant/members/${userId}`, { method: 'DELETE' })
  },

  /** DELETE /api/tokens/{id} — irreversible. */
  revokeApiToken(id: string): Promise<void> {
    return request<void>(`/tokens/${id}`, { method: 'DELETE' })
  },

  /**
   * POST /api/operators/query-engine/invoke {operation: "rewrite-oql"} — parse an OQL query over
   * the global schema G, optimize via Calcite, and return the logical plan, optimized plan, and SQL.
   */
  async rewriteOql(oql: string, opts?: { signal?: AbortSignal }): Promise<OqlRewriteResult> {
    const res = await invokeQueryEngine<{ data?: {
      sql?: string; logicalPlan?: string; optimizedPlan?: string; planShape?: string; planShapeText?: string
      planning?: string; placement?: string; columns?: string
    } }>(
      'rewrite-oql', { oql }, { deadline: 'long', signal: opts?.signal },
    )
    // `planning`, `placement` and `columns` used to be dropped here, so Explain could never say
    // "planning cut short" or why a source was read live (J8-F9, J5-F12). Passed through as the
    // strings they arrive as; the plan view parses them defensively.
    return {
      sql: res?.data?.sql ?? '',
      logicalPlan: res?.data?.logicalPlan ?? '',
      optimizedPlan: res?.data?.optimizedPlan ?? '',
      planShape: res?.data?.planShape,
      planShapeText: res?.data?.planShapeText,
      planning: res?.data?.planning,
      placement: res?.data?.placement,
      columns: res?.data?.columns,
    }
  },

  /**
   * POST /api/operators/query-engine/invoke {operation: "run-oql"} — execute an OQL query,
   * returning the rows plus the generated SQL.
   */
  async runOql(
    oql: string,
    opts?: { signal?: AbortSignal; offset?: number },
  ): Promise<{ result: QueryRunResult; sql: string }> {
    const params: Record<string, string> = { oql }
    if (typeof opts?.offset === 'number' && Number.isFinite(opts.offset) && opts.offset > 0) {
      params.offset = String(opts.offset)
    }
    const res = await invokeQueryEngine<{ data?: { result?: string; sql?: string } }>(
      'run-oql', params, { deadline: 'long', signal: opts?.signal },
    )
    const raw = res?.data?.result
    if (!raw) throw new ApiError(500, null, 'query-engine returned no result payload')
    return { result: JSON.parse(raw) as QueryRunResult, sql: res?.data?.sql ?? '' }
  },

  /**
   * POST /api/query/export — run `oql` with NO row cap and download the whole result.
   *
   * Deliberately not an operator invoke: that endpoint answers with a JSON envelope carrying a
   * string map, which is the wrong shape for a file that may be hundreds of megabytes. This one
   * streams, so the response is read as a blob and handed to the browser as a download.
   *
   * No deadline is imposed. An export is a bulk operation the user asked for explicitly, and
   * any tier in `DEADLINES` would abort a legitimately long one.
   */
  async exportOql(oql: string, format: 'csv' | 'parquet' = 'csv'): Promise<number | null> {
    const token = await freshToken()
    const correlationId = newCorrelationId()
    let response: Response
    try {
      response = await fetch('/api/query/export', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token ?? ''}`,
          'Content-Type': 'application/json',
          [CORRELATION_HEADER]: correlationId,
        },
        body: JSON.stringify({ oql, format: format.toUpperCase() }),
      })
    } catch {
      throw networkError(correlationId)
    }
    if (!response.ok) {
      throw await errorFromResponse(response, correlationId)
    }

    const rowCount = rowCountHeader(response)
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    try {
      const a = document.createElement('a')
      a.href = url
      a.download = filenameFrom(response) ?? `aran-export.${format}`
      a.click()
    } finally {
      URL.revokeObjectURL(url)
    }
    return rowCount
  },

  /**
   * POST /api/operators/bootstrapping/submit — re-bootstrap a dataset's source schema as a job.
   *
   * Returns the job id, not the outcome: extracting a schema takes as long as it
   * takes, and the caller watches it with `jobEvents` instead of holding a request
   * open or guessing with a timer.
   */
  bootstrapDataset(datasetId: string): Promise<{ jobId: string }> {
    return api.submitOperator('bootstrapping', {
      operation: 'bootstrap',
      parameters: { datasetId },
    })
  },

  /**
   * GET .../datasets/{id}/drift — what re-bootstrapping would change, writing nothing.
   *
   * Costs a snapshot download and an introspection, so it is called on demand (before a
   * re-bootstrap) rather than on every render of the dataset list.
   */
  checkDatasetDrift(projectId: string, repositoryId: string, datasetId: string): Promise<DriftReport> {
    return request<DriftReport>(
      `/projects/${projectId}/repositories/${repositoryId}/datasets/${datasetId}/drift`,
      { deadline: 'long' },
    )
  },

  /**
   * POST .../datasets/{id}/drift/repair — repoint mappings at renamed IRIs.
   *
   * The renames are passed explicitly rather than re-derived server-side, because they are a
   * decision: the diff proposes, the user confirms. Nothing is ever repaired automatically.
   */
  repairDatasetDrift(
    projectId: string, repositoryId: string, datasetId: string,
    renames: { fromIri: string; toIri: string }[],
  ): Promise<DriftRepairResult> {
    return request<DriftRepairResult>(
      `/projects/${projectId}/repositories/${repositoryId}/datasets/${datasetId}/drift/repair`,
      { method: 'POST', body: { renames }, deadline: 'long' },
    )
  },

  /**
   * POST /api/operators/bootstrapping/submit with force — apply a schema the drift gate withheld.
   *
   * Separate from `bootstrapDataset` so that forcing is never the accidental default: the plain
   * call leaves the gate in place, and only a user who has read the report reaches this one.
   */
  forceBootstrapDataset(datasetId: string): Promise<{ jobId: string }> {
    return api.submitOperator('bootstrapping', {
      operation: 'bootstrap',
      parameters: { datasetId, force: 'true' },
    })
  },

  /**
   * GET /api/graph/source/{datasetId} — retrieve the D3-ready source schema graph.
   * Returns { nodes: [{id, label, iri, type}], edges: [{source, target, label}], tripleCount, empty }.
   */
  fetchSourceGraph(datasetId: string): Promise<{
    datasetId: string
    graphUri: string
    tripleCount: number
    empty: boolean
    nodes: GraphNode[]
    edges: GraphEdge[]
  }> {
    return request(`/graph/source/${datasetId}`, { deadline: 'catalog' })
  },

  /** POST /api/graph/promote/{draftVersionId} — promote a draft schema version to active.
   *  Optional `notes` are persisted onto the promoted GraphVersion (M7). A draft an integration
   *  run produced is promoted as that run server-side (J4-F2). Refused 409 (`errorCode` on the
   *  problem) when it would remove another project's classes (UI-OD2) or was superseded. */
  promoteDraft(draftVersionId: string, notes?: string): Promise<{
    id: string
    versionNumber: number
    status: string
    notes: string | null
  }> {
    return request(`/graph/promote/${draftVersionId}`, {
      method: 'POST',
      body: notes !== undefined ? { notes } : undefined,
      deadline: 'long',
    })
  },

  /** GET /api/graph/schema/{versionId} — retrieve the D3-ready schema version graph. */
  fetchSchemaGraph(versionId: string): Promise<{
    versionId: string
    graphUri: string
    tripleCount: number
    empty: boolean
    nodes: Array<{ id: string; label?: string; iri: string; type: string }>
    edges: Array<{ source: string; target: string; label: string }>
  }> {
    return request(`/graph/schema/${versionId}`, { deadline: 'catalog' })
  },

  // ── Integration model (V16) ────────────────────────────────────────────────
  // A project's durable, N-ary alignment store. The alignments view reads and
  // writes the model through these; it keeps no server truth in React state.

  /** GET /api/projects/{projectId}/model — the whole model (sources, mappings, constraints). */
  getModel(projectId: string): Promise<IntegrationModelBundle> {
    return request<IntegrationModelBundle>(`/projects/${projectId}/model`)
  },

  /** POST /api/projects/{projectId}/model/sources — enrol a dataset (seeds its class wrappers). */
  addModelSource(projectId: string, datasetId: string): Promise<ModelSource[]> {
    return request<ModelSource[]>(`/projects/${projectId}/model/sources`, {
      method: 'POST',
      body: { datasetId },
      deadline: 'long',
    })
  },

  /** DELETE /api/projects/{projectId}/model/sources/{datasetId} — un-enrol a dataset. */
  removeModelSource(projectId: string, datasetId: string): Promise<void> {
    return request<void>(`/projects/${projectId}/model/sources/${datasetId}`, {
      method: 'DELETE',
    })
  },

  /** PUT /api/projects/{projectId}/model/sources/{datasetId}/wrappers — override a class wrapper (M6). */
  setModelWrapper(projectId: string, datasetId: string, classIri: string, sql: string | null): Promise<void> {
    // The wrapper is verified against the snapshot (or the live remote) before this answers.
    return request<void>(`/projects/${projectId}/model/sources/${datasetId}/wrappers`, {
      method: 'PUT',
      body: { classIri, sql },
      deadline: 'long',
    })
  },

  /** POST /api/projects/{projectId}/model/mappings — add a mapping; created as ACCEPTED. */
  addMapping(projectId: string, body: { kind: MappingKind; sourceIri: string; targetIri: string; label?: string; comment?: string }): Promise<ModelMapping> {
    return request<ModelMapping>(`/projects/${projectId}/model/mappings`, {
      method: 'POST',
      body,
    })
  },

  /** PATCH /api/projects/{projectId}/model/mappings/{mappingId} — decide a mapping (ACCEPTED|REJECTED). */
  patchMapping(projectId: string, mappingId: string, status: MappingStatus): Promise<void> {
    return request<void>(`/projects/${projectId}/model/mappings/${mappingId}`, {
      method: 'PATCH',
      body: { status },
    })
  },

  /** DELETE /api/projects/{projectId}/model/mappings/{mappingId} — remove a mapping. */
  deleteMapping(projectId: string, mappingId: string): Promise<void> {
    return request<void>(`/projects/${projectId}/model/mappings/${mappingId}`, {
      method: 'DELETE',
    })
  },

  /** POST /api/projects/{projectId}/model/suggest — persist SUGGESTED datatype mappings for a pair. */
  suggestMappings(projectId: string, leftDatasetId: string, rightDatasetId: string): Promise<ModelMapping[]> {
    return request<ModelMapping[]>(`/projects/${projectId}/model/suggest`, {
      method: 'POST',
      body: { leftDatasetId, rightDatasetId },
      deadline: 'long',
    })
  },

  /** POST /api/projects/{projectId}/model/compile — full REBUILD of all sources into a DRAFT
   *  schema; returns the job. Named `compile` server-side for compatibility; it is the rebuild. */
  compileModel(projectId: string): Promise<{ jobId: string }> {
    return request<{ jobId: string }>(`/projects/${projectId}/model/compile`, {
      method: 'POST',
    })
  },

  /** POST /api/projects/{projectId}/model/incremental-integrate — add ONE source on top of the
   *  promoted baseline without re-reading the others. Requires a baseline and no pending rebuild. */
  incrementalIntegrate(projectId: string, datasetId: string): Promise<{ jobId: string }> {
    return request<{ jobId: string }>(`/projects/${projectId}/model/incremental-integrate`, {
      method: 'POST',
      body: { datasetId },
    })
  },

  /** POST /api/projects/{projectId}/model/runs/{runId}/promote — model-aware promotion.
   *
   *  The alignments page's promote: it names the run, so a stale page cannot promote the wrong
   *  draft. (The generic `promoteDraft` used to leave the run in DRAFT and the model without a
   *  baseline; since J4-F2 it delegates here server-side.) Same 409 refusals as `promoteDraft`. */
  promoteModelRun(projectId: string, runId: string): Promise<{
    versionId: string
    versionNumber: string
  }> {
    return request(`/projects/${projectId}/model/runs/${runId}/promote`, {
      method: 'POST',
      deadline: 'long',
    })
  },

  /** POST /api/projects/{projectId}/model/constraints — add a class constraint (M6). */
  addConstraint(projectId: string, body: { classIri: string; expression: string }): Promise<ModelConstraint> {
    return request<ModelConstraint>(`/projects/${projectId}/model/constraints`, {
      method: 'POST',
      body,
      deadline: 'long',
    })
  },

  /** PATCH /api/projects/{projectId}/model/constraints/{constraintId} — toggle enforcement (M6). */
  patchConstraint(projectId: string, constraintId: string, enforced: boolean): Promise<void> {
    return request<void>(`/projects/${projectId}/model/constraints/${constraintId}`, {
      method: 'PATCH',
      body: { enforced },
    })
  },

  /** DELETE /api/projects/{projectId}/model/constraints/{constraintId} — remove a constraint (M6). */
  deleteConstraint(projectId: string, constraintId: string): Promise<void> {
    return request<void>(`/projects/${projectId}/model/constraints/${constraintId}`, {
      method: 'DELETE',
    })
  },

  /**
   * POST /api/projects/{projectId}/model/constraints/discover — run LIMA denial-constraint
   * discovery for one enrolled class (Phase 3B). Long-running like `compileModel`, so it only
   * submits and returns the job id to watch; survivors are persisted server-side as unenforced,
   * DISCOVERED-origin constraints. There is no separate accept call — a candidate is reviewed
   * and enforced through the existing `patchConstraint` above.
   */
  discoverConstraints(projectId: string, body: { datasetId: string; classIri: string }): Promise<{ jobId: string }> {
    return request<{ jobId: string }>(`/projects/${projectId}/model/constraints/discover`, {
      method: 'POST',
      body,
    })
  },

  // ── Schema versions (M7) ────────────────────────────────────────────────────

  /** GET /api/schema/versions — the tenant's version history, newest number first. */
  listSchemaVersions(): Promise<SchemaVersion[]> {
    return request<SchemaVersion[]>('/schema/versions')
  },

  /** GET /api/schema/versions/{a}/diff/{b} — class/property-level diff, a → b direction. */
  diffSchemaVersions(a: string, b: string): Promise<SchemaDiff> {
    return request<SchemaDiff>(`/schema/versions/${a}/diff/${b}`, { deadline: 'catalog' })
  },

  /** POST /api/schema/versions/{id}/rollback — restore a HISTORICAL version as a new ACTIVE one. */
  rollbackSchemaVersion(id: string): Promise<SchemaVersion> {
    return request<SchemaVersion>(`/schema/versions/${id}/rollback`, {
      method: 'POST',
      deadline: 'long',
    })
  },

  // ── Data products (M8) ──────────────────────────────────────────────────────

  /** GET /api/projects/{projectId}/products — a project's products, newest first. */
  listProducts(projectId: string): Promise<DataProduct[]> {
    return request<DataProduct[]>(`/projects/${projectId}/products`)
  },

  /** POST /api/projects/{projectId}/products — create; validates the OQL and pins the ACTIVE
   *  schema version. `accessMode` defaults MATERIALIZED server-side when omitted. */
  createProduct(
    projectId: string,
    body: { name: string; description?: string; oql: string; accessMode?: ProductAccessMode },
  ): Promise<DataProduct> {
    // Plans the OQL before it answers (J6-F6).
    return request<DataProduct>(`/projects/${projectId}/products`, {
      method: 'POST',
      body,
      deadline: 'long',
    })
  },

  /** POST /api/projects/{projectId}/products/{productId}/materialize — run the OQL to a parquet snapshot, as a job. */
  materializeProduct(projectId: string, productId: string): Promise<{ jobId: string }> {
    return request<{ jobId: string }>(`/projects/${projectId}/products/${productId}/materialize`, {
      method: 'POST',
    })
  },

  /** GET /api/projects/{projectId}/products/{productId}/preview — first rows of the materialized snapshot. */
  previewProduct(projectId: string, productId: string, limit = 100): Promise<QueryRunResult> {
    return request<QueryRunResult>(
      `/projects/${projectId}/products/${productId}/preview?limit=${limit}`,
      { deadline: 'long' },
    )
  },

  /**
   * GET /api/iceberg/files/{productId}/{snapshotId}/data/data.parquet — every row of a
   * MATERIALIZED product, as the snapshot it serves (J6-F4). Aran's own gated file route: the one
   * the Iceberg surface hands external engines, egress-limited and metered, so no lake URL ever
   * reaches the browser. No timeout, like `exportOql`: a whole product is a bulk read the user asked
   * for. A 404 means the snapshot is no longer the product's current one (refreshed meanwhile) or
   * has no recorded columns (built before V29).
   */
  async downloadProductSnapshot(productId: string, snapshotId: string, filename: string): Promise<void> {
    const token = await ensureFreshToken()
    const response = await fetch(
      `/api/iceberg/files/${encodeURIComponent(productId)}/${encodeURIComponent(snapshotId)}/data/data.parquet`,
      { headers: { Authorization: `Bearer ${token ?? ''}` } },
    )
    if (!response.ok) {
      let problem: ProblemDetail | null = null
      try { problem = (await response.json()) as ProblemDetail } catch { /* non-JSON body */ }
      throw new ApiError(response.status, problem, problem?.detail ?? `download failed: ${response.status}`)
    }
    const url = URL.createObjectURL(await response.blob())
    try {
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      a.click()
    } finally {
      URL.revokeObjectURL(url)
    }
  },

  /** DELETE /api/projects/{projectId}/products/{productId} — remove a product. */
  deleteProduct(projectId: string, productId: string): Promise<void> {
    return request<void>(`/projects/${projectId}/products/${productId}`, {
      method: 'DELETE',
    })
  },

  // ── Cubes (OLAP shaping) ────────────────────────────────────────────────────

  /** GET /api/projects/{projectId}/cubes — a project's cubes, newest first. */
  listCubes(projectId: string): Promise<CubeDto[]> {
    return request<CubeDto[]>(`/projects/${projectId}/cubes`)
  },

  /** GET /api/projects/{projectId}/cubes/evidence — the classes, columns and tiered join
   *  edges the shape-as-cube wizard pre-fills every step from. Plans the whole catalog
   *  server-side, so it gets the long deadline. */
  getCubeEvidence(projectId: string): Promise<CubeEvidence> {
    return request<CubeEvidence>(`/projects/${projectId}/cubes/evidence`, { deadline: 'long' })
  },

  /** POST /api/projects/{projectId}/cubes — write the cube annotations and its products in
   *  one act. Long deadline, for the same reason a materialization is not a round trip. */
  createCube(projectId: string, body: CreateCubeRequest): Promise<CubeDto> {
    return request<CubeDto>(`/projects/${projectId}/cubes`, {
      method: 'POST',
      body,
      deadline: 'long',
    })
  },

  /**
   * GET /api/projects/{projectId}/cubes/{cubeId}/yaml — the cube.yaml, as text.
   *
   * Fetched directly rather than through `request()`, which JSON-parses every body: this
   * endpoint answers text/plain and the YAML *is* the payload. Same minimal shape as
   * `exportOql` above, minus the download plumbing — the caller renders it.
   */
  async getCubeYaml(projectId: string, cubeId: string): Promise<string> {
    const token = await freshToken()
    const correlationId = newCorrelationId()
    let response: Response
    try {
      response = await fetch(`/api/projects/${projectId}/cubes/${cubeId}/yaml`, {
        headers: { Authorization: `Bearer ${token ?? ''}`, [CORRELATION_HEADER]: correlationId },
      })
    } catch {
      throw networkError(correlationId)
    }
    if (!response.ok) {
      throw await errorFromResponse(response, correlationId)
    }
    return response.text()
  },

  /** DELETE /api/projects/{projectId}/cubes/{cubeId} — remove a cube. */
  deleteCube(projectId: string, cubeId: string): Promise<void> {
    // Deletes the cube's star products with it.
    return request<void>(`/projects/${projectId}/cubes/${cubeId}`, {
      method: 'DELETE',
      deadline: 'long',
    })
  },
}


export { ApiError }

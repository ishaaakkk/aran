/**
 * Jobs you can find (UI audit Tier A6: J8-F7, J2-F5, J2-F24, J3-F7, J6-F7, register F5).
 *
 * A job row stores a type string and ids. Everything a person needs to find their work — what
 * ran, on what, for how long, why it failed, how to run it again — is derived here, once, for the
 * jobs drawer and for each object's own job card, so the two can never describe the same job two
 * ways. Pure: no store, no fetch (a retry takes its API as a parameter).
 *
 * There is no cancel anywhere in this file, deliberately: nothing can stop a running job (D7),
 * and a button that promised to would be the lie this whole package exists to remove.
 */
import type { Job } from '@/services/types'

export const isTerminal = (job: Pick<Job, 'status'>): boolean =>
  job.status === 'SUCCEEDED' || job.status === 'FAILED' || job.status === 'CANCELLED'

export const isLive = (job: Pick<Job, 'status'>): boolean =>
  job.status === 'PENDING' || job.status === 'RUNNING'

export const isFailure = (job: Pick<Job, 'status'>): boolean =>
  job.status === 'FAILED' || job.status === 'CANCELLED'

export interface JobKind {
  /** What the drawer's "kind" column says. */
  label: string
  /** The operational title a job card shows, in the `aran …` style. */
  command: string
}

const KINDS: Record<string, JobKind> = {
  'dataset:bootstrap': { label: 'Bootstrap', command: 'aran bootstrap' },
  'operator:bootstrapping:bootstrap': { label: 'Bootstrap', command: 'aran bootstrap' },
  'dataset:import': { label: 'Import', command: 'aran import' },
  'dataset:refresh': { label: 'Refresh', command: 'aran refresh' },
  'product:materialize': { label: 'Materialize', command: 'aran materialize' },
  'model:compile': { label: 'Rebuild', command: 'aran rebuild' },
  'model:incremental-integrate': { label: 'Add source', command: 'aran add source' },
  'model:discover-constraints': { label: 'Discover constraints', command: 'aran discover constraints' },
  'operator:profiling:profile': { label: 'Profile', command: 'aran profile' },
}

export function jobKind(jobType: string): JobKind {
  const known = KINDS[jobType]
  if (known) return known
  const parts = jobType.split(':')
  if (parts[0] === 'operator' && parts.length >= 3) {
    if (parts[1] === 'join-discovery') return { label: 'Discover joins', command: 'aran discover joins' }
    return { label: `${parts[1]} · ${parts.slice(2).join(':')}`, command: `aran ${parts[1]} ${parts.slice(2).join(':')}` }
  }
  return { label: jobType, command: `aran ${jobType}` }
}

/** The parameters a job keeps that name what it read, when the object itself has no name any more. */
const NAMING_PARAMETERS = ['tableName', 'stream', 'tableIdentifier', 'objectKey']

/**
 * What the job ran on, in words: the name the server resolved, else a table the job read, else
 * the project, else a short id saying the object is gone — never an empty cell.
 */
export function jobObjectLabel(job: Job, projectNames: Record<string, string> = {}): string {
  const subject = job.subject
  if (subject?.name) return subject.name
  for (const key of NAMING_PARAMETERS) {
    const value = job.parameters?.[key]
    if (value) return value
  }
  if (subject?.kind === 'project' || (!subject && job.projectId)) {
    const id = subject?.id ?? job.projectId!
    if (projectNames[id]) return projectNames[id]
  }
  if (subject) return `deleted ${subject.kind} ${subject.id.slice(0, 8)}`
  return '—'
}

/** Where the object a job ran on lives in the app, or null when there is no page for it. */
export function jobHref(job: Job): string | null {
  const datasetId = job.subject?.kind === 'dataset' ? job.subject.id : job.parameters?.datasetId
  if (job.jobType.startsWith('model:')) return '/alignments'
  if (datasetId) return `/datasets/${datasetId}`
  if (job.subject?.kind === 'product' || job.parameters?.productId) return '/products'
  return null
}

/** How long the job has run (or ran): from `startedAt`, which the server recorded. */
export function jobDurationMs(job: Job, now: number = Date.now()): number | null {
  if (!job.startedAt) return null
  const start = Date.parse(job.startedAt)
  const end = job.finishedAt ? Date.parse(job.finishedAt) : now
  if (Number.isNaN(start) || Number.isNaN(end)) return null
  return Math.max(0, end - start)
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return '< 1 s'
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds} s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ${seconds % 60} s`
  const hours = Math.floor(minutes / 60)
  return `${hours} h ${minutes % 60} min`
}

/**
 * What a dataset's live bootstrap, import or refresh is doing, as a button label — "Refreshing…" —
 * or null when none runs. The dataset's own refresh control says it instead of offering a second,
 * concurrent run (J2-F11).
 */
export function datasetBusyLabel(job: Job | null): string | null {
  if (!job || !isLive(job)) return null
  if (job.jobType === 'dataset:refresh') return 'Refreshing…'
  if (job.jobType === 'dataset:import') return 'Importing…'
  return 'Bootstrapping…'
}

/** The failure a person reads: the job's own message, never a blank. */
export function jobFailureReason(job: Job): string {
  return job.errorMessage ?? (job.status === 'CANCELLED' ? 'The job was cancelled.' : 'The job failed without a message.')
}

/** The newest job satisfying `match`, or null. */
export function latestJob(jobs: Iterable<Job>, match: (job: Job) => boolean): Job | null {
  let best: Job | null = null
  for (const job of jobs) {
    if (!match(job)) continue
    if (!best || job.createdAt > best.createdAt) best = job
  }
  return best
}

const datasetIdOf = (job: Job): string | undefined =>
  job.subject?.kind === 'dataset' ? job.subject.id : job.parameters?.datasetId

/** A job that moves a dataset's bootstrap status: bootstrap, import, refresh. */
export function isDatasetJob(job: Job, datasetId: string): boolean {
  if (datasetIdOf(job) !== datasetId) return false
  return job.jobType.startsWith('dataset:') || job.jobType === 'operator:bootstrapping:bootstrap'
}

export function isProfilingJob(job: Job, datasetId: string): boolean {
  return job.jobType === 'operator:profiling:profile' && job.parameters?.datasetId === datasetId
}

export function isProductJob(job: Job, productId: string): boolean {
  return job.jobType === 'product:materialize' && job.parameters?.productId === productId
}

export function isModelJob(job: Job, projectId: string): boolean {
  return (job.jobType === 'model:compile' || job.jobType === 'model:incremental-integrate')
    && (job.projectId === projectId || job.parameters?.projectId === projectId)
}

/**
 * Whether a page following one job (`heldId`, whose latest known state is `held`) should follow
 * `live` instead: nothing is held, or what is held has ended and a different job now runs — a
 * rebuild retried from the jobs drawer while its failed predecessor's card was still up. A held
 * job the store has not seen yet is one this page just started, and is kept.
 */
export function shouldReattach(held: Job | null | undefined, heldId: string | null, live: Job | null): boolean {
  if (!live) return false
  if (!heldId) return true
  if (heldId === live.id) return false
  return !!held && isTerminal(held)
}

export interface ResolvedStatus<S extends string> {
  status: S | 'FAILED'
  /** The last job's failure, when there is one to say. */
  reason: string | null
  /** The stage a live job reported; null when it reported none. */
  stage: string | null
  /** A job is working on it right now. */
  live: boolean
}

/**
 * Register F5: a dataset row saying PENDING is only as true as the job behind it. When that job
 * has ended in failure the row is Failed, with the job's reason — whether the row caught up or a
 * restart left it stranded. A row the server already calls FAILED gets the reason too (J2-F5).
 */
export function resolveDatasetStatus(rowStatus: string | undefined, job: Job | null): ResolvedStatus<string> {
  const status = rowStatus ?? 'PENDING'
  if (!job) return { status, reason: null, stage: null, live: false }
  if (status === 'PENDING' && isFailure(job)) {
    return { status: 'FAILED', reason: jobFailureReason(job), stage: null, live: false }
  }
  if (status === 'FAILED' && isLive(job)) {
    // A retry is running: the row is being bootstrapped again, not failed.
    return { status: 'PENDING', reason: null, stage: job.progressStage, live: true }
  }
  if (status === 'FAILED') {
    return { status, reason: isFailure(job) ? jobFailureReason(job) : null, stage: null, live: false }
  }
  if (status === 'PENDING' && isLive(job)) {
    return { status, reason: null, stage: job.progressStage, live: true }
  }
  return { status, reason: null, stage: null, live: isLive(job) }
}

/**
 * J6-F7: MATERIALIZING is only true while a materialize job is live. A failed job makes the
 * product FAILED with its reason; with no live job, nothing is running, so nothing may keep the
 * refresh button disabled.
 */
export function resolveProductStatus(
  status: string,
  job: Job | null,
): { status: string; reason: string | null; live: boolean } {
  const live = !!job && isLive(job)
  if (status === 'MATERIALIZING' && job && isFailure(job)) {
    return { status: 'FAILED', reason: jobFailureReason(job), live: false }
  }
  return { status, reason: job && isFailure(job) ? jobFailureReason(job) : null, live }
}

/**
 * Whether a product's refresh must wait (J6-F7, register residue of UA4's review): a materialize
 * job is live, or the row says MATERIALIZING and nothing this page knows has ended it — the jobs
 * list has not loaded yet, or holds no finished materialization of this product. Nothing on the
 * server stops two concurrent materializations of one product, so enabling the button on a guess
 * starts a second one. Once the list shows the run finished, the button comes back even if the
 * row still reads MATERIALIZING (a stranded row the startup sweep has not failed).
 */
export function materializeBusy(status: string, latest: Job | null, jobsLoaded: boolean): boolean {
  if (latest && isLive(latest)) return true
  if (status !== 'MATERIALIZING') return false
  return !jobsLoaded || !latest
}

export interface JobSections {
  running: Job[]
  failed: Job[]
  recent: Job[]
}

/**
 * The jobs drawer's three lists, each newest first: what is running, what failed (the thing a
 * person opens the drawer to find), and what finished.
 */
export function jobSections(jobs: Iterable<Job>): JobSections {
  const all = [...jobs].sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0))
  return {
    running: all.filter(isLive),
    failed: all.filter(isFailure),
    recent: all.filter((j) => j.status === 'SUCCEEDED'),
  }
}

/** Failures that ended after `seenAt` — what the top bar's jobs button flags until the drawer is opened. */
export function unseenFailures(jobs: Iterable<Job>, seenAt: number): number {
  let n = 0
  for (const job of jobs) {
    if (!isFailure(job)) continue
    const at = Date.parse(job.finishedAt ?? job.createdAt)
    if (!Number.isNaN(at) && at > seenAt) n += 1
  }
  return n
}

/** The routes a retry can re-invoke — `api`'s own signatures, injected so this stays pure. */
export interface RetryApi {
  bootstrapDataset(datasetId: string): Promise<{ jobId: string }>
  refreshDataset(projectId: string, repoId: string, datasetId: string): Promise<{ jobId: string }>
  materializeProduct(projectId: string, productId: string): Promise<{ jobId: string }>
  compileModel(projectId: string): Promise<{ jobId: string }>
  incrementalIntegrate(projectId: string, datasetId: string): Promise<{ jobId: string }>
  discoverConstraints(projectId: string, body: { datasetId: string; classIri: string }): Promise<{ jobId: string }>
  submitOperator(operatorId: string, payload: { operation: string; parameters: Record<string, string> }): Promise<{ jobId: string }>
}

export interface RetryAction {
  label: string
  run: () => Promise<{ jobId: string }>
}

/**
 * Run the same operation again, where one exists: the route that started this kind of job, with
 * the ids it was started for. Offered only for a failed or cancelled job, and never guessed — an
 * import whose repository is unknown, or a rebuild without a project, gets no retry at all.
 */
export function retryFor(job: Job, ops: RetryApi): RetryAction | null {
  if (!isFailure(job)) return null
  const p = job.parameters ?? {}
  const projectId = job.projectId ?? p.projectId ?? null
  const datasetId = datasetIdOf(job) ?? null
  switch (job.jobType) {
    case 'dataset:bootstrap':
    case 'operator:bootstrapping:bootstrap':
      if (!datasetId) return null
      return { label: 'Retry bootstrap', run: () => ops.bootstrapDataset(datasetId) }
    case 'dataset:import':
    case 'dataset:refresh': {
      // An import registers its upstream as a refresh policy, so re-reading it IS the retry.
      const repositoryId = job.subject?.repositoryId ?? null
      if (!datasetId || !projectId || !repositoryId) return null
      return {
        label: job.jobType === 'dataset:import' ? 'Retry import' : 'Retry refresh',
        run: () => ops.refreshDataset(projectId, repositoryId, datasetId),
      }
    }
    case 'product:materialize':
      if (!projectId || !p.productId) return null
      return { label: 'Retry materialize', run: () => ops.materializeProduct(projectId, p.productId) }
    case 'model:compile':
      if (!projectId) return null
      return { label: 'Retry rebuild', run: () => ops.compileModel(projectId) }
    case 'model:incremental-integrate':
      if (!projectId || !datasetId) return null
      return { label: 'Retry add', run: () => ops.incrementalIntegrate(projectId, datasetId) }
    case 'model:discover-constraints':
      if (!projectId || !datasetId || !p.classIri) return null
      return {
        label: 'Retry discovery',
        run: () => ops.discoverConstraints(projectId, { datasetId, classIri: p.classIri }),
      }
  }
  const parts = job.jobType.split(':')
  if (parts[0] === 'operator' && parts.length >= 3) {
    const operatorId = parts[1]
    const operation = parts.slice(2).join(':')
    return { label: 'Retry', run: () => ops.submitOperator(operatorId, { operation, parameters: { ...p } }) }
  }
  return null
}

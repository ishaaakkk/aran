import { fanOut, type SourceFailure } from './fanOut'
import type { Dataset, DataRepository, Project } from '@/services/types'

/**
 * The aggregate loads behind the Repositories and Datasets lists, as functions of their inputs so
 * the one question that matters — "is what the list shows everything there is?" — is decided
 * here and tested, not inside a view effect (UI audit J8-F2, J2-F22, FRONTEND-6).
 *
 * Three outcomes, never conflated:
 * - `error` set: nothing shown can be trusted (the project list failed, or every source did) —
 *   the view renders its error state with Retry, never its empty state.
 * - `failures` non-empty: some sources answered — show their rows under a "N of M could not be
 *   read" banner, and call nothing "missing". With no rows at all this is still not "empty":
 *   {@link emptyListClaim} is what a view's body asks before it may say "No … yet".
 * - neither: the list is complete, and an empty one really is empty.
 */
export interface ProjectSource {
  /** Make sure the project list is loaded; never throws. */
  ensureProjects: () => Promise<{ ok: boolean; error?: unknown }>
  projects: () => Project[]
}

/**
 * What a list's body may say when no row is on screen (J8-F2, J2-F22). Only `empty` may say
 * "No … yet" and offer to create one; every other arm is a statement about the LOAD, not about
 * the tenant's data.
 * - `loading` / `error` — nothing has answered yet, or nothing shown could be trusted.
 * - `filtered` — rows loaded, and the search or type filter hides all of them.
 * - `incomplete` — the sources that answered hold nothing, and at least one source did not
 *   answer: the rows may be exactly the ones that failed to load, so "none" would be a guess.
 * - `empty` — every source answered and none holds anything.
 */
export type EmptyListClaim = 'loading' | 'error' | 'filtered' | 'incomplete' | 'empty'

export function emptyListClaim(state: {
  loading: boolean
  error: unknown
  failures: readonly SourceFailure[]
  /** Rows loaded before any search or filter. */
  loaded: number
}): EmptyListClaim {
  if (state.loading) return 'loading'
  if (state.error != null) return 'error'
  if (state.loaded > 0) return 'filtered'
  return state.failures.length > 0 ? 'incomplete' : 'empty'
}

export interface RepositoryListLoad {
  repositories: DataRepository[]
  failures: SourceFailure[]
  /** Projects whose repository list could not be read — their repositories are unknown, not none. */
  unreadProjectIds: string[]
  error: unknown
}

export async function loadRepositoryList(
  source: ProjectSource,
  listRepositories: (projectId: string) => Promise<DataRepository[]>,
): Promise<RepositoryListLoad> {
  const outcome = await source.ensureProjects()
  if (!outcome.ok) {
    return { repositories: [], failures: [], unreadProjectIds: [], error: outcome.error ?? new Error('Failed to load projects') }
  }
  const projects = source.projects()
  const unreadProjectIds: string[] = []
  const { items, failures } = await fanOut(projects, (p) => p.name, async (p) => {
    try {
      return await listRepositories(p.id)
    } catch (error: unknown) {
      unreadProjectIds.push(p.id)
      throw error
    }
  })
  const everyOneFailed = projects.length > 0 && failures.length === projects.length
  return { repositories: items, failures, unreadProjectIds, error: everyOneFailed ? failures[0].error : null }
}

/**
 * The Repositories page: the list first, the per-repository dataset counts after it (review of
 * UA2). `onList` runs as soon as the list is known, before any count is asked for — the list, and
 * the Register button gated on it, must not wait on a detail-panel number from a slow source.
 * A count that failed is left out, never shown as 0 (J2-F22).
 */
export async function loadRepositoryPage(
  source: ProjectSource,
  listRepositories: (projectId: string) => Promise<DataRepository[]>,
  listDatasets: (projectId: string, repositoryId: string) => Promise<Dataset[]>,
  onList: (load: RepositoryListLoad) => void,
): Promise<Record<string, number>> {
  const load = await loadRepositoryList(source, listRepositories)
  onList(load)
  if (load.error != null) return {}
  const counts: Record<string, number> = {}
  await Promise.all(load.repositories.map(async (r) => {
    try {
      counts[r.id] = (await listDatasets(r.projectId, r.id)).length
    } catch {
      // no count rather than a false one
    }
  }))
  return counts
}

/**
 * What step 1 of the dataset wizard may say about the chosen project's repositories: `unread` when
 * its repository list failed to load (not "has no repository" — that sent a user to register a
 * second one), `none` only when it loaded empty.
 */
export function projectRepositoryClaim(
  projectId: string,
  repositories: readonly DataRepository[],
  unreadProjectIds: readonly string[],
): 'some' | 'none' | 'unread' {
  if (repositories.some((r) => r.projectId === projectId)) return 'some'
  return unreadProjectIds.includes(projectId) ? 'unread' : 'none'
}

export interface ProjectDatasetLoad {
  datasets: Array<Dataset & { repoName: string }>
  failures: SourceFailure[]
  repositoryCount: number
  error: unknown
}

/**
 * One project's datasets, as Alignments lists them (J4-F24): the repositories in parallel, not one
 * after another, and a failure kept rather than turned into "0 datasets found".
 */
export async function loadProjectDatasets(
  projectId: string,
  listRepositories: (projectId: string) => Promise<DataRepository[]>,
  listDatasets: (projectId: string, repositoryId: string) => Promise<Dataset[]>,
): Promise<ProjectDatasetLoad> {
  let repositories: DataRepository[]
  try {
    repositories = await listRepositories(projectId)
  } catch (error: unknown) {
    return { datasets: [], failures: [], repositoryCount: 0, error }
  }
  const { items, failures } = await fanOut(repositories, (r) => r.name, async (r) =>
    (await listDatasets(projectId, r.id)).map((ds) => ({ ...ds, repoName: r.name })))
  const everyOneFailed = repositories.length > 0 && failures.length === repositories.length
  return { datasets: items, failures, repositoryCount: repositories.length, error: everyOneFailed ? failures[0].error : null }
}

export interface DatasetRow extends Dataset {
  projectId: string
  projectName: string
  projectColor: string
  repoName: string
  repoType: DataRepository['type']
}

export interface DatasetListLoad {
  repositories: DataRepository[]
  /** See {@link RepositoryListLoad.unreadProjectIds}. */
  unreadProjectIds: string[]
  datasets: DatasetRow[]
  /** Named `project` or `project / repository`, whichever level failed. */
  failures: SourceFailure[]
  /** How many sources were asked, for "N of M" — projects plus the repositories reached. */
  sources: number
  error: unknown
}

export async function loadDatasetList(
  source: ProjectSource,
  listRepositories: (projectId: string) => Promise<DataRepository[]>,
  listDatasets: (projectId: string, repositoryId: string) => Promise<Dataset[]>,
): Promise<DatasetListLoad> {
  const repos = await loadRepositoryList(source, listRepositories)
  if (repos.error) {
    return { repositories: [], unreadProjectIds: repos.unreadProjectIds, datasets: [], failures: repos.failures, sources: 0, error: repos.error }
  }
  const projects = source.projects()
  const projectOf = new Map(projects.map((p) => [p.id, p]))
  const { items, failures } = await fanOut(
    repos.repositories,
    (r) => `${projectOf.get(r.projectId)?.name ?? 'project'} / ${r.name}`,
    async (r) => {
      const p = projectOf.get(r.projectId)
      const list = await listDatasets(r.projectId, r.id)
      return list.map((ds): DatasetRow => ({
        ...ds,
        projectId: r.projectId,
        projectName: p?.name ?? 'Unknown',
        projectColor: p?.color || '#8B5CF6',
        repoName: r.name,
        repoType: r.type,
      }))
    },
  )
  const allFailures = [...repos.failures, ...failures]
  const sources = projects.length + repos.repositories.length
  const everyRepositoryFailed = repos.repositories.length > 0 && failures.length === repos.repositories.length
  return {
    repositories: repos.repositories,
    unreadProjectIds: repos.unreadProjectIds,
    datasets: items,
    failures: allFailures,
    sources,
    error: everyRepositoryFailed && repos.failures.length === 0 ? failures[0].error : null,
  }
}

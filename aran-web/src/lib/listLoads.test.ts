import { describe, expect, it } from 'vitest'
import {
  emptyListClaim, loadDatasetList, loadProjectDatasets, loadRepositoryList, loadRepositoryPage, projectRepositoryClaim,
} from './listLoads'
import type { Dataset, DataRepository, Project } from '@/services/types'

const project = (id: string, name: string) => ({ id, name, color: '#123456' }) as Project
const repo = (id: string, projectId: string, name: string) =>
  ({ id, projectId, name, type: 'FILE_SYSTEM' }) as DataRepository
const dataset = (id: string, repositoryId: string, name: string) =>
  ({ id, repositoryId, name, description: '' }) as Dataset

const loaded = (projects: Project[]) => ({ ensureProjects: async () => ({ ok: true }), projects: () => projects })
const boom = (what: string) => new Error(`${what} failed`)

describe('loadRepositoryList (J8-F2 — the Repositories error state is reachable)', () => {
  it('a failed project list is an error, not an empty list', async () => {
    const err = boom('projects')
    const result = await loadRepositoryList(
      { ensureProjects: async () => ({ ok: false, error: err }), projects: () => [] },
      async () => [],
    )
    expect(result.error).toBe(err)
    expect(result.repositories).toEqual([])
  })

  it('one failing project is named, and the others still show', async () => {
    const result = await loadRepositoryList(
      loaded([project('p1', 'papers'), project('p2', 'health')]),
      async (projectId) => {
        if (projectId === 'p2') throw boom('health')
        return [repo('r1', 'p1', 'files')]
      },
    )
    expect(result.error).toBeNull()
    expect(result.repositories.map((r) => r.name)).toEqual(['files'])
    expect(result.failures.map((f) => f.name)).toEqual(['health'])
  })

  it('every project failing is the error state — nothing on screen would be true', async () => {
    const result = await loadRepositoryList(loaded([project('p1', 'papers')]), async () => { throw boom('papers') })
    expect(result.error).toBeInstanceOf(Error)
  })

  it('a tenant with no projects is an honest empty list', async () => {
    const result = await loadRepositoryList(loaded([]), async () => [])
    expect(result).toEqual({ repositories: [], failures: [], unreadProjectIds: [], error: null })
  })
})

describe('loadProjectDatasets (J4-F24 — Alignments never says "0 datasets found" for a failure)', () => {
  it('a failed repository list is an error', async () => {
    const err = boom('repositories')
    const result = await loadProjectDatasets('p1', async () => { throw err }, async () => [])
    expect(result.error).toBe(err)
    expect(result.datasets).toEqual([])
  })

  it('reads the repositories in parallel and names the one that failed', async () => {
    const started: string[] = []
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    const pending = loadProjectDatasets(
      'p1',
      async () => [repo('r1', 'p1', 'files'), repo('r2', 'p1', 'warehouse')],
      async (_p, repositoryId) => {
        started.push(repositoryId)
        await gate
        if (repositoryId === 'r2') throw boom('warehouse')
        return [dataset('d1', 'r1', 'acm.csv')]
      },
    )
    await new Promise((r) => setTimeout(r, 0))
    expect(started, 'both requests are in flight before either answers').toEqual(['r1', 'r2'])
    release()

    const result = await pending
    expect(result.error).toBeNull()
    expect(result.datasets.map((d) => [d.name, d.repoName])).toEqual([['acm.csv', 'files']])
    expect(result.failures.map((f) => f.name)).toEqual(['warehouse'])
  })
})

describe('loadDatasetList (J2-F22 — "No datasets found" only when that is true)', () => {
  it('a failing repository is named with its project, and its siblings\' datasets still show', async () => {
    const result = await loadDatasetList(
      loaded([project('p1', 'papers')]),
      async () => [repo('r1', 'p1', 'files'), repo('r2', 'p1', 'warehouse')],
      async (_p, repositoryId) => {
        if (repositoryId === 'r2') throw boom('warehouse')
        return [dataset('d1', 'r1', 'acm.csv')]
      },
    )
    expect(result.error).toBeNull()
    expect(result.datasets.map((d) => [d.name, d.projectName, d.repoName])).toEqual([['acm.csv', 'papers', 'files']])
    expect(result.failures.map((f) => f.name)).toEqual(['papers / warehouse'])
    expect(result.sources).toBe(3)
  })

  it('a failed project list is an error for the datasets list too', async () => {
    const err = boom('projects')
    const result = await loadDatasetList(
      { ensureProjects: async () => ({ ok: false, error: err }), projects: () => [] },
      async () => [], async () => [],
    )
    expect(result.error).toBe(err)
  })

  it('every repository failing is the error state', async () => {
    const result = await loadDatasetList(
      loaded([project('p1', 'papers')]),
      async () => [repo('r1', 'p1', 'files')],
      async () => { throw boom('files') },
    )
    expect(result.error).toBeInstanceOf(Error)
  })
})

/**
 * Review of UA2 (MAJOR): some sources fail and the ones that answered hold no rows. The loads
 * reported that correctly (error null, 0 rows, 1 failure), but the views branched only on loading
 * and error, so /datasets and /repositories showed "No … yet" and a Register button under the
 * "this list is incomplete" banner — J2-F22's duplicate-registration scenario, back.
 */
describe('emptyListClaim — a partial load with no rows is never "empty"', () => {
  const papersFailsHealthIsEmpty = [project('p1', 'papers'), project('p2', 'health')]
  const listRepositories = async (projectId: string) => {
    if (projectId === 'p1') throw boom('papers')
    return []
  }

  it('the Repositories list: the answering project is empty, the failing one unknown', async () => {
    const load = await loadRepositoryList(loaded(papersFailsHealthIsEmpty), listRepositories)
    expect(load.error).toBeNull()
    expect(emptyListClaim({ loading: false, error: load.error, failures: load.failures, loaded: load.repositories.length }))
      .toBe('incomplete')
  })

  it('the Datasets list, same tenant', async () => {
    const load = await loadDatasetList(loaded(papersFailsHealthIsEmpty), listRepositories, async () => [])
    expect(load.error).toBeNull()
    expect(emptyListClaim({ loading: false, error: load.error, failures: load.failures, loaded: load.datasets.length }))
      .toBe('incomplete')
  })

  it('only a complete, empty load may say "none"', () => {
    expect(emptyListClaim({ loading: false, error: null, failures: [], loaded: 0 })).toBe('empty')
  })

  it('loading and error come first; loaded rows hidden by a filter are "filtered", failures or not', () => {
    const failures = [{ name: 'papers', error: boom('papers') }]
    expect(emptyListClaim({ loading: true, error: null, failures, loaded: 0 })).toBe('loading')
    expect(emptyListClaim({ loading: false, error: boom('all'), failures, loaded: 0 })).toBe('error')
    expect(emptyListClaim({ loading: false, error: null, failures, loaded: 3 })).toBe('filtered')
    expect(emptyListClaim({ loading: false, error: null, failures: [], loaded: 3 })).toBe('filtered')
  })
})

describe('projectRepositoryClaim — the dataset wizard never calls an unread project empty', () => {
  it('a project whose repository list failed is "unread", not "has no repository yet"', async () => {
    const load = await loadDatasetList(
      loaded([project('p1', 'papers'), project('p2', 'health'), project('p3', 'labs')]),
      async (projectId) => {
        if (projectId === 'p1') throw boom('papers')
        return projectId === 'p3' ? [repo('r3', 'p3', 'uploads')] : []
      },
      async () => [],
    )
    expect(load.unreadProjectIds).toEqual(['p1'])
    expect(projectRepositoryClaim('p1', load.repositories, load.unreadProjectIds)).toBe('unread')
    expect(projectRepositoryClaim('p2', load.repositories, load.unreadProjectIds)).toBe('none')
    expect(projectRepositoryClaim('p3', load.repositories, load.unreadProjectIds)).toBe('some')
  })
})

describe('loadRepositoryPage — the list does not wait on the counts', () => {
  it('hands the list over before any count has answered', async () => {
    let listed = false
    let release!: () => void
    const gate = new Promise<void>((r) => { release = r })
    const page = loadRepositoryPage(
      loaded([project('p1', 'papers')]),
      async () => [repo('r1', 'p1', 'files'), repo('r2', 'p1', 'warehouse')],
      async (_p, repositoryId) => {
        await gate
        if (repositoryId === 'r2') throw boom('warehouse')
        return [dataset('d1', 'r1', 'acm.csv')]
      },
      (load) => {
        listed = true
        expect(load.repositories.map((r) => r.name)).toEqual(['files', 'warehouse'])
      },
    )
    await new Promise((r) => setTimeout(r, 0))
    expect(listed, 'the list (and Register) is live while a count is still pending').toBe(true)
    release()
    expect(await page, 'a failed count is left out, never 0').toEqual({ r1: 1 })
  })

  it('asks for no count when the list itself failed', async () => {
    let asked = false
    const counts = await loadRepositoryPage(
      loaded([project('p1', 'papers')]),
      async () => { throw boom('papers') },
      async () => { asked = true; return [] },
      () => {},
    )
    expect(counts).toEqual({})
    expect(asked).toBe(false)
  })
})

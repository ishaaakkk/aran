import { describe, expect, it, vi } from 'vitest'
import { createModelSession, type ModelSessionApi } from './modelSession'
import { wrapperSeedSelection, type AggregatedDataset } from './types'
import type { IntegrationModelBundle, ModelSource, QueryCatalog } from '@/services/types'

/**
 * Third review of UA2 (MAJOR, J4-F11): a wrapper save of one dataset that answered while another
 * was open re-read the saved dataset's model into the page. Reproduced in Chromium: open acm, edit,
 * Save with the PUT pending; open dblp — its Save already read "Saving…" and refused a click; type
 * into dblp's editor; acm's PUT answers — dblp's tab lists acm's class, its editor holds acm's
 * wrapper, and the unsaved dblp edit is gone. These drive that sequence against the store the view
 * renders from.
 */

const ACM_CLASS = 'https://example.org/acm#Paper'
const DBLP_CLASS = 'https://example.org/dblp#Article'

function dataset(id: string): AggregatedDataset {
  return {
    id, name: id, repositoryId: 'repo-1', snapshotId: 'snap-1', bootstrapStatus: 'READY', idx: '01',
    projectName: 'ACM', projectColor: '#8b5cf6', repoName: 'local', repoType: 'LOCAL_FILE', projectId: 'p1',
  } as AggregatedDataset
}
const acm = dataset('acm')
const dblp = dataset('dblp')

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (err: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const timeout = () => Object.assign(
  new Error('This is still working on the server — the page stopped waiting after 150 s. Check the result before trying again.'),
  { kind: 'timeout', mayStillBeRunning: true, correlationId: 'corr-1' })

/** One project holding both datasets, as the reviewer's harness did. `stored` is the server. */
function fakeServer() {
  const stored: Record<string, string> = {
    [ACM_CLASS]: 'SELECT * FROM ${SNAPSHOT}',
    [DBLP_CLASS]: 'SELECT id FROM ${SNAPSHOT}',
  }
  const sources = (): ModelSource[] => [
    { id: 's-acm', datasetId: 'acm', classIri: ACM_CLASS, wrapperSql: stored[ACM_CLASS] },
    { id: 's-dblp', datasetId: 'dblp', classIri: DBLP_CLASS, wrapperSql: stored[DBLP_CLASS] },
  ]
  const bundle = (): IntegrationModelBundle => ({
    model: {
      id: 'm1', projectId: 'p1', createdAt: '', updatedAt: '', activeIntegrationRunId: null, rebuildRequired: false,
    },
    sources: sources(), mappings: [], constraints: [], integratedDatasetIds: [],
  } as IntegrationModelBundle)
  const catalog = { empty: true, classes: [] } as unknown as QueryCatalog
  const api = {
    getModel: vi.fn(async () => bundle()),
    queryEngineCatalog: vi.fn(async () => catalog),
    setModelWrapper: vi.fn(async () => {}),
    addModelSource: vi.fn(async () => [] as ModelSource[]),
  }
  return { stored, bundle, api, typed: api as unknown as ModelSessionApi }
}

function session() {
  const server = fakeServer()
  const notify = vi.fn()
  const store = createModelSession({ api: server.typed, notify })
  return { server, notify, store, state: () => store.getState() }
}

describe('a wrapper save that answers after another dataset was opened', () => {
  it('leaves the open dataset\'s class list, selection and editor exactly as they were', async () => {
    const { server, notify, store, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT title FROM ${SNAPSHOT}')
    const put = deferred<void>()
    server.api.setModelWrapper.mockReturnValueOnce(put.promise)
    const saving = state().saveWrapper()

    await state().open(dblp)
    expect(state().modelSources.map((s) => s.classIri)).toEqual([DBLP_CLASS])
    expect(state().wrapperText).toBe('SELECT id FROM ${SNAPSHOT}')
    state().setWrapperText('SELECT id, title FROM ${SNAPSHOT}')

    server.stored[ACM_CLASS] = 'SELECT title FROM ${SNAPSHOT}'
    put.resolve()
    await saving

    expect(state().dataset?.id).toBe('dblp')
    expect(state().modelSources.map((s) => s.classIri)).toEqual([DBLP_CLASS])
    expect(state().selectedWrapperClass).toBe(DBLP_CLASS)
    expect(state().wrapperText).toBe('SELECT id, title FROM ${SNAPSHOT}')
    expect(state().seededSelection).toBe(wrapperSeedSelection('dblp', DBLP_CLASS))
    expect(notify).toHaveBeenCalledWith('acm: Wrapper SQL saved to the integration model.', 'success')
    expect(store.getState().savingWrappers).toEqual({})
  })

  it('checks a timed-out save against acm\'s model without writing that model onto dblp', async () => {
    const { server, notify, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT title FROM ${SNAPSHOT}')
    const put = deferred<void>()
    server.api.setModelWrapper.mockReturnValueOnce(put.promise)
    const saving = state().saveWrapper()

    await state().open(dblp)
    state().setWrapperText('SELECT id, title FROM ${SNAPSHOT}')
    server.stored[ACM_CLASS] = 'SELECT title FROM ${SNAPSHOT}'
    put.reject(timeout())
    await saving

    expect(state().modelSources.map((s) => s.classIri)).toEqual([DBLP_CLASS])
    expect(state().selectedWrapperClass).toBe(DBLP_CLASS)
    expect(state().wrapperText).toBe('SELECT id, title FROM ${SNAPSHOT}')
    // The answer is kept for acm's selection, and said now, naming acm.
    expect(state().saveChecks[wrapperSeedSelection('acm', ACM_CLASS)!]?.check.kind).toBe('saved')
    expect(state().saveChecks[wrapperSeedSelection('dblp', DBLP_CLASS)!]).toBeUndefined()
    expect(notify).toHaveBeenCalledWith(expect.stringMatching(/^acm: Saved — /), 'success')
  })

  it('keeps a "not stored yet" answer for acm until acm is reopened, and says where it is', async () => {
    const { server, notify, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT title FROM ${SNAPSHOT}')
    const put = deferred<void>()
    server.api.setModelWrapper.mockReturnValueOnce(put.promise)
    const saving = state().saveWrapper()
    await state().open(dblp)
    put.reject(timeout())
    await saving
    expect(notify).toHaveBeenCalledWith(expect.stringMatching(/^acm: Not stored yet — .*Its Wrapper tab keeps this note, with Check again\.$/), 'error')

    await state().open(acm)
    expect(state().saveChecks[wrapperSeedSelection('acm', ACM_CLASS)!]?.check.kind).toBe('not-yet')
  })

  it('drops a "saved" answer once its selection is left — it is done', async () => {
    const { server, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT title FROM ${SNAPSHOT}')
    server.api.setModelWrapper.mockRejectedValueOnce(timeout())
    server.stored[ACM_CLASS] = 'SELECT title FROM ${SNAPSHOT}'
    await state().saveWrapper()
    expect(state().saveChecks[wrapperSeedSelection('acm', ACM_CLASS)!]?.check.kind).toBe('saved')
    await state().open(dblp)
    expect(state().saveChecks).toEqual({})
  })

  it('does not make dblp\'s Save busy, nor refuse it, while acm\'s is in flight', async () => {
    const { server, state } = session()
    await state().open(acm)
    const put = deferred<void>()
    server.api.setModelWrapper.mockReturnValueOnce(put.promise)
    const saving = state().saveWrapper()

    await state().open(dblp)
    expect(wrapperSeedSelection('dblp', DBLP_CLASS)! in state().savingWrappers).toBe(false)
    state().setWrapperText('SELECT id, title FROM ${SNAPSHOT}')
    await state().saveWrapper()
    expect(server.api.setModelWrapper).toHaveBeenLastCalledWith('p1', 'dblp', DBLP_CLASS, 'SELECT id, title FROM ${SNAPSHOT}')

    put.resolve()
    await saving
  })

  it('still refuses a second save of the same class while the first is in flight', async () => {
    const { server, state } = session()
    await state().open(acm)
    const put = deferred<void>()
    server.api.setModelWrapper.mockReturnValueOnce(put.promise)
    const first = state().saveWrapper()
    await state().saveWrapper()
    expect(server.api.setModelWrapper).toHaveBeenCalledTimes(1)
    put.resolve()
    await first
  })
})

describe('a save still in flight when its dataset is reopened (fourth review of UA2)', () => {
  it('seeds the editor with the SQL being saved, so the next Save cannot revert it', async () => {
    const { server, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT title FROM ${SNAPSHOT}')
    const put = deferred<void>()
    server.api.setModelWrapper.mockReturnValueOnce(put.promise)
    const saving = state().saveWrapper()

    await state().open(dblp)
    await state().open(acm)
    expect(state().wrapperText).toBe('SELECT title FROM ${SNAPSHOT}')
    expect(wrapperSeedSelection('acm', ACM_CLASS)! in state().savingWrappers).toBe(true)

    server.stored[ACM_CLASS] = 'SELECT title FROM ${SNAPSHOT}'
    put.resolve()
    await saving
    expect(state().wrapperText).toBe('SELECT title FROM ${SNAPSHOT}')
    await state().saveWrapper()
    expect(server.api.setModelWrapper).toHaveBeenLastCalledWith('p1', 'acm', ACM_CLASS, 'SELECT title FROM ${SNAPSHOT}')
  })

  it('does the same for a timed-out save that is not yet decided', async () => {
    const { server, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT venue FROM ${SNAPSHOT}')
    server.api.setModelWrapper.mockRejectedValueOnce(timeout())
    await state().saveWrapper()
    expect(state().saveChecks[wrapperSeedSelection('acm', ACM_CLASS)!]?.check.kind).not.toBe('saved')

    await state().open(dblp)
    await state().open(acm)
    expect(state().wrapperText).toBe('SELECT venue FROM ${SNAPSHOT}')
  })
})

describe('a model read that answers after another dataset was opened', () => {
  it('writes nothing, and cannot end the loading state of the dataset now on screen', async () => {
    const { server, state } = session()
    const slow = deferred<IntegrationModelBundle>()
    server.api.getModel.mockReturnValueOnce(slow.promise)
    const opening = state().open(acm)

    const dblpRead = deferred<IntegrationModelBundle>()
    server.api.getModel.mockReturnValueOnce(dblpRead.promise)
    const openingDblp = state().open(dblp)
    slow.resolve(server.bundle())
    await opening
    expect(state().modelSources).toEqual([])
    expect(state().modelLoading).toBe(true)

    dblpRead.resolve(server.bundle())
    await openingDblp
    expect(state().modelSources.map((s) => s.classIri)).toEqual([DBLP_CLASS])
    expect(state().modelLoading).toBe(false)
  })

  it('is dropped when a newer read of the same dataset has already landed', async () => {
    const { server, state } = session()
    await state().open(acm)
    const older = deferred<IntegrationModelBundle>()
    server.api.getModel.mockReturnValueOnce(older.promise)
    const olderRead = state().reload()
    const staleBundle = server.bundle()

    server.stored[ACM_CLASS] = 'SELECT title FROM ${SNAPSHOT}'
    await state().reload()
    older.resolve(staleBundle)
    await olderRead

    expect(state().modelSources[0]?.wrapperSql).toBe('SELECT title FROM ${SNAPSHOT}')
    expect(state().modelLoading).toBe(false)
  })

  it('never re-seeds the editor for a re-read of the same selection (V49)', async () => {
    const { server, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT typed FROM ${SNAPSHOT}')
    server.stored[ACM_CLASS] = 'SELECT changed FROM ${SNAPSHOT}'
    await state().reload()
    expect(state().wrapperText).toBe('SELECT typed FROM ${SNAPSHOT}')
  })
})

describe('an enrol that answers after another dataset was opened', () => {
  it('leaves the open dataset alone, is keyed to its own dataset, and names it', async () => {
    const { server, notify, state } = session()
    await state().open(acm)
    const post = deferred<ModelSource[]>()
    server.api.addModelSource.mockReturnValueOnce(post.promise)
    const enrolling = state().enrol()
    expect(state().enrolling).toEqual({ acm: true })

    await state().open(dblp)
    expect('dblp' in state().enrolling).toBe(false)
    post.resolve([])
    await enrolling

    expect(state().modelSources.map((s) => s.classIri)).toEqual([DBLP_CLASS])
    expect(state().enrolling).toEqual({})
    expect(notify).toHaveBeenCalledWith('acm: Dataset enrolled in the integration model.', 'success')
  })
})

/**
 * Third review of UA2 (MINOR): an enrol that timed out and had not landed was a 4 s toast, after
 * which Enroll was clickable again — and a second enrol of a dataset the first then enrols is
 * refused.
 */
describe('an enrol the page stopped waiting for', () => {
  function unenrolled() {
    const s = session()
    const bundle = s.server.bundle
    s.server.api.getModel.mockImplementation(async () => ({ ...bundle(), sources: bundle().sources.filter((x) => x.datasetId !== 'acm') }))
    return s
  }

  it('leaves a "not enrolled yet" note on the dataset, and Enroll is refused while it is re-checked', async () => {
    const { server, notify, state } = unenrolled()
    await state().open(acm)
    server.api.addModelSource.mockRejectedValueOnce(timeout())
    await state().enrol()
    expect(state().enrolChecks.acm?.kind).toBe('not-yet')
    expect(notify).not.toHaveBeenCalled()

    const read = deferred<IntegrationModelBundle>()
    server.api.getModel.mockReturnValueOnce(read.promise)
    const checking = state().recheckEnrol()
    expect(state().checkingEnrols).toEqual({ acm: true })
    await state().enrol()
    expect(server.api.addModelSource).toHaveBeenCalledTimes(1)

    read.resolve(server.bundle())
    await checking
    expect(state().enrolChecks).toEqual({})
    expect(state().modelSources.map((s) => s.classIri)).toEqual([ACM_CLASS])
    expect(notify).toHaveBeenCalledWith('Dataset enrolled — the server finished after the page stopped waiting.', 'success')
  })

  it('says it could not tell when the model could not be read, and settles on the next read that succeeds', async () => {
    const { server, state } = unenrolled()
    await state().open(acm)
    server.api.addModelSource.mockRejectedValueOnce(timeout())
    server.api.getModel.mockRejectedValueOnce(new Error('boom'))
    await state().enrol()
    expect(state().enrolChecks.acm?.kind).toBe('unknown')
    await state().reload()
    expect(state().enrolChecks.acm?.kind).toBe('not-yet')
  })
})

/**
 * Third review of UA2 (MINOR): a save that timed out while the model could not be read left
 * "the model could not be read to check whether it landed" standing after a Retry had read it.
 */
describe('an unresolved save check', () => {
  const key = wrapperSeedSelection('acm', ACM_CLASS)!

  it('is re-decided by the next read of its dataset that succeeds, whatever made that read', async () => {
    const { server, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT title FROM ${SNAPSHOT}')
    server.api.setModelWrapper.mockRejectedValueOnce(timeout())
    server.api.getModel.mockRejectedValueOnce(Object.assign(new Error('boom'), { kind: 'server' }))
    await state().saveWrapper()
    expect(state().saveChecks[key]?.check.kind).toBe('unknown')
    expect(state().modelError).not.toBeNull()

    server.stored[ACM_CLASS] = 'SELECT title FROM ${SNAPSHOT}'
    await state().reload()
    expect(state().modelError).toBeNull()
    expect(state().saveChecks[key]?.check.kind).toBe('saved')
  })

  it('moves from "could not be read" to "not stored yet" when the read finds the old wrapper', async () => {
    const { server, state } = session()
    await state().open(acm)
    state().setWrapperText('SELECT title FROM ${SNAPSHOT}')
    server.api.setModelWrapper.mockRejectedValueOnce(timeout())
    server.api.getModel.mockRejectedValueOnce(new Error('boom'))
    await state().saveWrapper()
    await state().reload()
    expect(state().saveChecks[key]?.check.kind).toBe('not-yet')
  })
})

/** Third review of UA2 (MINOR): the re-read after a save swapped the editor for a spinner. */
describe('a re-read of a model already on screen', () => {
  it('keeps the tab as it is rather than "loading", so the editor and its Save stay mounted', async () => {
    const { server, state } = session()
    await state().open(acm)
    expect(state().modelLoading).toBe(false)
    const read = deferred<IntegrationModelBundle>()
    server.api.getModel.mockReturnValueOnce(read.promise)
    const saving = state().saveWrapper()
    await Promise.resolve()
    await Promise.resolve()
    expect(server.api.getModel).toHaveBeenCalledTimes(2)
    expect(state().modelLoading).toBe(false)
    read.resolve(server.bundle())
    await saving
  })

  it('is "loading" again only when nothing is on screen — the first read, or a retry after a failure', async () => {
    const { server, state } = session()
    server.api.getModel.mockRejectedValueOnce(new Error('boom'))
    await state().open(acm)
    expect(state().modelError).not.toBeNull()
    const retry = deferred<IntegrationModelBundle>()
    server.api.getModel.mockReturnValueOnce(retry.promise)
    const retrying = state().reload()
    expect(state().modelLoading).toBe(true)
    retry.resolve(server.bundle())
    await retrying
    expect(state().modelLoading).toBe(false)
  })
})

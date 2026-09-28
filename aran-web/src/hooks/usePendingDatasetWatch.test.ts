import { describe, expect, it, vi } from 'vitest'
import type { Job } from '@/services/types'

vi.mock('@/services/api', () => ({ api: {} }))

/**
 * J2-F24: "Forty imports pending: 20 list calls a second plus SSE." The datasets page now watches
 * repositories, not rows — and stops watching a row whose job already failed (register F5).
 */

function job(overrides: Partial<Job> = {}): Job {
  return {
    id: 'j', projectId: 'p', jobType: 'dataset:import', status: 'RUNNING', parameters: { datasetId: 'd1' },
    progressStage: null, progressPercent: null, result: null, errorCode: null, errorMessage: null,
    createdBy: null, createdAt: '2026-09-25T11:00:00Z', startedAt: null, finishedAt: null, subject: null,
    ...overrides,
  }
}

describe('repositoriesToWatch', () => {
  it('watches a repository once, however many of its datasets are pending', async () => {
    const { repositoriesToWatch } = await import('./usePendingDatasetWatch')
    const rows = Array.from({ length: 40 }, (_, i) => ({
      id: `d${i}`, projectId: 'p', repositoryId: 'repo-a', bootstrapStatus: 'PENDING',
    }))
    expect(repositoriesToWatch(rows, [])).toEqual([{ projectId: 'p', repositoryId: 'repo-a' }])
  })

  it('stops watching a pending row whose job already failed', async () => {
    const { repositoriesToWatch } = await import('./usePendingDatasetWatch')
    const rows = [{ id: 'd1', projectId: 'p', repositoryId: 'repo-a', bootstrapStatus: 'PENDING' }]
    expect(repositoriesToWatch(rows, [job({ status: 'FAILED', errorMessage: 'x' })])).toEqual([])
  })

  it('watches a READY dataset while a refresh of it runs, so its row updates when it lands', async () => {
    const { repositoriesToWatch } = await import('./usePendingDatasetWatch')
    const rows = [
      { id: 'd1', projectId: 'p', repositoryId: 'repo-a', bootstrapStatus: 'READY' },
      { id: 'd2', projectId: 'p', repositoryId: 'repo-b', bootstrapStatus: 'READY' },
    ]
    expect(repositoriesToWatch(rows, [job({ jobType: 'dataset:refresh' })]))
      .toEqual([{ projectId: 'p', repositoryId: 'repo-a' }])
  })

  it('leaves settled rows alone', async () => {
    const { repositoriesToWatch } = await import('./usePendingDatasetWatch')
    expect(repositoriesToWatch(
      [{ id: 'd1', projectId: 'p', repositoryId: 'r', bootstrapStatus: 'READY' }], [])).toEqual([])
  })
})

describe('repositoriesSettled', () => {
  const failedRow = [{ id: 'd1', projectId: 'p', repositoryId: 'repo-a', bootstrapStatus: 'FAILED' }]

  it('re-reads a FAILED row once its retry succeeds, though the row left the watch', async () => {
    // Register F5, the row Retry itself: the server keeps the row FAILED until the retry writes
    // READY as it ends, so when SUCCEEDED arrives the row reads FAILED and is no longer watched.
    // Without one last read it said "Failed", with Retry offered again, until a reload.
    const { repositoriesSettled, repositoriesToWatch } = await import('./usePendingDatasetWatch')
    const { newSettleMemo } = await import('./useJobEvents')
    const memo = newSettleMemo()
    const retry = job({ id: 'retry', jobType: 'dataset:bootstrap', createdAt: '2026-09-25T12:00:00Z' })
    const earlier = job({ id: 'first', jobType: 'dataset:bootstrap', status: 'FAILED', errorMessage: 'lake down' })

    expect(repositoriesSettled(memo, failedRow, [earlier, retry])).toEqual([])
    const succeeded = { ...retry, status: 'SUCCEEDED' as const }
    expect(repositoriesToWatch(failedRow, [earlier, succeeded])).toEqual([])
    expect(repositoriesSettled(memo, failedRow, [earlier, succeeded]))
      .toEqual([{ projectId: 'p', repositoryId: 'repo-a' }])
    expect(repositoriesSettled(memo, failedRow, [earlier, succeeded])).toEqual([])
  })

  it('re-reads for a retry this tab started even when it is first seen already finished', async () => {
    const { repositoriesSettled } = await import('./usePendingDatasetWatch')
    const { newSettleMemo } = await import('./useJobEvents')
    const done = job({ id: 'fast', jobType: 'dataset:bootstrap', status: 'SUCCEEDED' })
    expect(repositoriesSettled(newSettleMemo(), failedRow, [done], (id) => id === 'fast'))
      .toEqual([{ projectId: 'p', repositoryId: 'repo-a' }])
  })

  it('reads nothing for history: jobs loaded already finished that nobody here started', async () => {
    const { repositoriesSettled } = await import('./usePendingDatasetWatch')
    const { newSettleMemo } = await import('./useJobEvents')
    const done = job({ id: 'old', jobType: 'dataset:bootstrap', status: 'SUCCEEDED' })
    expect(repositoriesSettled(newSettleMemo(), failedRow, [done])).toEqual([])
  })
})

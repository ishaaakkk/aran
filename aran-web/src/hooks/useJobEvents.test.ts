import { describe, expect, it, vi } from 'vitest'
import type { Job } from '@/services/types'

vi.mock('@/services/api', () => ({ api: {} }))

/**
 * A page shows a job's card only while it runs, so the card is gone in the render that brings the
 * job's success. `settledSince` is how the page still hears the ending — the refresh that remade a
 * dataset (J2-F11), the materialize that rewrote a product (J6-F7) — and only for a job it watched
 * run, never for history it merely loaded.
 */

function job(id: string, status: Job['status']): Job {
  return {
    id, projectId: 'p', jobType: 'dataset:refresh', status, parameters: {}, progressStage: null,
    progressPercent: null, result: null, errorCode: null, errorMessage: null, createdBy: null,
    createdAt: '2026-09-25T11:00:00Z', startedAt: null, finishedAt: null, subject: null,
  }
}

describe('settledSince', () => {
  it('reports a job it saw running once it has finished, exactly once', async () => {
    const { settledSince, newSettleMemo } = await import('./useJobEvents')
    const memo = newSettleMemo()
    expect(settledSince(memo, [job('a', 'RUNNING')])).toEqual([])
    expect(settledSince(memo, [job('a', 'SUCCEEDED')]).map((j) => j.id)).toEqual(['a'])
    expect(settledSince(memo, [job('a', 'SUCCEEDED')])).toEqual([])
  })

  it('reports a failure the same way', async () => {
    const { settledSince, newSettleMemo } = await import('./useJobEvents')
    const memo = newSettleMemo()
    memo.live.add('b')
    expect(settledSince(memo, [job('b', 'FAILED')]).map((j) => j.id)).toEqual(['b'])
  })

  it('does not report history: a job first seen already finished', async () => {
    const { settledSince, newSettleMemo } = await import('./useJobEvents')
    expect(settledSince(newSettleMemo(), [job('c', 'SUCCEEDED'), null, undefined])).toEqual([])
  })

  it('reports, once, a job this tab started that it first sees already finished', async () => {
    // A retry quick enough to end before its stream opened is never seen live, and it is still
    // news: the row it retried must be re-read (register F5).
    const { settledSince, newSettleMemo } = await import('./useJobEvents')
    const memo = newSettleMemo()
    const startedHere = (id: string) => id === 'd'
    expect(settledSince(memo, [job('d', 'SUCCEEDED')], startedHere).map((j) => j.id)).toEqual(['d'])
    expect(settledSince(memo, [job('d', 'SUCCEEDED')], startedHere)).toEqual([])
  })
})

describe('jobsPollInterval', () => {
  it('polls fast only while someone is looking or something is running', async () => {
    const { jobsPollInterval } = await import('./useJobEvents')
    expect(jobsPollInterval(true, false)).toBe(3_000)
    expect(jobsPollInterval(false, true)).toBe(10_000)
    expect(jobsPollInterval(false, false)).toBe(60_000)
  })
})

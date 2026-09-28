import { useEffect, useMemo, useRef } from 'react'
import { api } from '@/services/api'
import type { Dataset, Job } from '@/services/types'
import { isDatasetJob, isLive, latestJob, resolveDatasetStatus } from '@/lib/jobs'
import { newSettleMemo, settledSince, type SettleMemo } from '@/hooks/useJobEvents'
import { wasStartedHere } from '@/stores/jobsStore'

export interface WatchedRow {
  id: string
  projectId: string
  repositoryId: string
  bootstrapStatus?: string
}

export interface RepositoryKey {
  projectId: string
  repositoryId: string
}

/**
 * The repositories whose datasets are mid-change: a row still PENDING (unless its job already
 * failed — register F5), or a row a live job is changing, such as a refresh of a READY dataset
 * (J2-F11). One entry per repository, however many of its rows qualify (J2-F24).
 */
export function repositoriesToWatch(rows: WatchedRow[], jobs: Job[]): RepositoryKey[] {
  const keys = new Map<string, RepositoryKey>()
  for (const row of rows) {
    const job = latestJob(jobs, (j) => isDatasetJob(j, row.id))
    const resolved = resolveDatasetStatus(row.bootstrapStatus, job)
    if (resolved.status === 'PENDING' || resolved.live) {
      keys.set(`${row.projectId}/${row.repositoryId}`, { projectId: row.projectId, repositoryId: row.repositoryId })
    }
  }
  return [...keys.values()]
}

/**
 * The repositories to read once more because the latest job on one of their datasets has just
 * ended (see {@link settledSince}: seen running, or started by this tab).
 *
 * The watch above cannot be the only reader. A row the server marked FAILED stays FAILED while a
 * retry runs — nothing sets it back to PENDING; the job writes READY only as it ends — so when the
 * retry succeeds the row no longer qualifies for watching, and without this last read it went on
 * saying "Failed", with Retry offered again, until a reload (register F5, the row Retry itself).
 */
export function repositoriesSettled(
  memo: SettleMemo,
  rows: WatchedRow[],
  jobs: Job[],
  startedHere: (jobId: string) => boolean = () => false,
): RepositoryKey[] {
  const rowOf = new Map<string, WatchedRow>()
  const rowJobs: Job[] = []
  for (const row of rows) {
    const job = latestJob(jobs, (j) => isDatasetJob(j, row.id))
    if (!job) continue
    rowOf.set(job.id, row)
    rowJobs.push(job)
  }
  const keys = new Map<string, RepositoryKey>()
  for (const job of settledSince(memo, rowJobs, startedHere)) {
    const row = rowOf.get(job.id)!
    keys.set(`${row.projectId}/${row.repositoryId}`, { projectId: row.projectId, repositoryId: row.repositoryId })
  }
  return [...keys.values()]
}

/** How often each watched repository's datasets are re-read. */
export const PENDING_POLL_MS = 3_000

/**
 * Follow datasets that are mid-change until they settle, reading each repository's list once
 * per tick — forty pending imports in one repository are one request every three seconds, where
 * each row used to poll the same list every two seconds on its own (J2-F24). When the latest
 * job on any listed dataset ends, its repository is read once more at once — even when that
 * dataset no longer qualifies for watching ({@link repositoriesSettled}).
 */
export function usePendingDatasetWatch(
  rows: WatchedRow[],
  jobs: Job[],
  onFresh: (fresh: Dataset[]) => void,
): void {
  const keys = useMemo(() => repositoriesToWatch(rows, jobs), [rows, jobs])
  const keysSignature = keys.map((k) => `${k.projectId}/${k.repositoryId}`).sort().join(',')
  const liveSignature = useMemo(() => {
    const watched = new Set(rows.map((r) => r.id))
    return jobs
      .filter((j) => isLive(j) && [...watched].some((id) => isDatasetJob(j, id)))
      .map((j) => j.id)
      .sort()
      .join(',')
  }, [rows, jobs])

  const onFreshRef = useRef(onFresh)
  useEffect(() => {
    onFreshRef.current = onFresh
  })
  const keysRef = useRef(keys)
  useEffect(() => {
    keysRef.current = keys
  })

  // The last read: whichever repository a just-ended job's dataset lives in, re-read now — the
  // watch below may already have let go of it.
  const settleMemo = useRef<SettleMemo | null>(null)
  const rowsRef = useRef(rows)
  const jobsRef = useRef(jobs)
  useEffect(() => {
    rowsRef.current = rows
    jobsRef.current = jobs
  })
  const rowJobsSignature = useMemo(() => rows
    .map((row) => latestJob(jobs, (j) => isDatasetJob(j, row.id)))
    .map((job) => (job ? `${job.id}:${job.status}` : '-'))
    .join(','), [rows, jobs])
  useEffect(() => {
    settleMemo.current ??= newSettleMemo()
    for (const key of repositoriesSettled(settleMemo.current, rowsRef.current, jobsRef.current, wasStartedHere)) {
      api.listDatasets(key.projectId, key.repositoryId)
        .then((fresh) => onFreshRef.current(fresh))
        .catch(() => {
          // The row keeps what it said; the next job event or a reload reads it again.
        })
    }
  }, [rowJobsSignature])

  useEffect(() => {
    if (!keysSignature) return
    let cancelled = false
    const readAll = () => {
      for (const key of keysRef.current) {
        api.listDatasets(key.projectId, key.repositoryId)
          .then((fresh) => {
            if (!cancelled) onFreshRef.current(fresh)
          })
          .catch(() => {
            // A missed tick is retried on the next one; the rows keep saying what they last said.
          })
      }
    }
    const first = setTimeout(readAll, 0)
    const timer = setInterval(readAll, PENDING_POLL_MS)
    return () => {
      cancelled = true
      clearTimeout(first)
      clearInterval(timer)
    }
  }, [keysSignature, liveSignature])
}

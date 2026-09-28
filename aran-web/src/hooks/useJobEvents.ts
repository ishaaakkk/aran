import { useEffect, useRef } from 'react'
import type { Job } from '@/services/types'
import { useJobsStore, wasStartedHere } from '@/stores/jobsStore'
import { isLive, isTerminal } from '@/lib/jobs'

/**
 * One job, live, for as long as the component showing it is mounted.
 *
 * Reads the job from the jobs store and follows it there, so every card for one job shares one
 * stream, and a card mounted after a navigation shows the job's current state at once instead of
 * starting from nothing (UI audit J8-F7, "job cards survive navigation").
 *
 * `onTerminal` fires once per job, the first time this component sees it in a terminal state —
 * including a job that had already ended before the card mounted, so a re-attached card still
 * lets its page re-read the object the job changed.
 */
export function useJobEvents(jobId: string | null | undefined, onTerminal?: (job: Job) => void): Job | null {
  const job = useJobsStore((s) => (jobId ? s.jobs[jobId] : undefined)) ?? null
  const follow = useJobsStore((s) => s.follow)

  useEffect(() => {
    if (!jobId) return
    return follow(jobId)
  }, [jobId, follow])

  const onTerminalRef = useRef(onTerminal)
  useEffect(() => {
    onTerminalRef.current = onTerminal
  })
  const notifiedFor = useRef<string | null>(null)
  useEffect(() => {
    if (job && isTerminal(job) && notifiedFor.current !== job.id) {
      notifiedFor.current = job.id
      onTerminalRef.current?.(job)
    }
  }, [job])

  return job
}

/**
 * Call `onSettled` once for each of `jobs` this component saw live and then sees finished — or
 * that this tab started and it first sees finished.
 *
 * A page shows a job's card only while it runs (or failed), so the card unmounts in the very
 * render that brings the job's success — before its own `onTerminal` could fire. This is the
 * page-level twin that still hears the ending: re-read the dataset a refresh just remade, the
 * products a materialize just rewrote, the profile a run just stored.
 */
export function useJobsSettled(jobs: Array<Job | null | undefined>, onSettled: (job: Job) => void): void {
  const memo = useRef<SettleMemo | null>(null)
  const onSettledRef = useRef(onSettled)
  useEffect(() => {
    onSettledRef.current = onSettled
  })
  const signature = jobs.map((j) => (j ? `${j.id}:${j.status}` : '-')).join(',')
  useEffect(() => {
    memo.current ??= newSettleMemo()
    for (const job of settledSince(memo.current, jobs, wasStartedHere)) onSettledRef.current(job)
    // Keyed on the jobs' ids and states, not the array's identity, which changes every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature])
}

/** What {@link settledSince} remembers between renders: jobs seen live, and endings already reported. */
export interface SettleMemo {
  live: Set<string>
  reported: Set<string>
}

export const newSettleMemo = (): SettleMemo => ({ live: new Set(), reported: new Set() })

/**
 * The jobs among `jobs` that have just finished, each reported once: a job seen live in an earlier
 * call, or one `startedHere` says this tab started even when it is first seen already finished (a
 * retry quick enough to end before its stream opened — register F5's row Retry). A job first seen
 * finished that nobody here started is history, not news, and is never reported.
 */
export function settledSince(
  memo: SettleMemo,
  jobs: Array<Job | null | undefined>,
  startedHere: (jobId: string) => boolean = () => false,
): Job[] {
  const settled: Job[] = []
  for (const job of jobs) {
    if (!job) continue
    if (isLive(job)) {
      memo.live.add(job.id)
      continue
    }
    if (memo.reported.has(job.id)) continue
    if (memo.live.has(job.id) || startedHere(job.id)) {
      memo.live.delete(job.id)
      memo.reported.add(job.id)
      settled.push(job)
    }
  }
  return settled
}

/** How often the list is re-read: often while a drawer shows it, less while jobs run, rarely otherwise. */
export function jobsPollInterval(drawerOpen: boolean, anyLive: boolean): number {
  if (drawerOpen) return 3_000
  if (anyLive) return 10_000
  return 60_000
}

/**
 * Keep the jobs store's list fresh for the top bar's badge and the drawer: re-read at
 * {@link jobsPollInterval}, and when the window regains focus. Lives in the top bar, so it runs
 * on every page of the workspace.
 */
export function useJobsPolling(drawerOpen: boolean): void {
  const refresh = useJobsStore((s) => s.refresh)
  const anyLive = useJobsStore((s) => Object.values(s.jobs).some(isLive))

  useEffect(() => {
    // The top bar remounts with each route; a list read seconds ago is still the answer.
    const loadedAt = useJobsStore.getState().loadedAt
    if (drawerOpen || !loadedAt || Date.now() - loadedAt > 5_000) void refresh()
    const timer = setInterval(() => void refresh(), jobsPollInterval(drawerOpen, anyLive))
    const onFocus = () => void refresh()
    window.addEventListener('focus', onFocus)
    return () => {
      clearInterval(timer)
      window.removeEventListener('focus', onFocus)
    }
  }, [drawerOpen, anyLive, refresh])
}

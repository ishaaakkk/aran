import { create } from 'zustand'
import { api } from '@/services/api'
import type { Job } from '@/services/types'
import { errorMessage } from '@/lib/errors'
import { isTerminal } from '@/lib/jobs'

/**
 * The jobs this browser tab knows about — the one place a job's latest state lives on the client
 * (UI audit A6: J8-F7, J2-F24, J6-F7).
 *
 * Fed from two directions: `refresh` lists the newest jobs the caller may see (`GET /api/jobs`),
 * and `follow` streams one job until it ends. A page that starts a job follows it; a page that
 * mounts after a navigation or a reload finds its object's job here by id and re-attaches its
 * card, instead of forgetting it — which is what `useState` job ids did, so a refresh or a
 * materialize "vanished" the moment the user looked elsewhere. Module-level, so it outlives each
 * route's `AppLayout`.
 */
interface JobsState {
  jobs: Record<string, Job>
  loadState: 'idle' | 'loading' | 'ready' | 'error'
  /** Why the last list failed — rendered by the drawer, never swallowed into an empty list. */
  loadError: string | null
  loadedAt: number | null
  /** Failed jobs a person has acknowledged on an object's card (kept per browser). */
  dismissed: Record<string, true>
  refresh: () => Promise<void>
  upsert: (job: Job) => void
  /**
   * Stream `jobId` until it ends, merging each update here. Reference-counted, so any number of
   * cards share one stream; returns the release.
   */
  follow: (jobId: string) => () => void
  /**
   * A job this tab just started: follow it to its end even if nothing is showing it, and call
   * `onSettled` when it gets there — however the end arrives (stream or list), and even when the
   * job finishes before any page saw it running. Resolves once the jobs list has been re-read,
   * so a caller that awaits it finds the new job in the store — a button can stay disabled from
   * the click to the job's end without a gap in between.
   */
  started: (jobId: string, onSettled?: (job: Job) => void) => Promise<void>
  dismiss: (jobId: string) => void
}

/** How many jobs the list asks for — the server's cap. */
export const JOBS_LIST_LIMIT = 100

const DISMISSED_KEY = 'aran.ui.dismissedJobs'
const DISMISSED_MAX = 200

function storedDismissed(): Record<string, true> {
  try {
    const raw = localStorage.getItem(DISMISSED_KEY)
    const ids: unknown = raw ? JSON.parse(raw) : []
    return Array.isArray(ids) ? Object.fromEntries(ids.map((id) => [String(id), true as const])) : {}
  } catch {
    return {}
  }
}

function storeDismissed(dismissed: Record<string, true>) {
  try {
    localStorage.setItem(DISMISSED_KEY, JSON.stringify(Object.keys(dismissed).slice(-DISMISSED_MAX)))
  } catch {
    // Storage can be unavailable (private mode); the in-memory value still works.
  }
}

/**
 * The newer of two views of one job. Events can arrive after a list that already saw the end, so
 * a terminal state is never replaced by a live one; and the event stream carries no `subject`, so
 * a known one is kept.
 */
export function mergeJob(known: Job | undefined, incoming: Job): Job {
  if (!known) return incoming
  if (isTerminal(known) && !isTerminal(incoming)) return known
  return { ...incoming, subject: incoming.subject ?? known.subject ?? null }
}

const followers = new Map<string, { count: number; stop: () => void }>()
/** Callbacks waiting for a started job's end, called once from the single merge point below. */
const settledListeners = new Map<string, Array<(job: Job) => void>>()
/**
 * Every job this tab started, whichever button started it. A page listening for its objects' jobs
 * to end ({@link settledSince}) also hears one of these that it first sees already finished — a
 * retry fast enough to end before its stream opened — which a job it merely loaded from history
 * never triggers.
 */
const startedHere = new Set<string>()

/** Whether this tab started `jobId` (see {@link JobsState.started}). */
export function wasStartedHere(jobId: string): boolean {
  return startedHere.has(jobId)
}

function notifySettled(job: Job) {
  if (!isTerminal(job)) return
  const listeners = settledListeners.get(job.id)
  if (!listeners) return
  settledListeners.delete(job.id)
  for (const listener of listeners) listener(job)
}

export const useJobsStore = create<JobsState>((set, get) => ({
  jobs: {},
  loadState: 'idle',
  loadError: null,
  loadedAt: null,
  dismissed: storedDismissed(),

  refresh: async () => {
    set((s) => ({ loadState: s.loadState === 'ready' ? 'ready' : 'loading' }))
    try {
      const list = await api.listJobs({ limit: JOBS_LIST_LIMIT })
      set((s) => {
        const jobs = { ...s.jobs }
        for (const job of list) jobs[job.id] = mergeJob(jobs[job.id], job)
        return { jobs, loadState: 'ready', loadError: null, loadedAt: Date.now() }
      })
      list.forEach(notifySettled)
    } catch (err: unknown) {
      set({ loadState: 'error', loadError: errorMessage(err, 'Could not load jobs.') })
    }
  },

  upsert: (job) => {
    set((s) => ({ jobs: { ...s.jobs, [job.id]: mergeJob(s.jobs[job.id], job) } }))
    notifySettled(job)
  },

  follow: (jobId) => {
    const known = get().jobs[jobId]
    if (known && isTerminal(known)) return () => {}
    let follower = followers.get(jobId)
    if (!follower) {
      const stop = api.jobEvents(jobId, (job) => {
        get().upsert(job)
        if (isTerminal(job)) followers.delete(jobId)
      }, () => followers.delete(jobId))
      follower = { count: 0, stop }
      followers.set(jobId, follower)
    }
    follower.count += 1
    let released = false
    return () => {
      if (released) return
      released = true
      const current = followers.get(jobId)
      if (!current) return
      current.count -= 1
      if (current.count <= 0) {
        current.stop()
        followers.delete(jobId)
      }
    }
  },

  started: (jobId, onSettled) => {
    startedHere.add(jobId)
    if (onSettled) settledListeners.set(jobId, [...(settledListeners.get(jobId) ?? []), onSettled])
    // Held, never released: the stream ends itself at the job's terminal state. The list refresh
    // brings the job's named subject, which the stream does not carry.
    get().follow(jobId)
    return get().refresh()
  },

  dismiss: (jobId) => {
    const dismissed = { ...get().dismissed, [jobId]: true as const }
    storeDismissed(dismissed)
    set({ dismissed })
  },
}))

export default useJobsStore

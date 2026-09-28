import { useCallback, useEffect, useReducer, useRef } from 'react'
import { errorMessage } from '@/lib/errors'

/**
 * One admin read, with its own loading, error and refresh time.
 *
 * Audit J7-F11 / J7-F3: the admin area loaded all five reads together, so every section waited for
 * the slowest (a live lake scan), switching the reporting window blanked the table, and one failed
 * read — the lake being the thing that is down — left the System page showing only an error. Each
 * section now owns its read: a failure is that section's, and the others render. Data is kept while
 * a reload is in flight, so changing the window does not flash the table away.
 */

export interface ResourceState<T> {
  data: T | null
  error: string | null
  loading: boolean
  /** When the data on screen was fetched; null until the first success. */
  refreshedAt: Date | null
}

export type ResourceAction<T> =
  | { type: 'start' }
  | { type: 'success'; data: T; at: Date }
  | { type: 'failure'; error: string }

export function initialResource<T>(): ResourceState<T> {
  return { data: null, error: null, loading: true, refreshedAt: null }
}

/**
 * The transitions, pure so they can be pinned without a renderer. A failure keeps the data it had:
 * a refresh that fails must not turn a table someone was reading into an error page, and the
 * section says the figures are from the earlier read instead.
 */
export function resourceReducer<T>(state: ResourceState<T>, action: ResourceAction<T>): ResourceState<T> {
  switch (action.type) {
    case 'start':
      return { ...state, loading: true, error: null }
    case 'success':
      return { data: action.data, error: null, loading: false, refreshedAt: action.at }
    case 'failure':
      return { ...state, loading: false, error: action.error }
  }
}

export interface AdminResource<T> extends ResourceState<T> {
  reload: () => void
}

/**
 * @param load re-run whenever `deps` change; a response that arrives after a newer request
 *             started is dropped, so a slow 30-day read cannot overwrite the 7-day one asked later
 */
export function useAdminResource<T>(load: () => Promise<T>, deps: readonly unknown[]): AdminResource<T> {
  const [state, dispatch] = useReducer(resourceReducer<T>, undefined, initialResource<T>)
  const generation = useRef(0)
  // `load` is a fresh closure every render; the latest one is what a reload runs, and `deps`
  // (serialised, so a caller passes values rather than a stable array) says when to run it again.
  const latest = useRef(load)
  useEffect(() => {
    latest.current = load
  })
  const key = JSON.stringify(deps)

  const reload = useCallback(() => {
    const mine = ++generation.current
    dispatch({ type: 'start' })
    latest.current().then(
      (data) => {
        if (mine === generation.current) dispatch({ type: 'success', data, at: new Date() })
      },
      (e: unknown) => {
        if (mine === generation.current) dispatch({ type: 'failure', error: errorMessage(e) })
      },
    )
  }, [])

  useEffect(() => {
    reload()
  }, [key, reload])

  return { ...state, reload }
}

/** "14:02 UTC" — the zone is stated, because an owner pastes this into a ticket. */
export function formatRefreshed(at: Date): string {
  return `${at.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' })} UTC`
}

import type React from 'react'
import { RefreshCw } from 'lucide-react'
import { formatRefreshed, type ResourceState } from './useAdminResource'

/**
 * How a section shows the state of its own read — loading, failed, or data with its age.
 *
 * Never a failure rendered as emptiness (the owner's standing rule): an error with no data is a
 * card that says which read failed, why, and offers Retry; an error with data keeps the table and
 * says the figures are from the earlier read. `hint` is where a section says what the failure
 * usually means — Storage's is "the lake did not answer; System shows whether it is down".
 */
export function SectionState<T>({
  resource, what, hint, onRetry, children,
}: {
  resource: ResourceState<T>
  /** What was being read, as a noun phrase: "system status", "storage". */
  what: string
  hint?: React.ReactNode
  onRetry: () => void
  children: (data: T) => React.ReactNode
}) {
  const { data, error, loading, refreshedAt } = resource

  if (data === null) {
    if (error) {
      return (
        <div className="admin-error admin-error-card" role="alert" data-testid="admin-section-error">
          <p className="admin-error-title">Could not load {what}.</p>
          <p className="admin-error-detail">{error}</p>
          {hint && <p className="admin-error-hint">{hint}</p>}
          <button type="button" className="admin-retry" onClick={onRetry}>
            <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
            <span>Retry</span>
          </button>
        </div>
      )
    }
    return <p className="admin-empty" role="status">{loading ? 'Loading…' : 'Nothing loaded yet.'}</p>
  }

  return (
    <>
      {error && (
        <p className="admin-error" role="alert">
          Refreshing {what} failed: {error}. The figures below are from{' '}
          {refreshedAt ? formatRefreshed(refreshedAt) : 'an earlier read'}.
        </p>
      )}
      {children(data)}
    </>
  )
}

/** "Refreshed 14:02 UTC · Refresh" — the age of what is on screen, and the way to renew it. */
export function RefreshedNote({
  refreshedAt, loading, onRefresh,
}: { refreshedAt: Date | null; loading: boolean; onRefresh: () => void }) {
  return (
    <p className="admin-refreshed">
      <span role="status">
        {loading ? 'Refreshing…' : refreshedAt ? `Refreshed ${formatRefreshed(refreshedAt)}` : ''}
      </span>
      <button type="button" className="admin-refresh" onClick={onRefresh} disabled={loading}>
        Refresh
      </button>
    </p>
  )
}

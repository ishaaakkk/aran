import type { AdminTableState, SortDirection } from './useAdminTable'

/** The presentational half of the shared table behaviour. See `useAdminTable` for the rest. */

/**
 * A sortable column header.
 *
 * `aria-sort` says the order the rows are really in (audit J7-F17: it said "ascending" for a
 * newest-first column), and the arrow agrees with it. The button names what a click will do, so a
 * screen reader hears "Joined, sorted descending — sort ascending" rather than only a label.
 */
export function SortHeader({
  label, column, state, align,
}: {
  label: string
  column: string
  state: { sort: string; direction: SortDirection; setSort: (k: string) => void }
  align?: 'num'
}) {
  const active = state.sort === column
  const next = active && state.direction === 'ascending' ? 'descending' : 'ascending'
  return (
    <th scope="col" className={align} aria-sort={active ? state.direction : 'none'}>
      <button
        type="button"
        className="admin-sort"
        onClick={() => state.setSort(column)}
        title={active ? `Sorted ${state.direction} — click to sort ${next}` : `Sort by ${label}`}
      >
        {label}
        <span aria-hidden="true" className={active ? 'is-active' : ''}>
          {active && state.direction === 'ascending' ? '▴' : '▾'}
        </span>
      </button>
    </th>
  )
}

/**
 * The search box and the count above a table.
 *
 * @param cappedAt when the server returned exactly this many rows, so the list is the most recent
 *                 N rather than all of them. Saying so is the whole point: a silently truncated
 *                 table reads as a complete one, and an operator concluding "there are only 100
 *                 audit events" from a page that fetched 100 is a mistake the page caused.
 */
export function TableToolbar<T>({
  state, placeholder, noun, cappedAt, children,
}: {
  state: AdminTableState<T>
  placeholder?: string
  noun: string
  cappedAt?: number
  children?: React.ReactNode
}) {
  const capped = cappedAt !== undefined && state.total >= cappedAt
  const filtering = state.query.trim() !== ''

  return (
    <div className="admin-toolbar-row">
      {placeholder ? (
        <input
          type="search"
          className="admin-search"
          value={state.query}
          onChange={(e) => state.setQuery(e.target.value)}
          placeholder={placeholder}
          aria-label={placeholder}
        />
      ) : <span />}

      <span className="admin-count-note">
        {children}
        {filtering
          ? `${state.rows.length} of ${state.total}`
          : `${state.total} ${noun}${state.total === 1 ? '' : 's'}`}
        {capped && ` — most recent ${cappedAt}, older ones not shown`}
      </span>
    </div>
  )
}

/**
 * A removable filter carried in the URL.
 *
 * Filters live in the query string so a view can be linked to — "look at Marta's tokens" is a URL
 * someone can paste, not a sequence of clicks to describe.
 */
export function FilterChip({ label, onClear }: { label: string; onClear: () => void }) {
  return (
    <span className="admin-filter-chip">
      {label}
      <button type="button" onClick={onClear} aria-label="Clear filter">×</button>
    </span>
  )
}

import { useMemo, useState } from 'react'

/**
 * Search and sorting, shared by every admin table.
 *
 * A hook rather than one generic `<AdminTable>`: the tables differ in ways a generic component
 * would have to grow escape hatches for — the unattributed usage row is styled apart, revoked
 * tokens are struck through, a degraded component is tinted — and a table component with a
 * `rowClassName` prop, a `renderCell` prop and a `colSpan` prop has stopped saving anyone
 * anything. What actually repeats is the *behaviour*, so that is what is extracted.
 *
 * The presentational half lives in `adminTable.tsx`; they are separate files because Fast Refresh
 * only works on modules that export components alone.
 */

export type SortDirection = 'ascending' | 'descending'

/**
 * How one column sorts, in both directions.
 *
 * `natural` is what a first click gives — ascending for a name, newest-first for a date,
 * largest-first for a size — and a second click reverses it (audit J7-F17: the tables sorted one
 * way only and announced "ascending" for a newest-first column). Direction is an argument rather
 * than a negated comparator because "never happened" stays at the bottom either way: reversing a
 * null-last comparator by negation would float every never-used token to the top.
 */
export interface Sorter<T> {
  natural: SortDirection
  compare: (a: T, b: T, direction: SortDirection) => number
}

export interface AdminTableOptions<T> {
  /** Fields that a search query matches against. Empty means the table has no search box. */
  searchable?: (row: T) => string
  /** Sort keys and how to order by each. */
  sorters: Record<string, Sorter<T>>
  initialSort: string
  /**
   * Rows that stay last whatever the sort — the unattributed usage row is data, not a member, and
   * must never be sorted in among people.
   */
  pinLast?: (row: T) => boolean
}

export interface AdminTableState<T> {
  rows: T[]
  total: number
  query: string
  setQuery: (q: string) => void
  sort: string
  direction: SortDirection
  /** Sort by `key`; the same key again reverses the direction. */
  setSort: (k: string) => void
}

export interface SortState {
  key: string
  direction: SortDirection
}

/** The sort after a header click: the same column flips, a new one starts at its natural order. */
export function nextSort<T>(
  current: SortState,
  clicked: string,
  sorters: Record<string, Sorter<T>>,
): SortState {
  if (clicked === current.key) {
    return { key: clicked, direction: current.direction === 'ascending' ? 'descending' : 'ascending' }
  }
  return { key: clicked, direction: sorters[clicked]?.natural ?? 'ascending' }
}

/** A sorted copy. Rows matching `pinLast` keep their relative order at the end. */
export function sortRows<T>(
  rows: readonly T[],
  sorter: Sorter<T> | undefined,
  direction: SortDirection,
  pinLast?: (row: T) => boolean,
): T[] {
  const body = pinLast ? rows.filter((r) => !pinLast(r)) : [...rows]
  const tail = pinLast ? rows.filter(pinLast) : []
  if (sorter) body.sort((a, b) => sorter.compare(a, b, direction))
  return [...body, ...tail]
}

export function useAdminTable<T>(
  all: T[] | null,
  options: AdminTableOptions<T>,
): AdminTableState<T> {
  const [query, setQuery] = useState('')
  const [sort, setSortState] = useState<SortState>(() => ({
    key: options.initialSort,
    direction: options.sorters[options.initialSort]?.natural ?? 'ascending',
  }))

  const rows = useMemo(() => {
    const source = all ?? []
    const needle = query.trim().toLowerCase()
    const matched = needle === '' || !options.searchable
      ? source
      : source.filter((row) => options.searchable!(row).toLowerCase().includes(needle))
    return sortRows(matched, options.sorters[sort.key], sort.direction, options.pinLast)
    // `options` is rebuilt on every render by every caller, so depending on it would recompute
    // constantly; the parts that matter are the ones listed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [all, query, sort])

  return {
    rows,
    total: all?.length ?? 0,
    query,
    setQuery,
    sort: sort.key,
    direction: sort.direction,
    setSort: (k: string) => setSortState((current) => nextSort(current, k, options.sorters)),
  }
}

/** Sort by a string field, case-insensitively. Natural order: A to Z. */
export function byText<T>(get: (row: T) => string): Sorter<T> {
  return {
    natural: 'ascending',
    compare: (a, b, direction) => {
      const order = get(a).localeCompare(get(b), undefined, { sensitivity: 'base' })
      return direction === 'ascending' ? order : -order
    },
  }
}

/** Sort by a number. Natural order: largest first — the useful direction for a size or a count. */
export function byNumberDesc<T>(get: (row: T) => number): Sorter<T> {
  return {
    natural: 'descending',
    compare: (a, b, direction) =>
      direction === 'descending' ? get(b) - get(a) : get(a) - get(b),
  }
}

/**
 * Sort by an ISO timestamp. Natural order: newest first. Never-happened is last in both
 * directions.
 *
 * Null is "this has not happened" — a token never used, a member never seen. Sorting it as an
 * empty string puts it at one end by accident of encoding; putting it deliberately at the bottom
 * matches what someone scanning for recent — or for the stalest real — activity means.
 */
export function byRecency<T>(get: (row: T) => string | null): Sorter<T> {
  return {
    natural: 'descending',
    compare: (a, b, direction) => {
      const left = get(a)
      const right = get(b)
      if (left === right) return 0
      if (left === null) return 1
      if (right === null) return -1
      return direction === 'descending' ? right.localeCompare(left) : left.localeCompare(right)
    },
  }
}

/**
 * Sort by a nullable number, nulls last in both directions — a p95 of "ran nothing" is not a
 * fast p95. Natural order: largest first.
 */
export function byNullableNumber<T>(get: (row: T) => number | null): Sorter<T> {
  return {
    natural: 'descending',
    compare: (a, b, direction) => {
      const left = get(a)
      const right = get(b)
      if (left === right) return 0
      if (left === null) return 1
      if (right === null) return -1
      return direction === 'descending' ? right - left : left - right
    },
  }
}

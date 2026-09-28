import type { QueryRunResult } from '../services/types'

/**
 * Paging arithmetic for a workbench result, kept out of the component so it can be tested.
 *
 * The server returns one page and one bit — "there is more" — never a total. Counting the whole
 * result would mean executing the query twice, so anything here that looks like it knows the size
 * of the result is wrong by construction: there is no page count and no "of N".
 */
export interface PageState {
  /** Row offset this page starts at. */
  offset: number
  /** Rows per page, as the server applied it. */
  pageSize: number
  /** Rows actually in this page. */
  rowCount: number
  /** Whether a further page exists. */
  hasMore: boolean
}

/**
 * Read the page state off a result, tolerating a server that predates paging.
 *
 * `offset`, `pageSize` and `hasMore` are all optional on the wire: an older backend sends only
 * `rowCount` and `truncated`. Defaulting to a single page at offset 0 makes such a payload render
 * exactly as it did before, rather than showing a pager that cannot work.
 */
export function pageStateOf(result: QueryRunResult): PageState {
  return {
    offset: result.offset ?? 0,
    // Falling back to rowCount, not to a hardcoded 1000: if the server's cap ever changes, a
    // constant here would send `next` to the wrong offset and silently skip or repeat rows.
    pageSize: result.pageSize ?? result.rowCount,
    rowCount: result.rowCount,
    hasMore: result.hasMore ?? result.truncated,
  }
}

/** Whether the pager is worth showing at all. */
export function showPager(state: PageState): boolean {
  // A single-page result is the overwhelmingly common case, and a row of disabled controls under
  // a nine-row answer is worse than the dead end this replaces.
  return state.hasMore || state.offset > 0
}

/** Offset of the previous page, clamped at the start. */
export function prevOffset(state: PageState): number {
  return Math.max(0, state.offset - state.pageSize)
}

/** Offset of the next page. */
export function nextOffset(state: PageState): number {
  return state.offset + state.pageSize
}

/** 1-based inclusive row range of this page, e.g. `1,001–2,000`. */
export function pageRangeLabel(state: PageState): string {
  if (state.rowCount === 0) {
    return 'no rows'
  }
  const first = state.offset + 1
  const last = state.offset + state.rowCount
  // Pinned to 'en-US' rather than the runtime's default locale: the workbench UI is English with
  // comma thousands separators regardless of the machine it runs on, and an unpinned
  // toLocaleString() renders '1.000' instead of '1,000' on a non-English OS locale.
  return `${first.toLocaleString('en-US')}–${last.toLocaleString('en-US')}`
}

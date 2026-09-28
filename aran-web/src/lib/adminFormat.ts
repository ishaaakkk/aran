/**
 * Formatters for the administration screen.
 *
 * Here rather than inline in the view because each one encodes a decision that is easy
 * to reverse by accident and impossible to notice afterwards: a token that has never
 * been used must not render the same as one whose last use is unknown, and a member who
 * moved no bytes must not render as having moved 0 B of something.
 */

/**
 * A coarse "how long ago", or the word for never.
 *
 * `null` means the thing genuinely has not happened — a token never presented, a member
 * who has not signed in since Aran started recording. "never" is the answer, not missing
 * data, so it must not render as an em-dash the way an inapplicable cell does.
 */
export function relativeTime(iso: string | null, now: number = Date.now()): string {
  if (!iso) return 'never'
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return 'never'
  const mins = Math.floor((now - then) / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} h ago`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} d ago`
  // Past a month the shape of the number stops being useful and the date itself starts
  // being the thing an owner wants.
  return new Date(iso).toLocaleDateString()
}

/**
 * Row and event counts, shortened.
 *
 * Exact below a thousand: the difference between 3 queries and 30 is the whole point of
 * the column, and "0.0k" would erase it.
 */
export function compactCount(n: number): string {
  if (n < 1000) return String(n)
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`
  return `${(n / 1_000_000).toFixed(1)}M`
}

/**
 * Bytes moved, in binary units.
 *
 * Zero renders as an em-dash rather than "0 B" — a member who ran nothing has no volume,
 * which is a different statement from having transferred an empty result.
 */
export function formatBytes(n: number): string {
  if (n === 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = n
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

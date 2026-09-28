/** One source a fan-out could not read, named as the user knows it. */
export interface SourceFailure {
  name: string
  error: unknown
}

export interface FanOutResult<T> {
  items: T[]
  failures: SourceFailure[]
}

/**
 * Read one list per source and keep both halves of the answer (UI audit J8-F2).
 *
 * The list views aggregate across every project (repositories) or every repository (datasets),
 * one request each, and each used to swallow a failing request into `console.error` — so one
 * slow or failing project silently hid its rows, and a partial list read as complete. This
 * returns what loaded AND what did not, so a view can say "2 of 5 projects could not be read"
 * and must not call anything "missing" while a source is unaccounted for.
 *
 * Items keep the sources' order, not completion order, so the list does not reshuffle between
 * two loads of the same data.
 */
export async function fanOut<S, T>(
  sources: readonly S[],
  name: (source: S) => string,
  read: (source: S) => Promise<readonly T[]>,
): Promise<FanOutResult<T>> {
  const settled = await Promise.allSettled(sources.map((s) => read(s)))
  const items: T[] = []
  const failures: SourceFailure[] = []
  settled.forEach((outcome, i) => {
    if (outcome.status === 'fulfilled') items.push(...outcome.value)
    else failures.push({ name: name(sources[i]), error: outcome.reason })
  })
  return { items, failures }
}

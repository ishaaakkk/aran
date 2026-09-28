import type { ModelMapping } from '@/services/types'

export interface SuggestOutcome {
  /** Null when the model could not be read to compare against: nothing is known to be new. */
  created: number | null
  alreadyReviewed: number | null
  alreadyQueued: number | null
  /** The persistent line the Alignments page shows after a run. */
  sentence: string
}

/**
 * What one "Suggest Property Alignments" run did, from what it returned and what the model held
 * before (UI audit J3-F17). The server returns every candidate it scored — the new rows AND the
 * rows that already existed, unchanged — so the difference is exactly what the run added. Run on
 * an unprofiled or fully reviewed pair, the button used to leave the queue identical and read as
 * broken; this says which of those it was.
 *
 * `before` is null when the model could not be read before the run: the returned rows include
 * the ones that already existed, so without that read none of them is known to be new, and
 * counting them all as "added" said so about rows the run left unchanged (second review of UA2).
 */
export function suggestOutcome(returned: ModelMapping[], before: ModelMapping[] | null): SuggestOutcome {
  if (before === null && returned.length > 0) {
    const n = returned.length
    return {
      created: null, alreadyReviewed: null, alreadyQueued: null,
      sentence: `${n} candidate${n === 1 ? '' : 's'} returned for this pair. The model could not be `
        + 'read beforehand, so this cannot say which are new — the review queue below shows them all.',
    }
  }
  const existing = new Map((before ?? []).map((m) => [m.id, m]))
  const created = returned.filter((m) => !existing.has(m.id)).length
  const known = returned.filter((m) => existing.has(m.id))
  const alreadyQueued = known.filter((m) => m.status === 'SUGGESTED').length
  const alreadyReviewed = known.length - alreadyQueued

  if (returned.length === 0) {
    return {
      created, alreadyReviewed, alreadyQueued,
      sentence: 'No candidates for this pair. Suggestions are scored from column profiles — '
        + 'profile both datasets first; if they are profiled, their columns have nothing in common.',
    }
  }
  const tail = [
    alreadyReviewed > 0 ? `${alreadyReviewed} already reviewed` : null,
    alreadyQueued > 0 ? `${alreadyQueued} already in the queue` : null,
  ].filter(Boolean).join(', ')
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`
  return {
    created, alreadyReviewed, alreadyQueued,
    sentence: created > 0
      ? `${plural(created, 'new suggestion')} added to the review queue${tail ? ` (${tail})` : ''}.`
      : `No new suggestions — every candidate for this pair already exists (${tail}).`,
  }
}

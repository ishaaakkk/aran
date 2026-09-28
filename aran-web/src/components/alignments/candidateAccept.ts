import { errorMessage } from '@/lib/errors';
import type { CandidateOutcome } from './types';

/**
 * Accepting discovered join candidates, one request each (J3-F4 / J4-F9, review UA5).
 *
 * A refusal the server made about the request itself — a 4xx, such as D2's duplicate-label 400 on
 * `join_id_id` for a second id↔id pair — comes back the same for the same request. Offering
 * "Retry" on it re-sent the identical request forever. So a refused row's label is editable, and a
 * deterministic refusal is re-sent only once its label changed; a transient failure (network, 5xx,
 * 408/429) is retried as it is.
 */
export function refusalOutcome(err: unknown, sentLabel: string): CandidateOutcome {
  const status = httpStatus(err);
  const deterministic = status !== null && status >= 400 && status < 500 && status !== 408 && status !== 429;
  return { state: 'failed', message: errorMessage(err, 'refused'), retryable: !deterministic, sentLabel };
}

/** Whether the next accept sends this candidate. */
export function isSendable(candidate: {
  selected: boolean;
  unavailable?: string;
  outcome?: CandidateOutcome;
  label: string;
}): boolean {
  const { selected, unavailable, outcome, label } = candidate;
  if (!selected || unavailable || !label.trim()) return false;
  if (outcome?.state === 'added') return false;
  if (outcome?.state === 'failed' && outcome.retryable === false && outcome.sentLabel === label.trim()) return false;
  return true;
}

function httpStatus(err: unknown): number | null {
  if (typeof err !== 'object' || err === null) return null;
  const status: unknown = (err as { status?: unknown }).status;
  return typeof status === 'number' ? status : null;
}

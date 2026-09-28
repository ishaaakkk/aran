import { describe, expect, it } from 'vitest';
import { isSendable, refusalOutcome } from './candidateAccept';

/** What `api` throws: an Error carrying the HTTP status. */
const httpError = (status: number, message: string) => Object.assign(new Error(message), { status });

describe('refusalOutcome (review UA5) — which refusals a plain retry can fix', () => {
  it('a 4xx is the server\'s answer to this request: not retryable as it is', () => {
    expect(refusalOutcome(httpError(400, "a join mapping labelled 'join_id_id' already exists"), 'join_id_id'))
      .toEqual({ state: 'failed', message: "a join mapping labelled 'join_id_id' already exists", retryable: false, sentLabel: 'join_id_id' });
    expect(refusalOutcome(httpError(409, 'conflict'), 'k').retryable).toBe(false);
  });

  it('a network failure, a 5xx, a timeout or a rate limit may pass next time', () => {
    expect(refusalOutcome(new Error('Failed to fetch'), 'k').retryable).toBe(true);
    expect(refusalOutcome(httpError(503, 'unavailable'), 'k').retryable).toBe(true);
    expect(refusalOutcome(httpError(408, 'timeout'), 'k').retryable).toBe(true);
    expect(refusalOutcome(httpError(429, 'slow down'), 'k').retryable).toBe(true);
  });
});

describe('isSendable — what the next accept sends', () => {
  const refused = refusalOutcome(httpError(400, 'duplicate label'), 'join_id_id');

  it('never re-sends a deterministic refusal unchanged — it would be refused again', () => {
    expect(isSendable({ selected: true, outcome: refused, label: 'join_id_id' })).toBe(false);
    expect(isSendable({ selected: true, outcome: refused, label: ' join_id_id ' })).toBe(false);
  });

  it('re-sends it once its label changed', () => {
    expect(isSendable({ selected: true, outcome: refused, label: 'venue_id_join' })).toBe(true);
  });

  it('re-sends a transient failure as it is', () => {
    expect(isSendable({ selected: true, outcome: refusalOutcome(new Error('Failed to fetch'), 'k'), label: 'k' })).toBe(true);
  });

  it('never sends an added, unselected, unavailable or unlabelled candidate', () => {
    expect(isSendable({ selected: true, outcome: { state: 'added' }, label: 'k' })).toBe(false);
    expect(isSendable({ selected: false, label: 'k' })).toBe(false);
    expect(isSendable({ selected: true, unavailable: 'already aligned', label: 'k' })).toBe(false);
    expect(isSendable({ selected: true, label: '  ' })).toBe(false);
  });
});

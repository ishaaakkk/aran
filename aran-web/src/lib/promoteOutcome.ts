import { errorDetail, errorMessage } from './errors'

/**
 * What a promote that did not answer "done" actually amounts to (J4-F11, review of UA2).
 *
 * Two failures do not mean "failed": a promote the page stopped waiting for may still land, and a
 * NOT_A_DRAFT conflict means the run is no longer a draft — most often because an earlier attempt
 * DID land after the page gave up, but also when a newer compile superseded it. The error alone
 * cannot tell those apart, so the caller re-reads the model and passes the run it now has active;
 * this decides from that state, not from the wording of the error.
 *
 * `activeRunIdNow` is the model's active run after the re-read — a run id, or null for none — and
 * `undefined` when the model was not read (not needed, or the re-read failed).
 *
 * `pending` and `unconfirmed` are not failures and are not shown as one: the panel renders them as
 * a status with "Check again", never under a "failed" heading (second review of UA2 — "Promotion
 * failed: … it was already promoted" contradicted itself).
 */
export type PromoteOutcome =
  | { kind: 'landed' }
  | { kind: 'pending'; message: string }
  | { kind: 'unconfirmed'; message: string }
  | { kind: 'failed'; message: string }

/** Whether the model must be re-read before a failed promote can be described. */
export function promoteNeedsRecheck(err: unknown): boolean {
  const e = err as { mayStillBeRunning?: boolean; errorCode?: string | null } | null
  return e?.mayStillBeRunning === true || e?.errorCode === 'NOT_A_DRAFT'
}

export function describePromoteFailure(
  err: unknown,
  runId: string,
  activeRunIdNow: string | null | undefined,
): PromoteOutcome {
  if (activeRunIdNow === runId) return { kind: 'landed' }
  const e = err as { mayStillBeRunning?: boolean; errorCode?: string | null } | null
  if (e?.mayStillBeRunning === true) {
    return { kind: 'pending', message: errorMessage(err) }
  }
  if (e?.errorCode === 'NOT_A_DRAFT' && activeRunIdNow === undefined) {
    // The server's sentence already says the run is no longer a draft, and most often why; with
    // no model read there is nothing to add but the way to find out.
    return {
      kind: 'unconfirmed',
      message: `${errorDetail(err)} The model could not be read to check whether this run is the `
        + 'active one now — check again.',
    }
  }
  return { kind: 'failed', message: 'Promotion failed: ' + errorMessage(err, 'unknown error') }
}

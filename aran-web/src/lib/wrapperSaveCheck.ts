/**
 * What a wrapper save the page stopped waiting for amounts to (J4-F11's wrapper half, second
 * review of UA2).
 *
 * A save verifies the wrapper before it answers — a `COUNT(*)` and a row hash against the
 * snapshot, or against the live remote for a federated table (`IntegrationModelService.setWrapper`)
 * — so it can outlive the page's deadline and still commit. The timeout cannot say which; only
 * the model can, so the caller re-reads it and passes what is stored now. Saving again instead
 * would start a second verify, which on a federated table is a second unbounded scan of the
 * source.
 *
 * `storedNow` is the class's stored wrapper after the re-read: a string or null when the model
 * answered, `undefined` when the re-read itself failed.
 */
export type WrapperSaveCheck =
  | { kind: 'saved'; message: string }
  | { kind: 'not-yet'; message: string }
  | { kind: 'unknown'; message: string }

export function describeWrapperSaveCheck(
  submitted: string,
  storedNow: string | null | undefined,
): WrapperSaveCheck {
  if (storedNow === undefined) {
    return {
      kind: 'unknown',
      message: 'The page stopped waiting for this save, and the model could not be read to check '
        + 'whether it landed. Check again before saving again — a second save verifies the '
        + 'wrapper against its source a second time.',
    }
  }
  if (storedNow === submitted) {
    return {
      kind: 'saved',
      message: 'Saved — the server finished after the page stopped waiting.',
    }
  }
  return {
    kind: 'not-yet',
    message: 'Not stored yet — the server may still be verifying this wrapper against its source. '
      + 'Check again in a moment rather than saving again, which would start a second verify. '
      + 'If it is still not stored after a few minutes, the server refused it: save again to see why.',
  }
}

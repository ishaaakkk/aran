import type { SchemaVersion } from '@/services/types'

/**
 * What a promote or rollback on /versions that the page stopped waiting for amounts to (J4-F11;
 * third review of UA2 — the Alignments promote already re-read, these did not).
 *
 * Both can outlive the page's 150 s deadline and still commit. A retried promote of a version that
 * has since gone ACTIVE is refused, but a retried rollback is not: its target stays HISTORICAL, so
 * a second rollback that lands creates another identical ACTIVE version. So the caller re-reads the
 * version list and passes it here, with the newest version number it knew of when it sent the
 * action; `versionsNow` is `null` when that re-read failed.
 *
 * A rollback re-materialises its target as a NEW version (SchemaVersionService.rollback), which
 * carries nothing naming the rollback that made it. A new ACTIVE version newer than anything the
 * page knew of is most likely this rollback — "most likely", because a promote elsewhere in the
 * tenant would look the same — so the message says so and points at the diff that settles it.
 */
export type VersionActionCheck =
  | { kind: 'landed'; message: string }
  | { kind: 'not-yet'; message: string }
  | { kind: 'unknown'; message: string }

export type VersionAction = 'promote' | 'rollback'

export function describeVersionActionTimeout(
  action: VersionAction,
  target: Pick<SchemaVersion, 'id' | 'versionNumber'>,
  newestBefore: number,
  versionsNow: SchemaVersion[] | null,
): VersionActionCheck {
  const v = `v${target.versionNumber}`
  if (versionsNow === null) {
    return {
      kind: 'unknown',
      message: `The page stopped waiting for the ${action === 'promote' ? `promote of ${v}` : `rollback to ${v}`}, `
        + 'and the version list could not be read to check whether it landed. Check again before '
        + `${action === 'promote' ? 'promoting' : 'rolling back'} again.`,
    }
  }
  if (action === 'promote') {
    const now = versionsNow.find((x) => x.id === target.id)
    if (now?.status === 'ACTIVE') {
      return { kind: 'landed', message: `${v} is active — the promote finished after the page stopped waiting.` }
    }
    return {
      kind: 'not-yet',
      message: `${v} is not active yet — the server may still be promoting it. Check again in a moment `
        + 'rather than promoting again.',
    }
  }
  const created = versionsNow.find((x) => x.status === 'ACTIVE' && x.versionNumber > newestBefore)
  if (created) {
    return {
      kind: 'landed',
      message: `v${created.versionNumber} is active, created after this rollback to ${v} was sent — most `
        + 'likely by it, finishing after the page stopped waiting. Select both to compare them before '
        + 'rolling back again.',
    }
  }
  return {
    kind: 'not-yet',
    message: `No new active version yet — the server may still be rolling back to ${v}. Check again in a `
      + 'moment rather than rolling back again: a second rollback that lands creates another identical '
      + 'version.',
  }
}

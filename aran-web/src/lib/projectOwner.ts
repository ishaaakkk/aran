/**
 * The project list's owner column (J1-F10: "names where resolvable").
 *
 * Only the caller's own id resolves to a name today — a member cannot list the organization's
 * members, so there is no display-name lookup for anyone else. The alternative the first pass took,
 * the first 8 characters of a UUID, is neither a name nor an id anyone can search for or copy
 * (second review of UA2). So: "you", or "someone else" with the full id on hover; the project's
 * detail panel shows the full id, selectable. A real name waits on a member-name lookup (residue).
 */
export function ownerLabel(createdBy: string | null | undefined, myUserId: string | null): string {
  if (!createdBy) return '—'
  return createdBy === myUserId ? 'you' : 'someone else'
}

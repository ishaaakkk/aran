import type { ProjectMember, TenantMember } from '@/services/types'

/**
 * Who can see a project, as the Members section lists them (audit J1-F4).
 *
 * `GET /projects/{id}/members` answers ids only; names come from the organization's member list.
 * A project member missing from it is someone who has since left the organization — shown by a
 * shortened id rather than dropped, because "who can see this" must not quietly under-count.
 *
 * When the organization's list could not be read at all (`tenantMembers` null), nobody is "missing"
 * from it: every line is an id with `known: null`, never the claim that they left.
 */
export interface ProjectMemberLine {
  userId: string
  name: string
  email: string | null
  isSelf: boolean
  /**
   * False when the user is no longer in the organization's member list; null when that list could
   * not be read, so whether they are is unknown.
   */
  known: boolean | null
}

export function memberLines(
  projectMembers: readonly ProjectMember[],
  tenantMembers: readonly TenantMember[] | null,
  meId: string | null,
): ProjectMemberLine[] {
  const byId = new Map((tenantMembers ?? []).map((m) => [m.userId, m]))
  return projectMembers
    .map((pm) => {
      const m = byId.get(pm.userId)
      return {
        userId: pm.userId,
        name: m ? m.displayName : `${pm.userId.slice(0, 8)}…`,
        email: m ? m.email : null,
        isSelf: pm.userId === meId,
        known: tenantMembers === null ? null : m !== undefined,
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
}

/** Organization members who cannot see the project yet — who "Add" can offer. */
export function addCandidates(
  tenantMembers: readonly TenantMember[],
  projectMembers: readonly ProjectMember[],
): TenantMember[] {
  const inProject = new Set(projectMembers.map((pm) => pm.userId))
  return tenantMembers
    .filter((m) => !inProject.has(m.userId))
    .sort((a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' }))
}

/**
 * Remove a project member, then do what follows from who it was: reload the list for someone
 * else, or hand back to the host when you removed yourself — the list is no longer yours to read,
 * and a panel left open on it offers controls that would all 404.
 */
export async function removeThenFollowUp(
  line: ProjectMemberLine,
  deps: { remove: (userId: string) => Promise<void>; reload: () => Promise<void>; onSelfRemoved?: () => void },
): Promise<void> {
  await deps.remove(line.userId)
  if (line.isSelf) {
    deps.onSelfRemoved?.()
    return
  }
  await deps.reload()
}

import type { RemovedTenantMember, TenantMember } from '@/services/types'

/**
 * What each member action does, in the words its confirmation uses.
 *
 * Pure so every sentence is pinned by a test: these are the promises an owner reads before an
 * irreversible or authority-changing click, and the owner's standing rule is that no such action
 * happens without a confirmation that names its impact. Each list says what changes, what does not,
 * and that the change is recorded — the three things Google Cloud IAM's and Snowsight's role-change
 * dialogs say.
 */

export type MemberRole = TenantMember['role']

export interface ConfirmCopy {
  title: string
  consequences: string[]
  confirmLabel: string
  tone: 'danger' | 'default'
}

const RECORDED = 'The change is recorded in Activity.'

/** The only owner cannot be demoted — the server refuses, so the control says so first. */
export function isOnlyOwner(user: TenantMember, users: readonly TenantMember[]): boolean {
  return user.role === 'OWNER' && users.filter((u) => u.role === 'OWNER').length <= 1
}

/**
 * Audit J7-F4: a role select committed on change, so ArrowDown on a focused select made someone an
 * owner. The select now only proposes; this is what the confirmation says.
 */
export function roleChangeCopy(user: TenantMember, next: MemberRole, isSelf: boolean): ConfirmCopy {
  if (next === 'OWNER') {
    return {
      title: `Make ${user.displayName} an owner?`,
      consequences: [
        'They can add and remove members, change roles, reset passwords and revoke anyone’s API tokens.',
        'They can open Administration — Usage, Storage, Credentials, Activity and System.',
        RECORDED,
      ],
      confirmLabel: 'Make owner',
      tone: 'default',
    }
  }
  if (isSelf) {
    return {
      title: 'Give up your owner role?',
      consequences: [
        'You lose access to Administration as soon as you confirm, and return to the workspace.',
        'Only another owner can make you an owner again.',
        'You keep your projects and API tokens.',
        RECORDED,
      ],
      confirmLabel: 'Make me a member',
      tone: 'danger',
    }
  }
  return {
    title: `Make ${user.displayName} a member?`,
    consequences: [
      'They lose access to Administration immediately.',
      'They keep their projects and API tokens.',
      RECORDED,
    ],
    confirmLabel: 'Make member',
    tone: 'danger',
  }
}

/** Audit J7-F16: the key icon replaced a working password at once, unconfirmed and unaudited. */
export function resetPasswordCopy(user: TenantMember): ConfirmCopy {
  return {
    title: `Reset ${user.displayName}’s password?`,
    consequences: [
      'Their current password stops working immediately.',
      'You see a temporary password once; they choose their own at their next sign-in.',
      'Their API tokens keep working.',
      RECORDED,
    ],
    confirmLabel: 'Reset password',
    tone: 'danger',
  }
}

/**
 * UI-OD9: what removal takes away, and what re-adding the same email later brings back.
 *
 * A project only they belong to is handed to you rather than left unreachable — visibility is
 * membership, so nobody, owners included, could open it again. Which projects those are is known
 * only after the removal (you cannot see them yet), so the confirmation says what happens to them
 * and {@link removalSummary} names them afterwards.
 */
export function removeMemberCopy(user: TenantMember, organization: string | null): ConfirmCopy {
  return {
    title: `Remove ${user.displayName} from ${organization ?? 'this organization'}?`,
    consequences: [
      'They lose access immediately and cannot sign in.',
      'Every API token they hold is revoked — scripts and notebooks using one stop working.',
      'They are removed from every project. A project only they belong to is shared with you, so none becomes unreachable; nothing in any project is deleted.',
      'Adding their email again later restores their account with a new temporary password — not their tokens or project access.',
      RECORDED,
    ],
    confirmLabel: `Remove ${user.displayName}`,
    tone: 'danger',
  }
}

/** After a removal: what it did, in one line, and the projects that are now the owner's to look after. */
export function removalSummary(name: string, result: RemovedTenantMember): { headline: string; handedOver: string | null } {
  const tokens = result.tokensRevoked === 0 ? 'no API token to revoke'
    : result.tokensRevoked === 1 ? '1 API token revoked' : `${result.tokensRevoked} API tokens revoked`
  const handed = result.projectsHandedOver.map((p) => p.name)
  return {
    headline: `${name} was removed — ${tokens}.`,
    handedOver: handed.length === 0 ? null
      : `Only ${name} could see ${listOf(handed)}, so ${handed.length === 1 ? 'it is' : 'they are'} now shared with you. Add someone to ${handed.length === 1 ? 'it' : 'each'} from Projects, or leave ${handed.length === 1 ? 'it' : 'them'} with you.`,
  }
}

function listOf(names: string[]): string {
  if (names.length <= 1) return names.join('')
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/**
 * The handover an owner used to write by hand (audit J7-F18): where to sign in, the username, the
 * temporary password, and that they will choose their own. Plain text, so it survives any chat or
 * mail client it is pasted into.
 */
export function invitationText({
  url, email, password, organization, reset, reinstated,
}: {
  url: string; email: string; password: string; organization: string | null; reset?: boolean
  /** A removed member's account brought back — with a new password, like a reset. */
  reinstated?: boolean
}): string {
  const opening = reset
    ? `Your Aran password${organization ? ` for ${organization}` : ''} was reset.`
    : reinstated
      ? `Your Aran access${organization ? ` to ${organization}` : ''} has been restored, with a new password.`
      : `You have an Aran account${organization ? ` in ${organization}` : ''}.`
  return [
    opening,
    '',
    `Sign in at: ${url}`,
    `Username: ${email}`,
    `Temporary password: ${password}`,
    '',
    'You will choose your own password when you sign in.',
  ].join('\n')
}

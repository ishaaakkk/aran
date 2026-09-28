import type { TenantMember } from '@/services/types'
import { errorMessage } from './errors'

/**
 * What a member create that did not answer "created" amounts to (UI audit J7-F7; second and third
 * reviews of UA2).
 *
 * With Keycloak cold, a create can outlive the page's deadline having provisioned the account and
 * the membership. Its one-time password is lost — it existed only in the response the page stopped
 * waiting for — and Reset password mints another. So a timeout or a conflict re-reads the members.
 *
 * Two outcomes find the person in the list, and they are not the same statement:
 *
 * - **landed-late** — this page stopped waiting for a create of this email (this attempt, or an
 *   earlier one it remembers), and the person joined after that attempt began. Only then does the
 *   page say the create "finished after the page stopped waiting" and offer Reset password — behind
 *   a confirmation, because it still replaces a password (`resetAfterLostPasswordCopy`).
 * - **already-member** — anything else, most often an owner re-adding someone who is simply a
 *   member (the server's ALREADY_A_MEMBER on a first submit). That person's password works; the
 *   page names them and when they joined, and offers nothing that would replace it.
 */
export type MemberCreateOutcome =
  | { kind: 'landed-late'; member: TenantMember; message: string }
  | { kind: 'already-member'; member: TenantMember; message: string }
  | { kind: 'failed'; message: string }

/** Normalised email → when the earliest create of it this page stopped waiting for began (ms). */
export type TimedOutAttempts = ReadonlyMap<string, number>

/**
 * How far the server's `joinedAt` may sit before the browser's record of when the attempt began and
 * still count as that attempt: the two clocks are not the same clock. Past it, the person reads as
 * already a member — a weaker statement that stays true, never the other way round.
 */
export const CLOCK_ALLOWANCE_MS = 2 * 60 * 1000

export function normaliseEmail(email: string): string {
  return email.trim().toLowerCase()
}

/** Whether the member list must be re-read before a failed create can be described. */
export function memberCreateNeedsRelist(err: unknown): boolean {
  const e = err as { kind?: string; mayStillBeRunning?: boolean } | null
  return e?.mayStillBeRunning === true || e?.kind === 'conflict'
}

/** Whether this failure is the page having stopped waiting — the attempt may still land. */
export function stoppedWaiting(err: unknown): boolean {
  return (err as { mayStillBeRunning?: boolean } | null)?.mayStillBeRunning === true
}

export function describeMemberCreateFailure(
  err: unknown,
  email: string,
  membersNow: TenantMember[] | null,
  timedOut: TimedOutAttempts,
  attemptStartedAt: number,
): MemberCreateOutcome {
  const wanted = normaliseEmail(email)
  const member = membersNow?.find((m) => normaliseEmail(m.email) === wanted)
  if (!member) return { kind: 'failed', message: errorMessage(err) }

  const starts = [timedOut.get(wanted), stoppedWaiting(err) ? attemptStartedAt : undefined]
    .filter((t): t is number => t !== undefined)
  const joined = Date.parse(member.joinedAt)
  const landedLate = starts.length > 0 && !Number.isNaN(joined)
    && joined >= Math.min(...starts) - CLOCK_ALLOWANCE_MS
  if (landedLate) {
    return {
      kind: 'landed-late',
      member,
      message: `${member.displayName} (${member.email}) is a member now — the attempt to add them from `
        + 'this page finished after the page stopped waiting. Its one-time password never reached '
        + 'this page; Reset password issues a new one.',
    }
  }
  return {
    kind: 'already-member',
    member,
    message: `${member.displayName} (${member.email}) is already a member — since ${joinedLabel(member.joinedAt)}.`,
  }
}

/**
 * The confirmation in front of Reset password on a landed-late notice. It still replaces a
 * password: most likely the one-time one nobody received, but not provably so — they may have been
 * given it another way — so the first line says what stops working, as any reset must.
 */
export function resetAfterLostPasswordCopy(member: TenantMember): {
  title: string
  consequences: string[]
  confirmLabel: string
} {
  return {
    title: `Reset ${member.displayName}’s password?`,
    consequences: [
      'Their current password stops working immediately. If they have not signed in since they were '
        + 'added, that is the one-time password this page never received.',
      'You see a new temporary password once; they choose their own at their next sign-in.',
      'Their API tokens keep working.',
    ],
    confirmLabel: 'Reset password',
  }
}

function joinedLabel(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return iso
  return at.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

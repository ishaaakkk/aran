import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Check, Copy, KeyRound, Trash2 } from 'lucide-react'
import { api } from '@/services/api'
import { errorMessage } from '@/lib/errors'
import { memberCreateNeedsRelist } from '@/lib/memberCreateOutcome'
import { relativeTime } from '@/lib/adminFormat'
import { useAuthStore } from '@/stores/authStore'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import type { CreatedTenantMember, RemovedTenantMember, TenantMember } from '@/services/types'
import { SortHeader, TableToolbar } from './adminTable'
import { byRecency, byText, useAdminTable } from './useAdminTable'
import { type ResourceState, useAdminResource } from './useAdminResource'
import { SectionState } from './SectionState'
import {
  type ConfirmCopy, type MemberRole, invitationText, isOnlyOwner, removalSummary, removeMemberCopy,
  resetPasswordCopy, roleChangeCopy,
} from './memberActions'

/**
 * The people who can sign into this Aran instance.
 *
 * **The one section of the admin area that writes members.** Usage, Storage and Activity are
 * read-only by construction — a screen that can edit the history it displays has lost the property
 * that made the history worth keeping. That argument covers records of what happened; it does not
 * cover the accounts themselves, and applying it to users was a mistake that left the admin area
 * able to show that Marta ran 967 queries while sending you elsewhere to reset her password.
 *
 * **Nothing here commits on a single gesture** (audit J7-F4, J7-F16). A role select only proposes
 * the change; reset and remove open a confirmation that names what they do. The owner's own row is
 * marked and cannot remove itself; the only owner cannot be demoted, and the control says so
 * before the server has to.
 *
 * The temporary password is the design constraint, as with API tokens: it exists exactly once, in
 * the response that created it. It gets a panel that stays until dismissed rather than a toast,
 * because a toast that auto-hides while someone is switching windows loses the credential. Unlike
 * a token it is recoverable — reset mints another — which is why the copy is a reassurance rather
 * than a warning. A removal's result gets the same treatment when it hands the owner projects:
 * that is something to act on, not a notification to miss.
 */

const ROLE_HINTS: Record<MemberRole, string> = {
  MEMBER: 'Can work in the tenant. Cannot manage users.',
  OWNER: 'Everything a member can, plus managing users and seeing this area.',
}

export type Pending =
  | { kind: 'role'; user: TenantMember; next: MemberRole }
  | { kind: 'reset'; user: TenantMember }
  | { kind: 'remove'; user: TenantMember }

/** What the panel above the table shows after a create, a reinstatement or a reset. */
interface Handover {
  created: CreatedTenantMember
  reset: boolean
}

/** What the panel above the table shows after a removal. */
interface Removal {
  name: string
  result: RemovedTenantMember
}

const ROLE_FILTERS = [
  { value: '', label: 'All roles' },
  { value: 'OWNER', label: 'Owners' },
  { value: 'MEMBER', label: 'Members' },
] as const

export function UsersPanel() {
  const me = useAuthStore((s) => s.me)
  const tenant = useAuthStore((s) => s.activeTenant)
  const loadMe = useAuthStore((s) => s.loadMe)
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const roleFilter = params.get('role') ?? ''

  // Its own read, like every other admin section (J7-F11): a failure is a card with Retry, and a
  // refresh that fails keeps the table and says it is from the earlier read.
  const members = useAdminResource(() => api.listTenantMembers(), [])
  const [formError, setFormError] = useState('')
  const [handover, setHandover] = useState<Handover | null>(null)
  const [removal, setRemoval] = useState<Removal | null>(null)

  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<MemberRole>('MEMBER')
  const [creating, setCreating] = useState(false)
  const [pending, setPending] = useState<Pending | null>(null)
  const [acting, setActing] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const create = async (e: React.FormEvent) => {
    e.preventDefault()
    setCreating(true)
    try {
      setHandover({ created: await api.createTenantMember({ email, displayName, role }), reset: false })
      setRemoval(null)
      setEmail('')
      setDisplayName('')
      setRole('MEMBER')
      setFormError('')
      members.reload()
    } catch (err) {
      if (memberCreateNeedsRelist(err)) members.reload()
      setFormError(errorMessage(err))
    } finally {
      setCreating(false)
    }
  }

  const confirmPending = async () => {
    if (!pending) return
    setActing(true)
    setActionError(null)
    try {
      if (pending.kind === 'role') {
        await api.changeTenantMemberRole(pending.user.userId, pending.next)
        if (pending.user.userId === me?.userId && pending.next !== 'OWNER') {
          // Self-demotion: the admin area is no longer this person's, and the workspace nav must
          // stop offering it — reload who they are before leaving (audit J7-F4).
          setPending(null)
          await loadMe()
          navigate('/dashboard', { replace: true })
          return
        }
      } else if (pending.kind === 'reset') {
        setHandover({ created: await api.resetTenantMemberPassword(pending.user.userId), reset: true })
      } else {
        const result = await api.removeTenantMember(pending.user.userId)
        setRemoval({ name: pending.user.displayName, result })
      }
      setPending(null)
      members.reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setActing(false)
    }
  }

  const copy: ConfirmCopy | null = pending === null ? null
    : pending.kind === 'role' ? roleChangeCopy(pending.user, pending.next, pending.user.userId === me?.userId)
      : pending.kind === 'reset' ? resetPasswordCopy(pending.user)
        : removeMemberCopy(pending.user, tenant?.name ?? null)

  const propose = (next: Pending) => {
    setActionError(null)
    setPending(next)
  }

  return (
    <>
      <header className="admin-section-head">
        <h1>Users</h1>
        <p>
          Everyone who can sign into this Aran instance. Accounts are created, reset and removed
          here — there is no separate identity console to open.
        </p>
      </header>

      {formError && <p className="admin-error" role="alert">{formError}</p>}

      {handover && (
        <HandoverPanel
          handover={handover}
          organization={tenant?.name ?? null}
          onDismiss={() => setHandover(null)}
        />
      )}

      {removal && <RemovalPanel removal={removal} onDismiss={() => setRemoval(null)} />}

      <form className="admin-form" onSubmit={create}>
        <span className="admin-form-label">Add someone</span>
        <input
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="colleague@example.org"
          aria-label="Email"
        />
        <input
          type="text"
          required
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          placeholder="Full name"
          aria-label="Full name"
        />
        <select
          value={role}
          onChange={(e) => setRole(e.target.value as MemberRole)}
          aria-label="Role"
          title={ROLE_HINTS[role]}
        >
          <option value="MEMBER">Member</option>
          <option value="OWNER">Owner</option>
        </select>
        <button type="submit" disabled={creating}>{creating ? 'Creating…' : 'Create'}</button>
        <p className="admin-form-note">
          Someone you removed can be added again with the same email: their account comes back with a
          new temporary password, without the tokens and project access the removal took away.
          Someone new sees no project until they are added to one.
        </p>
      </form>

      <UsersSection
        resource={members}
        onRetry={members.reload}
        meId={me?.userId ?? null}
        roleFilter={roleFilter}
        onRoleFilter={(value) => setParams(value ? { role: value } : {}, { replace: true })}
        onPropose={propose}
      />

      {copy && (
        <ConfirmDialog
          open
          title={copy.title}
          consequences={copy.consequences}
          confirmLabel={copy.confirmLabel}
          tone={copy.tone}
          busy={acting}
          error={actionError}
          onConfirm={() => void confirmPending()}
          onCancel={() => setPending(null)}
          testId="admin-member-confirm"
        />
      )}
    </>
  )
}

/**
 * The member list, in the state its read is in. Exported for the render test: a failed read is a
 * card with Retry, like every other admin section's, never an error line with no way back.
 */
export function UsersSection({
  resource, onRetry, meId, roleFilter, onRoleFilter, onPropose,
}: {
  resource: ResourceState<TenantMember[]>
  onRetry: () => void
  meId: string | null
  roleFilter: string
  onRoleFilter: (value: string) => void
  onPropose: (p: Pending) => void
}) {
  return (
    <SectionState resource={resource} what="the organization’s members" onRetry={onRetry}>
      {(users) => (
        <MembersTable
          users={users}
          meId={meId}
          roleFilter={roleFilter}
          onRoleFilter={onRoleFilter}
          onPropose={onPropose}
        />
      )}
    </SectionState>
  )
}

function MembersTable({
  users, meId, roleFilter, onRoleFilter, onPropose,
}: {
  users: TenantMember[]
  meId: string | null
  roleFilter: string
  onRoleFilter: (value: string) => void
  onPropose: (p: Pending) => void
}) {
  const visible = roleFilter ? users.filter((u) => u.role === roleFilter) : users
  const table = useAdminTable(visible, {
    searchable: (u) => `${u.displayName} ${u.email}`,
    sorters: {
      displayName: byText<TenantMember>((u) => u.displayName),
      email: byText<TenantMember>((u) => u.email),
      role: byText<TenantMember>((u) => u.role),
      joinedAt: byRecency<TenantMember>((u) => u.joinedAt),
      lastSeenAt: byRecency<TenantMember>((u) => u.lastSeenAt ?? null),
    },
    initialSort: 'displayName',
  })

  return (
    <>
      <TableToolbar state={table} placeholder="Search name or email" noun="user">
        <select
          className="admin-inline-select admin-role-filter"
          value={roleFilter}
          aria-label="Filter by role"
          onChange={(e) => onRoleFilter(e.target.value)}
        >
          {ROLE_FILTERS.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}
        </select>
      </TableToolbar>

      <div className="admin-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <SortHeader label="Name" column="displayName" state={table} />
              <SortHeader label="Email" column="email" state={table} />
              <SortHeader label="Role" column="role" state={table} />
              <SortHeader label="Last active" column="lastSeenAt" state={table} />
              <SortHeader label="Joined" column="joinedAt" state={table} />
              <th scope="col"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {table.rows.map((u) => (
              <MemberRow
                key={u.userId}
                user={u}
                isSelf={u.userId === meId}
                onlyOwner={isOnlyOwner(u, users)}
                onPropose={onPropose}
              />
            ))}
            {table.rows.length === 0 && (
              <tr>
                <td colSpan={6} className="admin-empty">
                  {table.query ? `Nobody matches "${table.query}".`
                    : roleFilter ? 'Nobody has that role.' : 'Nobody yet — add someone above.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

    </>
  )
}

/**
 * One member. Exported for the render test: the "(you)" mark, the disabled self-removal and the
 * only-owner lock are properties of a row, and they are what stops an owner locking themselves or
 * the tenant out.
 */
export function MemberRow({
  user, isSelf, onlyOwner, onPropose,
}: {
  user: TenantMember
  isSelf: boolean
  onlyOwner: boolean
  onPropose: (p: Pending) => void
}) {
  return (
    <tr data-testid={`admin-member-${user.email}`}>
      <td>
        <span className="admin-name">{user.displayName}</span>
        {isSelf && <span className="admin-you"> (you)</span>}
      </td>
      <td><span className="admin-sub">{user.email}</span></td>
      <td>
        {/* The select proposes; the confirmation commits (J7-F4). Its value stays the stored role
            until then, so cancelling puts it back by itself. */}
        <select
          className="admin-inline-select"
          value={user.role}
          disabled={onlyOwner}
          onChange={(e) => onPropose({ kind: 'role', user, next: e.target.value as MemberRole })}
          aria-label={`Role for ${user.displayName}`}
          title={onlyOwner ? 'The only owner cannot be demoted — make someone else an owner first.' : ROLE_HINTS[user.role]}
        >
          <option value="MEMBER">Member</option>
          <option value="OWNER">Owner</option>
        </select>
      </td>
      <td title={user.lastSeenAt ? new Date(user.lastSeenAt).toLocaleString() : undefined}>
        {relativeTime(user.lastSeenAt ?? null)}
      </td>
      {/* An absolute date, not "9 d ago". relativeTime falls back to a date past a month, which
          mixes both forms in one column and makes it impossible to scan — and joining is an event
          you reference by date anyway, unlike "last active", where recency is the whole question.
          Exact timestamp on hover. */}
      <td title={new Date(user.joinedAt).toLocaleString()}>{joined(user.joinedAt)}</td>
      <td>
        <div className="admin-row-actions">
          <button
            type="button"
            onClick={() => onPropose({ kind: 'reset', user })}
            disabled={isSelf}
            title={isSelf ? 'Change your own password from the account menu' : 'Reset password'}
            aria-label={`Reset password for ${user.displayName}`}
          >
            <KeyRound className="w-4 h-4" />
          </button>
          <button
            type="button"
            className="is-destructive"
            onClick={() => onPropose({ kind: 'remove', user })}
            disabled={isSelf}
            title={isSelf ? 'You cannot remove yourself — ask another owner' : 'Remove from organization'}
            aria-label={`Remove ${user.displayName}`}
          >
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      </td>
    </tr>
  )
}

/**
 * After a create, a reset or a reinstatement: what to hand over, shown until dismissed. A
 * reinstatement carries a new password too (UI-OD9), so it is the same panel under its own title.
 * Exported for the render test.
 */
export function HandoverPanel({
  handover, organization, onDismiss,
}: { handover: Handover; organization: string | null; onDismiss: () => void }) {
  const { created, reset } = handover
  const reinstated = created.reinstated === true
  const [copied, setCopied] = useState<'password' | 'invitation' | null>(null)

  const copy = async (what: 'password' | 'invitation', text: string) => {
    await navigator.clipboard.writeText(text)
    setCopied(what)
    setTimeout(() => setCopied(null), 2000)
  }

  const password = created.temporaryPassword
  const invitation = invitationText({
    url: typeof window === 'undefined' ? '' : window.location.origin,
    email: created.member.email,
    password,
    organization,
    reset,
    reinstated,
  })

  return (
    <div
      className="admin-secret"
      role="status"
      data-testid={reinstated ? 'admin-reinstated' : 'admin-temporary-password'}
    >
      <div>
        <p className="admin-secret-label">
          {reinstated
            ? `Access restored for ${created.member.displayName} — temporary password`
            : `Temporary password for ${created.member.displayName}`}
        </p>
        <code>{password}</code>
        <p className="admin-secret-note">
          {reinstated
            ? 'Their account is back with this new password — the one they had no longer works. '
              + 'Their earlier API tokens stay revoked, and they see no project until you add them to one. '
            : ''}
          Shown once. They will be asked to change it at their next sign-in — and if it is lost,
          reset mints a new one, so this is recoverable.
        </p>
      </div>
      <div className="admin-secret-actions">
        <button type="button" onClick={() => void copy('invitation', invitation)}>
          {copied === 'invitation' ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
          <span>{copied === 'invitation' ? 'Copied' : reset || reinstated ? 'Copy sign-in details' : 'Copy invitation'}</span>
        </button>
        <button type="button" onClick={() => void copy('password', password)}>
          {copied === 'password' ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
          <span>{copied === 'password' ? 'Copied' : 'Copy password'}</span>
        </button>
        <button type="button" className="is-quiet" onClick={onDismiss}>
          I have saved it
        </button>
      </div>
    </div>
  )
}

/**
 * After a removal: what it took away, and — when it handed projects over so none was left
 * unreachable — which ones are now the owner's to look after. Exported for the render test.
 */
export function RemovalPanel({ removal, onDismiss }: { removal: Removal; onDismiss: () => void }) {
  const { headline, handedOver } = removalSummary(removal.name, removal.result)
  return (
    <div className="admin-secret" role="status" data-testid="admin-member-removed">
      <div>
        <p className="admin-secret-label">{headline}</p>
        {handedOver && <p className="admin-secret-note">{handedOver}</p>}
      </div>
      <div className="admin-secret-actions">
        <button type="button" className="is-quiet" onClick={onDismiss}>Dismiss</button>
      </div>
    </div>
  )
}

/** Short, unambiguous, and the same shape for every row however old. */
function joined(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
  })
}

export default UsersPanel

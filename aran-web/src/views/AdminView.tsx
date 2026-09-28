import { useMemo, useState } from 'react'
import { Link, Navigate, useParams, useSearchParams } from 'react-router-dom'
import { ShieldAlert } from 'lucide-react'
import { api } from '../services/api'
import { AdminLayout } from '../layouts/AdminLayout'
import { useAuthStore } from '../stores/authStore'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { errorMessage } from '../lib/errors'
import type {
  AdminTokenStatus,
  AdminTokenSummary,
  DatasetStorage,
  AuditEntry,
  MemberUsage,
  StorageReport,
  SystemStatus,
  TenantMember,
  TenantOverview,
} from '../services/types'
import { compactCount, formatBytes, relativeTime } from '../lib/adminFormat'
import { UsersPanel } from './admin/UsersPanel'
import { FilterChip, SortHeader, TableToolbar } from './admin/adminTable'
import {
  byNullableNumber, byNumberDesc, byRecency, byText, useAdminTable,
} from './admin/useAdminTable'
import { useAdminResource } from './admin/useAdminResource'
import { RefreshedNote, SectionState } from './admin/SectionState'
import { NOT_YET_RECORDED, RECORDED_ACTIONS, actionLabel } from './admin/auditActions'
import { componentRole, componentsDown, describeDetails } from './admin/systemComponents'
import './admin.css'

/**
 * Tenant administration — the content of the admin area.
 *
 * The chrome is {@link AdminLayout}, which owns the whole viewport; this component picks a section
 * from the URL. `/admin/usage`, `/admin/credentials` and `/admin/activity` are real routes rather
 * than local tab state so an owner can link a colleague straight at what they are talking about,
 * and so the back button does what it looks like it does.
 *
 * **Each section owns its read** (audit J7-F11, J7-F3). They used to load together, so every
 * section waited for the slowest — a live lake scan — and one failure blanked them all: with the
 * lake down, System, the page opened to find what is down, showed only an error. Now a failure is
 * its section's own card with Retry, and the others render.
 *
 * **Owners only** (audit J7-F5). The server gates every route; the page now says so up front,
 * with who to ask, instead of showing a live form that refuses after it is used.
 */

const SECTIONS = ['users', 'usage', 'storage', 'credentials', 'activity', 'system'] as const
type Section = (typeof SECTIONS)[number]

/** What Activity asks for. Named so the table can say the list is the most recent N. */
const ACTIVITY_LIMIT = 100

const WINDOWS = [
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
]

export default function AdminView() {
  const { section } = useParams<{ section: string }>()
  const role = useAuthStore((s) => s.activeTenant?.role)

  // An unknown section is a typo, not an error page: send it to the default.
  if (!section || !SECTIONS.includes(section as Section)) {
    return <Navigate to="/admin/users" replace />
  }
  return <AdminScreen section={section as Section} role={role ?? null} />
}

/** The screen for a known section and the caller's role — split out so a test can pass the role. */
export function AdminScreen({ section: active, role }: { section: Section; role: 'OWNER' | 'MEMBER' | null }) {
  if (role !== 'OWNER') {
    return <AdminLayout restricted><NotAnOwner /></AdminLayout>
  }

  return (
    <AdminLayout>
      {active === 'users' && <UsersPanel />}
      {active === 'usage' && <UsageSection />}
      {active === 'storage' && <StorageSection />}
      {active === 'credentials' && <CredentialsSection />}
      {active === 'activity' && <ActivitySection />}
      {active === 'system' && <SystemSection />}
    </AdminLayout>
  )
}

function SectionHead({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <header className="admin-section-head">
      <h1>{title}</h1>
      <div className="admin-section-lede">{children}</div>
    </header>
  )
}

// ── not an owner ─────────────────────────────────────────────────────────

/**
 * What a member sees at `/admin/*` (audit J7-F5): who can help, and the way back. The owners are
 * read from the member list, which members may read; if that fails the panel still says what to do.
 */
function NotAnOwner() {
  const tenant = useAuthStore((s) => s.activeTenant)
  const members = useAdminResource(() => api.listTenantMembers(), [])
  return <NotAnOwnerPanel organization={tenant?.name ?? null} members={members.data} />
}

export function NotAnOwnerPanel({
  organization, members,
}: { organization: string | null; members: TenantMember[] | null }) {
  const owners = (members ?? []).filter((m) => m.role === 'OWNER')
  return (
    <section className="admin-permission" data-testid="admin-not-owner" aria-labelledby="admin-not-owner-title">
      <ShieldAlert className="admin-permission-icon" aria-hidden="true" />
      <h1 id="admin-not-owner-title">Administration is for organization owners</h1>
      <p>
        You are a member of {organization ?? 'this organization'}. Managing people, credentials and
        storage needs the owner role — ask an owner if you need something changed.
      </p>
      {owners.length > 0 && (
        <>
          <h2>Owners</h2>
          <ul>
            {owners.map((o) => (
              <li key={o.userId}>
                <span className="admin-name">{o.displayName}</span>{' '}
                <a className="admin-sub admin-link" href={`mailto:${o.email}`}>{o.email}</a>
              </li>
            ))}
          </ul>
        </>
      )}
      <Link to="/dashboard" className="admin-back">Back to workspace</Link>
    </section>
  )
}

// ── usage ────────────────────────────────────────────────────────────────

function UsageSection() {
  const [days, setDays] = useState(30)
  const overview = useAdminResource(() => api.tenantOverview(days), [days])

  return (
    <>
      <div className="admin-section-bar">
        <SectionHead title="Usage">
          <p>
            What each member has consumed
            {overview.data ? ` since ${new Date(overview.data.since).toLocaleDateString()}` : ''}.
            Sorted by activity, not alphabetically — the question this answers is who is doing the
            expensive work.
          </p>
        </SectionHead>

        <div className="admin-window" role="group" aria-label="Reporting window">
          {WINDOWS.map((w) => (
            <button
              key={w.days}
              type="button"
              className={days === w.days ? 'is-active' : ''}
              aria-pressed={days === w.days}
              onClick={() => setDays(w.days)}
            >
              {w.label}
            </button>
          ))}
        </div>
      </div>
      <RefreshedNote refreshedAt={overview.refreshedAt} loading={overview.loading} onRefresh={overview.reload} />
      <SectionState resource={overview} what="usage" onRetry={overview.reload}>
        {(data) => <UsagePanel overview={data} />}
      </SectionState>
    </>
  )
}

function UsagePanel({ overview }: { overview: TenantOverview }) {
  const table = useAdminTable(overview.members, {
    searchable: (m) => `${m.displayName} ${m.email}`,
    sorters: {
      displayName: byText<MemberUsage>((m) => m.displayName),
      role: byText<MemberUsage>((m) => m.role ?? ''),
      lastSeenAt: byRecency<MemberUsage>((m) => m.lastSeenAt),
      events: byNumberDesc<MemberUsage>((m) => m.events),
      rows: byNumberDesc<MemberUsage>((m) => m.rows),
      bytesOut: byNumberDesc<MemberUsage>((m) => m.bytesOut),
      p95: byNullableNumber<MemberUsage>((m) => m.p95DurationMs),
    },
    initialSort: 'events',
    // The unattributed row is real data, not a member — it never sorts in among people.
    pinLast: (m) => m.userId === null,
  })

  return (
    <>
      <section className="admin-stats">
        <Stat label="Members" value={overview.memberCount.toLocaleString()} />
        <Stat label="Active tokens" value={overview.activeTokenCount.toLocaleString()} />
        <Stat label="Operations" value={overview.totalEvents.toLocaleString()} />
        <Stat label="Rows returned" value={compactCount(overview.totalRows)} />
        {/* Three figures, not one "data moved": uploads, egress and lake writes cost
            different things, and summing them makes the total mean nothing. */}
        <Stat label="Ingress" value={formatBytes(overview.totalBytesIn)} />
        <Stat label="Egress" value={formatBytes(overview.totalBytesOut)} />
        <Stat label="Written to lake" value={formatBytes(overview.totalBytesStored)} />
      </section>

      <TableToolbar state={table} placeholder="Search member" noun="row" />

      <div className="admin-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <SortHeader label="Member" column="displayName" state={table} />
              <SortHeader label="Role" column="role" state={table} />
              <SortHeader label="Last seen" column="lastSeenAt" state={table} />
              <SortHeader label="Operations" column="events" state={table} align="num" />
              <SortHeader label="Rows" column="rows" state={table} align="num" />
              <SortHeader label="Egress" column="bytesOut" state={table} align="num" />
              <SortHeader label="p95" column="p95" state={table} align="num" />
            </tr>
          </thead>
          <tbody>
            {table.rows.map((m) => <UsageRow key={m.userId ?? 'unattributed'} member={m} />)}
            {table.rows.length === 0 && (
              <tr>
                <td colSpan={7} className="admin-empty">
                  {table.query ? 'Nobody matches.' : 'No members yet.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}

function UsageRow({ member }: { member: MemberUsage }) {
  // The unattributed row is real data, not a member — styled apart so it is never mistaken
  // for a person who has been very busy.
  const systemRow = member.userId === null
  return (
    <tr className={systemRow ? 'is-system' : undefined}>
      <td>
        {/* A member row is a starting point, not a dead end: this is the fastest route from
            "who is doing the expensive work" to "what credentials do they hold". */}
        {systemRow ? (
          <span className="admin-name">{member.displayName}</span>
        ) : (
          <Link className="admin-name admin-link" to={`/admin/credentials?user=${member.userId}`}>
            {member.displayName}
          </Link>
        )}
        {member.email && <span className="admin-sub">{member.email}</span>}
      </td>
      <td>{member.role ? <span className={`admin-role role-${member.role.toLowerCase()}`}>{member.role}</span> : '—'}</td>
      <td>{systemRow ? '—' : relativeTime(member.lastSeenAt)}</td>
      <td className="num">{member.events.toLocaleString()}</td>
      <td className="num">{compactCount(member.rows)}</td>
      <td className="num">{formatBytes(member.bytesOut)}</td>
      <td className="num">{member.p95DurationMs === null ? '—' : `${Math.round(member.p95DurationMs)} ms`}</td>
    </tr>
  )
}

// ── storage ──────────────────────────────────────────────────────────────

function StorageSection() {
  const storage = useAdminResource(() => api.adminStorage(), [])
  return (
    <>
      <SectionHead title="Storage">
        <p>
          What this tenant is holding, read live. Deduplicated is what actually occupies the
          bucket — snapshots are content-addressed, so the same bytes uploaded twice are stored
          once and only the first figure counts them twice.
        </p>
      </SectionHead>
      <RefreshedNote refreshedAt={storage.refreshedAt} loading={storage.loading} onRefresh={storage.reload} />
      <SectionState
        resource={storage}
        what="storage"
        onRetry={storage.reload}
        hint={
          <>
            Storage is read live from the lake, so this section cannot load while the lake is not
            answering. <Link className="admin-link" to="/admin/system">System</Link> shows whether
            it is down. This is not the same as storing nothing.
          </>
        }
      >
        {(data) => <StoragePanel report={data} />}
      </SectionState>
    </>
  )
}

function StoragePanel({ report }: { report: StorageReport }) {
  const growth = report.baselinePhysicalBytes === null
    ? null
    : report.physicalBytes - report.baselinePhysicalBytes

  return (
    <>
      <section className="admin-stats">
        <Stat label="Stored (deduplicated)" value={formatBytes(report.physicalBytes)} />
        <Stat label="Stored (logical)" value={formatBytes(report.logicalBytes)} />
        <Stat label="Snapshots" value={report.snapshotCount.toLocaleString()} />
        <Stat
          label={growth === null ? 'Growth' : `Growth since ${new Date(report.baselineMeasuredAt!).toLocaleDateString()}`}
          value={growth === null ? 'not yet measured' : `${growth >= 0 ? '+' : '−'}${formatBytes(Math.abs(growth))}`}
        />
        <Stat label="Reclaimable" value={reclaimable(report)} />
      </section>

      <p className="admin-note">
        {report.unreferenced.known ? (
          <>
            {report.unreferenced.snapshots.toLocaleString()} snapshot
            {report.unreferenced.snapshots === 1 ? '' : 's'} are referenced by no dataset, data
            product or schema fingerprint — every product refresh writes a new file and keeps
            the old one. Nothing here deletes them yet.
            {!report.unreferenced.complete && (
              <> This is a lower bound: the scan stopped after {report.unreferenced.scanned.toLocaleString()} snapshots.</>
            )}
          </>
        ) : (
          <>Reclaimable space could not be measured — the lake did not answer. This is not the
          same as having nothing to reclaim.</>
        )}
      </p>

      <DatasetTable report={report} />
    </>
  )
}

function DatasetTable({ report }: { report: StorageReport }) {
  const table = useAdminTable(report.datasets, {
    searchable: (d) => d.datasetId,
    sorters: {
      logicalBytes: byNumberDesc<DatasetStorage>((d) => d.logicalBytes),
      snapshotCount: byNumberDesc<DatasetStorage>((d) => d.snapshotCount),
      datasetId: byText<DatasetStorage>((d) => d.datasetId),
    },
    initialSort: 'logicalBytes',
  })

  return (
    <>
      <TableToolbar state={table} placeholder="Search dataset id" noun="dataset">
        {report.datasetsTruncated && (
          <span className="admin-truncated">largest only — </span>
        )}
      </TableToolbar>

      <div className="admin-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <SortHeader label="Dataset" column="datasetId" state={table} />
              <SortHeader label="Snapshots" column="snapshotCount" state={table} align="num" />
              <SortHeader label="Size" column="logicalBytes" state={table} align="num" />
            </tr>
          </thead>
          <tbody>
            {table.rows.map((d) => (
              <tr key={d.datasetId}>
                <td><span className="admin-name mono">{d.datasetId}</span></td>
                <td className="num">{d.snapshotCount.toLocaleString()}</td>
                <td className="num">{formatBytes(d.logicalBytes)}</td>
              </tr>
            ))}
            {table.rows.length === 0 && (
              <tr>
                <td colSpan={3} className="admin-empty">
                  {table.query ? 'No datasets match.' : 'Nothing stored yet.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}

/** "Unknown" and "none" are different answers and must not share a rendering. */
function reclaimable(report: StorageReport): string {
  if (!report.unreferenced.known) return 'unknown'
  if (report.unreferenced.bytes === 0) return 'none'
  return `${report.unreferenced.complete ? '' : '≥ '}${formatBytes(report.unreferenced.bytes)}`
}

// ── credentials ──────────────────────────────────────────────────────────

const TOKEN_STATUS_LABEL: Record<AdminTokenStatus, string> = {
  active: 'active',
  expired: 'expired',
  revoked: 'revoked',
  owner_removed: 'holder removed',
}

/** The server's word when it sent one; a pre-status payload is read conservatively, never as live. */
function tokenStatus(t: AdminTokenSummary): AdminTokenStatus | 'unknown' {
  if (t.status) return t.status
  return t.revoked ? 'revoked' : 'unknown'
}

function CredentialsSection() {
  const tokens = useAdminResource(() => api.adminTokens(), [])
  return (
    <>
      <SectionHead title="Credentials">
        <p>
          Every API token in this tenant. Aran stores a hash and the visible prefix — the secret
          itself cannot be shown again by anyone, including you. Revoking a token here stops it
          at once and is recorded in Activity; removing a member revokes all of theirs.
        </p>
      </SectionHead>
      <RefreshedNote refreshedAt={tokens.refreshedAt} loading={tokens.loading} onRefresh={tokens.reload} />
      <SectionState resource={tokens} what="API tokens" onRetry={tokens.reload}>
        {(data) => <CredentialsPanel tokens={data} onChanged={tokens.reload} />}
      </SectionState>
    </>
  )
}

function CredentialsPanel({ tokens, onChanged }: { tokens: AdminTokenSummary[]; onChanged: () => void }) {
  const [params, setParams] = useSearchParams()
  const userFilter = params.get('user')
  const [revoking, setRevoking] = useState<AdminTokenSummary | null>(null)
  const [busy, setBusy] = useState(false)
  const [revokeError, setRevokeError] = useState<string | null>(null)

  const scoped = userFilter ? tokens.filter((t) => t.userId === userFilter) : tokens
  const table = useAdminTable(scoped, {
    searchable: (t) => `${t.name} ${t.ownerName} ${t.prefix}`,
    sorters: {
      name: byText<AdminTokenSummary>((t) => t.name),
      ownerName: byText<AdminTokenSummary>((t) => t.ownerName),
      scope: byText<AdminTokenSummary>((t) => t.scope),
      status: byText<AdminTokenSummary>((t) => tokenStatus(t)),
      lastUsedAt: byRecency<AdminTokenSummary>((t) => t.lastUsedAt),
      expiresAt: byRecency<AdminTokenSummary>((t) => t.expiresAt),
    },
    initialSort: 'lastUsedAt',
  })
  const filterName = tokens.find((t) => t.userId === userFilter)?.ownerName ?? 'one member'

  const revoke = async () => {
    if (!revoking) return
    setBusy(true)
    setRevokeError(null)
    try {
      await api.revokeTenantToken(revoking.id)
      setRevoking(null)
      onChanged()
    } catch (e) {
      setRevokeError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <TableToolbar state={table} placeholder="Search token, owner or prefix" noun="token">
        {userFilter && (
          <FilterChip label={filterName} onClear={() => setParams({}, { replace: true })} />
        )}
      </TableToolbar>

      <div className="admin-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <SortHeader label="Token" column="name" state={table} />
              <SortHeader label="Owner" column="ownerName" state={table} />
              <SortHeader label="Scope" column="scope" state={table} />
              <SortHeader label="Status" column="status" state={table} />
              <SortHeader label="Last used" column="lastUsedAt" state={table} />
              <SortHeader label="Expires" column="expiresAt" state={table} />
              <th scope="col"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {table.rows.map((t) => (
              <TokenRow key={t.id} token={t} onRevoke={() => { setRevokeError(null); setRevoking(t) }} />
            ))}
            {table.rows.length === 0 && (
              <tr>
                <td colSpan={7} className="admin-empty">
                  {table.query || userFilter ? 'No tokens match.' : 'No tokens have been created.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {revoking && (
        <ConfirmDialog
          open
          title={`Revoke “${revoking.name}”?`}
          consequences={[
            `${revoking.ownerName}’s token ${revoking.prefix}… stops working immediately — any script, notebook or JDBC client using it fails its next request.`,
            revoking.lastUsedAt
              ? `It was last used ${relativeTime(revoking.lastUsedAt)}.`
              : 'It has never been used.',
            'This cannot be undone; they can create a new token themselves.',
            'The revocation is recorded in Activity.',
          ]}
          confirmLabel="Revoke token"
          busy={busy}
          error={revokeError}
          onConfirm={() => void revoke()}
          onCancel={() => setRevoking(null)}
          testId="admin-token-revoke"
        />
      )}
    </>
  )
}

/** One token row. Exported for the render test: its status comes from the server, never inferred. */
export function TokenRow({ token, onRevoke }: { token: AdminTokenSummary; onRevoke: () => void }) {
  const status = tokenStatus(token)
  const live = status === 'active' || status === 'unknown'
  return (
    <tr className={live ? undefined : 'is-revoked'} data-testid={`admin-token-${token.id}`}>
      <td>
        <span className="admin-name">{token.name}</span>
        <span className="admin-sub mono">{token.prefix}…</span>
      </td>
      <td>{token.ownerName}</td>
      <td><span className={`admin-scope scope-${token.scope.toLowerCase()}`}>{token.scope}</span></td>
      <td className="admin-keep">
        <span className={`admin-token-status status-${status}`}>
          {status === 'unknown' ? 'unknown' : TOKEN_STATUS_LABEL[status]}
        </span>
      </td>
      <td>{relativeTime(token.lastUsedAt)}</td>
      <td>{token.expiresAt ? new Date(token.expiresAt).toLocaleDateString() : 'never'}</td>
      <td className="admin-keep">
        <div className="admin-row-actions">
          {status !== 'revoked' && (
            <button
              type="button"
              className="admin-text-action is-destructive"
              onClick={onRevoke}
              aria-label={`Revoke token ${token.name} held by ${token.ownerName}`}
            >
              Revoke
            </button>
          )}
        </div>
      </td>
    </tr>
  )
}

// ── activity ─────────────────────────────────────────────────────────────

function ActivitySection() {
  const activity = useAdminResource(() => api.adminActivity(ACTIVITY_LIMIT), [])
  // Names for the actor column. The member list, not the usage overview: it is the cheap read,
  // and it is the one that exists for exactly this question.
  const members = useAdminResource(() => api.listTenantMembers(), [])
  return (
    <>
      <ActivityHeader />
      <RefreshedNote refreshedAt={activity.refreshedAt} loading={activity.loading} onRefresh={activity.reload} />
      <SectionState resource={activity} what="activity" onRetry={activity.reload}>
        {(data) => <ActivityPanel entries={data} members={members.data ?? []} />}
      </SectionState>
    </>
  )
}

/**
 * Audit J7-F1: the log was described as governance while recording five actions, so a missing
 * line read as "nobody did it". The header now says exactly what is recorded — the list is pinned
 * against the server's writers by `auditActions.test.ts` — and what is not yet.
 */
export function ActivityHeader() {
  const recorded = Object.values(RECORDED_ACTIONS)
  return (
    <SectionHead title="Activity">
      <p>
        Governance events, newest first. Append-only: nothing here can be edited or removed, which
        is the property that makes it worth keeping.
      </p>
      <p className="admin-recorded" data-testid="admin-activity-recorded">
        <strong>Recorded:</strong> {recorded.join(', ')}.{' '}
        <strong>Everything else is not recorded yet</strong>, including{' '}
        {NOT_YET_RECORDED.join(', ')} — a missing line for any of it does not mean it did not
        happen.
      </p>
    </SectionHead>
  )
}

function ActivityPanel({ entries, members }: { entries: AuditEntry[]; members: TenantMember[] }) {
  const [params, setParams] = useSearchParams()
  const actorFilter = params.get('actor')
  const nameOf = useMemo(() => {
    const byId = new Map(members.map((m) => [m.userId, m.displayName]))
    return (userId: string | null) => {
      if (!userId) return 'system'
      // A shortened id rather than the bare UUID: an audit row whose actor left the tenant still
      // has to say *something*, and eight characters tell two apart.
      return byId.get(userId) ?? `${userId.slice(0, 8)}…`
    }
  }, [members])

  const scoped = actorFilter ? entries.filter((e) => e.actorId === actorFilter) : entries
  const table = useAdminTable(scoped, {
    searchable: (e) => `${e.action} ${actionLabel(e.action)} ${e.targetType} ${nameOf(e.actorId)}`,
    sorters: {
      occurredAt: byRecency<AuditEntry>((e) => e.occurredAt),
      action: byText<AuditEntry>((e) => e.action),
      actor: byText<AuditEntry>((e) => nameOf(e.actorId)),
    },
    initialSort: 'occurredAt',
  })

  return (
    <>
      <TableToolbar
        state={table}
        placeholder="Search action, target or actor"
        noun="event"
        cappedAt={actorFilter ? undefined : ACTIVITY_LIMIT}
      >
        {actorFilter && (
          <FilterChip label={nameOf(actorFilter)} onClear={() => setParams({}, { replace: true })} />
        )}
      </TableToolbar>

      <div className="admin-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <SortHeader label="When" column="occurredAt" state={table} />
              <SortHeader label="Action" column="action" state={table} />
              <SortHeader label="Actor" column="actor" state={table} />
              <th scope="col">Target</th>
            </tr>
          </thead>
          <tbody>
            {table.rows.map((e) => (
              <tr key={e.id}>
                {/* Relative to read, exact to quote in a ticket. */}
                <td title={new Date(e.occurredAt).toLocaleString()}>
                  {relativeTime(e.occurredAt)}
                </td>
                <td title={actionLabel(e.action)}><code className="admin-action">{e.action}</code></td>
                <td>{e.actorId ? nameOf(e.actorId) : <span className="admin-sub">system</span>}</td>
                <td><span className="admin-sub mono">{e.targetType}</span></td>
              </tr>
            ))}
            {table.rows.length === 0 && (
              <tr>
                <td colSpan={4} className="admin-empty">
                  {table.query || actorFilter ? 'No events match.' : 'Nothing recorded yet.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}

// ── system ───────────────────────────────────────────────────────────────

function SystemSection() {
  const system = useAdminResource(() => api.adminSystem(), [])
  return (
    <>
      <SectionHead title="System">
        <p>
          This deployment and the services it depends on. Everything here is meant to be pasted
          into a bug report — a version, a commit and the component states turn "it doesn't work"
          into something someone can act on.
        </p>
      </SectionHead>
      <RefreshedNote refreshedAt={system.refreshedAt} loading={system.loading} onRefresh={system.reload} />
      <SectionState
        resource={system}
        what="system status"
        onRetry={system.reload}
        hint="The status read goes to Aran itself, so this usually means Aran is restarting or overloaded, not that a dependency is down."
      >
        {(data) => <SystemPanel status={data} />}
      </SectionState>
    </>
  )
}

/** Exported for the render test: a DOWN service gets a card that says what stops working. */
export function SystemPanel({ status }: { status: SystemStatus }) {
  const down = componentsDown(status.components)
  return (
    <>
      <section className="admin-stats">
        <Stat label="Version" value={status.version} />
        <Stat label="Commit" value={status.commit ?? '—'} />
        <Stat label="Overall" value={status.status} />
        <Stat label="Components" value={String(status.components.length)} />
      </section>

      {down.length > 0 && (
        <section className="admin-down-list" aria-label="Services that are not up" data-testid="admin-system-down">
          {down.map((c) => {
            const role = componentRole(c.name)
            return (
              <article key={c.name} className="admin-down-card" data-testid={`admin-system-down-${c.name}`}>
                <h2>
                  {role.label} is {c.status}
                </h2>
                <p>{role.impact}</p>
                <p className="admin-sub mono">{c.name}: {describeDetails(c.details)}</p>
              </article>
            )
          })}
        </section>
      )}

      <p className="admin-note">
        A component is UP when it answers at all — a 401 still means the process is listening,
        which is the distinction between "restart this container" and "look elsewhere". Only a
        timeout or a refused connection is DOWN. Profiling and query execution degrade
        independently, so one DOWN row does not mean Aran is unusable.
      </p>

      <div className="admin-scroll">
        <table className="admin-table">
          <thead>
            <tr>
              <th scope="col">Component</th>
              <th scope="col">Status</th>
              <th scope="col">Detail</th>
            </tr>
          </thead>
          <tbody>
            {status.components.map((c) => (
              <tr key={c.name} className={c.status === 'UP' ? undefined : 'is-degraded'}>
                <td>
                  <span className="admin-name">{c.name}</span>
                  <span className="admin-sub">{componentRole(c.name).label}</span>
                </td>
                <td>
                  {/* Neutral when UP. Amber means authority on this screen — an UP row wearing
                      it would compete with an OWNER badge for the same glance. */}
                  <span className={`admin-scope${c.status === 'UP' ? '' : ' scope-down'}`}>
                    {c.status}
                  </span>
                </td>
                <td><span className="admin-sub mono">{describeDetails(c.details)}</span></td>
              </tr>
            ))}
            {status.components.length === 0 && (
              <tr><td colSpan={3} className="admin-empty">No components reported.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="admin-stat">
      <span className="admin-stat-value">{value}</span>
      <span className="admin-stat-label">{label}</span>
    </div>
  )
}

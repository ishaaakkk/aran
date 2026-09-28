import React, { useEffect, useMemo, useState } from 'react';
import { api } from '@/services/api';
import { errorMessage } from '@/lib/errors';
import { CmdHeader } from '@/components/CmdHeader';
import { CONCEPTS } from '@/lib/concepts';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PromoteConfirmDialog } from '@/components/PromoteConfirmDialog';
import { SchemaDiffList } from '@/components/SchemaDiffList';
import { formatZoned } from '@/lib/zonedTime';
import { rollbackConsequences } from '@/lib/rollbackConsequences';
import { LiveStatus } from '@/components/LiveStatus';
import { describeVersionActionTimeout, type VersionAction, type VersionActionCheck } from '@/lib/versionActionOutcome';
import { RotateCw } from 'lucide-react';
import type { SchemaVersion, SchemaDiff } from '@/services/types';

function stillRunning(err: unknown): boolean {
  return (err as { mayStillBeRunning?: boolean } | null)?.mayStillBeRunning === true;
}

/**
 * VersionsView (M7).
 *
 * Makes the GraphVersion machinery visible: a history table, a class/property-level
 * diff between any two selected versions, promotion with notes, and rollback of a
 * HISTORICAL version. Every row here is a real version the tenant owns; nothing is
 * invented.
 */

const STATUS_STYLES: Record<string, string> = {
  ACTIVE: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
  DRAFT: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
  HISTORICAL: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
};

/**
 * `data-testid="version-status-badge"` is a plain attribute addition for the e2e suite:
 * AlignmentsPage.promote() navigates here after a model-aware promotion (see
 * AlignmentsView.handlePromoteDraft) and has no other reliable, view-local signal that
 * the new version actually went ACTIVE.
 */
const StatusBadge: React.FC<{ status: string }> = ({ status }) => (
  <span
    data-testid="version-status-badge"
    className={`inline-flex items-center font-mono font-semibold uppercase tracking-wider border rounded-sm text-[9px] px-1.5 py-[2px] leading-tight select-none ${
      STATUS_STYLES[status] ?? 'bg-muted/20 text-muted-foreground border-border'
    }`}
  >
    {status}
  </span>
);

export const VersionsView: React.FC = () => {
  const [versions, setVersions] = useState<SchemaVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Diff selection: up to two version ids (a → b).
  const [selected, setSelected] = useState<string[]>([]);
  const [diff, setDiff] = useState<SchemaDiff | null>(null);
  const [diffError, setDiffError] = useState<string | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);

  // Promote-with-notes modal.
  const [promoteTarget, setPromoteTarget] = useState<SchemaVersion | null>(null);
  const [promoteNotes, setPromoteNotes] = useState('');
  const [busy, setBusy] = useState(false);
  // Rendered INSIDE the open modal: a failure that lands in the page banner sits behind
  // the modal's dimmed backdrop, so a failed promote used to look like a slow one
  // (redesign brief, versions HIGH #2).
  const [modalError, setModalError] = useState<string | null>(null);

  // Rollback typed-confirmation modal, with what it changes against ACTIVE (J4-F18).
  const [rollbackTarget, setRollbackTarget] = useState<SchemaVersion | null>(null);
  const [rollbackDiff, setRollbackDiff] = useState<SchemaDiff | null>(null);
  const [rollbackDiffError, setRollbackDiffError] = useState<string | null>(null);

  // A promote or rollback the page stopped waiting for (J4-F11, third review of UA2): its modal
  // closes — so its button is not one click from a duplicate — and what a re-read of the list says
  // stays on the page with Check again.
  const [actionCheck, setActionCheck] = useState<{
    action: VersionAction;
    target: SchemaVersion;
    newestBefore: number;
    check: VersionActionCheck | null;
    checking: boolean;
  } | null>(null);

  const refresh = () => {
    setLoading(true);
    api
      .listSchemaVersions()
      .then((v) => {
        setVersions(v);
        setError(null);
      })
      .catch((err: unknown) => setError(errorMessage(err, 'Failed to load schema versions.')))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
  }, []);

  const byIdForOrder = useMemo(() => new Map(versions.map((v) => [v.id, v])), [versions]);

  // The diff always reads older → newer regardless of click order: selecting v5 then v3
  // used to silently present removals as additions (redesign brief, versions MED).
  const [versionA, versionB] = useMemo(() => {
    if (selected.length !== 2) return selected;
    return [...selected].sort(
      (x, y) => (byIdForOrder.get(x)?.versionNumber ?? 0) - (byIdForOrder.get(y)?.versionNumber ?? 0),
    );
  }, [selected, byIdForOrder]);

  // Load the diff whenever exactly two versions are selected.
  useEffect(() => {
    if (selected.length !== 2) {
      // Clears a stale diff from a previous two-selection; the fetch below (when the
      // condition below it doesn't early-return) has no render-time equivalent either.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setDiff(null);
      setDiffError(null);
      return;
    }
    setDiffLoading(true);
    api
      .diffSchemaVersions(versionA, versionB)
      .then((d) => {
        setDiff(d);
        setDiffError(null);
      })
      .catch((err: unknown) => setDiffError(errorMessage(err, 'Failed to compute diff.')))
      .finally(() => setDiffLoading(false));
  }, [selected, versionA, versionB]);

  const byId = useMemo(() => new Map(versions.map((v) => [v.id, v])), [versions]);
  const active = versions.find((v) => v.status === 'ACTIVE') ?? null;

  // What a rollback would change, read the way a promote's confirmation reads: ACTIVE → target.
  useEffect(() => {
    if (!rollbackTarget || !active) return;
    let cancelled = false;
    // Clears the previous target's diff before fetching this one's.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setRollbackDiff(null);
    setRollbackDiffError(null);
    api.diffSchemaVersions(active.id, rollbackTarget.id)
      .then((d) => { if (!cancelled) setRollbackDiff(d); })
      .catch((err: unknown) => { if (!cancelled) setRollbackDiffError(errorMessage(err, 'Failed to compute diff.')); });
    return () => { cancelled = true; };
  }, [rollbackTarget, active]);

  const toggleSelect = (id: string) => {
    setSelected((prev) => {
      if (prev.includes(id)) return prev.filter((x) => x !== id);
      if (prev.length < 2) return [...prev, id];
      // Replace the oldest selection with the new one (keep a sliding pair).
      return [prev[1], id];
    });
  };

  const newestVersionNumber = () => versions.reduce((max, v) => Math.max(max, v.versionNumber), 0);

  // Re-reads the list and says what a timed-out promote or rollback amounts to.
  const checkAction = async (action: VersionAction, target: SchemaVersion, newestBefore: number) => {
    // The previous answer stays while the next is read, so Check again stays mounted (and focused).
    setActionCheck((prev) => ({ action, target, newestBefore, check: prev?.check ?? null, checking: true }));
    const versionsNow = await api.listSchemaVersions().catch(() => null);
    if (versionsNow) {
      setVersions(versionsNow);
      setError(null);
    }
    setActionCheck({
      action, target, newestBefore, checking: false,
      check: describeVersionActionTimeout(action, target, newestBefore, versionsNow),
    });
  };

  const doPromote = async () => {
    if (!promoteTarget) return;
    const target = promoteTarget;
    const newestBefore = newestVersionNumber();
    setBusy(true);
    setModalError(null);
    setActionCheck(null);
    try {
      await api.promoteDraft(target.id, promoteNotes.trim() || undefined);
      setPromoteTarget(null);
      setPromoteNotes('');
      refresh();
    } catch (err: unknown) {
      if (stillRunning(err)) {
        setPromoteTarget(null);
        await checkAction('promote', target, newestBefore);
      } else {
        setModalError(errorMessage(err, 'Promote failed.'));
      }
    } finally {
      setBusy(false);
    }
  };

  const doRollback = async () => {
    if (!rollbackTarget) return;
    const target = rollbackTarget;
    const newestBefore = newestVersionNumber();
    setBusy(true);
    setModalError(null);
    setActionCheck(null);
    try {
      await api.rollbackSchemaVersion(target.id);
      setRollbackTarget(null);
      setSelected([]);
      refresh();
    } catch (err: unknown) {
      if (stillRunning(err)) {
        setRollbackTarget(null);
        await checkAction('rollback', target, newestBefore);
      } else {
        setModalError(errorMessage(err, 'Rollback failed.'));
      }
    } finally {
      setBusy(false);
    }
  };

  const actionTone = actionCheck?.check?.kind === 'landed' ? 'border-success/40 bg-success/5'
    : actionCheck?.check ? 'border-warning/40 bg-warning/5' : 'border-border bg-muted/10';

  return (
    <div className="flex flex-col gap-4 p-4 max-w-6xl mx-auto w-full">
      {/* A promote or rollback the page stopped waiting for: always mounted (LiveStatus), so its
          answer is announced when it arrives; Check again is aria-disabled while it reads. */}
      <div
        data-testid={actionCheck ? 'versions-action-check' : undefined}
        className={actionCheck
          ? `flex flex-wrap items-center gap-x-3 gap-y-2 rounded border px-3 py-2 text-[11px] font-mono text-foreground ${actionTone}`
          : 'sr-only'}
      >
        <LiveStatus testId="versions-action-status" className="flex-1 min-w-0">
          {actionCheck === null ? null
            : actionCheck.check?.message
              ?? `The page stopped waiting for the ${actionCheck.action === 'promote'
                ? `promote of v${actionCheck.target.versionNumber}`
                : `rollback to v${actionCheck.target.versionNumber}`} — checking whether it landed…`}
        </LiveStatus>
        {actionCheck?.check && actionCheck.check.kind !== 'landed' && (
          <button
            type="button"
            onClick={() => {
              if (!actionCheck.checking) void checkAction(actionCheck.action, actionCheck.target, actionCheck.newestBefore);
            }}
            aria-disabled={actionCheck.checking || undefined}
            className="inline-flex items-center gap-1.5 h-7 px-3 rounded border border-border font-semibold hover:border-primary hover:text-primary aria-disabled:opacity-50 aria-disabled:cursor-not-allowed focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
          >
            <RotateCw size={11} className={actionCheck.checking ? 'animate-spin' : ''} aria-hidden="true" />
            {actionCheck.checking ? 'Checking…' : 'Check again'}
          </button>
        )}
        {actionCheck?.check && !actionCheck.checking && (
          <button
            type="button"
            onClick={() => setActionCheck(null)}
            className="h-7 px-3 rounded border border-transparent text-muted-foreground hover:text-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
          >
            Dismiss
          </button>
        )}
      </div>

      {/* History table */}
      <div id="tour-versions-history" className="bg-card border border-border rounded overflow-hidden">
        <CmdHeader
          command="Version history"
          info={CONCEPTS.schemaVersion}
          right={
            <span>
              {versions.length} version{versions.length === 1 ? '' : 's'}
              {selected.length > 0 && ` · ${selected.length}/2 selected for diff`}
            </span>
          }
        />
        {error && (
          <div className="m-4 p-3 border border-rose-500/40 bg-rose-500/10 rounded text-[11px] font-mono text-rose-300">
            {error}
          </div>
        )}
        {loading ? (
          <div className="p-4 text-[11px] font-mono text-muted-foreground">loading…</div>
        ) : versions.length === 0 ? (
          <div className="p-4 text-[11px] font-mono text-muted-foreground">
            No schema versions yet. Compile and promote an integration model to create one.
          </div>
        ) : (
          <table className="w-full text-[11px] font-mono">
            <thead>
              <tr className="text-muted-foreground border-b border-border">
                <th className="text-left font-medium px-4 py-2 w-8"></th>
                <th className="text-left font-medium px-2 py-2">version</th>
                <th className="text-left font-medium px-2 py-2">status</th>
                <th className="text-left font-medium px-2 py-2">created</th>
                <th className="text-left font-medium px-2 py-2">promoted</th>
                <th className="text-left font-medium px-2 py-2">notes</th>
                <th className="text-right font-medium px-4 py-2">actions</th>
              </tr>
            </thead>
            <tbody>
              {versions.map((v) => {
                const isSelected = selected.includes(v.id);
                // Marker follows diff order (older = a, newer = b), not click order.
                const selectionIndex =
                  selected.length === 2 ? [versionA, versionB].indexOf(v.id) : selected.indexOf(v.id);
                return (
                  <tr
                    key={v.id}
                    className={`border-b border-border/50 hover:bg-muted/20 transition-colors ${
                      isSelected ? 'bg-primary/5' : ''
                    }`}
                  >
                    <td className="px-4 py-2">
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => toggleSelect(v.id)}
                        className="accent-primary cursor-pointer"
                        title="Select for diff (pick two)"
                        aria-label={`Select v${v.versionNumber} to compare`}
                      />
                    </td>
                    <td className="px-2 py-2 text-foreground font-semibold">
                      v{v.versionNumber}
                      {isSelected && (
                        <span className="ml-1.5 text-[9px] text-primary">
                          {selectionIndex === 0 ? '(a)' : '(b)'}
                        </span>
                      )}
                    </td>
                    <td className="px-2 py-2">
                      <StatusBadge status={v.status} />
                    </td>
                    <td className="px-2 py-2 text-muted-foreground">
                      {formatZoned(v.createdAt)}
                    </td>
                    <td className="px-2 py-2 text-muted-foreground">
                      {v.promotedAt ? formatZoned(v.promotedAt) : '—'}
                    </td>
                    <td className="px-2 py-2 text-muted-foreground truncate max-w-[200px]">
                      {v.notes || '—'}
                    </td>
                    <td className="px-4 py-2 text-right whitespace-nowrap">
                      {active && v.status !== 'ACTIVE' && (
                        <button
                          type="button"
                          data-testid={`version-compare-${v.versionNumber}`}
                          onClick={() => setSelected([active.id, v.id])}
                          aria-label={`Compare v${v.versionNumber} with the active v${active.versionNumber}`}
                          className="mr-2 text-[10px] font-semibold text-muted-foreground hover:text-foreground border border-border rounded px-2 py-1 bg-transparent cursor-pointer transition-colors"
                        >
                          Compare with active
                        </button>
                      )}
                      {v.status === 'DRAFT' && (
                        <button
                          type="button"
                          onClick={() => {
                            setPromoteTarget(v);
                            setPromoteNotes(v.notes ?? '');
                            setModalError(null);
                          }}
                          aria-label={`Promote v${v.versionNumber} (tenant-wide)`}
                          className="text-[10px] font-semibold text-emerald-400 hover:text-emerald-300 border border-emerald-500/30 hover:border-emerald-500/50 rounded px-2 py-1 bg-transparent cursor-pointer transition-colors"
                        >
                          Promote
                        </button>
                      )}
                      {v.status === 'HISTORICAL' && (
                        <button
                          type="button"
                          onClick={() => {
                            setRollbackTarget(v);
                            setModalError(null);
                          }}
                          aria-label={`Roll back to v${v.versionNumber}`}
                          className="text-[10px] font-semibold text-amber-400 hover:text-amber-300 border border-amber-500/30 hover:border-amber-500/50 rounded px-2 py-1 bg-transparent cursor-pointer transition-colors"
                        >
                          Rollback
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* Diff panel */}
      {selected.length === 2 && (
        <div className="bg-card border border-border rounded overflow-hidden">
          <CmdHeader
            command={`Changes v${byId.get(versionA)?.versionNumber} → v${byId.get(versionB)?.versionNumber}`}
            right={<span>older → newer</span>}
          />
          <div className="p-4">
            {diffLoading ? (
              <div className="text-[11px] font-mono text-muted-foreground">computing diff…</div>
            ) : diffError ? (
              <div className="text-[11px] font-mono text-rose-300">{diffError}</div>
            ) : diff ? (
              <SchemaDiffList diff={diff} />
            ) : null}
          </div>
        </div>
      )}

      {/* Promote-with-notes, confirmed against ACTIVE and named tenant-wide (J4-F1). A model run's
          draft promoted here becomes that model's baseline (J4-F2, server-side delegation). */}
      {promoteTarget && (
        <PromoteConfirmDialog
          open
          draft={{ id: promoteTarget.id, versionNumber: promoteTarget.versionNumber }}
          busy={busy}
          error={modalError}
          onConfirm={() => void doPromote()}
          onCancel={() => setPromoteTarget(null)}
          notes={promoteNotes}
          onNotesChange={setPromoteNotes}
        />
      )}

      {/* Rollback: typed confirmation, the diff against ACTIVE, and what it does NOT roll back (J4-F18). */}
      {rollbackTarget && (
        <ConfirmDialog
          open
          title={`Roll back to v${rollbackTarget.versionNumber} (tenant-wide)?`}
          confirmLabel="Roll back"
          testId="version-rollback"
          typeToConfirm={String(rollbackTarget.versionNumber)}
          busy={busy}
          error={modalError}
          onConfirm={() => void doRollback()}
          onCancel={() => setRollbackTarget(null)}
          consequences={rollbackConsequences(rollbackTarget.versionNumber, active ? active.versionNumber : null)}
        >
          <div className="max-h-[30vh] overflow-y-auto rounded border border-border bg-muted/10 p-3">
            {!active ? (
              <div className="text-[11px] font-mono text-muted-foreground">No schema is active; nothing to compare against.</div>
            ) : rollbackDiffError ? (
              <div className="text-[11.5px] text-warning">The comparison with the active schema could not be computed ({rollbackDiffError}).</div>
            ) : !rollbackDiff ? (
              <div className="text-[11px] font-mono text-muted-foreground" role="status">Comparing with the active schema…</div>
            ) : (
              <SchemaDiffList
                diff={rollbackDiff}
                testId="version-rollback-diff"
                removedNote="Queries, virtual products and enforced constraints over these classes stop working."
              />
            )}
          </div>
        </ConfirmDialog>
      )}
    </div>
  );
};

export default VersionsView;

import React, { useEffect, useState } from 'react';
import { api } from '@/services/api';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { SchemaDiffList } from '@/components/SchemaDiffList';
import { loadPromoteComparison, type PromoteComparison } from '@/lib/promoteComparison';
import type { VersionRef } from '@/services/types';

/**
 * The confirmation every promote goes through (J4-F1, UI-OD2).
 *
 * The ACTIVE schema is one per tenant, and a promote replaces it — for every project, query,
 * product and constraint, not only the project that compiled the draft. Before this a promote was
 * one unconfirmed click. The dialog says "tenant-wide" in its title and its button, and shows what
 * changes against ACTIVE: added, removed (with what losing them costs) and changed classes — the
 * same diff the Versions page draws and the same removed-class rule the server's guard refuses on.
 *
 * ACTIVE is read when the dialog opens, never taken from the page (review UA5): another project
 * may have promoted since the page loaded, and the diff must be against what this replaces.
 *
 * The server stays the authority: a promote that would remove another project's classes comes back
 * 409 naming them, and that refusal is rendered here, inside the dialog, never behind it.
 *
 * Confirm waits for the diff (nobody should be asked to approve a list they cannot see yet); if
 * the diff cannot be computed the dialog says so and lets the user decide.
 */
interface PromoteConfirmDialogProps {
  open: boolean;
  draft: VersionRef;
  busy: boolean;
  error: string | null;
  onConfirm: () => void;
  onCancel: () => void;
  /** Optional notes recorded on the version (the Versions page's promote-with-notes). */
  notes?: string;
  onNotesChange?: (notes: string) => void;
}

type DiffState = { kind: 'loading' } | PromoteComparison;

export const PromoteConfirmDialog: React.FC<PromoteConfirmDialogProps> = ({
  open, draft, busy, error, onConfirm, onCancel, notes, onNotesChange,
}) => {
  const [state, setState] = useState<DiffState>({ kind: 'loading' });

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    // A fresh open computes a fresh diff; the network call has no render-time equivalent.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState({ kind: 'loading' });
    void loadPromoteComparison(draft.id, api).then((next) => { if (!cancelled) setState(next); });
    return () => { cancelled = true; };
  }, [open, draft.id]);

  const removes = state.kind === 'ready' && state.diff.removedClasses.length > 0;
  const title = `Promote v${draft.versionNumber} (tenant-wide)`;

  return (
    <ConfirmDialog
      open={open}
      title={title}
      confirmLabel={title}
      tone={removes ? 'danger' : 'default'}
      testId="promote-confirm"
      busy={busy}
      confirmDisabled={state.kind === 'loading'}
      error={error}
      onConfirm={onConfirm}
      onCancel={onCancel}
      body={
        state.kind === 'first'
          ? `No schema is active yet. v${draft.versionNumber} becomes the tenant's active schema — what every project's queries, products and constraints read.`
          : state.kind === 'ready'
            ? `This replaces the active schema v${state.active.versionNumber} for the whole tenant — every project's queries, products and constraints, not only this project's. Materialized products go stale.`
            : 'This replaces the tenant’s active schema — every project’s queries, products and constraints, not only this project’s. Materialized products go stale.'
      }
    >
      <div className="max-h-[40vh] overflow-y-auto rounded border border-border bg-muted/10 p-3">
        {state.kind === 'loading' && (
          <div className="text-[11px] font-mono text-muted-foreground" role="status">Comparing with the active schema…</div>
        )}
        {state.kind === 'failed' && (
          <div data-testid="promote-confirm-diff-failed" className="text-[11.5px] text-warning leading-relaxed">
            The comparison with the active schema could not be computed ({state.message}), so what
            this promote adds or removes is not shown. Nothing has been promoted.
          </div>
        )}
        {state.kind === 'first' && (
          <div className="text-[11px] font-mono text-muted-foreground">Nothing to compare against.</div>
        )}
        {state.kind === 'ready' && (
          <SchemaDiffList
            diff={state.diff}
            testId="promote-confirm-diff"
            removedNote="Queries, virtual products and enforced constraints over these classes stop working when this is promoted."
          />
        )}
      </div>
      {onNotesChange && (
        <label className="flex flex-col gap-1 text-[11px] text-muted-foreground">
          Notes (optional, recorded on the version)
          <textarea
            value={notes ?? ''}
            onChange={(e) => onNotesChange(e.target.value)}
            rows={2}
            className="w-full bg-input/40 border border-border rounded p-2 text-[11px] font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          />
        </label>
      )}
    </ConfirmDialog>
  );
};

export default PromoteConfirmDialog;

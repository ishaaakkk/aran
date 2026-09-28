import React from 'react';
import { Loader2, Sparkles, ShieldAlert, Trash2 } from 'lucide-react';
import { JobCard } from '@/components/JobCard';
import { InfoTip } from '@/components/InfoTip';
import { ErrorNotice } from '@/components/ErrorNotice';
import { CONCEPTS } from '@/lib/concepts';
import { CONSTRAINT_ENFORCE_EFFECT } from '@/lib/impactCopy';
import type { CatalogClass, Job, ModelConstraint } from '@/services/types';

interface DatasetConstraintsTabProps {
  modelLoading: boolean;
  /** The model or the active catalog could not be read — not "no integrated classes" (J8-F2). */
  modelError?: unknown;
  onRetryModel?: () => void;
  datasetClasses: CatalogClass[];
  selectedConstraintClass: string | null;
  setSelectedConstraintClass: (iri: string) => void;
  modelConstraints: ModelConstraint[];
  /**
   * Constraints the server marked `orphaned` — their class is no longer in the active catalog, so
   * the engine skips them and they filter nothing (V42). Deliberately NOT part of
   * `modelConstraints`: that list is filtered by the selected class, and an orphan has no class in
   * the picker to be selected under — which is exactly why, before V42, they were visible nowhere
   * while the API still reported them enforced. Passed whole rather than per-dataset for the same
   * reason: with the class gone, nothing ties the row to one dataset any more.
   */
  orphanedConstraints: ModelConstraint[];
  constraintExpr: string;
  setConstraintExpr: (expr: string) => void;
  savingConstraint: boolean;
  onAddConstraint: () => void;
  onToggleEnforced: (constraint: ModelConstraint) => void;
  onDeleteConstraint: (constraint: ModelConstraint) => void;
  /**
   * classIri → true from the moment that class's "Discover constraints" was clicked until its
   * job is terminal (including the brief round-trip before `discoverJobs` itself has an entry).
   * Keyed exactly like `DatasetsListView`'s `bootstrapJobs` (keyed by datasetId) — so switching
   * the class dropdown mid-discovery shows THIS class's own state, never another class's job
   * bleeding into view (Phase 3B).
   */
  discoveringClasses: Record<string, boolean>;
  /** classIri → the job id to watch, once its POST has resolved. Long-running (job substrate,
   *  like compile), so this tab only renders progress for the selected class — the caller owns
   *  submitting it and reacting to `onDiscoverJobDone`. */
  discoverJobs: Record<string, string>;
  /** Submit LIMA discovery for the given class. The tab always calls this with its own resolved
   *  `selCls.iri` (never trusting a caller's copy of "the selected class", which can briefly lag
   *  this tab's fallback-to-first-class below) so the caller keys its state by the right class. */
  onDiscoverConstraints: (classIri: string) => void;
  /** The watched job for `classIri` reached a terminal state — the caller reloads
   *  `modelConstraints` on success, same as every other write in this tab. */
  onDiscoverJobDone: (classIri: string, job: Job) => void;
  /**
   * V43: classIri → the server's own sentence about the last discovery run that persisted
   * nothing. LIMA is a row-PAIR miner (`Schema.buildColumnPairs` builds only self pairs), so a
   * run can legitimately find a real dependency and still have nothing to offer as a constraint
   * over ONE row — and a discovery that says "complete" while the list stays empty is the empty
   * success V43 exists to stop. Absent for a class whose last run produced candidates, and for
   * one that has not been run.
   */
  discoveryNotes: Record<string, string>;
  isDark: boolean;
}

/**
 * A constraint's provenance badge (Phase 3B). `origin` is `"DISCOVERED"` for an unenforced LIMA
 * candidate persisted by discovery, `"MANUAL"` for one a person typed, and null/absent for every
 * row written before this field existed — the server itself defaults a null origin to MANUAL
 * (see `ModelConstraint.java`), so "no chip" is correct for both MANUAL and legacy alike. There
 * is deliberately no badge for MANUAL: the badge exists to flag a row that has never been
 * reviewed by a person, not to label the common case.
 *
 * It also decides whether the enforce control is live: the server refuses to enforce a DISCOVERED
 * row with a 400 (V43: constraint discovery states its result over a PAIR of rows, and
 * only the single-row collapse of that can be stored, so enforcing it would filter nothing, or
 * everything, and drop NULL-bearing rows either way). A live checkbox whose only possible outcome
 * is an error toast is a worse affordance than a disabled one that says why.
 */
function isDiscovered(origin: ModelConstraint['origin']): boolean {
  return origin === 'DISCOVERED';
}

const ENFORCE_BLOCKED_EXPLANATION =
  'Discovered constraints are evidence only — enforcement requires inter-tuple semantics not yet implemented';

/**
 * V42: constraints whose integrated class the active catalog no longer serves.
 *
 * <p>The defect this closes is silence in both directions: the engine stopped applying these the
 * moment an alignment merged their class away, the API went on calling them enforced, and the tab
 * showed them nowhere at all — a user who enforced a constraint and then accepted an alignment had
 * no surface anywhere saying it had stopped working. Rendered above the class list so it is read
 * before the (still-live) constraints below it.
 *
 * <p>The enforce toggle is disabled rather than absent: the control is the one that promised the
 * guarantee, so it stays visible saying it no longer holds. Delete is the tab's own existing
 * action, not a new one — without it an orphan would be a row a user can see and never remove.
 * There is deliberately no "re-point to the surviving class" action: which class inherits a merged
 * class's constraints is a decision the product has not made (V42's own "carry vs surface" ruling
 * chose surface), and a button that guessed would be worse than none.
 */
const OrphanedConstraints: React.FC<{
  constraints: ModelConstraint[];
  onDeleteConstraint: (constraint: ModelConstraint) => void;
}> = ({ constraints, onDeleteConstraint }) => {
  if (constraints.length === 0) return null;
  return (
    <div data-testid="orphaned-constraints" className="flex flex-col gap-2 shrink-0">
      <div className="flex items-center gap-2">
        <ShieldAlert size={13} className="text-red-400 shrink-0" />
        <span className="font-mono text-[11px] uppercase tracking-wider text-red-400 font-semibold">
          {constraints.length} orphaned constraint{constraints.length === 1 ? '' : 's'}
        </span>
      </div>
      <div className="text-[11px] text-muted-foreground font-mono leading-relaxed">
        not enforced — class no longer in the active schema. These were left behind when their
        integrated class was merged away or retired; the query engine skips them, so they filter
        nothing.
      </div>
      {constraints.map((c) => (
        <div
          key={c.id}
          data-testid="orphaned-constraint-row"
          data-expression={c.expression}
          data-class-iri={c.classIri}
          className="flex items-center justify-between gap-4 p-3 rounded border border-red-500/30 bg-red-500/5 font-mono text-xs text-muted-foreground"
        >
          <div className="min-w-0 flex-1 flex flex-col gap-1">
            <span className="break-all select-text text-foreground/80">{c.expression}</span>
            <span className="break-all select-text text-[10px] text-muted-foreground/60">
              {c.classIri}
            </span>
          </div>
          <label className="flex items-center gap-1.5 shrink-0 select-none opacity-50" title="This class is not in the active schema, so there is nothing to enforce against">
            <input
              id={`enforced-${c.id}`}
              type="checkbox"
              checked={false}
              disabled
              readOnly
              className="accent-amber-500"
            />
            <span>enforced</span>
          </label>
          <button
            type="button"
            onClick={() => onDeleteConstraint(c)}
            className="p-1.5 rounded border border-border/30 text-muted-foreground hover:text-red-400 hover:border-red-500/30 transition-all shrink-0"
            title="Delete constraint"
          >
            <Trash2 size={11} />
          </button>
        </div>
      ))}
    </div>
  );
};

/** Denial Constraints tab: a class picker, an add-constraint input, and the
 *  list of constraints on the selected integrated class. */
export const DatasetConstraintsTab: React.FC<DatasetConstraintsTabProps> = ({
  modelLoading,
  modelError = null,
  onRetryModel,
  datasetClasses,
  selectedConstraintClass,
  setSelectedConstraintClass,
  modelConstraints,
  orphanedConstraints,
  constraintExpr,
  setConstraintExpr,
  savingConstraint,
  onAddConstraint,
  onToggleEnforced,
  onDeleteConstraint,
  discoveringClasses,
  discoverJobs,
  onDiscoverConstraints,
  onDiscoverJobDone,
  discoveryNotes,
  isDark,
}) => {
  if (modelLoading) {
    return (
      <div className="flex-grow flex items-center justify-center text-muted-foreground text-xs font-mono gap-2">
        <Loader2 size={14} className="animate-spin" /> Loading integration model…
      </div>
    );
  }

  if (modelError) {
    return (
      <div className="flex-grow flex items-center justify-center p-6">
        <ErrorNotice
          title="Couldn't load this dataset's constraints"
          error={modelError}
          onRetry={onRetryModel}
          testId="dataset-constraints-model-error"
        />
      </div>
    );
  }

  if (datasetClasses.length === 0) {
    // The orphan list still renders here: a dataset whose classes were all merged away lands in
    // exactly this branch, and that is precisely when a user most needs to be told their
    // constraints stopped applying.
    return (
      <div className="flex-grow flex flex-col p-6 min-h-0 overflow-y-auto gap-4">
        <OrphanedConstraints constraints={orphanedConstraints} onDeleteConstraint={onDeleteConstraint} />
        <div className="flex flex-col items-center justify-center p-12 border border-dashed border-border rounded flex-grow text-center text-xs text-muted-foreground space-y-4 font-mono">
          <ShieldAlert size={32} className="text-amber-500/60" />
          <div className="max-w-md leading-relaxed">
            No integrated classes for this dataset yet.
            <br />
            <span className="text-muted-foreground/60 text-[11px]">
              Enroll the dataset and promote a schema so its classes appear here — an enforced constraint then applies to graph-tier queries over them.
            </span>
          </div>
        </div>
      </div>
    );
  }

  const selCls = datasetClasses.find(c => c.iri === selectedConstraintClass) ?? datasetClasses[0];
  const classConstraints = modelConstraints.filter(c => c.classIri === selCls.iri);
  const cols = Array.from(new Set(selCls.sources.flatMap(s => s.columns.map(col => col.globalName)))).sort();
  // Looked up by THIS class's iri, not passed down pre-resolved — the only way a job started
  // for a different class can never render here (button, spinner, or JobCard) once the class
  // dropdown moves on.
  const discovering = !!discoveringClasses[selCls.iri];
  const discoverJobId = discoverJobs[selCls.iri] ?? null;
  // Looked up by THIS class's iri for the same reason as the two above.
  const discoveryNote = discoveryNotes[selCls.iri] ?? null;

  return (
    <div className="flex-grow flex flex-col p-6 min-h-0 overflow-y-auto gap-4">
      <div className="flex items-center justify-between border-b border-border/30 pb-2 shrink-0 gap-3 flex-wrap">
        <div className="flex items-center gap-2 min-w-0">
          <ShieldAlert size={14} className="text-amber-500 shrink-0" />
          <span className="font-mono text-xs uppercase tracking-wider text-muted-foreground font-semibold shrink-0 inline-flex items-center gap-1.5">
            Constraints
            <InfoTip text={CONCEPTS.denialConstraint} label="denial constraints" />
          </span>
          <select
            data-testid="dataset-constraint-class-select"
            value={selCls.iri}
            onChange={(e) => setSelectedConstraintClass(e.target.value)}
            className="text-xs font-mono bg-muted/20 border border-border/40 rounded px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-primary/50 max-w-[220px] truncate"
          >
            {datasetClasses.map(c => (
              <option key={c.iri} value={c.iri}>{c.name}</option>
            ))}
          </select>
        </div>
        <button
          type="button"
          data-testid="dataset-discover-constraints"
          onClick={() => onDiscoverConstraints(selCls.iri)}
          disabled={discovering}
          title="Run constraint discovery (LIMA) for this class; candidates land below as unenforced and reviewable"
          className="px-2.5 py-1.5 text-xs bg-violet-500/10 text-violet-400 border border-violet-500/30 font-medium rounded hover:bg-violet-500/20 disabled:opacity-40 flex items-center gap-1.5 shrink-0"
        >
          {discovering ? <Loader2 size={12} className="animate-spin" /> : <Sparkles size={12} />}
          Discover constraints
        </button>
      </div>

      <OrphanedConstraints constraints={orphanedConstraints} onDeleteConstraint={onDeleteConstraint} />

      {/* Discovery running: show the job, not a guess about it (same idiom as the dataset
          detail panel's bootstrap JobCard). */}
      {discoverJobId && (
        <div className="shrink-0">
          <JobCard
            jobId={discoverJobId}
            title="aran discover constraints"
            onDone={(job) => onDiscoverJobDone(selCls.iri, job)}
          />
        </div>
      )}

      {/* V43 — why the list below did not grow. Rendered as a standing note rather than left to
          the toast: the question "I ran discovery and nothing appeared" outlives four seconds. */}
      {discoveryNote && (
        <div
          data-testid="discovery-empty-note"
          className="shrink-0 flex items-start gap-2 p-3 rounded border border-violet-500/30 bg-violet-500/5 text-[11px] font-mono leading-relaxed text-muted-foreground"
        >
          <Sparkles size={12} className="text-violet-400 shrink-0 mt-0.5" />
          <span className="min-w-0">{discoveryNote}</span>
        </div>
      )}

      {/* Add a constraint. The server validates the expression against the class's
          columns (DuckDB EXPLAIN) and rejects bad SQL with its parse message. */}
      <div className="flex flex-col gap-2 shrink-0">
        <div className="flex gap-2">
          <input
            value={constraintExpr}
            onChange={(e) => setConstraintExpr(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') onAddConstraint(); }}
            className="flex-1 font-mono text-sm px-3 py-2 bg-muted/20 border border-border/40 rounded focus:outline-none focus:ring-1 focus:ring-primary/50"
            placeholder="h_index >= 0"
          />
          <button
            onClick={onAddConstraint}
            disabled={savingConstraint || !constraintExpr.trim()}
            className="px-3 py-2 text-xs bg-primary text-primary-foreground font-medium rounded hover:bg-primary/90 disabled:opacity-40 flex items-center gap-2 shrink-0"
          >
            {savingConstraint && <Loader2 size={12} className="animate-spin" />} Add
          </button>
        </div>
        {cols.length > 0 && (
          <div className="text-[10px] text-muted-foreground/60 font-mono">
            columns: {cols.join(', ')}
          </div>
        )}
      </div>

      {classConstraints.length > 0 ? (
        <div className="flex flex-col gap-2">
          {classConstraints.map((c) => {
            const enforceBlocked = isDiscovered(c.origin);
            return (
            <div
              key={c.id}
              // Expression/origin/enforced are mirrored onto attributes so a spec can read a
              // whole row without parsing the rendered line, which interleaves the expression,
              // an optional provenance chip, a checkbox label and a delete button. Same reason
              // as `catalog-class-*` in the workbench; `v34-constraint-discovery.spec.ts` reads
              // them and `DatasetConstraintsTab.test.tsx` pins them.
              data-testid="constraint-row"
              data-expression={c.expression}
              data-origin={c.origin ?? 'MANUAL'}
              data-enforced={String(c.enforced)}
              className={`group flex items-center justify-between gap-4 p-3 rounded border transition-all font-mono text-xs ${
                c.enforced ? 'border-amber-500/40 bg-amber-500/5' : 'border-border/20 bg-background/20'
              } ${isDark ? 'text-zinc-300' : 'text-zinc-700'}`}
            >
              <div className="flex-1 min-w-0 flex flex-col gap-1">
                <span className="break-all select-text">{c.expression}</span>
                {/* Rendered, not only tooltipped: why a control is dead is not something a user
                    can discover by hovering a disabled input. */}
                {enforceBlocked && (
                  <span
                    data-testid="constraint-enforce-blocked"
                    className="text-[10px] leading-snug text-muted-foreground/70"
                  >
                    {ENFORCE_BLOCKED_EXPLANATION}
                  </span>
                )}
              </div>
              {enforceBlocked && (
                <span
                  data-testid="constraint-discovered-badge"
                  title="found by constraint discovery — evidence about the data, not an enforceable filter"
                  className="inline-flex items-center gap-1 shrink-0 px-1.5 py-0.5 rounded border border-violet-500/30 bg-violet-500/10 text-violet-400 text-[10px] uppercase tracking-wider"
                >
                  <span className="w-1.5 h-1.5 rounded-full bg-violet-500 shrink-0" />
                  discovered
                </span>
              )}
              {/* A DISCOVERED row's enforce PATCH is refused by the server (400), so the control is
                  dead rather than merely inadvisable; see isDiscovered above (V43). */}
              <label
                htmlFor={`enforced-${c.id}`}
                className={`flex items-center gap-1.5 shrink-0 select-none ${enforceBlocked ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}
                title={enforceBlocked ? ENFORCE_BLOCKED_EXPLANATION : CONSTRAINT_ENFORCE_EFFECT}
              >
                <input
                  id={`enforced-${c.id}`}
                  type="checkbox"
                  checked={c.enforced}
                  disabled={enforceBlocked}
                  onChange={() => onToggleEnforced(c)}
                  className="accent-amber-500"
                />
                <span className={c.enforced ? 'text-amber-400' : 'text-muted-foreground'}>enforced</span>
              </label>
              <button
                type="button"
                onClick={() => onDeleteConstraint(c)}
                className="p-1.5 rounded border border-border/30 text-muted-foreground hover:text-red-400 hover:border-red-500/30 transition-all shrink-0"
                title="Delete constraint"
              >
                <Trash2 size={11} />
              </button>
            </div>
            );
          })}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center p-8 border border-dashed border-border rounded text-center text-xs text-muted-foreground space-y-2 font-mono">
          <ShieldAlert size={24} className="text-muted-foreground/35 text-amber-500/50" />
          <div className="max-w-md leading-relaxed">
            No constraints on <span className="text-foreground">{selCls.name}</span> yet. Add a rule above. {CONSTRAINT_ENFORCE_EFFECT}
          </div>
        </div>
      )}
    </div>
  );
};

export default DatasetConstraintsTab;

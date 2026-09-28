import React from 'react';
import { Loader2, Network, RotateCw } from 'lucide-react';
import { InfoTip } from '@/components/InfoTip';
import { ErrorNotice } from '@/components/ErrorNotice';
import { LiveStatus } from '@/components/LiveStatus';
import { CONCEPTS } from '@/lib/concepts';
import type { WrapperSaveCheck } from '@/lib/wrapperSaveCheck';
import type { EnrolCheck } from '@/lib/enrolCheck';
import type { DatasetPreview, ModelSource } from '@/services/types';

interface DatasetWrapperTabProps {
  modelLoading: boolean;
  /** The model could not be read: say so, never "enrol this dataset" (J8-F2) — enrolling on that
   *  premise is a write made on a guess. */
  modelError?: unknown;
  onRetryModel?: () => void;
  modelSources: ModelSource[];
  onEnrollDataset: () => void;
  /** An enrol is in flight: its button is off, so a slow seed is not enrolled twice. */
  enrolling?: boolean;
  /** An enrol the page stopped waiting for that a re-read has not seen land: a note that stays,
   *  with Check again, rather than a toast after which Enroll is clickable again. */
  enrolCheck?: EnrolCheck | null;
  checkingEnrol?: boolean;
  onCheckEnrol?: () => void;
  /** A save is in flight (J4-F11): Save is off and says so. The save verifies the wrapper against
   *  its source before it answers, and a second click would start a second verify. */
  savingWrapper?: boolean;
  /** What a save the page stopped waiting for amounts to, from a re-read of the model. */
  saveCheck?: WrapperSaveCheck | null;
  checkingSave?: boolean;
  onCheckSave?: () => void;
  selectedWrapperClass: string | null;
  setSelectedWrapperClass: (classIri: string) => void;
  wrapperText: string;
  /** False until `wrapperText` holds the selected class's stored wrapper. Until then the editor is
   *  read-only and Save/Preview are off: typed text would be overwritten by the seed, and a
   *  preview or save would send the previous selection's SQL (J8-F13). Defaults to true. */
  wrapperSeeded?: boolean;
  setWrapperText: (sql: string) => void;
  wrapperPreviewLoading: boolean;
  wrapperPreviewData: DatasetPreview | null;
  onPreviewWrapper: () => void;
  onSaveWrapper: () => void;
  /** Display names of the integrated classes this dataset feeds that have an enforced denial
   *  constraint right now (`CatalogClass.enforcedConstraintCount > 0`). The planner never
   *  expands such a class, whatever its wrapper's verdict (J4-F12). */
  enforcedConstraintClasses?: string[];
}

/** Why a verified wrapper can still run as written: the planner's own refusals
 *  (`OqlPlanner.pushExpandedMember`), which verify-wrapper does not mirror (WRAPPER_VERIFY-9). */
function expansionScope(enforcedConstraintClasses: string[]): string {
  const now = enforcedConstraintClasses.length > 0
    ? `${enforcedConstraintClasses.join(', ')} ${enforcedConstraintClasses.length === 1 ? 'has' : 'have'} an enforced denial constraint, so queries run this wrapper as written. `
    : '';
  return `${now}Verified means the SQL round-trips; queries still run it as written while its class has an enforced denial constraint or a DECIMAL or TIMESTAMP column.`;
}

/** Longest reason text shown inline on the badge before truncating; the full text always still
 *  reaches the user via the `title` tooltip. `expansionReason` can run up to 512 chars
 *  server-side (IntegrationModelService.REASON_MAX_LENGTH), far too long for a one-line chip. */
const REASON_PREVIEW_LENGTH = 80;

/** Maps a model-source's compile-time verify-wrapper verdict (Phase 3A) to badge copy.
 *  `status`/`reason` are null together exactly when the wrapper was seeded but never verified
 *  (see `ModelSource`'s javadoc) — that reads as opaque-with-no-explanation-yet, same as an
 *  OPAQUE verdict that happens to carry no reason string. */
function wrapperExpansionBadge(
  status: ModelSource['expansionStatus'],
  reason: ModelSource['expansionReason'],
): { text: string; title?: string; tone: 'expanded' | 'opaque' } {
  if (status === 'EXPANDED') {
    return { text: 'Optimizable — verified round-trip', tone: 'expanded' };
  }
  if (reason) {
    const preview = reason.length > REASON_PREVIEW_LENGTH
      ? `${reason.slice(0, REASON_PREVIEW_LENGTH)}…`
      : reason;
    return { text: `Opaque — ${preview}`, title: reason, tone: 'opaque' };
  }
  return { text: 'Opaque — not yet verified', tone: 'opaque' };
}

/** SQL Wrapper tab: an editable wrapper-SQL textarea for the selected enrolled
 *  class, plus a validate-and-preview results table. */
export const DatasetWrapperTab: React.FC<DatasetWrapperTabProps> = (props) => {
  const {
    enrolling = false,
    enrolCheck = null,
    checkingEnrol = false,
    onCheckEnrol,
    savingWrapper = false,
    saveCheck = null,
    checkingSave = false,
    onCheckSave,
  } = props;
  // One live region for what a save or an enrol amounts to, mounted above every branch of the
  // tab: a status that arrives already holding its text is often not announced (BlockedReason's
  // rule), and a save the page stopped waiting for must still be answered when the model itself
  // could not be read — the error branch used to replace it (third review of UA2). "Check again"
  // sits beside the region, not inside it, so the announcement is the sentence, not a button's name.
  const note: {
    tone: 'working' | 'saved' | 'unsettled';
    content: React.ReactNode;
    recheck?: { checking: boolean; run: () => void };
  } | null = savingWrapper
    ? {
      tone: 'working',
      content: (
        <span data-testid="dataset-wrapper-saving">
          Saving verifies the wrapper against its data first — on a table read live, against the
          source itself — so this can take a while.
        </span>
      ),
    }
    : saveCheck
      ? {
        tone: saveCheck.kind === 'saved' ? 'saved' : 'unsettled',
        content: <span data-testid="dataset-wrapper-save-check" data-kind={saveCheck.kind}>{saveCheck.message}</span>,
        recheck: saveCheck.kind !== 'saved' && onCheckSave ? { checking: checkingSave, run: onCheckSave } : undefined,
      }
      : enrolCheck
        ? {
          tone: 'unsettled',
          content: <span data-testid="dataset-enrol-check" data-kind={enrolCheck.kind}>{enrolCheck.message}</span>,
          recheck: onCheckEnrol ? { checking: checkingEnrol, run: onCheckEnrol } : undefined,
        }
        : null;

  return (
    <div className="flex-1 flex flex-col min-h-0">
      <div
        data-testid="dataset-wrapper-status-row"
        className={note === null ? 'shrink-0' : `flex flex-wrap items-center gap-x-3 gap-y-2 rounded-md border px-3 py-2 mx-6 mt-4 text-[11px] text-foreground shrink-0 ${
          note.tone === 'saved' ? 'border-success/40 bg-success/5'
            : note.tone === 'unsettled' ? 'border-warning/40 bg-warning/5'
              : 'border-border bg-muted/10'
        }`}
      >
        <LiveStatus id="dataset-wrapper-status" testId="dataset-wrapper-status" className="flex-1 min-w-0">
          {note?.content ?? null}
        </LiveStatus>
        {note?.recheck && (
          <button
            type="button"
            data-testid="dataset-wrapper-check-again"
            onClick={() => { if (!note.recheck?.checking) note.recheck?.run(); }}
            aria-disabled={note.recheck.checking || undefined}
            className="inline-flex items-center gap-1.5 h-7 px-3 rounded-md border border-border font-semibold hover:border-primary hover:text-primary aria-disabled:opacity-50 aria-disabled:cursor-not-allowed focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
          >
            <RotateCw size={11} className={note.recheck.checking ? 'animate-spin' : ''} aria-hidden="true" />
            {note.recheck.checking ? 'Checking…' : 'Check again'}
          </button>
        )}
      </div>
      <WrapperTabBody
        {...props}
        busySaving={savingWrapper || checkingSave}
        busyEnrolling={enrolling || checkingEnrol}
        describedBy={note === null ? undefined : 'dataset-wrapper-status'}
      />
    </div>
  );
};

/** The tab's branches — loading, a model that could not be read, not enrolled, the editor — below
 *  its status region, which none of them replaces. */
const WrapperTabBody: React.FC<DatasetWrapperTabProps & {
  /** A save or its check is in flight: Save is aria-disabled, not disabled, so it keeps focus. */
  busySaving: boolean;
  busyEnrolling: boolean;
  /** The status region's id while it says something, for the buttons it concerns. */
  describedBy?: string;
}> = ({
  modelLoading,
  modelError = null,
  onRetryModel,
  modelSources,
  onEnrollDataset,
  enrolling = false,
  savingWrapper = false,
  selectedWrapperClass,
  setSelectedWrapperClass,
  wrapperText,
  wrapperSeeded = true,
  setWrapperText,
  wrapperPreviewLoading,
  wrapperPreviewData,
  onPreviewWrapper,
  onSaveWrapper,
  enforcedConstraintClasses = [],
  busySaving,
  busyEnrolling,
  describedBy,
}) => {
  if (modelLoading) {
    return (
      <div className="flex-1 flex items-center justify-center text-muted-foreground text-xs font-mono gap-2">
        <Loader2 size={14} className="animate-spin" /> Loading integration model…
      </div>
    );
  }

  if (modelError) {
    return (
      <div className="flex-1 flex items-center justify-center p-6">
        <ErrorNotice
          title="Couldn't load the integration model"
          error={modelError}
          onRetry={onRetryModel}
          testId="dataset-wrapper-model-error"
        />
      </div>
    );
  }

  if (modelSources.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center p-12 text-center text-xs text-muted-foreground gap-4 font-mono">
        <Network size={28} className="text-violet-400/60" />
        <div className="max-w-md leading-relaxed">
          Enroll this dataset in the integration model to edit wrappers.
        </div>
        {/* aria-disabled, not disabled, while in flight: a disabled button drops keyboard focus to
            <body> the moment it is pressed. */}
        <button
          type="button"
          data-testid="dataset-enrol"
          onClick={() => { if (!busyEnrolling) onEnrollDataset(); }}
          aria-disabled={busyEnrolling || undefined}
          aria-busy={enrolling || undefined}
          aria-describedby={describedBy}
          className="px-3 py-1.5 text-xs bg-primary text-primary-foreground font-medium rounded hover:bg-primary/90 inline-flex items-center gap-2 aria-disabled:opacity-50 aria-disabled:cursor-not-allowed"
        >
          {enrolling && <Loader2 size={12} className="animate-spin" aria-hidden="true" />}
          {enrolling ? 'Enrolling…' : 'Enroll in model'}
        </button>
      </div>
    );
  }

  const selectedSource = modelSources.find(s => s.classIri === selectedWrapperClass);
  const badge = wrapperExpansionBadge(selectedSource?.expansionStatus, selectedSource?.expansionReason);
  // Unsaved = the editor differs from what the model holds. Switching class re-seeds the editor
  // from the model (DatasetsListView's seed effect), so the picker waits while this is true.
  const savedText = selectedSource?.wrapperSql ?? '';
  const dirty = selectedSource !== undefined && wrapperText !== savedText;

  return (
    <div className="flex-1 flex flex-col p-6 min-h-0 overflow-hidden gap-4">
      <div className="flex items-center justify-between border-b border-border/30 pb-2 shrink-0 gap-3 flex-wrap">
        <div className="flex items-center gap-2 min-w-0">
          <h3 className="text-sm font-semibold tracking-tight shrink-0 inline-flex items-center gap-1.5">
            Wrapper
            <InfoTip text={CONCEPTS.wrapper} label="wrappers" />
          </h3>
          <select
            data-testid="dataset-wrapper-class-select"
            aria-label="Source class"
            value={selectedWrapperClass ?? ''}
            onChange={(e) => setSelectedWrapperClass(e.target.value)}
            disabled={dirty}
            title={dirty ? 'Save or revert this wrapper before switching class' : undefined}
            className="text-xs font-mono bg-muted/20 border border-border/40 rounded px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-primary/50 max-w-[220px] truncate disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {modelSources.map(s => (
              <option key={s.classIri} value={s.classIri}>{s.classIri.replace(/^.*[/#:]/, '')}</option>
            ))}
          </select>
          {dirty && (
            <span
              data-testid="dataset-wrapper-unsaved"
              role="status"
              className="text-[11px] font-mono text-warning shrink-0"
            >
              ● unsaved changes
            </span>
          )}
        </div>
        <div className="flex gap-2">
          {dirty && (
            <button
              type="button"
              data-testid="dataset-wrapper-revert"
              onClick={() => setWrapperText(savedText)}
              className="px-3 py-1.5 text-xs border border-border text-muted-foreground font-medium rounded hover:text-foreground hover:border-foreground/40"
            >
              Revert
            </button>
          )}
          {/* Until the editor holds its selection's wrapper, both are truly disabled (J8-F13). While
              a preview or save is in flight they are aria-disabled instead: a disabled button drops
              keyboard focus to <body> the moment it is pressed (third review of UA2). */}
          <button
            type="button"
            data-testid="dataset-wrapper-preview"
            onClick={() => { if (!wrapperPreviewLoading) onPreviewWrapper(); }}
            disabled={!wrapperSeeded}
            aria-disabled={wrapperPreviewLoading || undefined}
            aria-busy={wrapperPreviewLoading || undefined}
            className="px-3 py-1.5 text-xs bg-muted text-foreground font-medium rounded hover:bg-muted/80 flex items-center gap-2 disabled:opacity-50 aria-disabled:opacity-50 aria-disabled:cursor-not-allowed"
          >
            {wrapperPreviewLoading && <Loader2 size={12} className="animate-spin" aria-hidden="true" />} Validate & Preview
          </button>
          <button
            type="button"
            data-testid="dataset-wrapper-save"
            onClick={() => { if (!busySaving) onSaveWrapper(); }}
            disabled={!wrapperSeeded}
            aria-disabled={busySaving || undefined}
            aria-busy={savingWrapper || undefined}
            aria-describedby={describedBy}
            className="px-3 py-1.5 text-xs bg-primary text-primary-foreground font-medium rounded hover:bg-primary/90 flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed aria-disabled:opacity-50 aria-disabled:cursor-not-allowed"
          >
            {savingWrapper && <Loader2 size={12} className="animate-spin" aria-hidden="true" />}
            {savingWrapper ? 'Saving…' : 'Save Wrapper'}
          </button>
        </div>
      </div>
      <span
        data-testid="dataset-wrapper-expansion-badge"
        title={badge.title}
        className={`inline-flex items-center gap-1.5 self-start px-2 py-1 rounded border text-xs font-mono shrink-0 ${
          badge.tone === 'expanded'
            ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-400'
            : 'border-amber-500/30 bg-amber-500/10 text-amber-400'
        }`}
      >
        <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${badge.tone === 'expanded' ? 'bg-emerald-500' : 'bg-amber-500'}`} />
        {badge.text}
      </span>
      {badge.tone === 'expanded' && (
        <p
          data-testid="dataset-wrapper-expansion-scope"
          className="text-[11px] font-mono text-muted-foreground leading-relaxed -mt-2 shrink-0"
        >
          {expansionScope(enforcedConstraintClasses)}
        </p>
      )}
      <textarea
        data-testid="dataset-wrapper-editor"
        aria-label="Wrapper SQL"
        value={wrapperText}
        readOnly={!wrapperSeeded}
        aria-busy={!wrapperSeeded || undefined}
        onChange={(e) => setWrapperText(e.target.value)}
        className="w-full font-mono text-sm p-4 bg-muted/20 border border-border/40 rounded-lg focus:outline-none focus:ring-1 focus:ring-primary/50 resize-none h-48 shrink-0"
        placeholder="SELECT * FROM ${SNAPSHOT}"
      />
      <div className="flex-1 overflow-auto bg-muted/10 rounded-lg border border-border/40 min-h-[200px]">
        {wrapperPreviewData ? (
          <table data-testid="dataset-wrapper-preview-table" className="w-full text-left border-collapse text-xs whitespace-nowrap">
            <thead className="bg-muted/30 sticky top-0 z-10 backdrop-blur-sm">
              <tr>
                {wrapperPreviewData.headers.map((h, i) => (
                  <th key={i} className="px-4 py-3 font-semibold font-mono tracking-tight text-foreground/80 border-b border-r last:border-r-0 border-border">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {wrapperPreviewData.rows.length === 0 ? (
                <tr><td colSpan={wrapperPreviewData.headers.length} className="px-4 py-8 text-center text-muted-foreground font-mono">No data matched wrapper query</td></tr>
              ) : (
                wrapperPreviewData.rows.map((row, rowIdx) => (
                  <tr key={rowIdx} className="hover:bg-muted/20 transition-colors group">
                    {row.map((cell, colIdx) => (
                      <td key={colIdx} className="px-4 py-2 border-b border-r last:border-r-0 border-border/50 text-muted-foreground group-hover:text-foreground">
                        {cell !== null ? String(cell) : <span className="text-muted-foreground/30 italic">NULL</span>}
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        ) : (
          <div className="h-full flex items-center justify-center text-muted-foreground text-xs font-mono">Run Validate & Preview to see results</div>
        )}
      </div>
    </div>
  );
};

export default DatasetWrapperTab;

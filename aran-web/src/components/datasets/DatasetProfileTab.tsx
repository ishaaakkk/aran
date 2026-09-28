import React, { useState } from 'react';
import { AlertTriangle, Loader2, RefreshCw, ShieldAlert, Sparkles } from 'lucide-react';
import { OChip } from '@/components/OChip';
import { InfoTip } from '@/components/InfoTip';
import { CONCEPTS } from '@/lib/concepts';
import type { ProfileGroup, StoredColumnProfile } from '@/services/types';
import { fmtFixed, fmtInt, fmtPct, keyEvidenceNote, subjectLabel, type ProfileLoad } from './profileModel';

interface DatasetProfileTabProps {
  /** The dataset the tab is showing. A `load` for any other dataset is not rendered (J3-F8). */
  datasetId: string;
  /** The stored profile (J3-F1), read from `GET …/datasets/{id}/profiles`. */
  load: ProfileLoad | null;
  /** A profiling run for THIS dataset is in flight. */
  running: boolean;
  /** Columns the last run for this dataset could not profile, with freyja's reason (J3-F9). */
  runErrors: Record<string, string> | null;
  hasSnapshot: boolean;
  /**
   * The dataset is read live from its source (a federated table, an Iceberg table, an object
   * read in place), so it has no stored snapshot BY DESIGN — which is not a failure and must not
   * read as one (UI audit J3-F11).
   */
  onRunProfiling: () => void;
  onRetry: () => void;
  /**
   * The profiling job's own card, when one is running or failed (J3-F7): its real stages in place
   * of a scripted narration, and its failure kept on screen with a Retry.
   */
  activity?: React.ReactNode;
}

function Row({ label, value, tone }: { label: string; value: React.ReactNode; tone?: 'warn' }) {
  return (
    <div className="flex justify-between py-1">
      <span className="text-muted-foreground">{label}</span>
      <span className={`font-bold ${tone === 'warn' ? 'text-rose-400' : 'text-foreground'}`}>{value}</span>
    </div>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="border border-border bg-card rounded p-4 font-mono space-y-2">
      <h4 className="text-[10px] font-bold text-primary uppercase border-b border-border/50 pb-1.5 tracking-wider">{title}</h4>
      <div className="divide-y divide-border/20 text-[11px] pt-1.5">{children}</div>
    </div>
  );
}

/**
 * One column's metrics. A HARVESTED row carries the catalog's distinct-count estimate and a
 * derived uniqueness and nothing else — every other field is a structural zero, and a zero is not
 * "no evidence" to a reader (ColumnProfile.measuredOverSnapshotRows' javadoc) — so only what the
 * catalog actually said is shown, marked as an estimate.
 */
function ColumnMetrics({ group, col }: { group: ProfileGroup; col: StoredColumnProfile }) {
  if (group.subject === 'HARVESTED') {
    return (
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <Card title="Catalog estimates">
          <Row label="Distinct values (est.)" value={`~${fmtInt(col.cardinality)}`} />
          <Row label="Uniqueness (derived)" value={fmtPct(col.uniqueness)} />
          <Row label="Null fraction" value={fmtPct(col.incompleteness)} />
        </Card>
        <div className="text-[11px] text-muted-foreground leading-relaxed font-mono self-center">
          The source database's statistics carry no value distribution, lengths or common values, so
          none are shown.
        </div>
      </div>
    );
  }
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <Card title="Cardinality & information">
          <Row label="Cardinality count" value={fmtInt(col.cardinality)} />
          <Row label="Entropy (bits)" value={fmtFixed(col.entropy, 4)} />
          <Row label="Uniqueness ratio" value={fmtPct(col.uniqueness)} />
          <Row label="Incompleteness (nulls)" value={fmtPct(col.incompleteness)} />
        </Card>
        <Card title="Frequency & distribution">
          <Row label="Constancy" value={fmtPct(col.constancy)} />
          <Row label="Average frequency" value={fmtFixed(col.frequencyAvg)} />
          <Row label="Frequency bounds" value={`[${fmtInt(col.frequencyMin)} – ${fmtInt(col.frequencyMax)}]`} />
          <Row label="Frequency std dev" value={fmtFixed(col.frequencySd)} />
        </Card>
        <Card title="Text boundaries & lengths">
          <Row label="First value (lexical)" value={<span className="max-w-[150px] truncate inline-block align-bottom">{col.firstWord ?? '—'}</span>} />
          <Row label="Last value (lexical)" value={<span className="max-w-[150px] truncate inline-block align-bottom">{col.lastWord ?? '—'}</span>} />
          <Row label="Average word length" value={`${fmtFixed(col.lenAvgWord)} chars`} />
          <Row label="Word length range" value={`[${fmtInt(col.lenMinWord)} – ${fmtInt(col.lenMaxWord)}] chars`} />
        </Card>
        <Card title="Words & flags">
          <Row label="Empty column" value={col.emptyColumn ? 'yes' : 'no'} tone={col.emptyColumn ? 'warn' : undefined} />
          <Row label="Binary column" value={col.binaryColumn ? 'yes' : 'no'} />
          <Row label="Average words per value" value={fmtFixed(col.wordsCntAvg)} />
          <Row label="Total words" value={fmtInt(col.numberWords)} />
        </Card>
      </div>
      <div className="border border-border bg-muted/5 rounded p-4 font-mono space-y-3">
        <h4 className="text-[10px] font-bold text-primary uppercase border-b border-border/50 pb-1.5 tracking-wider">
          Most common values
        </h4>
        {col.commonValues && col.commonValues.length > 0 ? (
          <div className="flex flex-wrap gap-1.5 pt-1">
            {col.commonValues.map((val, vidx) => (
              <span key={vidx} className="text-[10px] font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 rounded px-2.5 py-1 select-text">
                {val === '' ? <span className="italic font-medium">[blank]</span> : val}
              </span>
            ))}
          </div>
        ) : (
          <div className="text-xs text-muted-foreground py-2 italic text-center">
            {col.emptyColumn ? 'The column is empty.' : 'No common values were recorded.'}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The Profile tab: the column profiles already stored for the dataset (J3-F1), one generation at
 * a time, and a way to refresh the snapshot's.
 *
 * It used to render only the result of a run it had started, and otherwise said "This dataset does
 * not have a cached column profile" — false for every bootstrapped dataset — then invited a run
 * that narrowed the stored evidence (J3-F2). Exemplars: Dataiku and Snowsight show a dataset's
 * stored statistics on open and offer "recompute" beside them, never a blank page in their place.
 */
const DatasetProfileTabBody: React.FC<Omit<DatasetProfileTabProps, 'activity'>> = ({
  datasetId,
  load,
  running,
  runErrors,
  hasSnapshot,
  onRunProfiling,
  onRetry,
}) => {
  const [groupIdx, setGroupIdx] = useState(0);
  const [colIdx, setColIdx] = useState(0);
  const own = load && load.datasetId === datasetId ? load : null;
  const failedColumns = Object.entries(runErrors ?? {});

  const runErrorsBanner = failedColumns.length > 0 && (
    <div role="alert" className="border border-warning/40 bg-warning/10 rounded px-3 py-2 text-[11px] font-mono text-foreground space-y-1" data-testid="dataset-profile-run-errors">
      <div className="flex items-center gap-1.5 font-semibold">
        <AlertTriangle size={12} className="text-warning" aria-hidden="true" />
        The last run could not profile {failedColumns.length} column{failedColumns.length === 1 ? '' : 's'}; nothing is stored for {failedColumns.length === 1 ? 'it' : 'them'}.
      </div>
      <ul className="pl-5 list-disc">
        {failedColumns.map(([col, reason]) => <li key={col}><span className="font-semibold">{col}</span> — {reason}</li>)}
      </ul>
    </div>
  );

  if (!own || own.status === 'loading') {
    return (
      <div className="flex-1 flex items-center justify-center py-20 gap-2 text-muted-foreground text-xs font-mono" data-testid="dataset-profile-loading">
        <Loader2 size={16} className="animate-spin text-primary" aria-hidden="true" />
        Loading the stored profile…
      </div>
    );
  }

  if (own.status === 'error') {
    return (
      <div role="alert" className="flex-1 flex flex-col items-center justify-center p-12 gap-3 border border-dashed border-border rounded text-center text-xs font-mono text-foreground">
        <AlertTriangle size={20} className="text-warning" aria-hidden="true" />
        <div>Could not load the stored profile: {own.message}</div>
        <button type="button" onClick={onRetry}
          className="rounded border border-border px-3 py-1 text-[11px] uppercase tracking-wider hover:bg-muted/20">
          Retry
        </button>
      </div>
    );
  }

  const groups = own.groups;
  if (groups.length === 0) {
    return (
      <div className="flex-1 flex flex-col gap-3 min-h-0 overflow-auto">
        {runErrorsBanner}
        <div className="flex flex-col items-center justify-center p-12 border border-dashed border-border rounded flex-grow text-center text-xs text-muted-foreground space-y-4 font-mono">
          {running ? (
            <>
              <Loader2 size={24} className="animate-spin text-primary" aria-hidden="true" />
              <div role="status">Profiling the snapshot… this reads every column once.</div>
            </>
          ) : (
            <>
              <Sparkles size={32} className="text-muted-foreground/55" aria-hidden="true" />
              <div className="max-w-md">
                No stored profile for this dataset yet. Bootstrap profiles each dataset just after
                it turns ready, so profiling may still be running — check again in a moment. If it
                stays empty, the profiling service was unreachable at the time.
              </div>
              <button
                type="button"
                data-testid="dataset-profile-recheck"
                onClick={onRetry}
                className="rounded border border-border px-3 py-1 text-[11px] uppercase tracking-wider text-foreground hover:bg-muted/20"
              >
                Check again
              </button>
              {hasSnapshot ? (
                <button
                  type="button"
                  data-testid="dataset-run-profiling"
                  onClick={onRunProfiling}
                  className="px-4 py-2 bg-emerald-500 hover:bg-emerald-400 text-black font-semibold rounded text-xs uppercase tracking-wider flex items-center gap-1.5 transition-colors disabled:opacity-50"
                >
                  <Sparkles size={12} aria-hidden="true" />
                  Run profiling
                </button>
              ) : (
                <div className="text-rose-400 italic text-[11px] bg-rose-500/5 px-3 py-1.5 rounded border border-rose-500/10 flex items-center gap-1.5">
                  <ShieldAlert size={12} aria-hidden="true" />
                  Cannot profile: this dataset metadata has no bound snapshot ID.
                </div>
              )}
            </>
          )}
        </div>
      </div>
    );
  }

  const group = groups[Math.min(groupIdx, groups.length - 1)];
  const col = group.columns[Math.min(colIdx, group.columns.length - 1)];
  const note = keyEvidenceNote(group);

  return (
    <div className="flex-1 flex flex-col gap-3 min-h-0 overflow-auto">
      {/* Which generation, whether it is key evidence, and the one action. */}
      <div className="flex flex-wrap items-center gap-2 font-mono text-[11px]">
        <span className="text-[10px] uppercase tracking-wider text-muted-foreground">Profiled</span>
        {/* Toggle buttons, as the column list beside it: each is a Tab stop answering to
            Enter/Space. role="radio" would promise arrow-key movement it did not have. */}
        <div role="group" aria-label="Which stored profile to show" className="flex flex-wrap gap-1">
          {groups.map((g, i) => (
            <button
              key={`${g.subject}:${g.classIri ?? ''}`}
              type="button"
              aria-pressed={g === group}
              data-testid={`dataset-profile-group-${g.subject}`}
              onClick={() => { setGroupIdx(i); setColIdx(0); }}
              className={`px-2.5 py-1 rounded border transition-colors ${g === group
                ? 'border-primary/50 bg-primary/10 text-primary'
                : 'border-border text-muted-foreground hover:text-foreground'}`}
            >
              {subjectLabel(g)}
              {!g.keyEvidence && <span className="text-[9px] text-muted-foreground"> · not key evidence</span>}
            </button>
          ))}
        </div>
        <span
          className={`text-[9px] uppercase tracking-wider px-1.5 py-0.5 rounded border ${group.keyEvidence
            ? 'border-success/30 text-success'
            : 'border-warning/40 text-warning'}`}
          title={note ?? 'Freyja measured these columns exactly; the planner may take a unique column here as a key.'}
        >
          {group.keyEvidence ? 'key evidence' : 'not key evidence'}
        </span>
        <div className="flex-1" />
        {hasSnapshot && (
          <button
            type="button"
            data-testid="dataset-refresh-profile"
            disabled={running}
            onClick={onRunProfiling}
            title="Profile the dataset's snapshot again. Replaces the stored snapshot profile; the other generations are untouched."
            className="flex items-center gap-1.5 rounded border border-border px-2.5 py-1 text-[11px] text-foreground hover:bg-muted/20 disabled:opacity-50"
          >
            <RefreshCw size={11} className={running ? 'animate-spin' : ''} aria-hidden="true" />
            {running ? 'Profiling…' : 'Refresh profile'}
          </button>
        )}
      </div>
      {note && <div className="text-[11px] font-mono text-muted-foreground">{note}</div>}
      {runErrorsBanner}

      <div className="flex flex-col md:flex-row flex-1 min-h-0 overflow-hidden gap-4" data-testid="dataset-profile-metrics">
        <div className="md:w-60 border border-border bg-muted/10 rounded flex flex-col shrink-0 overflow-y-auto max-h-[150px] md:max-h-none">
          <div className="border-b border-border bg-muted/20 px-3 py-2 text-[9px] uppercase tracking-wider text-muted-foreground font-semibold flex items-center gap-1.5">
            Columns ({group.columns.length})
            <InfoTip text={CONCEPTS.profiling} label="column profiling" />
          </div>
          <div className="flex-1 divide-y divide-border/40">
            {group.columns.map((c, idx) => {
              const isSel = c === col;
              return (
                <button
                  key={`${c.columnIndex}:${c.columnName}`}
                  type="button"
                  aria-pressed={isSel}
                  onClick={() => setColIdx(idx)}
                  className={`w-full text-left px-3.5 py-2.5 flex items-center justify-between text-[11px] transition-all font-mono ${
                    isSel
                      ? 'bg-primary/10 text-primary border-l-2 border-l-primary font-semibold'
                      : 'text-foreground hover:bg-muted/10'
                  }`}
                >
                  <span className="truncate max-w-[150px]">{c.columnName}</span>
                  <span className="text-[8px] uppercase tracking-wider px-1 bg-border/20 text-muted-foreground rounded shrink-0">
                    {c.sourceType}
                  </span>
                </button>
              );
            })}
          </div>
        </div>

        {col && (
          <div className="flex-grow flex flex-col min-h-0 overflow-y-auto space-y-4">
            <div className="border border-border/80 bg-muted/15 rounded px-4 py-2.5 flex items-center justify-between font-mono shrink-0">
              <div className="text-xs font-bold text-foreground">
                Column: <span className="text-emerald-400 font-bold bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20 select-text">{col.columnName}</span>
              </div>
              <div className="text-[10px] text-muted-foreground uppercase flex items-center gap-2">
                Physical type: <OChip label={col.sourceType} type="datatype" sm />
              </div>
            </div>
            <ColumnMetrics group={group} col={col} />
          </div>
        )}
      </div>
    </div>
  );
};


/** The tab: the profiling job's card (UA4, J3-F7) above the stored evidence (UA3, J3-F1). */
export const DatasetProfileTab: React.FC<DatasetProfileTabProps> = ({ activity, ...rest }) => (
  <div className="flex-1 flex flex-col min-h-0 overflow-auto">
    {activity && <div className="mb-4 shrink-0" data-testid="dataset-profile-job">{activity}</div>}
    <DatasetProfileTabBody {...rest} />
  </div>
);

export default DatasetProfileTab;

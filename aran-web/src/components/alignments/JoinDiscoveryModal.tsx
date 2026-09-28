import React, { useState } from 'react';
import { X, AlertCircle, AlertTriangle, RefreshCw, Sparkles, Check, ChevronDown, ChevronRight } from 'lucide-react';
import { CmdHeader } from '@/components/CmdHeader';
import { Dialog } from '@/components/ui/Dialog';
import { CONCEPTS } from '@/lib/concepts';
import { localName } from '@/lib/iri';
import { joinScoreTone } from '@/lib/joinScore';
import {
  defaultRelationship,
  discoveryState,
  emptyOutcomeMessage,
  joinMultiplicity,
  looksLikeTwoSurrogateKeys,
  overlapSummary,
  packJoinLabel,
  queriedRelationship,
  relationshipNameProblem,
  unsampledNote,
  type JoinCandidate,
  type JoinDirection,
  type JoinDiscoveryRun,
  type JoinEvidence,
} from '@/lib/joinDiscovery';
import { isSendable } from './candidateAccept';
import type { CandidateOutcome } from './types';

interface JoinDiscoveryModalProps {
  show: boolean;
  onClose: () => void;
  threshold: number;
  setThreshold: (threshold: number) => void;
  method: 'ml' | 'exact';
  setMethod: (method: 'ml' | 'exact') => void;
  strictness: number;
  setStrictness: (strictness: number) => void;
  discovering: boolean;
  /** A run that failed outright (the job, or the request) — not a degraded result. */
  discoveryError: string | null;
  /** The last settled run's result; null before the first run or after a failed one. */
  result: JoinDiscoveryRun | null;
  /** Id of the last run that settled either way — what `data-run` publishes to the e2e page object. */
  settledRunId: number | null;
  selectedCandidates: Record<number, boolean>;
  setSelectedCandidates: (selected: Record<number, boolean>) => void;
  onRunDiscovery: () => void;
  /** `labels[idx]` is the packed label to post for ticked candidate idx (J3-F14). */
  onAcceptCandidates: (labels: Record<number, string>) => void;
  /** An accept is in flight — Accept is disabled so a second click cannot double-post (J3-F4). */
  accepting?: boolean;
  /** What happened to each candidate the last accept sent, by index (J3-F4 / J4-F9). */
  outcomes?: Record<number, CandidateOutcome>;
  /** Why a candidate cannot be added — it is already aligned, queued or rejected — by index. */
  unavailable?: Record<number, string>;
  /** A refused candidate's edited label, by index (review UA5); absent → the discovered label. */
  labels?: Record<number, string>;
  onLabelChange?: (index: number, label: string) => void;
}

const SCORE_TONE_CLASS = { strong: 'text-success', good: 'text-primary', weak: 'text-foreground' } as const;
const SCORE_TONE_CHIP = {
  strong: 'border-success/40 bg-success/10 text-success',
  good: 'border-primary/40 bg-primary/10 text-primary',
  weak: 'border-border bg-muted/30 text-muted-foreground',
} as const;

const pct = (x: number | null | undefined) =>
  typeof x === 'number' ? `${Math.round(x * 100)} %` : '—';

const MULTIPLICITY_TEXT: Record<string, string> = {
  'n..1': 'each left row met at most one right row (right column unique)',
  '1..n': 'each right row met at most one left row (left column unique)',
  '1..1': 'both columns unique',
  'n..m': 'neither column unique — a join multiplies rows',
};

/** One candidate's measured facts, expanded under its row (J3-F6). */
const EvidenceDetail: React.FC<{ cand: JoinCandidate; nameA: string; nameB: string }> = ({ cand, nameA, nameB }) => {
  const e: JoinEvidence = cand.evidence ?? {};
  const multiplicity = joinMultiplicity(e);
  const left = `${nameA}.${cand.columnA}`;
  const right = `${nameB}.${cand.columnB}`;
  const contained = typeof e.containment === 'number' && (e.containmentOf === 'A' || e.containmentOf === 'B')
    ? { share: e.containment, inner: e.containmentOf === 'A' ? left : right, outer: e.containmentOf === 'A' ? right : left }
    : null;
  return (
    <dl className="grid grid-cols-[auto_1fr_1fr] gap-x-4 gap-y-1 text-[10.5px] font-mono text-muted-foreground">
      <dt />
      <dd className="text-foreground font-semibold truncate">{left}</dd>
      <dd className="text-foreground font-semibold truncate">{right}</dd>
      <dt>type</dt><dd>{e.typeA ?? '—'}</dd><dd>{e.typeB ?? '—'}</dd>
      <dt>distinct values</dt>
      <dd className="tabular-nums">{e.distinctA?.toLocaleString() ?? '—'}</dd>
      <dd className="tabular-nums">{e.distinctB?.toLocaleString() ?? '—'}</dd>
      <dt>unique (distinct ÷ rows)</dt><dd>{pct(e.uniquenessA)}</dd><dd>{pct(e.uniquenessB)}</dd>
      <dt>empty (NULL)</dt><dd>{pct(e.nullFractionA)}</dd><dd>{pct(e.nullFractionB)}</dd>
      <dt>contained</dt>
      <dd className="col-span-2" data-testid="align-evidence-containment">
        {contained
          ? `≈ ${pct(contained.share)} of ${contained.inner}'s distinct values appear in ${contained.outer} — estimated from a hash sample and the profiled distinct counts`
          : typeof e.valueOverlap === 'number' ? '—' : 'not estimated — a side was profiled before value samples existed; re-run profiling'}
      </dd>
      <dt>shared distinct values</dt>
      <dd className="col-span-2">
        {typeof e.valueOverlap === 'number'
          ? `≈ ${pct(e.valueOverlap)} of the two columns' distinct values together (Jaccard) — estimated from a hash sample`
          : 'not estimated — a side was profiled before value samples existed; re-run profiling'}
      </dd>
      <dt>shape</dt>
      <dd className="col-span-2">
        {multiplicity ? `${multiplicity} — ${MULTIPLICITY_TEXT[multiplicity]}, measured when profiled` : '—'}
      </dd>
    </dl>
  );
};

/**
 * The join-discovery settings + results dialog. Every settled run renders exactly one outcome
 * state (`align-discovery-outcome`, `data-state`): candidates, a partial or failed run (degraded,
 * with the reason and Retry), a missing profile, a run whose every pair lacked a value sample
 * (`unmeasured` — nothing was compared), or a clean "nothing matched" that says how many pairs
 * were compared. A failure or an unmeasured run is never shown as an empty list (J3-F5, J4-F21).
 */
export const JoinDiscoveryModal: React.FC<JoinDiscoveryModalProps> = ({
  show,
  onClose,
  threshold,
  setThreshold,
  method,
  setMethod,
  strictness,
  setStrictness,
  discovering,
  discoveryError,
  result,
  settledRunId,
  selectedCandidates,
  setSelectedCandidates,
  onRunDiscovery,
  onAcceptCandidates,
  accepting = false,
  outcomes = {},
  unavailable = {},
  labels = {},
  onLabelChange = () => {},
}) => {
  // Per-candidate relationship edits, keyed by the run they were made against so a new run
  // (whose indexes mean different pairs) starts clean.
  const [edits, setEdits] = useState<{ runId: number | null; byIdx: Record<number, { relationship: string; direction: JoinDirection }> }>(
    { runId: null, byIdx: {} },
  );
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  if (!show) return null;
  const pair = result?.datasetNameA && result?.datasetNameB ? `${result.datasetNameA} × ${result.datasetNameB}` : null;

  const candidates = result?.candidates ?? [];
  const nameA = result?.datasetNameA ?? 'A';
  const nameB = result?.datasetNameB ?? 'B';
  const byIdx = edits.runId === (result?.runId ?? null) ? edits.byIdx : {};
  const editOf = (idx: number, cand: JoinCandidate) =>
    byIdx[idx] ?? { relationship: defaultRelationship(cand.label), direction: 'forward' as JoinDirection };
  const setEdit = (idx: number, next: { relationship: string; direction: JoinDirection }) =>
    setEdits({ runId: result?.runId ?? null, byIdx: { ...byIdx, [idx]: next } });

  const state = discoveryError ? 'error' : result ? discoveryState(result) : null;
  const showList = !discovering && !discoveryError && candidates.length > 0;

  const labelOf = (idx: number) => labels[idx] ?? candidates[idx]?.label ?? '';
  const sendableAt = (idx: number) => isSendable({
    selected: !!selectedCandidates[idx], unavailable: unavailable[idx], outcome: outcomes[idx], label: labelOf(idx),
  });
  const failedCount = Object.values(outcomes).filter((o) => o.state === 'failed').length;
  const sendable = candidates.some((_, idx) => sendableAt(idx));
  // Refused rows the next click would send, and refused rows it would not: the same request
  // refused for itself (a 4xx) is refused again, so it waits for its label to change (review UA5).
  const retryCount = candidates.filter((_, idx) => outcomes[idx]?.state === 'failed' && sendableAt(idx)).length;
  const stuckCount = failedCount - retryCount;

  // A ticked candidate whose typed relationship name cannot be posted blocks Accept, and says why.
  const relationshipBlocked = candidates.some((cand, idx) =>
    !!selectedCandidates[idx] && relationshipNameProblem(editOf(idx, cand).relationship) !== null);

  const accept = () => {
    if (relationshipBlocked) return;
    // Only the rows the click sends (J3-F4): a refused row is retried, an added one is not resent.
    const packed: Record<number, string> = {};
    candidates.forEach((cand, idx) => {
      if (!sendableAt(idx)) return;
      const e = editOf(idx, cand);
      packed[idx] = packJoinLabel(labelOf(idx), e.relationship, e.direction);
    });
    onAcceptCandidates(packed);
  };

  const retryButton = (
    <button
      type="button"
      data-testid="align-discovery-retry"
      onClick={onRunDiscovery}
      className="mt-2 h-7 px-3 border border-border rounded-md text-[11px] font-mono text-foreground hover:border-primary hover:text-primary inline-flex items-center gap-1.5"
    >
      <RefreshCw size={12} aria-hidden="true" /> Retry
    </button>
  );

  // The one Dialog (role=dialog, Escape closes, focus returns) — the hand-rolled overlay had none.
  return (
    <Dialog
      open={show}
      onClose={onClose}
      label="Join discovery"
      maxWidth="max-w-3xl"
      className="flex flex-col max-h-[85vh]"
      testId="align-discovery-panel"
    >
        <CmdHeader
          command={pair ? `Join discovery — ${pair}` : 'Join discovery'}
          info={CONCEPTS.joinDiscovery}
          right={
            <button
              type="button"
              data-testid="align-discovery-close"
              aria-label="Close join discovery"
              onClick={onClose}
              className="text-muted-foreground hover:text-foreground cursor-pointer p-1 rounded hover:bg-muted/50 transition-colors border-0 bg-transparent focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
            >
              <X size={15} aria-hidden="true" />
            </button>
          }
        />

        <div className="p-5 flex flex-col gap-4 overflow-y-auto">
          <div>
            <h4 className="text-xs font-mono font-bold uppercase tracking-wider text-foreground">Join Discovery Settings</h4>
            <p className="text-[10.5px] font-mono text-muted-foreground mt-1">
              Candidates scoring below the minimum join score are left out. At 0, every pair that shares any values is listed.
            </p>
          </div>

          {/* Threshold Slider */}
          <div className="p-4 border border-border/60 bg-muted/5 rounded-md flex flex-col gap-3">
            <div className="flex items-center justify-between text-xs font-mono">
              <label htmlFor="discovery-threshold" className="text-muted-foreground">Minimum join score:</label>
              <span className="text-primary font-bold">{threshold.toFixed(2)}</span>
            </div>
            <div className="flex items-center gap-4">
              <input
                id="discovery-threshold"
                type="range"
                aria-label="Similarity threshold"
                min="0.0"
                max="1.0"
                step="0.05"
                value={threshold}
                onChange={(e) => setThreshold(parseFloat(e.target.value))}
                disabled={discovering}
                className="flex-1 accent-primary h-1 bg-border rounded-lg appearance-none cursor-pointer disabled:cursor-not-allowed"
              />
              <button
                type="button"
                data-testid="align-discovery-run"
                onClick={onRunDiscovery}
                disabled={discovering}
                className="h-8 px-4 bg-primary text-primary-foreground font-mono text-[11px] uppercase tracking-wider font-bold rounded-md hover:bg-primary/95 transition-all shadow-md active:scale-98 disabled:opacity-50 disabled:cursor-not-allowed shrink-0 border-0 cursor-pointer"
              >
                {discovering ? 'Analyzing...' : 'Run Discovery'}
              </button>
            </div>
          </div>

          {/* Method & Strictness Selection */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <fieldset className="p-4 border border-border/60 bg-muted/5 rounded-md flex flex-col gap-2">
              <legend className="text-[11px] font-mono font-semibold text-muted-foreground uppercase tracking-wide px-1">
                Discovery Method
              </legend>
              <div className="flex flex-col gap-2 mt-1">
                <label htmlFor="discovery-method-ml" className="flex items-center gap-2 text-xs font-mono cursor-pointer text-foreground select-none">
                  <input
                    id="discovery-method-ml"
                    type="radio"
                    name="discoveryMethod"
                    value="ml"
                    checked={method === 'ml'}
                    // The two methods score on different scales (lib/joinScore), so a threshold
                    // chosen for one means nothing for the other: reset it on a switch (J3-F13).
                    onChange={() => { if (method !== 'ml') { setMethod('ml'); setThreshold(0); } }}
                    disabled={discovering}
                    className="accent-primary"
                  />
                  <span>Predicted join quality (learned model)</span>
                </label>
                <label htmlFor="discovery-method-exact" className="flex items-center gap-2 text-xs font-mono cursor-pointer text-foreground select-none">
                  <input
                    id="discovery-method-exact"
                    type="radio"
                    name="discoveryMethod"
                    value="exact"
                    checked={method === 'exact'}
                    onChange={() => { if (method !== 'exact') { setMethod('exact'); setThreshold(0); } }}
                    disabled={discovering}
                    className="accent-primary"
                  />
                  <span>Exact value overlap</span>
                </label>
                <div className="text-[10px] font-mono text-muted-foreground/75 pl-5 pt-1 border-t border-border/20 leading-normal">
                  {method === 'ml'
                    ? 'Predicts how well two columns join from how alike their profiles are.'
                    : 'Scores how many values the two columns share, weighed by how alike their sizes are. Scores top out near 50% at the default strictness, never near 100%.'}
                  {/* J3-F19: the formula is for the curious, not the headline. */}
                  <details className="mt-1.5">
                    <summary className="cursor-pointer text-muted-foreground">How is this scored?</summary>
                    <p className="pt-1 leading-relaxed">
                      {method === 'ml'
                        ? 'A gradient-boosting model trained on 22 distance features between the two column profiles (value lengths, word counts, common values and more). See “Run join discovery” in the Aran docs.'
                        : 'It combines the multiset Jaccard similarity of a hash sample of each column’s distinct values (MJ) with the ratio of the two columns’ distinct counts (K), each mapped through a truncated-normal distribution and multiplied. On this scale two identical value sets score about 0.5. See “Run join discovery” in the Aran docs.'}
                    </p>
                  </details>
                </div>
              </div>
            </fieldset>

            {/* Strictness selector (only active when method is exact) */}
            <div className={`p-4 border border-border/60 rounded-md flex flex-col gap-2 transition-all ${
              method === 'exact' ? 'bg-muted/5 opacity-100' : 'bg-muted/5 opacity-40 select-none pointer-events-none'
            }`}>
              <div className="flex items-center justify-between text-[11px] font-mono font-semibold text-muted-foreground uppercase tracking-wide">
                <label htmlFor="discovery-strictness">Strictness Modifier</label>
                {method === 'exact' && (
                  <span className="text-primary font-bold">{strictness.toFixed(2)}</span>
                )}
              </div>
              <div className="flex items-center gap-2 mt-2">
                <input
                  id="discovery-strictness"
                  type="range"
                  aria-label="Strictness"
                  min="0.0"
                  max="1.0"
                  step="0.05"
                  value={strictness}
                  onChange={(e) => setStrictness(parseFloat(e.target.value))}
                  disabled={discovering || method !== 'exact'}
                  className="flex-1 accent-primary h-1 bg-border rounded-lg appearance-none cursor-pointer disabled:cursor-not-allowed"
                />
              </div>
              <div className="flex items-center justify-between text-[9px] font-mono text-muted-foreground/60 mt-1">
                <span>Relaxed (0.0)</span>
                <span>Balanced (0.25)</span>
                <span>Strict (1.0)</span>
              </div>
            </div>
          </div>

          {/* Discovering state — no outcome is shown while a run is in flight. */}
          {discovering && (
            <div role="status" className="flex flex-col items-center justify-center py-10 gap-3 border border-border bg-muted/5 rounded-md">
              <RefreshCw className="animate-spin text-primary shrink-0" size={24} aria-hidden="true" />
              <span className="text-xs font-mono text-muted-foreground">
                Comparing the two sources&apos; column profiles and scoring every column pair…
              </span>
            </div>
          )}

          {/* The settled outcome: exactly one state, published for the e2e page object. */}
          {!discovering && state !== null && (
            <div
              data-testid="align-discovery-outcome"
              data-state={state}
              data-run={settledRunId ?? ''}
              data-pairs-scored={result?.pairsScored ?? ''}
              className="flex flex-col gap-3"
            >
              {state === 'error' && (
                <div role="alert" className="p-3 bg-error/5 border border-error/30 rounded-md text-xs font-mono text-error flex items-start gap-2.5">
                  <AlertCircle size={14} className="shrink-0 mt-0.5" aria-hidden="true" />
                  <div>
                    <div className="font-semibold">Discovery failed</div>
                    <div className="text-[10.5px] text-foreground mt-0.5">{discoveryError}</div>
                    {retryButton}
                  </div>
                </div>
              )}

              {(state === 'degraded' || state === 'partial') && result && (
                <div
                  role="alert"
                  data-testid="align-discovery-degraded"
                  className={`p-3 rounded-md text-xs font-mono flex items-start gap-2.5 border ${
                    state === 'degraded' ? 'bg-error/5 border-error/30 text-error' : 'bg-warning/5 border-warning/40 text-warning'
                  }`}
                >
                  <AlertTriangle size={14} className="shrink-0 mt-0.5" aria-hidden="true" />
                  <div>
                    <div className="font-semibold">
                      {state === 'degraded'
                        ? 'Discovery could not score these sources — this is not a "no match"'
                        : 'Incomplete — some columns could not be scored'}
                    </div>
                    <div className="text-[10.5px] text-foreground mt-0.5">{result.reason}</div>
                    <div className="text-[10.5px] text-foreground mt-1">
                      {state === 'degraded'
                        ? 'No column pair was compared for the failed columns. Check the scoring service under Admin › System, then retry.'
                        : 'The candidates below come only from the columns that were scored; a missing pair may simply not have been compared.'}
                    </div>
                    {retryButton}
                  </div>
                </div>
              )}

              {state === 'no-profile' && result && (
                <div role="status" className="p-3 border border-warning/40 bg-warning/5 rounded-md text-xs font-mono text-foreground flex items-start gap-2.5">
                  <AlertTriangle size={14} className="shrink-0 mt-0.5 text-warning" aria-hidden="true" />
                  <div>
                    <div className="font-semibold">
                      Nothing to compare: {result.profiledColumnsA === 0 ? nameA : nameB} has no measured column profile.
                    </div>
                    <div className="text-[10.5px] text-muted-foreground mt-0.5">
                      Profile it from Datasets (its Profile tab), then run discovery again.
                    </div>
                  </div>
                </div>
              )}

              {state === 'unmeasured' && result && (() => {
                const msg = emptyOutcomeMessage(result);
                return (
                  <div role="status" data-testid="align-discovery-unmeasured" className="p-3 border border-warning/40 bg-warning/5 rounded-md text-xs font-mono text-foreground flex items-start gap-2.5">
                    <AlertTriangle size={14} className="shrink-0 mt-0.5 text-warning" aria-hidden="true" />
                    <div>
                      <div className="font-semibold">{msg.headline}</div>
                      {msg.advice && <div className="text-[10.5px] text-muted-foreground mt-0.5">{msg.advice}</div>}
                    </div>
                  </div>
                );
              })()}

              {state === 'none' && result && (() => {
                const msg = emptyOutcomeMessage(result);
                return (
                  <div role="status" className="flex flex-col items-center justify-center py-8 border border-border/50 border-dashed rounded-md bg-muted/5 text-center px-4">
                    <Sparkles size={20} className="text-muted-foreground/60 shrink-0" aria-hidden="true" />
                    <span className="text-xs font-mono text-foreground mt-2">{msg.headline}</span>
                    {msg.advice && <span className="text-[10.5px] font-mono text-muted-foreground mt-1">{msg.advice}</span>}
                  </div>
                );
              })()}
            </div>
          )}

          {/* Candidates results list */}
          {showList && (
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-mono font-bold uppercase tracking-wider text-foreground">
                  Discovered Candidates ({candidates.length})
                  {result?.pairsScored != null && (
                    <span className="ml-2 normal-case font-normal tracking-normal text-muted-foreground">
                      of {result.pairsScored - (result.unsampledPairs ?? 0)} pairs compared
                    </span>
                  )}
                </span>
                <div className="flex items-center gap-3 text-[10.5px] font-mono">
                  <button
                    type="button"
                    onClick={() => {
                      const all: Record<number, boolean> = {};
                      candidates.forEach((_, idx) => { if (!unavailable[idx]) all[idx] = true; });
                      setSelectedCandidates(all);
                    }}
                    className="text-primary hover:underline cursor-pointer border-0 bg-transparent"
                  >
                    Select All
                  </button>
                  <span className="text-muted-foreground/30" aria-hidden="true">|</span>
                  <button
                    type="button"
                    onClick={() => setSelectedCandidates({})}
                    className="text-muted-foreground hover:text-foreground cursor-pointer border-0 bg-transparent"
                  >
                    Clear All
                  </button>
                </div>
              </div>

              {result && unsampledNote(result) && (
                <div role="status" data-testid="align-discovery-unsampled-note" className="text-[10.5px] font-mono text-warning flex items-start gap-1.5">
                  <AlertTriangle size={11} className="shrink-0 mt-px" aria-hidden="true" />
                  <span>{unsampledNote(result)} Re-run profiling on {nameA} and {nameB} to include them.</span>
                </div>
              )}

              <div className="border border-border rounded-md overflow-hidden max-h-[340px] overflow-y-auto">
                <table className="w-full text-left border-collapse font-mono text-xs">
                  <thead>
                    <tr className="bg-muted/40 border-b border-border text-muted-foreground text-[10px] uppercase font-bold tracking-wider">
                      <th className="p-2.5 w-10 text-center">Select</th>
                      <th className="p-2.5">Attribute A (Left)</th>
                      <th className="p-2.5">Attribute B (Right)</th>
                      <th className="p-2.5">Evidence</th>
                      <th className="p-2.5 text-right">Join score</th>
                      <th className="p-2.5">Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {candidates.map((cand, idx) => {
                      const leftLocal = localName(cand.iriA);
                      const rightLocal = localName(cand.iriB);
                      const key = `${leftLocal}-${rightLocal}`;
                      const tone = joinScoreTone(cand.score, method);
                      const multiplicity = joinMultiplicity(cand.evidence);
                      const surrogate = looksLikeTwoSurrogateKeys(cand.evidence);
                      const selected = !!selectedCandidates[idx];
                      const edit = editOf(idx, cand);
                      const problem = relationshipNameProblem(edit.relationship);
                      const open = !!expanded[key];
                      const outcome = outcomes[idx];
                      const blocked = unavailable[idx];
                      return (
                      <React.Fragment key={idx}>
                      <tr
                        data-testid={`align-candidate-${leftLocal}-${rightLocal}`}
                        data-rank={idx}
                        data-left={leftLocal}
                        data-right={rightLocal}
                        className="hover:bg-muted/10 transition-colors"
                      >
                        <td className="p-2.5 text-center">
                          <input
                            type="checkbox"
                            data-testid={`align-candidate-check-${leftLocal}-${rightLocal}`}
                            checked={selected && !blocked}
                            disabled={!!blocked || outcome?.state === 'added' || accepting}
                            aria-label={blocked
                              ? `${cand.columnA ?? leftLocal} ↔ ${cand.columnB ?? rightLocal}: ${blocked}`
                              : `Accept ${cand.columnA ?? leftLocal} ↔ ${cand.columnB ?? rightLocal}`}
                            onChange={(e) => {
                              setSelectedCandidates({
                                ...selectedCandidates,
                                [idx]: e.target.checked
                              });
                            }}
                            className="accent-primary cursor-pointer w-3.5 h-3.5"
                          />
                        </td>
                        <td className="p-2.5 max-w-[170px]" title={`${nameA}.${cand.columnA ?? ''}`}>
                          <div className="text-foreground font-bold truncate">{cand.columnA}</div>
                          <div className="text-[10px] text-muted-foreground truncate">{nameA}</div>
                        </td>
                        <td className="p-2.5 max-w-[170px]" title={`${nameB}.${cand.columnB ?? ''}`}>
                          <div className="text-foreground font-bold truncate">{cand.columnB}</div>
                          <div className="text-[10px] text-muted-foreground truncate">{nameB}</div>
                        </td>
                        <td className="p-2.5 text-[10.5px] text-muted-foreground">
                          <button
                            type="button"
                            data-testid={`align-evidence-${key}`}
                            aria-expanded={open}
                            aria-label={`Evidence for ${cand.columnA ?? leftLocal} ↔ ${cand.columnB ?? rightLocal}`}
                            onClick={() => setExpanded({ ...expanded, [key]: !open })}
                            className="inline-flex items-center gap-1 text-left hover:text-foreground border-0 bg-transparent cursor-pointer p-0"
                          >
                            {open ? <ChevronDown size={11} aria-hidden="true" /> : <ChevronRight size={11} aria-hidden="true" />}
                            <span>
                              {overlapSummary(cand) ?? (cand.evidence ? 'overlap not sampled' : 'details')}
                              {multiplicity && ` · ${multiplicity}`}
                            </span>
                          </button>
                          {surrogate && (
                            <div className="mt-1 flex items-start gap-1 text-warning" data-testid={`align-surrogate-warning-${key}`}>
                              <AlertTriangle size={11} className="shrink-0 mt-px" aria-hidden="true" />
                              <span>Two unique numeric columns — ids numbered 1..N overlap whether or not they name the same thing.</span>
                            </div>
                          )}
                        </td>
                        <td className="p-2.5 text-right font-bold">
                          <div className="flex items-center justify-end gap-2">
                            <span className={`tabular-nums ${SCORE_TONE_CLASS[tone]}`}>{cand.score.toFixed(2)}</span>
                            <span className={`text-[9px] uppercase tracking-wider border rounded px-1 py-px font-semibold ${SCORE_TONE_CHIP[tone]}`}>
                              {tone}
                            </span>
                          </div>
                        </td>
                        <td className="p-2.5 text-[10.5px]" data-testid={`align-candstatus-${leftLocal}-${rightLocal}`}>
                          {blocked ? (
                            <span className="text-muted-foreground">{blocked}</span>
                          ) : outcome?.state === 'added' ? (
                            <span className="text-success">added</span>
                          ) : outcome?.state === 'failed' ? (
                            <div className="flex flex-col gap-1">
                              <span className="text-error" role="alert">not added: {outcome.message}</span>
                              <label className="flex items-center gap-1.5 text-muted-foreground">
                                <span>label</span>
                                <input
                                  type="text"
                                  data-testid={`align-candlabel-${leftLocal}-${rightLocal}`}
                                  aria-label={`Label for ${cand.columnA ?? leftLocal} ↔ ${cand.columnB ?? rightLocal}`}
                                  value={labelOf(idx)}
                                  disabled={accepting}
                                  onChange={(e) => onLabelChange(idx, e.target.value)}
                                  className="min-w-0 flex-1 bg-input/40 border border-border rounded px-1.5 py-0.5 font-mono text-[10.5px] text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                                />
                              </label>
                            </div>
                          ) : null}
                        </td>
                      </tr>
                      {open && (
                        <tr className="bg-muted/10">
                          <td />
                          <td colSpan={4} className="p-2.5">
                            <EvidenceDetail cand={cand} nameA={nameA} nameB={nameB} />
                          </td>
                        </tr>
                      )}
                      {selected && (
                        <tr className="bg-primary/5">
                          <td />
                          <td colSpan={4} className="p-2.5">
                            <div className="flex flex-wrap items-center gap-2 text-[10.5px] font-mono">
                              <label htmlFor={`align-relationship-${key}`} className="text-muted-foreground">Relationship name</label>
                              <input
                                id={`align-relationship-${key}`}
                                type="text"
                                data-testid={`align-relationship-${key}`}
                                value={edit.relationship}
                                aria-invalid={problem !== null}
                                aria-describedby={problem !== null ? `align-relationship-error-${key}` : undefined}
                                onChange={(e) => setEdit(idx, { ...edit, relationship: e.target.value })}
                                className={`h-7 w-56 rounded-md border bg-muted/20 px-2 text-foreground focus:outline-none focus:ring-1 focus:ring-primary ${problem !== null ? 'border-error' : 'border-border'}`}
                              />
                              <label htmlFor={`align-direction-${key}`} className="sr-only">Direction</label>
                              <select
                                id={`align-direction-${key}`}
                                data-testid={`align-direction-${key}`}
                                value={edit.direction}
                                onChange={(e) => setEdit(idx, { ...edit, direction: e.target.value as JoinDirection })}
                                className="h-7 rounded-md border border-border bg-muted/20 px-2 text-foreground cursor-pointer"
                              >
                                <option value="forward">{nameA} → {nameB}</option>
                                <option value="backward">{nameB} → {nameA}</option>
                              </select>
                              <span className="text-muted-foreground">
                                queried as <code className="text-foreground" data-testid={`align-relationship-queried-${key}`}>-[{queriedRelationship(cand.label, edit.relationship)}]-&gt;</code>
                              </span>
                            </div>
                            {problem !== null && (
                              <div id={`align-relationship-error-${key}`} role="alert" className="mt-1 text-[10.5px] font-mono text-error">
                                {problem}
                              </div>
                            )}
                          </td>
                        </tr>
                      )}
                      </React.Fragment>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              <p className="text-[10.5px] font-mono text-muted-foreground leading-relaxed">
                An accepted join enters the model as a <span className="text-foreground font-semibold">HYPOTHESIS</span>:
                the planner uses it for estimates only and never to drop a join, and a cube refuses it unless you
                confirm it. Uniqueness shown here was measured when the columns were profiled — it is not a guarantee.
              </p>

              {/* Accept / Insert action */}
              {failedCount > 0 && !accepting && (
                <p role="alert" className="text-[11px] text-error leading-relaxed">
                  {failedCount} selected candidate{failedCount === 1 ? ' was' : 's were'} not added — the
                  reason is on {failedCount === 1 ? 'its row' : 'each row'}. The others were added.
                  {stuckCount > 0 && (
                    <> {stuckCount === 1 ? 'One was' : `${stuckCount} were`} refused for the request itself, so
                    sending {stuckCount === 1 ? 'it' : 'them'} again unchanged would be refused again: change the
                    label on {stuckCount === 1 ? 'its row' : 'each row'}, or align {stuckCount === 1 ? 'it' : 'them'} by
                    hand with the form above.</>
                  )}
                </p>
              )}
              <button
                type="button"
                data-testid="align-candidates-accept"
                onClick={accept}
                disabled={accepting || !sendable || relationshipBlocked}
                className="h-9.5 mt-2 bg-primary/10 border border-primary/40 text-primary hover:bg-primary/20 hover:border-primary/65 font-mono text-xs uppercase tracking-wider font-bold rounded-md flex items-center justify-center gap-1.5 transition-all shadow-md active:scale-98 cursor-pointer disabled:opacity-30 disabled:cursor-not-allowed"
              >
                {accepting
                  ? <><RefreshCw size={14} className="animate-spin" /> Adding…</>
                  : retryCount > 0
                    ? <><Check size={14} /> Retry the {retryCount} not added</>
                    : <><Check size={14} /> Accept & Add Alignments</>}
              </button>
            </div>
          )}
        </div>
    </Dialog>
  );
};

export default JoinDiscoveryModal;

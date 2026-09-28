import React from 'react';
import { CheckCircle, Info, Sparkles, Trash2, Check, X } from 'lucide-react';
import { CmdHeader } from '@/components/CmdHeader';
import { CONCEPTS } from '@/lib/concepts';
import type { CubeEvidenceEdge, ModelMapping } from '@/services/types';
import { localName } from '@/lib/iri';
import { joinRelationshipOf } from '@/lib/joinDiscovery';
import { formatZoned } from '@/lib/zonedTime';
import {
  addedBy, alignmentStanding, joinTier, TIER_DETAIL, TIER_LABEL, type AlignmentStandingKey, type StandingContext,
} from '@/lib/alignmentStanding';

interface MappingPanelProps {
  acceptedMappings: ModelMapping[];
  suggestedMappings: ModelMapping[];
  onDelete: (mappingId: string) => void;
  onAccept: (mappingId: string) => void;
  onReject: (mappingId: string) => void;
  /** Accepted rows the last compile failure named (J4-F6): flagged so they can be found and fixed. */
  flaggedIds?: Set<string>;
  /** The ACTIVE version, the model's baseline and its pending draft — what "live" is measured against. */
  standing?: StandingContext;
  /** The ACTIVE catalog's join edges with their trust tiers (J4-F17); null while loading. */
  joinEdges?: CubeEvidenceEdge[] | null;
  /** Why the tiers could not be read — said, never shown as "no tiers". */
  joinEdgesError?: string | null;
  /** Who is looking, so a row they added says "by you" (J4-F14's author and date). */
  currentUserId?: string | null;
}

const STANDING_STYLE: Record<AlignmentStandingKey, string> = {
  live: 'bg-success/10 text-success border-success/30',
  draft: 'bg-primary/10 text-primary border-primary/30',
  pending: 'bg-muted text-muted-foreground border-border',
  deferred: 'bg-warning/10 text-warning border-warning/30',
  stale: 'bg-warning/10 text-warning border-warning/30',
};

const pairName = (m: ModelMapping) => `${localName(m.sourceIri)} ↔ ${localName(m.targetIri)}`;

/** The accepted-mappings table plus the suggestion review queue — both read
 *  straight from the project's integration model. */
export const MappingPanel: React.FC<MappingPanelProps> = ({
  acceptedMappings,
  suggestedMappings,
  onDelete,
  onAccept,
  onReject,
  flaggedIds,
  standing = {},
  joinEdges = null,
  joinEdgesError = null,
  currentUserId = null,
}) => {
  const hasJoins = acceptedMappings.some((m) => m.kind === 'join');
  return (
    <div className="flex flex-col rounded-md border border-border bg-card overflow-hidden min-h-[160px]">
      <CmdHeader
        command="Alignments — accepted & pending"
        info={CONCEPTS.alignment}
        right={
          <span className="text-[10px] text-muted-foreground">
            {acceptedMappings.length} accepted · {suggestedMappings.length} queued
          </span>
        }
      />

      <div className="flex-1 overflow-x-auto overflow-y-auto max-h-[420px]">
        {/* ── Accepted section ─────────────────────────────────────────── */}
        <div className="px-3 pt-2.5 pb-1 flex items-center gap-1.5 text-[10px] font-mono font-bold uppercase tracking-wider text-success/90">
          <CheckCircle size={11} /> Accepted ({acceptedMappings.length})
        </div>
        {hasJoins && (
          <p className="px-3 pb-1.5 text-[11px] text-muted-foreground leading-relaxed">
            An accepted join is a hypothesis, not a uniqueness guarantee: unless its source declares the
            key, a row can match several rows on the other side, and per-key aggregates over it can
            double-count.
            {joinEdgesError && (
              <span className="text-warning"> Trust tiers could not be read ({joinEdgesError}).</span>
            )}
          </p>
        )}
        {acceptedMappings.length === 0 ? (
          <div className="px-4 py-4 text-center font-mono text-[11px] text-muted-foreground/50 italic flex flex-col items-center gap-1.5">
            <Info size={13} className="text-muted-foreground/30" />
            <span>No accepted correspondences yet.</span>
            <span className="text-[10px] text-muted-foreground/40 font-sans not-italic">Select nodes from the graphs and add them above, or accept a suggestion below.</span>
          </div>
        ) : (
          <table className="w-full text-left font-mono text-[11.5px] border-collapse">
            <thead>
              <tr className="border-b border-border/60 bg-muted/20 text-[10px] uppercase text-muted-foreground/80 font-bold select-none">
                <th className="p-2.5">Source Element A</th>
                <th className="p-2.5">Source Element B</th>
                <th className="p-2.5">Integrated concept</th>
                <th className="p-2.5">Type</th>
                <th className="p-2.5">Status</th>
                <th className="p-2.5">Added</th>
                <th className="p-2.5 w-[50px]"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {acceptedMappings.map((m) => {
                const label = m.label ?? '';
                const standingOf = alignmentStanding(m, standing);
                // A join is at least a hypothesis; the catalog can only raise it, and only while
                // live. Until the evidence has loaded nothing is claimed: a declared-key join must
                // not flash "hypothesis" first.
                const tierLoading = m.kind === 'join' && !joinEdgesError && joinEdges === null;
                const tier = m.kind === 'join' && !joinEdgesError && joinEdges !== null
                  ? joinTier(m, joinEdges) ?? 'HYPOTHESIS'
                  : null;
                return (
                  <tr key={m.id} data-testid={`align-mapping-${m.id}`}
                    data-flagged={flaggedIds?.has(m.id) || undefined}
                    className={`hover:bg-muted/10 transition-colors${flaggedIds?.has(m.id) ? ' bg-warning/10 border-l-2 border-l-warning' : ''}`}>
                    <td className="p-2.5 max-w-[150px] truncate text-primary/95" title={m.sourceIri}>
                      {localName(m.sourceIri)}
                      {flaggedIds?.has(m.id) && (
                        <div className="text-[9.5px] text-warning font-sans whitespace-normal">Named in the last compile error</div>
                      )}
                    </td>
                    <td className="p-2.5 max-w-[150px] truncate text-accent" title={m.targetIri}>{localName(m.targetIri)}</td>
                    <td className="p-2.5 max-w-[220px] truncate font-bold text-foreground">
                      {(() => {
                        if (m.kind === 'join') {
                          // Always the name OQL uses — a discovery-accepted join carries a bare label
                          // and its relationship is minted as <label>_join (JoinLabel.propertyName),
                          // which the user otherwise had to guess (J3-F14); a typed name is shown as
                          // the server mints it (Names.iriFragment), `has author` as `has_author`.
                          const { name, relationship, direction } = joinRelationshipOf(label);
                          const dirSign = direction === 'backward' ? '←' : '→';
                          return (
                            <div className="flex flex-col gap-0.5 font-mono text-[10px]">
                              <span className="font-semibold text-foreground truncate">{name}</span>
                              <span className="text-[8.5px] text-muted-foreground font-normal">
                                queried as <code className="text-foreground font-bold bg-muted/40 px-1 py-0.2 rounded">-[{relationship}]-&gt;</code> ({dirSign})
                              </span>
                            </div>
                          );
                        }
                        return label;
                      })()}
                    </td>
                    <td className="p-2.5">
                      <span className={`text-[9px] uppercase font-bold tracking-wider px-1.5 py-0.5 rounded ${
                        m.kind === 'class' ? 'bg-primary/10 text-primary border border-primary/20' :
                        m.kind === 'datatype' ? 'bg-accent/10 text-accent border border-accent/20' :
                        'bg-success/10 text-success border border-success/20'
                      }`}>
                        {m.kind}
                      </span>
                      {tier && (
                        <span
                          data-testid={`align-tier-${m.id}`}
                          title={TIER_DETAIL[tier]}
                          className={`ml-1.5 text-[9px] font-bold tracking-wider px-1.5 py-0.5 rounded border ${
                            tier === 'HYPOTHESIS'
                              ? 'bg-warning/10 text-warning border-warning/30'
                              : 'bg-success/10 text-success border-success/30'
                          }`}
                        >
                          {TIER_LABEL[tier]}
                          <span className="sr-only">: {TIER_DETAIL[tier]}</span>
                        </span>
                      )}
                      {tierLoading && (
                        <span
                          data-testid={`align-tier-loading-${m.id}`}
                          title="Reading this join's trust tier from the active schema…"
                          className="ml-1.5 text-[9px] font-bold tracking-wider px-1.5 py-0.5 rounded border bg-muted text-muted-foreground border-border"
                        >
                          tier…
                          <span className="sr-only">: reading this join&apos;s trust tier from the active schema</span>
                        </span>
                      )}
                    </td>
                    <td className="p-2.5">
                      <span
                        data-testid={`align-state-${m.id}`}
                        data-state={standingOf.key}
                        title={standingOf.detail}
                        className={`text-[9px] font-bold tracking-wider px-1.5 py-0.5 rounded border whitespace-nowrap ${STANDING_STYLE[standingOf.key]}`}
                      >
                        {standingOf.label}
                        <span className="sr-only">: {standingOf.detail}</span>
                      </span>
                    </td>
                    <td className="p-2.5 text-[10px] text-muted-foreground whitespace-nowrap" data-testid={`align-added-${m.id}`}>
                      <time dateTime={m.createdAt}>{formatZoned(m.createdAt)}</time>
                      {addedBy(m.author, currentUserId) && (
                        <span className="block">{addedBy(m.author, currentUserId)}</span>
                      )}
                    </td>
                    <td className="p-2.5 text-center">
                      <button
                        type="button"
                        onClick={() => onDelete(m.id)}
                        className="text-muted-foreground hover:text-error transition-colors p-1 cursor-pointer rounded focus:outline-none focus:ring-2 focus:ring-ring"
                        title={`Delete alignment ${pairName(m)}`}
                        aria-label={`Delete alignment ${pairName(m)}`}
                      >
                        <Trash2 size={12} aria-hidden="true" />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}

        {/* ── Suggestion review queue ──────────────────────────────────── */}
        <div id="tour-align-suggestions" className="px-3 pt-3 pb-1 flex items-center gap-1.5 text-[10px] font-mono font-bold uppercase tracking-wider text-primary/90 border-t border-border/50 mt-1">
          <Sparkles size={11} /> Suggestions — review queue ({suggestedMappings.length})
        </div>
        {suggestedMappings.length === 0 ? (
          <div className="px-4 py-4 text-center font-mono text-[11px] text-muted-foreground/40 italic">
            No pending suggestions. Run “Suggest Property Alignments” for the selected pair.
          </div>
        ) : (
          <table className="w-full text-left font-mono text-[11.5px] border-collapse">
            <thead>
              <tr className="border-b border-border/60 bg-muted/20 text-[10px] uppercase text-muted-foreground/80 font-bold select-none">
                <th className="p-2.5">Source A</th>
                <th className="p-2.5">Source B</th>
                <th className="p-2.5 text-right">Name</th>
                <th className="p-2.5 text-right">Values</th>
                <th className="p-2.5 text-right">Score</th>
                <th className="p-2.5 w-[110px] text-center">Decision</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/40">
              {suggestedMappings.map((m) => {
                const ev = m.evidence ?? {};
                const pct = (v: unknown) =>
                  typeof v === 'number' ? `${(v * 100).toFixed(0)}%` : '—';
                const score = typeof ev.score === 'number' ? ev.score : null;
                return (
                  <tr key={m.id} className="hover:bg-muted/10 transition-colors">
                    <td className="p-2.5 max-w-[130px] truncate text-primary/95" title={m.sourceIri}>{localName(m.sourceIri)}</td>
                    <td className="p-2.5 max-w-[130px] truncate text-accent" title={m.targetIri}>{localName(m.targetIri)}</td>
                    <td className="p-2.5 text-right tabular-nums text-muted-foreground">{pct(ev.linguisticScore)}</td>
                    <td className="p-2.5 text-right tabular-nums text-muted-foreground">{pct(ev.valueScore)}</td>
                    <td className="p-2.5 text-right font-bold">
                      <div className="flex items-center justify-end gap-2">
                        {score != null && (
                          <div className="w-12 bg-muted border border-border h-2 rounded overflow-hidden hidden sm:block">
                            <div className="bg-primary h-full" style={{ width: `${Math.min(100, Math.max(0, score * 100))}%` }} />
                          </div>
                        )}
                        <span className={score != null && score >= 0.7 ? 'text-success' : score != null && score >= 0.5 ? 'text-primary' : 'text-foreground'}>
                          {pct(score)}
                        </span>
                      </div>
                    </td>
                    <td className="p-2.5">
                      <div className="flex items-center justify-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => onAccept(m.id)}
                          title="Accept — becomes an accepted alignment"
                          aria-label={`Accept suggestion ${pairName(m)}`}
                          className="p-1 rounded text-success hover:bg-success/15 border border-success/30 cursor-pointer transition-colors focus:outline-none focus:ring-2 focus:ring-ring"
                        >
                          <Check size={12} aria-hidden="true" />
                        </button>
                        <button
                          type="button"
                          onClick={() => onReject(m.id)}
                          title="Reject — removes it from the queue"
                          aria-label={`Reject suggestion ${pairName(m)}`}
                          className="p-1 rounded text-error hover:bg-error/15 border border-error/30 cursor-pointer transition-colors focus:outline-none focus:ring-2 focus:ring-ring"
                        >
                          <X size={12} aria-hidden="true" />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

export default MappingPanel;

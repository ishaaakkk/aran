import React from 'react';
import { GitCompare, Play, RefreshCw, Sparkles, CheckCircle, AlertCircle } from 'lucide-react';
import { CmdHeader } from '@/components/CmdHeader';
import { StatusDot } from '@/components/StatusDot';
import { JobCard } from '@/components/JobCard';
import { LiveStatus } from '@/components/LiveStatus';
import { SchemaGraphCanvas } from '@/components/SchemaGraphCanvas';
import type { Job } from '@/services/types';
import { localName } from '@/lib/iri';
import type { GraphData, IntegrationResult, SchemaNode } from './types';

interface CompilePanelProps {
  rebuildRequired: boolean;
  onIntegrate: () => void;
  integrating: boolean;
  compileJobId: string | null;
  selectedProjectId: string | null;
  enrolledDatasetIds: string[];
  incrementalDatasetId: string;
  setIncrementalDatasetId: (id: string) => void;
  canIncrement: boolean;
  /** The baseline is no longer the ACTIVE schema — the server would refuse STALE_INCREMENTAL_BASE. */
  staleBaseline?: boolean;
  addableDatasetIds: string[];
  datasetName: (datasetId: string) => string;
  onIncrementalAdd: () => void;
  hasBaseline: boolean;
  integrationResult: IntegrationResult | null;
  onCompileDone: (job: Job) => void;
  integrationError: string | null;
  /** A promote that is neither confirmed nor failed — the page stopped waiting, or the model
   *  could not be read after a NOT_A_DRAFT (J4-F11). Shown as a status, never under "failed". */
  promoteCheck?: { message: string; checking: boolean } | null;
  onRecheckPromote?: () => void;
  promoted: boolean;
  onPromoteDraft: () => void;
  promoting: boolean;
  integratedGraph: GraphData | null;
  integratedGraphLoading: boolean;
  integratedGraphError: string | null;
  integratedSelectedNode: SchemaNode | null;
  setIntegratedSelectedNode: (node: SchemaNode | null) => void;
}

/** Integration triggers (rebuild / incremental add) plus the compiled draft's
 *  result workspace — unused-alignments report, promote action, Turtle view
 *  and the integrated schema graph preview. */
export const CompilePanel: React.FC<CompilePanelProps> = ({
  rebuildRequired,
  onIntegrate,
  integrating,
  compileJobId,
  selectedProjectId,
  enrolledDatasetIds,
  incrementalDatasetId,
  setIncrementalDatasetId,
  canIncrement,
  staleBaseline = false,
  addableDatasetIds,
  datasetName,
  onIncrementalAdd,
  hasBaseline,
  integrationResult,
  onCompileDone,
  integrationError,
  promoteCheck = null,
  onRecheckPromote,
  promoted,
  onPromoteDraft,
  promoting,
  integratedGraph,
  integratedGraphLoading,
  integratedGraphError,
  integratedSelectedNode,
  setIntegratedSelectedNode,
}) => {
  return (
    <>
      {/* Integration triggers and result inspection */}
      <div className="flex flex-col gap-4 border-none">
        <div className="bg-card border border-border rounded-lg p-4 flex flex-col gap-4">

          {/* A rebuild is always available; an incremental add only once a promoted baseline
              exists and nothing has invalidated it. The banner explains the latter case. */}
          {rebuildRequired && (
            <div className="rounded-md border border-warning/40 bg-warning/10 px-3 py-2 text-[11px] font-mono text-warning">
              Integrated material changed since the last promotion — a full rebuild is required
              before sources can be added incrementally again.
            </div>
          )}

          <button
            id="tour-align-integrate"
            data-testid="align-integrate"
            onClick={onIntegrate}
            disabled={integrating || !!compileJobId || !selectedProjectId || enrolledDatasetIds.length === 0}
            className={`w-full h-11 font-mono text-xs uppercase tracking-widest font-extrabold rounded-md flex items-center justify-center gap-2 transition-all border shadow-lg ${
              !selectedProjectId || enrolledDatasetIds.length === 0 || integrating || compileJobId
                ? 'bg-muted/20 border-border/50 text-muted-foreground/30 cursor-not-allowed'
                : 'bg-success/10 border-success/40 text-success hover:bg-success/20 hover:border-success/60 active:scale-[0.97] cursor-pointer'
            }`}
          >
            {integrating ? (
              <>
                <RefreshCw size={14} className="animate-spin" />
                Submitting job...
              </>
            ) : (
              <>
                <Play size={13} fill="currentColor" />
                Rebuild draft schema ({enrolledDatasetIds.length} sources)
              </>
            )}
          </button>

          <div className="flex flex-col gap-2 border-t border-border/60 pt-3">
            <label htmlFor="incremental-select" className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
              Add source (incremental)
            </label>
            <select
              id="incremental-select"
              data-testid="align-incremental-select"
              value={incrementalDatasetId}
              onChange={(e) => setIncrementalDatasetId(e.target.value)}
              disabled={!canIncrement || addableDatasetIds.length === 0 || integrating || !!compileJobId}
              className="h-9 bg-input border border-border rounded-md px-2 font-mono text-xs disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <option value="">Select an enrolled source…</option>
              {addableDatasetIds.map((id) => (
                <option key={id} value={id}>{datasetName(id)}</option>
              ))}
            </select>
            <button
              data-testid="align-incremental-add"
              onClick={onIncrementalAdd}
              disabled={!canIncrement || !incrementalDatasetId || integrating || !!compileJobId}
              className={`w-full h-9 font-mono text-[11px] uppercase tracking-widest font-bold rounded-md flex items-center justify-center gap-2 transition-all border ${
                !canIncrement || !incrementalDatasetId || integrating || compileJobId
                  ? 'bg-muted/20 border-border/50 text-muted-foreground/30 cursor-not-allowed'
                  : 'bg-primary/10 border-primary/40 text-primary hover:bg-primary/20 active:scale-[0.97] cursor-pointer'
              }`}
            >
              <Play size={12} fill="currentColor" />
              Add without re-reading the others
            </button>
            <p className="font-mono text-[10px] text-muted-foreground leading-relaxed">
              {!hasBaseline
                ? 'Needs a promoted baseline first — rebuild, then promote the draft.'
                : rebuildRequired
                  ? 'Blocked: integrated material changed; rebuild first.'
                  : staleBaseline
                    ? 'Blocked: the active schema is no longer this model\u2019s last promote (a rollback or another project\u2019s promote replaced it) — rebuild and promote first.'
                    : addableDatasetIds.length === 0
                    ? 'Every enrolled source is already in the active graph — enrol another to add one.'
                    : 'Reads only the chosen source and the active integrated graph.'}
            </p>
          </div>

          {compileJobId && !integrationResult && (
            <JobCard
              jobId={compileJobId}
              // The job's own `aran rebuild …` / `aran add source …` title, and its reported stages (J4-F16).
              onDone={onCompileDone}
            />
          )}

          {integrationError && (
            <div className="p-3.5 bg-error/5 border border-error/20 rounded-md text-xs font-mono text-error flex items-start gap-2.5">
              <AlertCircle size={15} className="shrink-0 mt-0.5" />
              <div>
                <div className="font-semibold">Integration Failed</div>
                <div className="text-[10.5px] text-error/85 mt-1">{integrationError}</div>
              </div>
            </div>
          )}

          {/* Always mounted — visually hidden while there is nothing to say — so the note is
              announced when it appears (LiveStatus); Check again is aria-disabled while it runs,
              so it keeps focus (third review of UA2). */}
          <div
            data-testid={promoteCheck ? 'align-promote-unconfirmed' : undefined}
            className={promoteCheck
              ? 'p-3.5 bg-warning/5 border border-warning/40 rounded-md text-xs font-mono text-foreground flex items-start gap-2.5'
              : 'sr-only'}
          >
            {promoteCheck && <AlertCircle size={15} className="shrink-0 mt-0.5 text-warning" aria-hidden="true" />}
            <div className="space-y-2">
              <LiveStatus testId="align-promote-status" className="text-[10.5px]">
                {promoteCheck ? (
                  <>
                    <span className="block text-xs font-semibold">Promotion not confirmed yet</span>
                    {promoteCheck.message}
                  </>
                ) : null}
              </LiveStatus>
              {promoteCheck && onRecheckPromote && (
                <button
                  type="button"
                  onClick={() => { if (!promoteCheck.checking) onRecheckPromote(); }}
                  aria-disabled={promoteCheck.checking || undefined}
                  className="inline-flex items-center gap-1.5 h-7 px-3 rounded-md border border-border font-semibold hover:border-primary hover:text-primary aria-disabled:opacity-50 aria-disabled:cursor-not-allowed focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
                >
                  <RefreshCw size={11} className={promoteCheck.checking ? 'animate-spin' : ''} aria-hidden="true" />
                  {promoteCheck.checking ? 'Checking…' : 'Check again'}
                </button>
              )}
            </div>
          </div>

          {!compileJobId && !integrationResult && (
            <div className="p-4 border border-border/60 bg-muted/10 rounded-md text-xs font-mono text-muted-foreground text-center">
              Enrol sources and accept alignments, then compile all {enrolledDatasetIds.length} enrolled
              source{enrolledDatasetIds.length === 1 ? '' : 's'} into a draft schema version.
            </div>
          )}

          {integrationResult && (
            <div className="p-4 border border-success/20 bg-success/5 rounded-md text-xs font-mono text-success text-center">
              {integrationResult.restored
                ? `Draft v${integrationResult.draftVersionNumber} was compiled earlier and has not been promoted — review the preview below, then promote it or rebuild.`
                : 'Draft schema built — review the preview below, then promote.'}
            </div>
          )}

        </div>
      </div>

      {/* Integrated result workspace (Graph + Actions) */}
      {integrationResult && (
        <div className="bg-card border border-border rounded-lg overflow-hidden shrink-0 flex flex-col gap-0 mt-2 animate-in">
          <CmdHeader
            command={`Draft schema v${integrationResult.draftVersionNumber}`}
            right={
              <div className="flex items-center gap-2">
                <StatusDot variant={promoted ? "ok" : "warn"} label={promoted ? "active schema committed" : "draft pending review"} />
              </div>
            }
          />
          <div className="grid grid-cols-1 lg:grid-cols-[380px_1fr] divide-y lg:divide-y-0 lg:divide-x divide-border min-h-[360px]">

            <div className="p-4 flex flex-col gap-4 bg-muted/10">
              <div className="flex items-center gap-2 text-xs font-mono font-bold uppercase tracking-wider text-foreground">
                <Sparkles size={13} className="text-success" />
                <span>Draft details</span>
              </div>

              <div className="bg-card border border-border rounded p-3 text-[11px] font-mono flex flex-col gap-1.5 leading-relaxed text-muted-foreground">
                <div className="flex justify-between">
                  <span>Draft version:</span>
                  <span className="font-bold text-foreground">v{integrationResult.draftVersionNumber}</span>
                </div>
                <div className="flex justify-between">
                  <span>Draft Graph ID:</span>
                  <span className="font-bold text-foreground text-xs truncate max-w-[180px]" title={integrationResult.draftVersionId}>{integrationResult.draftVersionId}</span>
                </div>
                {!integrationResult.restored && (
                  <div className="flex justify-between">
                    <span>Total size:</span>
                    <span className="font-bold text-success">{integrationResult.tripleCount} RDF triples</span>
                  </div>
                )}
              </div>

              {integrationResult.restored && (
                <div data-testid="align-draft-restored" className="bg-card border border-border rounded p-3 text-[10.5px] font-mono text-muted-foreground leading-relaxed">
                  The compile report — triple count, unused alignments, Turtle — was shown to the session
                  that ran it and is not stored. The draft itself is intact; the Versions page compares it
                  with the active schema.
                </div>
              )}

              {/* Unused-alignments report — persists with the draft, not a modal */}
              {!integrationResult.restored && (() => {
                let unused: Array<{ iriA: string; iriB: string; label: string; type: string }> = [];
                try {
                  const parsed = JSON.parse(integrationResult.unusedAlignments || '[]');
                  if (Array.isArray(parsed)) unused = parsed;
                } catch {
                  // Leave empty on malformed payload; the count below reads 0.
                }
                return (
                  <div className="bg-card border border-border rounded p-3 flex flex-col gap-2">
                    <div className="flex items-center gap-1.5 text-[10px] font-mono font-bold uppercase tracking-wider text-warning">
                      <AlertCircle size={11} /> Unused alignments ({unused.length})
                    </div>
                    {unused.length === 0 ? (
                      <div className="text-[10.5px] font-mono text-success/80">
                        Every accepted alignment was used by the compiler.
                      </div>
                    ) : (
                      <div className="flex flex-col gap-1 max-h-[110px] overflow-y-auto">
                        {unused.map((u, i) => (
                          <div key={i} className="flex items-center gap-2 font-mono text-[10px] text-muted-foreground">
                            <span className="text-[8px] uppercase font-bold bg-warning/10 text-warning border border-warning/20 px-1 py-0.5 rounded shrink-0">{u.type}</span>
                            <span className="truncate" title={`${u.iriA} ↔ ${u.iriB}`}>
                              {localName(u.iriA)} ↔ {localName(u.iriB)}
                            </span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })()}

              {promoted ? (
                <div data-testid="align-version-badge" className="p-3 bg-success/10 border border-success/30 rounded-md flex items-start gap-2.5 font-mono text-xs text-success leading-relaxed">
                  <CheckCircle size={15} className="shrink-0 mt-0.5 text-success" />
                  <div>
                    <div className="font-bold">Promoted — this draft is now the active schema.</div>
                    <div className="text-[10px] text-success/80 mt-1">
                      Every query now reads this version. MATERIALIZED products keep serving their snapshot and turn STALE until refreshed; VIRTUAL products follow it on their next read.
                    </div>
                  </div>
                </div>
              ) : (
                <button
                  data-testid="align-promote"
                  onClick={onPromoteDraft}
                  disabled={promoting}
                  className={`h-11 font-mono text-[11px] uppercase tracking-widest font-extrabold rounded-md flex items-center justify-center gap-2 border shadow-md transition-all ${
                    promoting
                      ? 'bg-muted/30 border-border/50 text-muted-foreground/35 cursor-not-allowed'
                      : 'bg-primary/20 border-primary/40 text-primary hover:bg-primary/30 hover:border-primary/60 cursor-pointer hover:shadow-lg active:scale-[0.98]'
                  }`}
                >
                  {promoting ? (
                    <>
                      <RefreshCw size={13} className="animate-spin" />
                      Saving & Promoting...
                    </>
                  ) : (
                    <>
                      <CheckCircle size={13} />
                      Promote v{integrationResult.draftVersionNumber} (tenant-wide)…
                    </>
                  )}
                </button>
              )}

              {/* J4-F22: the RDF is for experts — a disclosure, not a panel every user reads.
                  A restored (re-attached) compile carries no turtle to show. */}
              {!integrationResult.restored && (
              <details
                data-testid="align-draft-turtle"
                className="rounded border border-border/70 overflow-hidden"
              >
                <summary className="h-7.5 px-2 py-1.5 bg-muted/40 cursor-pointer text-[10px] font-mono text-muted-foreground">
                  Expert: the draft graph as Turtle ({integrationResult.tripleCount} triples)
                </summary>
                <pre className="p-2.5 overflow-auto font-mono text-[9.5px] text-foreground/80 bg-card/65 max-h-[140px] whitespace-pre select-text leading-tight scrollbar-thin">
                  {integrationResult.turtle}
                </pre>
              </details>
              )}
            </div>

            <div className="flex flex-col min-w-0 min-h-0 relative h-[360px]">
              <div className="h-8.5 px-3 border-b border-border/50 bg-muted/20 flex items-center justify-between shrink-0 font-mono text-[10px] uppercase text-muted-foreground">
                <span className="flex items-center gap-1.5 font-bold">
                  <GitCompare size={11} /> Integrated schema graph preview
                </span>
                {integratedGraph && (
                  <span className="text-[9.5px] lowercase bg-muted px-2 py-0.5 rounded text-muted-foreground/60">
                    {integratedGraph.nodes.length} nodes · {integratedGraph.edges.length} edges
                  </span>
                )}
              </div>

              <div className="flex-grow relative bg-[radial-gradient(circle_at_1px_1px,var(--border)_1px,transparent_0)] bg-[length:16px_16px] overflow-hidden flex flex-col items-center justify-center min-h-0">
                {integratedGraphLoading ? (
                  <div className="flex flex-col items-center gap-2 font-mono text-xs text-muted-foreground">
                    <RefreshCw size={16} className="animate-spin text-success" />
                    <span>Constructing integrated visual representation...</span>
                  </div>
                ) : integratedGraphError ? (
                  <div className="p-4 text-center max-w-md flex flex-col items-center gap-2">
                    <AlertCircle size={20} className="text-warning" />
                    <div className="font-mono text-xs text-foreground font-semibold">Preview rendering skipped</div>
                    <div className="text-[10px] text-muted-foreground/60 font-mono leading-relaxed">
                      Integrated model was created, but D3 canvas visual preview failed to load: {integratedGraphError}
                    </div>
                  </div>
                ) : !integratedGraph ? (
                  <div className="p-4 text-center max-w-md flex flex-col items-center gap-1.5">
                    <AlertCircle size={20} className="text-muted-foreground/40" />
                    <div className="font-mono text-xs text-muted-foreground font-semibold">No schema graph structure</div>
                    <div className="text-[10px] text-muted-foreground/45 font-mono">Integrated graph contains no renderable nodes. Check the expert Turtle view.</div>
                  </div>
                ) : (
                  <SchemaGraphCanvas
                    graphData={integratedGraph}
                    selectedNode={integratedSelectedNode}
                    onNodeSelect={setIntegratedSelectedNode}
                    ariaLabel={`Draft schema v${integrationResult.draftVersionNumber}`}
                    // F18: the integrated preview mixes classes minted from different
                    // sources, so two "Author" nodes are indistinguishable without the
                    // source qualifier. Source-namespaced IRIs carry their dataset id.
                    nodeSublabel={(n) => {
                      const m = n.iri?.match(/\/source\/([0-9a-f-]{36})\//i);
                      return m ? datasetName(m[1]) : null;
                    }}
                  />
                )}
              </div>

              <div className="h-8.5 px-3 bg-muted/20 border-t border-border/50 flex items-center justify-between shrink-0 font-mono text-[10px]">
                <span className="text-muted-foreground/75">Integrated Selection:</span>
                {integratedSelectedNode ? (
                  <div className="flex items-center gap-1.5 max-w-[80%] min-w-0">
                    <span
                      className="w-1.5 h-1.5 rounded-full shrink-0"
                      style={{
                        backgroundColor:
                          integratedSelectedNode.type === 'class'
                            ? 'var(--primary)'
                            : 'var(--accent)',
                      }}
                    />
                    <span className="text-foreground font-bold truncate" title={integratedSelectedNode.iri}>
                      {integratedSelectedNode.label || integratedSelectedNode.id}
                    </span>
                    <span className="text-[8.5px] font-bold bg-muted px-1.5 py-0.2 rounded text-muted-foreground/60 uppercase">
                      {integratedSelectedNode.type}
                    </span>
                  </div>
                ) : (
                  <span className="text-muted-foreground/30 italic">Click an integrated node to inspect</span>
                )}
              </div>
            </div>

          </div>
        </div>
      )}
    </>
  );
};

export default CompilePanel;

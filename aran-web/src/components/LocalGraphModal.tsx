import React from 'react';
import { Network, Loader2, RotateCcw, ZoomIn, ZoomOut, Maximize2, X } from 'lucide-react';
import { SchemaGraphCanvas } from '@/components/SchemaGraphCanvas';
import type { GraphNode, GraphEdge, Dataset } from '@/services/types';
import type { SchemaGraphInstance } from '@/lib/schema-graph';

/**
 * Full-screen viewer for a dataset's local source graph (G_S): D3 canvas,
 * node inspector, legend, zoom controls, and re-bootstrap. Extracted from
 * DatasetsListView; all state stays with the parent.
 */
interface LocalGraphModalProps {
  dataset: Dataset;
  graphData: { nodes: GraphNode[]; edges: GraphEdge[]; tripleCount: number } | null;
  selectedNode: GraphNode | null;
  onNodeSelect: (node: GraphNode | null) => void;
  instanceRef: React.MutableRefObject<SchemaGraphInstance | null>;
  graphLoading: boolean;
  bootstrapLoading: boolean;
  onReBootstrap: () => void;
  onClose: () => void;
  isDark: boolean;
}

export const LocalGraphModal: React.FC<LocalGraphModalProps> = ({
  dataset, graphData, selectedNode, onNodeSelect, instanceRef,
  graphLoading, bootstrapLoading, onReBootstrap, onClose, isDark,
}) => (
  <div
    className="fixed inset-0 z-[55] flex items-center justify-center bg-black/85 backdrop-blur-sm"
    onClick={onClose}
    role="button"
    tabIndex={0}
    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onClose(); }}
  >
    <div
      className={`w-full max-w-7xl h-[90vh] mx-4 rounded border shadow-2xl overflow-hidden flex flex-col ${
        isDark ? 'border-violet-500/30 bg-[#0F0F16]' : 'border-border bg-card'
      }`}
      onClick={(e) => e.stopPropagation()}
      role="presentation"
      onKeyDown={(e) => e.stopPropagation()}
    >
      <div className={`flex items-center justify-between border-b px-5 py-3 shrink-0 ${
        isDark ? 'border-violet-500/20 bg-violet-500/5' : 'border-border bg-muted/25'
      }`}>
        <div className="flex items-center gap-3">
          <Network size={16} className={isDark ? 'text-violet-400' : 'text-primary'} />
          <div>
            <h2 className={`font-mono text-xs uppercase tracking-widest font-semibold ${
              isDark ? 'text-violet-300' : 'text-foreground'
            }`}>
              local source graph — {dataset.name}
            </h2>
            {graphData && (
              <p className="font-mono text-[10px] text-muted-foreground mt-0.5">
                {graphData.tripleCount} triples · {graphData.nodes.length} nodes · {graphData.edges.length} edges
              </p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            data-testid="local-graph-rebootstrap"
            title="Re-read the source's schema; anything that would break an existing mapping is shown for review before it is applied"
            disabled={!!bootstrapLoading}
            onClick={onReBootstrap}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded font-mono text-[10px] uppercase tracking-wider transition-colors disabled:opacity-40 border ${
              isDark
                ? 'border-violet-500/30 bg-violet-500/10 text-violet-400 hover:bg-violet-500/20'
                : 'border-border bg-primary/5 text-primary hover:bg-primary/10'
            }`}
          >
            {bootstrapLoading ? <Loader2 size={11} className="animate-spin" /> : <RotateCcw size={11} />}
            Re-read source schema
          </button>
          <button
            onClick={() => instanceRef.current?.zoomBy(1.3)}
            className="p-1.5 rounded border border-border/50 bg-muted/10 text-muted-foreground hover:text-foreground transition-colors"
            title="Zoom in"
          ><ZoomIn size={12} /></button>
          <button
            onClick={() => instanceRef.current?.zoomBy(0.75)}
            className="p-1.5 rounded border border-border/50 bg-muted/10 text-muted-foreground hover:text-foreground transition-colors"
            title="Zoom out"
          ><ZoomOut size={12} /></button>
          <button
            onClick={() => instanceRef.current?.fit()}
            className="p-1.5 rounded border border-border/50 bg-muted/10 text-muted-foreground hover:text-foreground transition-colors"
            title="Fit to viewport (also: dbl-click canvas)"
          ><Maximize2 size={12} /></button>
          <button
            type="button"
            data-testid="local-graph-close"
            onClick={onClose}
            className="text-muted-foreground hover:text-foreground transition-colors ml-1"
          ><X size={16} /></button>
        </div>
      </div>

      <div className="flex flex-1 min-h-0 overflow-hidden">
        <div className="flex-1 relative min-h-0">
          {(graphLoading || bootstrapLoading) && (
            <div className={`absolute inset-0 flex flex-col items-center justify-center gap-3 z-10 ${
              isDark ? 'bg-[#0F0F16]' : 'bg-card'
            }`}>
              <Network size={32} className={`animate-pulse ${isDark ? 'text-violet-400' : 'text-primary'}`} />
              <span className="font-mono text-xs text-muted-foreground">
                {bootstrapLoading ? 'bootstrapping…' : 'loading graph…'}
              </span>
            </div>
          )}
          {graphData && graphData.nodes.length === 0 && !graphLoading && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3">
              <Network size={32} className="text-muted-foreground/40" />
              <span className="font-mono text-xs text-muted-foreground">Graph is empty — no schema extracted.</span>
            </div>
          )}
          <SchemaGraphCanvas
            graphData={graphData}
            onNodeSelect={onNodeSelect}
            instanceRef={instanceRef}
          />
        </div>

        <div className={`w-72 shrink-0 border-l flex flex-col overflow-hidden ${
          isDark ? 'border-violet-500/15 bg-[#0F0F16]' : 'border-border bg-card'
        }`}>
          <div className={`px-4 py-3 border-b font-mono text-[10px] uppercase tracking-widest text-muted-foreground shrink-0 ${
            isDark ? 'border-border/30 bg-muted/5' : 'border-border bg-muted/10'
          }`}>
            Node Inspector</div>
          {selectedNode ? (
            <div className="flex-1 overflow-y-auto p-4 space-y-4 font-mono text-xs">
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground uppercase text-[10px]">Type</span>
                <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase border ${
                  selectedNode.type === 'class'
                    ? 'bg-violet-500/10 text-violet-400 border-violet-500/25'
                    : selectedNode.type === 'integrated'
                    ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/25'
                    : selectedNode.type === 'object'
                    ? 'bg-sky-500/10 text-sky-400 border-sky-500/25'
                    : selectedNode.type === 'xsd'
                    ? 'bg-orange-500/10 text-orange-400 border-orange-500/25'
                    : 'bg-pink-500/10 text-pink-400 border-pink-500/25'
                }`}>
                  {selectedNode.type}
                </span>
              </div>
              <div className="space-y-1">
                <div className="text-[10px] uppercase text-muted-foreground">Local Name</div>
                <div className="text-foreground font-bold text-sm bg-muted/20 px-2.5 py-1.5 rounded border border-border/40">
                  {selectedNode.id}
                </div>
              </div>
              <div className="space-y-1">
                <div className="text-[10px] uppercase text-muted-foreground">Full IRI</div>
                <div className="text-foreground/70 text-[10px] bg-muted/10 px-2.5 py-2 rounded border border-border/30 break-all">
                  {selectedNode.iri}
                </div>
              </div>
              <div className="text-[10px] text-muted-foreground/60 pt-2 border-t border-border/20">
                Right-click a node to release its pinned position.<br />
                Double-click canvas to re-fit.
              </div>
            </div>
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center gap-2 text-center px-4">
              <Network size={28} className="text-muted-foreground/25" />
              <p className="font-mono text-[11px] text-muted-foreground/60">
                Click a node to inspect its IRI and type.
              </p>
            </div>
          )}

          <div className="p-4 border-t border-border/30 space-y-2 shrink-0">
            <div className="font-mono text-[10px] uppercase text-muted-foreground mb-2">Legend</div>
            {[
              { color: isDark ? '#9C8CF8' : '#5C49C4', label: 'Source Class' },
              { color: isDark ? '#D67BF0' : '#9032AE', label: 'Datatype Property' },
              { color: isDark ? '#5E9CE8' : '#2A5FB8', label: 'Object Property' },
              { color: isDark ? '#F08E5E' : '#B8551E', label: 'XSD Scalar' },
              { color: isDark ? '#3DCEA0' : '#1F8A5B', label: 'Integrated' },
            ].map(({ color, label }) => (
              <div key={label} className="flex items-center gap-2 font-mono text-[11px] text-muted-foreground">
                <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ backgroundColor: color }} />
                {label}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  </div>
);

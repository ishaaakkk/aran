import React from 'react';
import { GitCompare, Plus, RefreshCw, Sparkles, AlertCircle } from 'lucide-react';
import type { MappingKind } from '@/services/types';
import type { SchemaNode } from './types';
import { alignmentKindsFor } from '@/lib/alignmentKinds';

interface AlignmentCreatorProps {
  leftSelectedNode: SchemaNode | null;
  rightSelectedNode: SchemaNode | null;
  alignmentLabel: string;
  setAlignmentLabel: (label: string) => void;
  alignmentType: MappingKind;
  setAlignmentType: (type: MappingKind) => void;
  relationshipName: string;
  setRelationshipName: (name: string) => void;
  relationshipDirection: 'forward' | 'backward';
  setRelationshipDirection: (direction: 'forward' | 'backward') => void;
  leftDatasetId: string | null;
  rightDatasetId: string | null;
  leftGraphLoading: boolean;
  rightGraphLoading: boolean;
  suggesting: boolean;
  suggestionError: string | null;
  /** What the last "Suggest Property Alignments" run did, kept on screen (J3-F17). */
  suggestionNote?: string | null;
  addMappingError: string | null;
  discoveryCandidates: unknown[];
  discoveryError: string | null;
  discovering: boolean;
  /** Whether the discovery modal is showing — the status line speaks only when it is not. */
  discoveryOpen?: boolean;
  onAddAlignment: () => void;
  onSuggest: () => void;
  onOpenDiscovery: () => void;
  onRunDiscovery: () => void;
}

/** The "Alignment Creator" panel: shows the two selected nodes, label/type
 *  configuration, and the three action buttons (add correspondence, suggest
 *  property alignments, auto-infer join alignments). */
export const AlignmentCreator: React.FC<AlignmentCreatorProps> = ({
  leftSelectedNode,
  rightSelectedNode,
  alignmentLabel,
  setAlignmentLabel,
  alignmentType,
  setAlignmentType,
  relationshipName,
  setRelationshipName,
  relationshipDirection,
  setRelationshipDirection,
  leftDatasetId,
  rightDatasetId,
  leftGraphLoading,
  rightGraphLoading,
  suggesting,
  suggestionError,
  suggestionNote = null,
  addMappingError,
  discoveryCandidates,
  discoveryError,
  discovering,
  discoveryOpen = false,
  onAddAlignment,
  onSuggest,
  onOpenDiscovery,
  onRunDiscovery,
}) => {
  // J4-F6: offer only the kinds this pair admits, and say why when it admits none.
  const allowed = alignmentKindsFor(leftSelectedNode?.type, rightSelectedNode?.type);
  const kindAllowed = allowed.kinds.includes(alignmentType);
  const canAdd = !!leftSelectedNode && !!rightSelectedNode && !!alignmentLabel.trim() && kindAllowed;
  return (
    <div className="bg-card border border-border rounded-lg p-4 shrink-0 flex flex-col gap-4">
      <div className="flex items-center gap-2 border-b border-border/40 pb-2">
        <GitCompare size={14} className="text-primary" />
        <h3 className="font-mono text-xs font-bold uppercase tracking-wider text-foreground">Alignment Creator</h3>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 items-end">

        {/* Node A Selection Detail */}
        <div className="bg-muted/30 border border-border p-2.5 rounded-md text-xs font-mono flex flex-col gap-1 min-h-[58px]">
          <span className="text-[8.5px] uppercase tracking-wider text-muted-foreground font-bold flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-primary" /> Element A (Left)
          </span>
          {leftSelectedNode ? (
            <div className="truncate">
              <div className="text-primary font-bold text-xs truncate">{leftSelectedNode.label || leftSelectedNode.id}</div>
              <div className="text-[9px] text-muted-foreground/60 truncate" title={leftSelectedNode.iri}>
                {leftSelectedNode.iri}
              </div>
            </div>
          ) : (
            <span className="text-muted-foreground/30 italic text-[11px] mt-1">Select Left node above...</span>
          )}
        </div>

        {/* Node B Selection Detail */}
        <div className="bg-muted/30 border border-border p-2.5 rounded-md text-xs font-mono flex flex-col gap-1 min-h-[58px]">
          <span className="text-[8.5px] uppercase tracking-wider text-muted-foreground font-bold flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-accent" /> Element B (Right)
          </span>
          {rightSelectedNode ? (
            <div className="truncate">
              <div className="text-accent font-bold text-xs truncate">{rightSelectedNode.label || rightSelectedNode.id}</div>
              <div className="text-[9px] text-muted-foreground/60 truncate" title={rightSelectedNode.iri}>
                {rightSelectedNode.iri}
              </div>
            </div>
          ) : (
            <span className="text-muted-foreground/30 italic text-[11px] mt-1">Select Right node above...</span>
          )}
        </div>

        {/* Integrated Config fields */}
        <div className="flex flex-col gap-1.5">
          <span className="font-mono text-[9px] text-muted-foreground uppercase tracking-wider font-bold">Label & Type Configuration</span>
          <div className="flex gap-2">
            <input
              type="text"
              data-testid="align-label-input"
              placeholder="Integrated concept label"
              value={alignmentLabel}
              onChange={(e) => setAlignmentLabel(e.target.value)}
              disabled={!leftSelectedNode || !rightSelectedNode}
              className="h-8.5 flex-grow px-2 rounded-md border border-border bg-muted/20 font-mono text-[11.5px] focus:outline-none focus:ring-1 focus:ring-primary placeholder:text-muted-foreground/30 text-foreground disabled:cursor-not-allowed"
            />
            <select
              data-testid="align-kind-select"
              value={alignmentType}
              onChange={(e) => setAlignmentType(e.target.value as MappingKind)}
              disabled={!leftSelectedNode || !rightSelectedNode}
              className="h-8.5 px-2 rounded-md border border-border bg-muted/20 font-mono text-[11.5px] text-foreground focus:outline-none focus:ring-1 focus:ring-primary shrink-0 cursor-pointer disabled:cursor-not-allowed"
            >
              <option value="class" disabled={!allowed.kinds.includes('class')}>class</option>
              <option value="datatype" disabled={!allowed.kinds.includes('datatype')}>datatype</option>
              <option value="object" disabled={!allowed.kinds.includes('object')}>object</option>
              <option value="join" disabled={!allowed.kinds.includes('join')}>join (JoinProperty)</option>
            </select>
          </div>
          {alignmentType === 'join' && (
            <div className="flex flex-col gap-1.5 mt-1.5">
              <input
                type="text"
                data-testid="align-relationship-input"
                placeholder="Relationship name (e.g. hasArtist)"
                value={relationshipName}
                onChange={(e) => setRelationshipName(e.target.value)}
                disabled={!leftSelectedNode || !rightSelectedNode}
                className="h-8 rounded-md border border-border bg-muted/20 font-mono text-[11px] px-2 focus:outline-none focus:ring-1 focus:ring-primary placeholder:text-muted-foreground/35 text-foreground disabled:cursor-not-allowed"
              />
              <select
                value={relationshipDirection}
                onChange={(e) => setRelationshipDirection(e.target.value as 'forward' | 'backward')}
                disabled={!leftSelectedNode || !rightSelectedNode}
                className="h-8 rounded-md border border-border bg-muted/20 font-mono text-[11px] px-2 text-foreground focus:outline-none focus:ring-1 focus:ring-primary cursor-pointer disabled:cursor-not-allowed"
              >
                <option value="forward">A → B (Left to Right)</option>
                <option value="backward">B → A (Right to Left)</option>
              </select>
            </div>
          )}
        </div>

        {/* Action Buttons */}
        <div className="flex flex-col gap-2">
          <button
            type="button"
            data-testid="align-add"
            onClick={onAddAlignment}
            disabled={!canAdd}
            title={allowed.reason ?? undefined}
            className={`h-8.5 font-mono text-[11px] uppercase tracking-widest font-bold rounded-md flex items-center justify-center gap-1.5 transition-all border ${
              canAdd
                ? 'bg-primary/10 border-primary/40 text-primary hover:bg-primary/20 hover:border-primary/60 cursor-pointer shadow-md active:scale-[0.98]'
                : 'bg-muted/10 border-border/50 text-muted-foreground/30 cursor-not-allowed'
            }`}
          >
            <Plus size={12} /> Add Correspondence
          </button>
          {allowed.reason && (
            <div role="alert" data-testid="align-kind-refused" className="text-[10.5px] font-mono text-warning leading-snug">
              {allowed.reason}
            </div>
          )}

          <div className="h-px bg-border/50 my-1" />

          <button
            type="button"
            data-testid="align-suggest"
            onClick={onSuggest}
            disabled={!leftDatasetId || !rightDatasetId || suggesting}
            className={`h-8.5 font-mono text-[11px] uppercase tracking-widest font-bold rounded-md flex items-center justify-center gap-1.5 transition-all border ${
              leftDatasetId && rightDatasetId && !suggesting
                ? 'bg-primary/10 border-primary/30 text-primary hover:bg-primary/20 cursor-pointer shadow-md active:scale-[0.98]'
                : 'bg-muted/10 border-border/50 text-muted-foreground/30 cursor-not-allowed'
            }`}
          >
            {suggesting
              ? <><RefreshCw size={12} className="animate-spin" /> Suggesting…</>
              : <><Sparkles size={12} /> Suggest Property Alignments</>}
          </button>

          <button
            type="button"
            id="tour-align-discover"
            data-testid="align-discovery-open"
            onClick={() => {
              onOpenDiscovery();
              if (discoveryCandidates.length === 0 && !discoveryError && !discovering) {
                onRunDiscovery();
              }
            }}
            disabled={!leftDatasetId || !rightDatasetId || leftGraphLoading || rightGraphLoading}
            className={`h-8.5 font-mono text-[11px] uppercase tracking-widest font-bold rounded-md flex items-center justify-center gap-1.5 transition-all border ${
              leftDatasetId && rightDatasetId
                ? 'bg-secondary/15 border-secondary/40 text-secondary-foreground hover:bg-secondary/25 cursor-pointer shadow-md active:scale-[0.98]'
                : 'bg-muted/10 border-border/50 text-muted-foreground/30 cursor-not-allowed'
            }`}
          >
            <Sparkles size={12} /> Auto-Infer Join Alignments
          </button>
          {/* Discovery runs only when asked (UI-OD7); a run finishing behind a closed modal says
              so here instead of reopening it over whatever the user moved on to. */}
          <p data-testid="align-discovery-status" aria-live="polite" className="text-[10.5px] font-mono text-muted-foreground min-h-[1em]">
            {discoveryOpen ? null
              : discovering ? 'Finding join candidates…'
                : discoveryError ? 'Discovery failed — open it for the reason.'
                  : discoveryCandidates.length > 0
                    ? `${discoveryCandidates.length} join candidate${discoveryCandidates.length === 1 ? '' : 's'} found — open to review.`
                    : null}
          </p>
        </div>
      </div>

      {suggestionNote && !suggestionError && (
        <div role="status" data-testid="align-suggest-outcome" className="p-2.5 bg-muted/30 border border-border rounded-md text-[10.5px] font-mono text-foreground">
          {suggestionNote}
        </div>
      )}

      {(addMappingError || suggestionError) && (
        <div className="p-2.5 bg-error/5 border border-error/20 rounded-md text-[10.5px] font-mono text-error flex items-start gap-2">
          <AlertCircle size={13} className="shrink-0 mt-0.5" />
          <span>{addMappingError ?? suggestionError}</span>
        </div>
      )}
    </div>
  );
};

export default AlignmentCreator;

import { useState } from 'react';
import type { PlanShapeNode } from '../../services/types';

const NODE_COLORS: { pattern: RegExp; color: string }[] = [
  { pattern: /Scan|Source|Values/i,           color: '#5E9CE8' },
  { pattern: /Join/i,                          color: '#E8B339' },
  { pattern: /Filter/i,                        color: '#8B5CF6' },
  { pattern: /Project/i,                       color: '#3DCEA0' },
  { pattern: /Aggregate|Agg/i,                 color: '#F08E5E' },
  { pattern: /Sort|Limit|TopN/i,               color: '#7E7E8A' },
  { pattern: /Union|Intersect|Minus|Except/i,  color: '#D67BF0' },
];

function nodeColor(kind: string): string {
  for (const { pattern, color } of NODE_COLORS) {
    if (pattern.test(kind)) return color;
  }
  return '#9C8CF8';
}

/**
 * Defensive JSON.parse: `planShape` is a wire string on both callers (see
 * `types.ts`'s `OqlRewriteResult`/`QueryRunResult` docs on the STRING-vs-nested-object
 * asymmetry — the caller normalizes to one string before this component sees it), and
 * an old demo fixture or a future backend change could omit or garble it. A garbled or
 * shape-mismatched value must fall back to raw text, never a half-built or crashing tree.
 */
function isPlanShapeNode(value: unknown): value is PlanShapeNode {
  if (!value || typeof value !== 'object') return false;
  const node = value as Record<string, unknown>;
  return (
    typeof node.kind === 'string' &&
    typeof node.detail === 'string' &&
    Array.isArray(node.inputs) &&
    node.inputs.every(isPlanShapeNode)
  );
}

function parsePlanShape(raw: string | undefined): PlanShapeNode | null {
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (isPlanShapeNode(parsed)) {
      return parsed;
    }
  } catch {
    // fall through — the caller renders the raw text instead
  }
  return null;
}

function PlanShapeNodeView({ node }: { node: PlanShapeNode }) {
  const [open, setOpen] = useState(true);
  const color = nodeColor(node.kind);
  const hasChildren = node.inputs.length > 0;

  // A SCAN's detail is "<table>, access=MODE" where annotated (PlanShape.java) — split it
  // so the table name reads as plain text and the access mode gets its own chip. MIXED
  // is not cosmetic (§9): it flags a class whose member sources disagree on placement, so
  // it earns a visually distinct chip rather than blending in with FEDERATED/MATERIALIZED.
  let detail = node.detail;
  let access: string | null = null;
  if (node.kind === 'SCAN') {
    const marker = ', access=';
    const idx = detail.indexOf(marker);
    if (idx >= 0) {
      access = detail.slice(idx + marker.length);
      detail = detail.slice(0, idx);
    }
  }

  return (
    <div className="pn">
      {/* kind/detail/access are mirrored onto attributes because the rendered head interleaves
          the kind chip, the detail text, an access chip and a toggle glyph with no separators
          that survive innerText — the same reason `catalog-class-*`/`catalog-rel-*` are read
          off attributes (see WorkbenchPage.catalogClassNames). `data-detail` carries the SPLIT
          detail, never the raw `"<table>, access=MODE"`: the access mode is its own attribute
          exactly as it is its own chip, so a spec reading a scan's table name never has to
          know the suffix exists. `WorkbenchPage.planNodes()` reads these; `e2eContract.test.tsx`
          pins them. */}
      <div
        data-testid="plan-node"
        data-kind={node.kind}
        data-detail={detail}
        data-access={access ?? undefined}
        className={`pn-head${hasChildren ? ' pn-clickable' : ''}`}
        onClick={() => hasChildren && setOpen(o => !o)}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { if (hasChildren) setOpen(o => !o); } }}
      >
        <span className="pn-type" style={{ color, background: `${color}1A`, border: `1px solid ${color}44` }}>
          {node.kind}
        </span>
        {detail && <span className="pn-attrs">{detail}</span>}
        <div style={{ flex: 1 }} />
        {access && (
          <span
            className={`pn-badge access${access === 'MIXED' ? ' access-mixed' : ''}`}
            title="Access mode"
          >{access}</span>
        )}
        {hasChildren && (
          <span className="pn-toggle" style={{ color }}>{open ? '▾' : '▸'}</span>
        )}
      </div>
      {open && hasChildren && (
        <div className="pn-children" style={{ borderLeftColor: `${color}50` }}>
          {node.inputs.map((c, i) => <PlanShapeNodeView key={i} node={c} />)}
        </div>
      )}
    </div>
  );
}

interface PlanTreeProps {
  logicalPlan: string;
  optimizedPlan: string;
  /** JSON-encoded `PlanShapeNode` — see `parsePlanShape`. Absent/unparseable degrades to
   *  `planShapeText`/raw `optimizedPlan` text, never a crash and never a guessed tree. */
  planShape?: string;
  planShapeText?: string;
}

export function PlanTree({ logicalPlan, optimizedPlan, planShape, planShapeText }: PlanTreeProps) {
  const hasOptimized = optimizedPlan.trim().length > 0;
  const hasLogical = logicalPlan.trim().length > 0;
  const hasBoth = hasOptimized && hasLogical;
  const [view, setView] = useState<'optimized' | 'logical'>(hasOptimized ? 'optimized' : 'logical');

  if (!hasOptimized && !hasLogical) {
    return <div className="result-mid">no plan available</div>;
  }

  // Only the optimized plan carries the structured contract — the logical plan was always
  // a cosmetic tree (regex-parsed from a Calcite string), so it renders as plain text now
  // that the tree view is backed by a real contract instead of guessing at one.
  const shapeRoot = view === 'optimized' ? parsePlanShape(planShape) : null;

  return (
    <div className="plan-tree">
      {hasBoth && (
        <div className="plan-tree-seg">
          <button
            className={`pt-seg-btn${view === 'optimized' ? ' is-on' : ''}`}
            onClick={() => setView('optimized')}
          >optimized</button>
          <button
            className={`pt-seg-btn${view === 'logical' ? ' is-on' : ''}`}
            onClick={() => setView('logical')}
          >logical</button>
        </div>
      )}
      {view === 'logical' ? (
        <pre className="plan-pre">{logicalPlan}</pre>
      ) : shapeRoot ? (
        <div className="pn-root" data-testid="oql-plan-tree">
          <PlanShapeNodeView node={shapeRoot} />
        </div>
      ) : (
        <div className="plan-tree-unstructured">
          <div className="plan-tree-note">unstructured — no usable plan shape</div>
          <pre className="plan-pre">{planShapeText || optimizedPlan}</pre>
        </div>
      )}
    </div>
  );
}

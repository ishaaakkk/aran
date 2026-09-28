import React, { useState, useRef, useEffect, useLayoutEffect, useMemo } from 'react';
import { MappedEdge, WorkbenchClass, edgeKey } from '../../lib/workbenchModel';
import { savePositions, loadPositions } from '../../lib/workbenchStorage';
import { PRESET_LAYOUTS } from '../../lib/demoLayouts';

const TYPE_COLOR = {
  integrated: '#3DCEA0',
  class:      '#9C8CF8',
  object:     '#5E9CE8',
  datatype:   '#D67BF0',
  xsd:        '#F08E5E',
};

const NODE_W = 152;
const NODE_H = 56;

interface Position { x: number; y: number; }
interface LassoRect { x1: number; y1: number; x2: number; y2: number; }

function boundary(from: Position, to: Position, padW = 0, padH = 0): Position {
  const dx = to.x - from.x, dy = to.y - from.y;
  if (dx === 0 && dy === 0) return { x: to.x, y: to.y };
  const hw = NODE_W / 2 + padW, hh = NODE_H / 2 + padH;
  const sx = Math.abs(dx) > 1e-6 ? hw / Math.abs(dx) : Infinity;
  const sy = Math.abs(dy) > 1e-6 ? hh / Math.abs(dy) : Infinity;
  const s = Math.min(sx, sy);
  return { x: to.x - dx * s, y: to.y - dy * s };
}

function circleLayout(index: number, total: number): Position {
  const cx = 500, cy = 310, r = Math.min(200, 80 + total * 18);
  const angle = (index / total) * 2 * Math.PI - Math.PI / 2;
  return { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) };
}

function fmtCount(n: number): string {
  if (n <= 0) return '';
  if (n < 1_000) return `${n}`;
  if (n < 1_000_000) return `${+(n / 1000).toFixed(n < 10_000 ? 1 : 0)}k`;
  return `${+(n / 1_000_000).toFixed(1)}M`;
}

function simpleHash(s: string): string {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return Math.abs(h).toString(36);
}

// Simple force-directed layout: repulsion + edge springs + center gravity.
// Runs synchronously for a fixed number of iterations — fast enough for < 30 nodes.
function runForceLayout(
  nodes: Array<{ name: string; x: number; y: number }>,
  edges: MappedEdge[],
  iterations: number,
): void {
  const nodeMap: Record<string, { name: string; x: number; y: number }> = {};
  nodes.forEach(n => { nodeMap[n.name] = n; });

  for (let iter = 0; iter < iterations; iter++) {
    const f: Record<string, { fx: number; fy: number }> = {};
    nodes.forEach(n => { f[n.name] = { fx: 0, fy: 0 }; });

    // Node-node repulsion
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const a = nodes[i], b = nodes[j];
        const dx = (b.x - a.x) || 0.01, dy = (b.y - a.y) || 0.01;
        const dist = Math.sqrt(dx * dx + dy * dy);
        const force = 22000 / (dist * dist);
        const fx = force * (dx / dist), fy = force * (dy / dist);
        f[b.name].fx += fx; f[b.name].fy += fy;
        f[a.name].fx -= fx; f[a.name].fy -= fy;
      }
    }

    // Edge spring (attract connected nodes toward ideal distance)
    const seen = new Set<string>();
    edges.forEach(e => {
      const key = [e.from, e.to].sort().join('↔');
      if (seen.has(key)) return;
      seen.add(key);
      const a = nodeMap[e.from], b = nodeMap[e.to];
      if (!a || !b) return;
      const dx = b.x - a.x, dy = b.y - a.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 1;
      const spring = (dist - 260) * 0.0035;
      const fx = spring * (dx / dist), fy = spring * (dy / dist);
      f[b.name].fx -= fx; f[b.name].fy -= fy;
      f[a.name].fx += fx; f[a.name].fy += fy;
    });

    // Gravity toward canvas center
    nodes.forEach(n => {
      f[n.name].fx += (500 - n.x) * 0.011;
      f[n.name].fy += (310 - n.y) * 0.011;
    });

    // Apply with clamping
    nodes.forEach(n => {
      n.x = Math.max(NODE_W / 2 + 24, Math.min(1000 - NODE_W / 2 - 24, n.x + f[n.name].fx));
      n.y = Math.max(NODE_H / 2 + 24, Math.min(620  - NODE_H / 2 - 24, n.y + f[n.name].fy));
    });
  }
}

interface GraphCanvasProps {
  catalogData: { classes: WorkbenchClass[]; edges: MappedEdge[]; classByName: Record<string, WorkbenchClass> };
  highlight?: { classes: Record<string, boolean>; fields: Record<string, boolean>; edges: Record<string, boolean> };
  noDim?: boolean;
  buildSelection?: { classes: Record<string, boolean>; joins: MappedEdge[] };
  mode?: 'explore' | 'build';
  selectedId?: string | null;
  onNodeClick?: (className: string) => void;
  onNodeAdd?: (className: string) => void;
  onEdgeClick?: (edge: MappedEdge) => void;
  onLassoSelect?: (classNames: string[], edges: MappedEdge[]) => void;
}

export function GraphCanvas(props: GraphCanvasProps) {
  const { catalogData: { classes, edges } } = props;
  const hl = props.highlight || { classes: {}, fields: {}, edges: {} };
  const noDim = props.noDim;
  const anyHl = Object.keys(hl.classes).length > 0;
  const build = props.buildSelection || { classes: {}, joins: [] };
  const mode = props.mode || 'explore';

  // ── Canvas transform ──────────────────────────────────────────────────────
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  // Mirrored into refs (rather than read from `pan`/`zoom` directly) so the
  // wheel/drag handlers below — registered once via a stable useEffect — read
  // the current value without needing to be re-attached on every pan/zoom.
  const panRef = useRef(pan);
  const zoomRef = useRef(zoom);
  useLayoutEffect(() => { panRef.current = pan; }, [pan]);
  useLayoutEffect(() => { zoomRef.current = zoom; }, [zoom]);

  // ── Node positions (mutable, persisted per catalog) ───────────────────────
  const classNamesKey = useMemo(() => classes.map(c => c.name).sort().join('\0'), [classes]);
  const posKey = useMemo(() => simpleHash(classNamesKey), [classNamesKey]);

  const [positions, setPositions] = useState<Record<string, Position>>({});
  const positionsRef = useRef(positions);
  useLayoutEffect(() => { positionsRef.current = positions; }, [positions]);

  useEffect(() => {
    if (classes.length === 0) return;
    const saved = loadPositions(posKey);
    const allSaved = saved && classes.every(c => saved[c.name]);

    const nodes = classes.map((c, i) => {
      const base = (allSaved ? saved![c.name] : null)
        ?? PRESET_LAYOUTS[c.name]
        ?? circleLayout(i, classes.length);
      return { name: c.name, x: base.x, y: base.y };
    });

    if (!allSaved && classes.length > 1) {
      runForceLayout(nodes, edges, 100);
    }

    const next: Record<string, Position> = {};
    nodes.forEach(n => { next[n.name] = { x: n.x, y: n.y }; });
    // Derived from localStorage + a force-layout run, not from props/state — there is
    // no render-time equivalent to compute this from, so an effect is the right tool.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPositions(next);
  }, [posKey]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Drag refs ─────────────────────────────────────────────────────────────
  const svgRef = useRef<SVGSVGElement>(null);
  const drag = useRef<{ sx: number; sy: number; px: number; py: number } | null>(null);
  const nodeDrag = useRef<{ name: string; startX: number; startY: number; origX: number; origY: number } | null>(null);

  // ── Lasso state ───────────────────────────────────────────────────────────
  const [lassoMode, setLassoMode] = useState(false);
  const [lassoRect, setLassoRect] = useState<LassoRect | null>(null);
  const lassoModeRef = useRef(false);
  useLayoutEffect(() => { lassoModeRef.current = lassoMode; }, [lassoMode]);
  const lassoStart = useRef<Position | null>(null);

  const [hover, setHover] = useState<string | null>(null);

  // ── Non-passive wheel zoom (disabled during lasso/node-drag) ─────────────
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const handler = (e: WheelEvent) => {
      if (lassoModeRef.current || nodeDrag.current) return;
      e.preventDefault();
      const factor = e.ctrlKey ? 0.01 : 0.003;
      const dz = -e.deltaY * factor;
      const prevZoom = zoomRef.current;
      const newZoom = Math.max(0.25, Math.min(4, prevZoom + dz));
      if (newZoom === prevZoom) return;
      const rect = svg.getBoundingClientRect();
      const svgX = (e.clientX - rect.left) / rect.width * 1000;
      const svgY = (e.clientY - rect.top) / rect.height * 620;
      const ratio = newZoom / prevZoom;
      const { x: px, y: py } = panRef.current;
      setZoom(newZoom);
      setPan({ x: svgX - (svgX - px) * ratio, y: svgY - (svgY - py) * ratio });
    };
    svg.addEventListener('wheel', handler, { passive: false });
    return () => svg.removeEventListener('wheel', handler);
  }, []);

  // ── Coordinate helpers ────────────────────────────────────────────────────
  function toModelCoords(clientX: number, clientY: number): Position {
    const svg = svgRef.current;
    if (!svg) return { x: 0, y: 0 };
    const rect = svg.getBoundingClientRect();
    const svgX = (clientX - rect.left) / rect.width * 1000;
    const svgY = (clientY - rect.top) / rect.height * 620;
    return { x: (svgX - panRef.current.x) / zoomRef.current, y: (svgY - panRef.current.y) / zoomRef.current };
  }

  // ── Zoom to fit all nodes ─────────────────────────────────────────────────
  function zoomToFit() {
    if (classes.length === 0) return;
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    classes.forEach(c => {
      const p = positions[c.name]; if (!p) return;
      minX = Math.min(minX, p.x - NODE_W / 2); minY = Math.min(minY, p.y - NODE_H / 2);
      maxX = Math.max(maxX, p.x + NODE_W / 2); maxY = Math.max(maxY, p.y + NODE_H / 2);
    });
    if (!isFinite(minX)) return;
    const pad = 48;
    const newZoom = Math.max(0.25, Math.min(2,
      Math.min(1000 / (maxX - minX + pad * 2), 620 / (maxY - minY + pad * 2))));
    const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
    setZoom(newZoom);
    setPan({ x: 500 - cx * newZoom, y: 310 - cy * newZoom });
  }

  // ── SVG event handlers ────────────────────────────────────────────────────
  function onBgDown(e: React.MouseEvent<SVGSVGElement>) {
    if (lassoMode) {
      const pt = toModelCoords(e.clientX, e.clientY);
      lassoStart.current = pt;
      setLassoRect({ x1: pt.x, y1: pt.y, x2: pt.x, y2: pt.y });
      return;
    }
    if (nodeDrag.current) return; // node drag in progress
    drag.current = { sx: e.clientX, sy: e.clientY, px: pan.x, py: pan.y };
    if (svgRef.current) svgRef.current.style.cursor = 'grabbing';
  }

  function onMove(e: React.MouseEvent<SVGSVGElement>) {
    if (lassoMode && lassoStart.current) {
      const pt = toModelCoords(e.clientX, e.clientY);
      setLassoRect({ x1: lassoStart.current.x, y1: lassoStart.current.y, x2: pt.x, y2: pt.y });
      return;
    }
    if (nodeDrag.current) {
      const d = nodeDrag.current;
      const pt = toModelCoords(e.clientX, e.clientY);
      setPositions(prev => ({ ...prev, [d.name]: { x: d.origX + pt.x - d.startX, y: d.origY + pt.y - d.startY } }));
      return;
    }
    if (!drag.current) return;
    setPan({ x: drag.current.px + (e.clientX - drag.current.sx), y: drag.current.py + (e.clientY - drag.current.sy) });
  }

  function onUp(e: React.MouseEvent<SVGSVGElement>) {
    if (lassoMode && lassoStart.current) {
      const pt = toModelCoords(e.clientX, e.clientY);
      finalizeLasso({ x1: lassoStart.current.x, y1: lassoStart.current.y, x2: pt.x, y2: pt.y });
      return;
    }
    if (nodeDrag.current) {
      const d = nodeDrag.current;
      const pt = toModelCoords(e.clientX, e.clientY);
      const finalPos = { x: d.origX + pt.x - d.startX, y: d.origY + pt.y - d.startY };
      const finalPositions = { ...positionsRef.current, [d.name]: finalPos };
      setPositions(finalPositions);
      savePositions(posKey, finalPositions);
      nodeDrag.current = null;
      if (svgRef.current) svgRef.current.style.cursor = lassoMode ? 'crosshair' : 'grab';
      return;
    }
    drag.current = null;
    if (svgRef.current) svgRef.current.style.cursor = lassoMode ? 'crosshair' : 'grab';
  }

  function finalizeLasso(r: LassoRect) {
    const minX = Math.min(r.x1, r.x2), maxX = Math.max(r.x1, r.x2);
    const minY = Math.min(r.y1, r.y2), maxY = Math.max(r.y1, r.y2);
    if ((maxX - minX) * (maxY - minY) > 100) {
      const selected: string[] = [];
      classes.forEach(n => {
        const p = positions[n.name]; if (!p) return;
        if (p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY) selected.push(n.name);
      });
      if (selected.length > 0) {
        const sel = new Set(selected);
        props.onLassoSelect?.(selected, edges.filter(e => sel.has(e.from) && sel.has(e.to)));
      }
    }
    lassoStart.current = null;
    setLassoRect(null);
    setLassoMode(false);
    if (svgRef.current) svgRef.current.style.cursor = 'grab';
  }

  function toggleLasso() {
    const next = !lassoMode;
    setLassoMode(next);
    setLassoRect(null);
    lassoStart.current = null;
    if (svgRef.current) svgRef.current.style.cursor = next ? 'crosshair' : 'grab';
  }

  // ── Derived ───────────────────────────────────────────────────────────────
  const joinKeys: Record<string, boolean> = {};
  build.joins.forEach(j => { if (j._key) joinKeys[j._key] = true; });

  const cursor = lassoMode ? 'crosshair' : 'grab';

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="graph-wrap">
      <div className="graph-tools">
        <div className="seg seg-zoom">
          {[1, 1.25, 1.5].map(z => (
            <button key={z} className={`seg-btn${zoom === z ? ' is-on' : ''}`} onClick={() => setZoom(z)}>
              {z}×
            </button>
          ))}
        </div>
        <button className="icon-btn" title="Zoom to fit" onClick={zoomToFit}>⊡</button>
        <button className="icon-btn" title="Reset view" onClick={() => { setZoom(1); setPan({ x: 0, y: 0 }); }}>↻</button>
        <button
          className={`icon-btn${lassoMode ? ' is-on' : ''}`}
          title={lassoMode ? 'Cancel selection' : 'Draw area to select nodes'}
          onClick={toggleLasso}
        >◫</button>
      </div>

      <svg
        ref={svgRef}
        className="graph-svg" viewBox="0 0 1000 620" preserveAspectRatio="xMidYMid meet"
        style={{ cursor }}
        onMouseDown={onBgDown} onMouseMove={onMove} onMouseUp={onUp} onMouseLeave={onUp}
      >
        <defs>
          <marker id="arr" viewBox="0 0 10 10" refX={8} refY={5} markerWidth={7} markerHeight={7} orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" style={{ fill: 'var(--muted-foreground)' }} />
          </marker>
          <marker id="arrA" viewBox="0 0 10 10" refX={8} refY={5} markerWidth={7} markerHeight={7} orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill="#E8B339" />
          </marker>
        </defs>
        <rect x={-2000} y={-2000} width={5000} height={5000} fill="transparent" style={{ pointerEvents: 'all' }} />
        <g
          transform={`translate(${pan.x},${pan.y}) scale(${zoom})`}
          // Read directly rather than mirrored into state: promoting "is dragging" to state
          // would re-render on every drag start/end, which is exactly what these two refs
          // exist to avoid. Transition timing is cosmetic, not correctness-affecting.
          // eslint-disable-next-line react-hooks/refs
          style={{ transition: (drag.current || nodeDrag.current) ? 'none' : 'transform .18s ease' }}
        >
          {/* edges */}
          {edges.map((e, i) => {
            const key = e._key || edgeKey(e, i);
            const aPos = positions[e.from], bPos = positions[e.to];
            if (!aPos || !bPos) return null;
            const p1 = boundary(bPos, aPos, 4, 4);
            const p2 = boundary(aPos, bPos, 6, 6);
            const active = hl.edges[key], inJoin = joinKeys[key];
            const dim = anyHl && !active && !noDim;
            const mx = (p1.x + p2.x) / 2, my = (p1.y + p2.y) / 2;
            const edgeStroke = (active || inJoin) ? '#E8B339' : 'var(--input)';
            return (
              <g key={key} opacity={dim ? 0.28 : 1} style={{ transition: 'opacity .2s' }}>
                <path d={`M${p1.x},${p1.y} L${p2.x},${p2.y}`} fill="none"
                  style={{ stroke: edgeStroke }} strokeWidth={(active || inJoin) ? 1.8 : 1.2}
                  strokeDasharray={(active || inJoin) ? 'none' : '4 3'}
                  markerEnd={(active || inJoin) ? 'url(#arrA)' : 'url(#arr)'} />
                {mode === 'build' && !lassoMode && (
                  <path d={`M${p1.x},${p1.y} L${p2.x},${p2.y}`} fill="none" stroke="transparent" strokeWidth={16}
                    style={{ cursor: 'pointer' }}
                    onMouseDown={ev => ev.stopPropagation()}
                    onClick={ev => { ev.stopPropagation(); props.onEdgeClick?.(e); }} />
                )}
                <g transform={`translate(${mx - 30},${my - 9})`} style={{ pointerEvents: 'none' }}>
                  <rect width={60} height={16} rx={3} style={{ fill: 'var(--card)', stroke: (active || inJoin) ? '#E8B339' : 'var(--border)' }} />
                  <text x={30} y={11} textAnchor="middle" fontFamily="JetBrains Mono, monospace" fontSize={9}
                    style={{ fill: (active || inJoin) ? '#E8B339' : 'var(--muted-foreground)' }}>{e.name}</text>
                </g>
              </g>
            );
          })}

          {/* nodes */}
          {classes.map(n => {
            const pos = positions[n.name]; if (!pos) return null;
            const sel = props.selectedId === n.name;
            const hi = hl.classes[n.name], inBuild = build.classes[n.name], isHover = hover === n.name;
            const dim = anyHl && !hi && !sel && !noDim;
            const ring = sel ? '#8B5CF6' : inBuild ? '#E8B339' : hi ? '#3DCEA0' : 'var(--border)';
            const ringW = (sel || inBuild) ? 2 : hi ? 1.6 : 1;
            return (
              <g key={n.name}
                transform={`translate(${pos.x - NODE_W / 2},${pos.y - NODE_H / 2})`}
                opacity={dim ? 0.32 : 1}
                style={{ cursor: lassoMode ? 'crosshair' : 'grab', transition: 'opacity .2s' }}
                onMouseDown={e => {
                  if (lassoMode) return;
                  // Start node drag — don't stop propagation so onBgDown can see nodeDrag.current
                  const pt = toModelCoords(e.clientX, e.clientY);
                  nodeDrag.current = { name: n.name, startX: pt.x, startY: pt.y, origX: pos.x, origY: pos.y };
                  if (svgRef.current) svgRef.current.style.cursor = 'move';
                }}
                onMouseEnter={() => setHover(n.name)}
                onMouseLeave={() => setHover(null)}
                onClick={ev => { if (!lassoMode) { ev.stopPropagation(); props.onNodeClick?.(n.name); } }}
                onDoubleClick={ev => { if (!lassoMode) { ev.stopPropagation(); props.onNodeAdd?.(n.name); } }}
              >
                {(hi || sel) && <rect x={-3} y={-3} width={NODE_W + 6} height={NODE_H + 6} rx={9} fill="none" style={{ stroke: ring }} strokeWidth={1} opacity={0.25} />}
                <rect width={NODE_W} height={NODE_H} rx={7} style={{ fill: 'var(--card)', stroke: ring }} strokeWidth={ringW} />
                <rect x={0} y={0} width={4} height={NODE_H} rx={2} fill={TYPE_COLOR.integrated} />
                <text x={14} y={21} fontFamily="Inter, sans-serif" fontSize={13} fontWeight={600} style={{ fill: 'var(--foreground)' }}>{n.name}</text>
                <text x={14} y={37} fontFamily="JetBrains Mono, monospace" fontSize={8.5} style={{ fill: 'var(--muted-foreground)' }} letterSpacing="0.04em">INTEGRATED</text>
                <text x={14} y={50} fontFamily="JetBrains Mono, monospace" fontSize={9} style={{ fill: 'var(--muted-foreground)' }}>
                  {n.fields?.length || 0} props{fmtCount(n.rowCount ?? 0) ? ` · ${fmtCount(n.rowCount ?? 0)} rows` : ''}
                </text>
                {(mode === 'build' || isHover) && !lassoMode && (
                  <g transform={`translate(${NODE_W - 20}, 8)`}
                    onClick={ev => { ev.stopPropagation(); props.onNodeAdd?.(n.name); }}
                    style={{ cursor: 'pointer' }}>
                    <rect x={-2} y={-2} width={16} height={16} rx={4}
                      style={{ fill: inBuild ? '#E8B339' : 'var(--muted)', stroke: inBuild ? '#E8B339' : 'var(--input)' }} />
                    <text x={6} y={9} textAnchor="middle" fontFamily="JetBrains Mono, monospace" fontSize={11} fontWeight="bold"
                      style={{ fill: inBuild ? '#0C0C10' : 'var(--muted-foreground)' }}>{inBuild ? '✓' : '+'}</text>
                  </g>
                )}
              </g>
            );
          })}

          {/* lasso rectangle */}
          {lassoRect && (() => {
            const x = Math.min(lassoRect.x1, lassoRect.x2), y = Math.min(lassoRect.y1, lassoRect.y2);
            const w = Math.abs(lassoRect.x2 - lassoRect.x1), h = Math.abs(lassoRect.y2 - lassoRect.y1);
            return (
              <rect x={x} y={y} width={w} height={h} rx={3 / zoom}
                fill="rgba(93,168,248,0.08)" stroke="#5DA8F8"
                strokeWidth={1.5 / zoom} strokeDasharray={`${4 / zoom} ${3 / zoom}`}
                style={{ pointerEvents: 'none' }} />
            );
          })()}
        </g>
      </svg>

      <div className="graph-legend">
        <span className="lg"><span className="lg-dot" style={{ background: '#3DCEA0' }} />in query</span>
        <span className="lg"><span className="lg-dot" style={{ background: '#8B5CF6' }} />inspecting</span>
        <span className="lg"><span className="lg-dot" style={{ background: '#E8B339' }} />join / picked</span>
        {lassoMode && <span className="lg" style={{ color: '#5DA8F8' }}>◫ drag to select · click ◫ to cancel</span>}
      </div>
    </div>
  );
}

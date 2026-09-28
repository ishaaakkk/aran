import { useMemo, useState } from 'react';
import type { QueryRunResult } from '../../services/types';
import {
  buildSeries, classifyColumns, formatValue,
  labelledIndices, omittedCount, stripCommonPrefix, truncateLabel, valueScale,
  type ChartKind, type ChartPoint, type ChartSpec,
} from '@/lib/chartModel';
import { recommend, type Candidate } from '@/lib/chartRecommender';

/** Plot geometry. The container is sized to include the x-axis band, not just the plot. */
const WIDTH = 720;
const PLOT_HEIGHT = 240;
const PAD = { top: 18, right: 16, bottom: 46, left: 52 };
const HEIGHT = PLOT_HEIGHT + PAD.top + PAD.bottom;

/**
 * A query result as a chart.
 *
 * <p>Inline SVG rather than a charting library: the marks here are simple, and a dependency would
 * buy nothing while taking control of exactly the details that matter — mark thickness, the gap
 * between bars, which points get labelled. `GraphCanvas` and `SchemaGraphCanvas` already draw
 * their own SVG in this codebase.
 *
 * <p>Single-series by design. The dominant OQL shape is one group key and one aggregate, and a
 * single measure means one hue, no legend to read, and room to label points directly. A second
 * measure is a second chart; the y-column picker switches between them.
 *
 * <p>Colours come from CSS custom properties defined in workbench.css so the light and dark steps
 * live in one place. They are the validated categorical slot 1 — checked against Aran's own
 * surfaces (`#FFFFFF` light, `#13131A` dark) rather than assumed. Three light-mode slots sit
 * below 3:1 contrast, which obliges "relief": visible labels or a table view. Both are present —
 * direct labels here, and the result tab beside it is the same data as a table.
 */
export function ResultChart({ result }: { result: QueryRunResult }) {
  const kinds = useMemo(() => classifyColumns(result), [result]);
  const recommendation = useMemo(() => recommend(result), [result]);

  // User overrides, reset when the result identity changes — adjusted during render, the
  // documented alternative to an effect, as ResultTable does for its sort.
  const [override, setOverride] = useState<Partial<ChartSpec>>({});
  const [prevResult, setPrevResult] = useState(result);
  const [hover, setHover] = useState<number | null>(null);
  const [showWhy, setShowWhy] = useState(false);
  if (result !== prevResult) {
    setPrevResult(result);
    setOverride({});
    setHover(null);
  }

  const best: Candidate | undefined = recommendation.candidates[0];
  if (!best) {
    return (
      <div className="chart-empty" data-testid="oql-chart-empty">
        <span className="result-error-icon">▤</span>
        <div className="chart-empty-body">
          <div>{recommendation.advice ?? 'No chart expresses this result honestly.'}</div>
          {recommendation.rejected.map(r => (
            <div key={r.kind} className="chart-why-row">
              <b>{r.kind}</b> — {r.reason}
            </div>
          ))}
        </div>
      </div>
    );
  }

  const spec: ChartSpec = {
    kind: best.kind, labelColumn: best.labelColumn, valueColumn: best.valueColumn, ...override,
  };
  const points = buildSeries(result, spec);
  const omitted = omittedCount(result, spec);
  const scale = valueScale(points);
  const labelled = labelledIndices(points);

  const numericColumns = kinds.flatMap((k, i) => (k === 'number' ? [i] : []));
  const labelCandidates = kinds.flatMap((k, i) => (k !== 'empty' ? [i] : []));

  return (
    <div className="chart-wrap" data-testid="oql-chart">
      <div className="chart-controls">
        <label>
          <span>chart</span>
          <select value={spec.kind} data-testid="chart-kind"
            onChange={e => setOverride(o => ({ ...o, kind: e.target.value as ChartKind }))}>
            <option value="bar">bar</option>
            <option value="line">line</option>
          </select>
        </label>
        <label>
          <span>x</span>
          <select value={spec.labelColumn} data-testid="chart-x"
            onChange={e => setOverride(o => ({ ...o, labelColumn: Number(e.target.value) }))}>
            {labelCandidates.map(i => (
              <option key={i} value={i}>{result.headerNames[i]}</option>
            ))}
          </select>
        </label>
        <label>
          <span>y</span>
          <select value={spec.valueColumn} data-testid="chart-y"
            onChange={e => setOverride(o => ({ ...o, valueColumn: Number(e.target.value) }))}>
            {numericColumns.map(i => (
              <option key={i} value={i}>{result.headerNames[i]}</option>
            ))}
          </select>
        </label>
        <button type="button" className="chart-why-btn" data-testid="chart-why"
          onClick={() => setShowWhy(w => !w)}>
          {showWhy ? 'hide why' : 'why this chart?'}
        </button>
        {omitted > 0 && (
          <span className="chart-note">
            first {points.length} of {points.length + omitted} — the rest are in the table
          </span>
        )}
      </div>

      {/*
        The recommendation explains itself on demand. A chooser that cannot say why it picked
        something is worse than a plain default: the user already knows what they asked for, and
        an unexplained switch just looks like the tool second-guessing them.
      */}
      {showWhy && (
        <div className="chart-why" data-testid="chart-why-panel">
          {recommendation.candidates.map((c, i) => (
            <div key={c.kind} className="chart-why-row">
              <b>{i === 0 ? '✓' : '·'} {c.kind}</b> — {c.rationale}
            </div>
          ))}
          {recommendation.rejected.map(r => (
            <div key={r.kind} className="chart-why-row is-out">
              <b>✕ {r.kind}</b> — {r.reason}
            </div>
          ))}
        </div>
      )}

      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="chart-svg"
        role="img" aria-label={`${spec.kind} chart of ${result.headerNames[spec.valueColumn]} by ${result.headerNames[spec.labelColumn]}`}>
        <Grid scale={scale} />
        {spec.kind === 'bar'
          ? <Bars points={points} scale={scale} labelled={labelled} hover={hover} onHover={setHover} />
          : <Line points={points} scale={scale} labelled={labelled} hover={hover} onHover={setHover} />}
        <XAxis points={points} />
        {hover !== null && points[hover] && (
          <Tooltip point={points[hover]} index={hover} count={points.length} scale={scale} />
        )}
      </svg>
    </div>
  );
}

type Scale = ReturnType<typeof valueScale>;

const plotWidth = WIDTH - PAD.left - PAD.right;
const yOf = (value: number, scale: Scale) =>
  PAD.top + PLOT_HEIGHT - ((value - scale.min) / (scale.max - scale.min)) * PLOT_HEIGHT;
const bandWidth = (count: number) => plotWidth / Math.max(1, count);
const centreOf = (i: number, count: number) => PAD.left + bandWidth(count) * (i + 0.5);

/** Solid hairlines one shade off the surface — never dashed, which reads as "threshold". */
function Grid({ scale }: { scale: Scale }) {
  return (
    <g className="chart-grid">
      {scale.ticks.map(tick => {
        const y = yOf(tick, scale);
        return (
          <g key={tick}>
            <line x1={PAD.left} x2={WIDTH - PAD.right} y1={y} y2={y} />
            <text x={PAD.left - 8} y={y + 3} className="chart-tick" textAnchor="end">
              {formatValue(tick)}
            </text>
          </g>
        );
      })}
    </g>
  );
}

interface MarkProps {
  points: ChartPoint[];
  scale: Scale;
  labelled: Set<number>;
  hover: number | null;
  onHover: (i: number | null) => void;
}

function Bars({ points, scale, labelled, hover, onHover }: MarkProps) {
  const band = bandWidth(points.length);
  // A 2px surface gap between adjacent bars, not a border drawn around them.
  const width = Math.max(2, Math.min(band - 2, 46));
  const zeroY = yOf(0, scale);

  return (
    <g>
      {points.map((p, i) => {
        const y = yOf(p.value, scale);
        const top = Math.min(y, zeroY);
        const height = Math.max(1, Math.abs(zeroY - y));
        const x = centreOf(i, points.length) - width / 2;
        return (
          <g key={i} onMouseEnter={() => onHover(i)} onMouseLeave={() => onHover(null)}>
            {/* Hit target spans the whole band, so hovering never demands precision. */}
            <rect x={PAD.left + band * i} y={PAD.top} width={band} height={PLOT_HEIGHT}
              fill="transparent" />
            {/* 4px rounded data-end; the baseline end stays square because it is anchored. */}
            <rect x={x} y={top} width={width} height={height} rx={4} ry={4}
              className={`chart-bar${hover === i ? ' is-hover' : ''}`} />
            {labelled.has(i) && height > 14 && (
              // Above the bar for a positive value, below it for a negative one. Always placing
              // it above `top` would put a negative bar's label on the wrong side of the zero
              // line, where it reads as belonging to the axis rather than to the mark.
              <text x={x + width / 2} y={p.value < 0 ? top + height + 12 : top - 5}
                className="chart-value" textAnchor="middle">
                {formatValue(p.value)}
              </text>
            )}
          </g>
        );
      })}
    </g>
  );
}

function Line({ points, scale, labelled, hover, onHover }: MarkProps) {
  const band = bandWidth(points.length);
  const path = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'} ${centreOf(i, points.length)} ${yOf(p.value, scale)}`)
    .join(' ');

  return (
    <g>
      <path d={path} className="chart-line" fill="none" />
      {points.map((p, i) => {
        const cx = centreOf(i, points.length);
        const cy = yOf(p.value, scale);
        return (
          <g key={i} onMouseEnter={() => onHover(i)} onMouseLeave={() => onHover(null)}>
            <rect x={PAD.left + band * i} y={PAD.top} width={band} height={PLOT_HEIGHT}
              fill="transparent" />
            {/* 2px surface ring, so overlapping markers stay countable. */}
            <circle cx={cx} cy={cy} r={hover === i ? 5.5 : 4}
              className={`chart-dot${hover === i ? ' is-hover' : ''}`} />
            {labelled.has(i) && (
              <text x={cx} y={cy - 10} className="chart-value" textAnchor="middle">
                {formatValue(p.value)}
              </text>
            )}
          </g>
        );
      })}
    </g>
  );
}

/**
 * Category labels, thinned to whatever fits.
 *
 * Rotation is deliberately avoided: angled text is harder to read than fewer labels, and the
 * tooltip names every point on hover regardless.
 */
function XAxis({ points }: { points: ChartPoint[] }) {
  const band = bandWidth(points.length);
  const every = Math.ceil(points.length / Math.max(1, Math.floor(plotWidth / 64)));
  const maxChars = Math.max(3, Math.floor((band * every) / 6.5));
  const labels = stripCommonPrefix(points.map(p => p.label));

  return (
    <g>
      <line x1={PAD.left} x2={WIDTH - PAD.right} y1={PAD.top + PLOT_HEIGHT} y2={PAD.top + PLOT_HEIGHT}
        className="chart-axis" />
      {points.map((_, i) => (i % every === 0 ? (
        <text key={i} x={centreOf(i, points.length)} y={PAD.top + PLOT_HEIGHT + 16}
          className="chart-tick" textAnchor="middle">
          {truncateLabel(labels[i], maxChars)}
        </text>
      ) : null))}
    </g>
  );
}

/** Names the point and its value — a value is never only reachable through this. */
function Tooltip({ point, index, count, scale }: {
  point: ChartPoint; index: number; count: number; scale: Scale;
}) {
  const cx = centreOf(index, count);
  const label = truncateLabel(point.label, 28);
  const text = `${label}  ${formatValue(point.value)}`;
  const width = text.length * 6.2 + 16;
  // Flip to the left near the right edge so the box never leaves the plot.
  const x = Math.min(Math.max(PAD.left, cx - width / 2), WIDTH - PAD.right - width);
  const y = Math.max(PAD.top, yOf(point.value, scale) - 34);

  return (
    <g className="chart-tip" pointerEvents="none">
      <rect x={x} y={y} width={width} height={22} rx={3} />
      <text x={x + 8} y={y + 15}>{text}</text>
    </g>
  );
}

export default ResultChart;

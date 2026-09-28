import React, { useState, useMemo, useRef, useEffect } from 'react';
import { QueryRunResult, OqlRewriteResult, PlanningReport } from '../../services/types';
import { PlanTree } from './PlanTree';
import { ResultTable } from '../ResultTable';
import { pageStateOf, showPager, prevOffset, nextOffset, pageRangeLabel } from '@/lib/resultPaging';
import { resultState, type ExportFormat } from '@/lib/workbenchRun';
import { runTiming } from '@/lib/runTiming';
import { ResultChart } from './ResultChart';
import { SimulationBadge } from '../SimulationBadge';
import { downloadResultCsv } from '@/lib/resultCsv';
import { MappedEdge, Predicate, OrderClause, WorkbenchClass, HavingClause, AggFn, AGG_FNS, opsForType, valueHints, edgeKey, filterGroups, type OrderOption, type HavingOption } from '../../lib/workbenchModel';

function fmtCount(n: number): string {
  if (n <= 0) return '';
  if (n < 1_000) return String(n);
  if (n < 1_000_000) return `${+(n / 1000).toFixed(n < 10_000 ? 1 : 0)}k`;
  return `${+(n / 1_000_000).toFixed(1)}M`;
}

/**
 * CSV of the rows currently on screen — one page, not the query's result.
 *
 * Named for what it does: this button existed before paging and silently produced a
 * 1,000-row file for a 50,000-row query, which is the same dead end the "increase the
 * LIMIT" message was. `onExportAll` is the uncapped path.
 */
function downloadVisiblePageCsv(r: QueryRunResult) {
  // The shared writer (lib/resultCsv): this copy called .includes on every cell and threw on
  // the numbers the engine sends for a numeric column.
  downloadResultCsv(r, 'aran-query-page.csv');
}

function needsValue(op: string) {
  return op !== 'IS NULL' && op !== 'IS NOT NULL';
}

export function CmdHead(props: { cmd: string; flat?: boolean; right?: React.ReactNode }) {
  return (
    <div className={`cmdhead${props.flat ? ' flat' : ''}`}>
      <span className="cmdhead-cmd"><span className="prompt-sign">$</span> {props.cmd}</span>
      {props.right && <div className="cmdhead-right">{props.right}</div>}
    </div>
  );
}

// ── Catalog Browser ────────────────────────────────────────────────────────
interface CatalogBrowserProps {
  catalogData: { classes: WorkbenchClass[]; edges: MappedEdge[]; classByName: Record<string, WorkbenchClass> };
  highlight?: { classes: Record<string, boolean>; edges: Record<string, boolean> };
  buildSelection?: { classes: Record<string, boolean> };
  selectedId?: string | null;
  onSelect: (className: string) => void;
  onAddClass: (className: string) => void;
  /**
   * The sources-tier tables, shown only in SQL mode — the authoring names a statement types
   * (`SELECT title FROM acm`), which are neither the OQL class names above nor view internals.
   */
  sourceTables?: { name: string; datasetId: string; columns: string[] }[];
}

export function CatalogBrowser(props: CatalogBrowserProps) {
  const [q, setQ] = useState('');
  const hl = props.highlight || { classes: {}, edges: {} };
  const build = props.buildSelection || { classes: {} };
  const { classes, edges } = props.catalogData;

  const filteredClasses = useMemo(() => {
    return classes.filter(c => c.name.toLowerCase().includes(q.toLowerCase()));
  }, [classes, q]);

  return (
    <aside className="panel catalog">
      <CmdHead cmd="catalog" right={<span>{classes.length} cls · {edges.length} rel</span>} />
      <div className="catalog-search">
        <span className="search-i">⌕</span>
        <input value={q} onChange={e => setQ(e.target.value)} placeholder="filter classes…" />
      </div>
      <div className="catalog-scroll">
        <div className="cat-sec">
          <div className="cat-sec-h">▣ integrated classes</div>
          {filteredClasses.map(c => {
            const sel = props.selectedId === c.name;
            const inq = hl.classes[c.name];
            const inb = build.classes[c.name];
            return (
              <button
                key={c.name}
                className={`cat-item${sel ? ' is-sel' : ''}${inq ? ' is-inq' : ''}`}
                data-testid={`catalog-class-${c.name}`}
                onClick={() => props.onSelect(c.name)}
                onDoubleClick={() => props.onAddClass(c.name)}
              >
                <span className="cat-chev">{sel ? '▾' : '›'}</span>
                <span className="ochip ochip-int">{c.name}</span>
                {inb && <span className="cat-badge">in query</span>}
                {fmtCount(c.rowCount ?? 0) && <span className="cat-rows">{fmtCount(c.rowCount ?? 0)}</span>}
                <span className="cat-count">{c.sources?.length ?? 0} src</span>
              </button>
            );
          })}
        </div>
        {props.sourceTables && props.sourceTables.length > 0 && (
          <div className="cat-sec">
            <div className="cat-sec-h">▤ source tables</div>
            {props.sourceTables.map(t => (
              <div key={t.name} className="cat-rel" data-testid={`catalog-source-${t.name}`}>
                <span className="ochip ochip-src">{t.name}</span>
                <span className="cat-rel-path">{t.columns.join(', ')}</span>
              </div>
            ))}
          </div>
        )}
        <div className="cat-sec">
          <div className="cat-sec-h">⎨ join / object properties</div>
          {edges.map((e, i) => {
            const key = e._key || edgeKey(e, i);
            const inq = hl.edges[key];
            return (
              <div
                key={key}
                data-testid={`catalog-rel-${e.name}`}
                data-from={e.from}
                data-to={e.to}
                className={`cat-rel${inq ? ' is-inq' : ''}`}
              >
                <span className="ochip ochip-obj">{e.name}</span>
                <span className="cat-rel-path">{e.from} → {e.to}</span>
              </div>
            );
          })}
        </div>
      </div>
    </aside>
  );
}

// ── Inspector ──────────────────────────────────────────────────────────────
interface InspectorProps {
  focus?: WorkbenchClass | null;
  mode: 'explore' | 'build';
  buildSelection?: { classes: Record<string, boolean>; fields: Record<string, boolean>; joins: MappedEdge[] };
  catalogEdges: MappedEdge[];
  onToggleField: (fieldKey: string) => void;
  onFilterField?: (fieldKey: string) => void;
  onAddJoin: (edge: MappedEdge) => void;
}

export function Inspector(props: InspectorProps) {
  const c = props.focus;
  const build = props.buildSelection || { classes: {}, fields: {}, joins: [] };
  
  if (!c) {
    return (
      <aside className="panel inspector">
        <CmdHead cmd="inspect" />
        <div className="inspector-empty">
          <div className="ie-glyph">▤</div>
          <div>select a node to inspect its</div>
          <div>properties and relations</div>
          <div className="ie-sub">double-click a node to add it to the query</div>
        </div>
      </aside>
    );
  }

  const joinKeys: Record<string, boolean> = {};
  build.joins.forEach(j => { if (j._key) joinKeys[j._key] = true; });

  const rels = props.catalogEdges.filter(e => e.from === c.name || e.to === c.name);

  return (
    <aside className="panel inspector">
      <CmdHead cmd={`inspect ${c.name}`} right={<span className="ochip ochip-int sm">integrated</span>} />
      <div className="inspector-scroll">
        <div className="insp-grp">
          <div className="insp-label">sources</div>
          {c.sources.map(s => (
            <div key={s.datasetId} className="insp-src"><span className="insp-db">▤</span>{s.datasetId}</div>
          ))}
        </div>
        {c.enforcedConstraintCount > 0 && (
          <div className="insp-grp">
            <div className="insp-label">⛨ constraints</div>
            <div className="insp-src" title="Rows failing an enforced denial constraint are filtered out and land in the quarantine view">
              <span className="insp-fi" style={{ color: '#E8B339' }}>▪</span>
              {c.enforcedConstraintCount} enforced · quarantine: {c.intView.replace(/^int_/, 'q_')}
            </div>
          </div>
        )}
        <div className="insp-grp">
          <div className="insp-label insp-label-row">
            <span>▤ properties</span>
            <span className="insp-dim">{props.mode === 'build' ? 'click to add to SELECT' : c.fields.length}</span>
          </div>
          <div className="insp-fields">
            {c.fields.map(f => {
              const fk = `${c.name}.${f.name}`;
              const picked = build.fields[fk];
              const clickable = props.mode === 'build';
              return (
                <div key={f.name} className={`insp-field${clickable ? ' clickable' : ''}${picked ? ' picked' : ''}`}>
                  <span className="insp-field-main" onClick={clickable ? () => props.onToggleField(fk) : undefined} role="button" tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { if (clickable) props.onToggleField(fk); } }}>
                    {clickable 
                      ? <span className="insp-check">{picked ? '✓' : ''}</span> 
                      : <span className="insp-fi" style={{ color: f.key ? '#E8B339' : '#7E7E8A' }}>{f.key ? '⦿' : '▪'}</span>}
                    <span className="insp-fname">{f.name}</span>
                    <span className="insp-ftype">{f.type}</span>
                  </span>
                  {clickable && props.onFilterField && (
                    <button className="insp-filter" title="add to WHERE" aria-label={`Filter on ${fk}`} onClick={ev => { ev.stopPropagation(); props.onFilterField!(fk); }}>
                      ⍱
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
        <div className="insp-grp">
          <div className="insp-label">⎨ relations</div>
          {rels.length === 0 ? <div className="insp-dim sm">no relations</div> : rels.map(e => {
            const other = e.from === c.name ? e.to : e.from;
            const dir = e.from === c.name ? '→' : '←';
            const key = e._key || edgeKey(e, 0);
            const inJoin = joinKeys[key];
            return (
              <div key={key} className="insp-rel">
                <span className="insp-dot" style={{ background: '#5E9CE8' }} />
                <span className="insp-rel-arr">{dir}</span>
                <span className="insp-rel-name">{e.name}</span>
                <span className="insp-rel-to">{other}</span>
                {props.mode === 'build' && (
                  <button className={`insp-addjoin${inJoin ? ' on' : ''}`} onClick={() => props.onAddJoin(e)}>
                    {inJoin ? '✓ join' : '+ join'}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </aside>
  );
}

// ── Result Panel ───────────────────────────────────────────────────────────
/**
 * Says when the plan on screen is not the plan the optimizer would have chosen (D3): the
 * production budget or the wall-clock guard cut planning short, and what is shown is the best
 * plan so far — or, when nothing was implemented yet, the structural one. Silent on NONE, which
 * is every plan a person should normally see. Accepts rewrite-oql's JSON string as well as
 * run-oql's object, the same asymmetry the plan view already absorbs for `planShape`.
 */
function parsePlanning(planning?: PlanningReport | string | null): PlanningReport | null {
  if (typeof planning !== 'string') return planning ?? null;
  try {
    return JSON.parse(planning) as PlanningReport;
  } catch {
    return null;
  }
}

function PlanningNotice({ planning }: { planning?: PlanningReport | string | null }) {
  const report = parsePlanning(planning);
  if (!report || report.degradation === 'NONE') return null;
  const what = report.degradation === 'STRUCTURAL_FALLBACK'
    ? 'the structural plan — correct, but never costed'
    : 'the best plan found so far — correct, possibly not the cheapest';
  const why = report.tripped === 'WALL_CLOCK'
    ? `the ${Math.round(report.elapsedMillis / 1000)}s wall-clock guard`
    : `the ${report.budget.toLocaleString('en-US')}-production budget`;
  return (
    <div className="plan-block" data-testid="planning-notice" data-degradation={report.degradation}>
      <div className="plan-head">
        <span className="ochip ochip-int sm">planning cut short</span>
        <span className="plan-sub">
          {why} ended planning after {report.productions.toLocaleString('en-US')} rule productions; this is {what}.
          An administrator can raise the planner’s budget to plan it fully.
        </span>
        {/* J8-F22: the setting is for whoever runs the server, not for the analyst reading. */}
        <details className="plan-sub">
          <summary>Admin details</summary>
          ARAN_PLANNER_PRODUCTION_BUDGET on the Aran server sets the budget
          (currently {report.budget.toLocaleString('en-US')} productions).
        </details>
      </div>
    </div>
  );
}

interface ResultPanelProps {
  running?: boolean;
  result?: QueryRunResult | null;
  generatedSql?: string;
  explainResult?: OqlRewriteResult | null;
  focus?: WorkbenchClass | null;
  queryClasses?: WorkbenchClass[];
  error?: string | null;
  activeTab?: string;
  onTabChange?: (tab: string) => void;
  /** When set, the meta bar shows a "save as data product" action (M8). */
  onSaveAsProduct?: () => void;
  /** Re-run the current query at a different row offset. */
  onPage?: (offset: number) => void;
  /** Run the query without the page cap and download the whole result, in the given format. */
  onExportAll?: (format: ExportFormat) => void;
  /**
   * The query's own trailing LIMIT, when it has one: "every row" is then at most that many, and
   * the menu says so before the click rather than after (J5-F14).
   */
  exportLimit?: number | null;
  /** What the last export did — its row count, or why it failed (J5-F14). */
  exportNotice?: { tone: 'ok' | 'error'; text: string } | null;
  /** True while an export is in flight, so the button can say so. */
  exporting?: boolean;
  /**
   * The editor no longer holds the query that produced this result (J5-F1). The result stays —
   * it is still the answer to that run — but the pane says so, because every action on it
   * (pager, export, save) executes that run, not the editor.
   */
  stale?: boolean;
  /** The text of the run on screen, revealed on request while {@link stale}. */
  ranText?: string;
  /** Run what is in the editor now — offered beside the stale banner. */
  onRunCurrent?: () => void;
  /** The user stopped waiting for a run (J5-F11). The server may still be executing it. */
  stopped?: boolean;
}

/**
 * Prev/next over the server's pages.
 *
 * Renders nothing on a single-page result, which is the overwhelmingly common case — a workbench
 * cluttered with disabled controls for a nine-row answer would be worse than the dead end this
 * replaces. There is no page COUNT because there is no total: the engine never counts the full
 * result (that would mean executing it twice), it fetches one row past the cap and infers only
 * "there is more". Claiming "page 3 of 47" would be inventing a number.
 */
function ResultPager(props: { result: QueryRunResult; onPage?: (offset: number) => void }) {
  const state = pageStateOf(props.result);
  if (!props.onPage || !showPager(state)) return null;

  return (
    <div className="result-pager" data-testid="oql-pager">
      <button className="btn btn-ghost sm" data-testid="oql-page-prev"
        disabled={state.offset === 0}
        onClick={() => props.onPage?.(prevOffset(state))}>← prev</button>
      <span className="result-pager-at">rows {pageRangeLabel(state)}</span>
      <button className="btn btn-ghost sm" data-testid="oql-page-next"
        disabled={!state.hasMore}
        onClick={() => props.onPage?.(nextOffset(state))}>next →</button>
    </div>
  );
}

/**
 * The stale banner (J5-F1): the result on screen answers a query the editor no longer holds.
 * "Show it" reveals that query read-only rather than writing it back into the editor — nothing
 * overwrites text the user typed (rule R2).
 */
function StaleBanner(props: { ranText?: string; onRunCurrent?: () => void }) {
  const [shown, setShown] = useState(false);
  return (
    <div className="result-stale" data-testid="oql-stale" role="status">
      <div className="result-stale-row">
        <span className="result-error-icon" aria-hidden="true">⚠</span>
        <span className="result-stale-msg">
          Editor changed since this run — paging, export and save use the query that produced this result.
        </span>
        {props.ranText && (
          <button type="button" className="btn btn-ghost sm" data-testid="oql-stale-show"
            aria-expanded={shown} onClick={() => setShown(v => !v)}>
            {shown ? 'Hide it' : 'Show it'}
          </button>
        )}
        {props.onRunCurrent && (
          <button type="button" className="btn btn-ghost sm" data-testid="oql-stale-run"
            onClick={props.onRunCurrent}>Run current</button>
        )}
      </div>
      {shown && props.ranText && <pre className="result-stale-pre">{props.ranText}</pre>}
    </div>
  );
}

/**
 * Stopping the wait is not cancelling the query (D7: there is no server-side cancel). The card
 * says what is true: the server may still be executing it, and no result is shown because none
 * belongs to the query that was stopped (J5-F11).
 */
function StoppedCard() {
  return (
    <div className="result-mid" data-testid="oql-stopped" role="status">
      Stopped waiting. Aran may still be finishing this query on the server (up to about two
      minutes). No result is shown because none belongs to this query.
    </div>
  );
}

export function ResultPanel(props: ResultPanelProps) {
  const [tab, setTab] = useState(props.activeTab ?? 'result');
  const [showProfiler, setShowProfiler] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const err = props.error;
  const state = resultState({
    running: !!props.running,
    stopped: !!props.stopped,
    error: err ?? null,
    hasResult: !!props.result,
    stale: !!props.stale,
  });
  // R4: a failure replaces the result and a running query shows neither. The result object may
  // still be held by the caller (a page can be re-run from it); it is simply not this pane's
  // answer while another state holds.
  const r = state === 'ok' || state === 'stale' ? props.result ?? null : null;
  // 'explain' is the tab OqlPlanView.explain() has always switched to — it just didn't
  // exist, so the button visibly did nothing (redesign brief, workbench HIGH #1).
  const tabs = ['result', 'chart', 'mappings', 'explain'];

  // `tab` is externally steerable via `props.activeTab` but also locally overridable
  // (clicking a tab button calls setTab directly) — adjusted during render, the
  // documented alternative to an effect, rather than after an extra render pass.
  const [prevActiveTab, setPrevActiveTab] = useState(props.activeTab);
  if (props.activeTab !== prevActiveTab) {
    setPrevActiveTab(props.activeTab);
    if (props.activeTab !== undefined) setTab(props.activeTab);
  }

  const mappingClasses = props.queryClasses?.length ? props.queryClasses : (props.focus ? [props.focus] : []);

  return (
    <div className={`panel result${showProfiler ? ' with-profiler' : ''}`} data-state={state}>
      <div className="result-tabs">
        <div className="rt-list">
          {tabs.map(t => (
            <button key={t} className={`rt${tab === t ? ' is-on' : ''}`} onClick={() => { setTab(t); props.onTabChange?.(t); }}>
              {t}
            </button>
          ))}
          {/* UI-OD6: on every tab, so the result and plan panes both carry it in demo mode. */}
          <SimulationBadge className="ml-2 self-center" />
        </div>
        {tab === 'result' && r && (
          <div className="rt-meta">
            <span><b>{r.rowCount?.toLocaleString()}</b> rows</span>
            <span>·</span>
            <span>{runTiming(r)}</span>
            {(r.hasMore ?? r.truncated) && (
              <span className="rt-trunc" title="This is one page of a larger result">⚠ more rows</span>
            )}
            <button
              className={`rt-prof${showProfiler ? ' is-on' : ''}`}
              data-testid="oql-profiler"
              onClick={() => setShowProfiler(p => !p)}
            >
              ⚡ profiler
            </button>
            {/* Four verbs used to sit here — two of them the same act at different scopes, and
                one of them ("save as data product") not a view control at all but the creation
                of a durable artifact. The exports collapse into one menu; saving a product moved
                out to the panel's own footer, where an act with consequences belongs. */}
            <div className="rt-export-wrap">
              <button
                className={`rt-csv${exportOpen ? ' is-on' : ''}`}
                data-testid="oql-export"
                title="Download the result"
                disabled={props.exporting}
                onClick={() => setExportOpen(o => !o)}
              >{props.exporting ? '⋯ exporting' : '⤓ export'}</button>
              {exportOpen && (
                <div className="rt-export-menu">
                  <button
                    onClick={() => { downloadVisiblePageCsv(r); setExportOpen(false); }}
                    title="CSV of the rows on screen — this page only"
                  >this page (CSV)</button>
                  {props.onExportAll && (
                    <>
                      <button
                        data-testid="oql-export-all"
                        title="Run the query without the page cap and download the complete result"
                        onClick={() => { props.onExportAll?.('csv'); setExportOpen(false); }}
                      >every row (CSV)</button>
                      <button
                        data-testid="oql-export-all-parquet"
                        title="Run the query without the page cap and download it as Parquet — column types survive"
                        onClick={() => { props.onExportAll?.('parquet'); setExportOpen(false); }}
                      >every row (Parquet)</button>
                      {props.exportLimit != null && (
                        <span className="rt-export-note" data-testid="oql-export-limit">
                          at most {props.exportLimit.toLocaleString('en-US')} rows — your query&apos;s LIMIT applies
                        </span>
                      )}
                    </>
                  )}
                </div>
              )}
            </div>
          </div>
        )}
      </div>
      {props.exportNotice && (
        <div
          className={`result-export-notice${props.exportNotice.tone === 'error' ? ' is-error' : ''}`}
          data-testid="oql-export-notice"
          role={props.exportNotice.tone === 'error' ? 'alert' : 'status'}
        >
          {props.exportNotice.text}
        </div>
      )}
      <div className="result-body-wrap">
        <div className="result-body">
          {err && !props.running && (
            <div className="result-error" data-testid="oql-error"><span className="result-error-icon">⚠</span>{err}</div>
          )}
          {tab === 'result' && r && (r.hasMore ?? r.truncated) && (
            <div className="result-trunc" data-testid="oql-truncated">
              <span className="result-error-icon">⚠</span>
              {/*
                This used to read "increase the LIMIT to retrieve more", which cannot work:
                the server wraps every statement in its own LIMIT and caps the response
                regardless of what the query asks for. Advice that provably does nothing is
                worse than none, so the banner now names the two routes that do.
              */}
              Showing rows {((r.offset ?? 0) + 1).toLocaleString()}–
              {((r.offset ?? 0) + r.rowCount).toLocaleString()} of a larger result.
              The server caps a page at {(r.pageSize ?? r.rowCount).toLocaleString()} rows.
              {props.onExportAll && <> Use <b>export all</b> for the complete result.</>}
            </div>
          )}
          {tab === 'result' && state === 'stale' && (
            <StaleBanner ranText={props.ranText} onRunCurrent={props.onRunCurrent} />
          )}
          {tab === 'result' ? (
            props.running ? <div className="result-mid"><span className="spin" /> Running your query…</div>
            : state === 'stopped' ? <StoppedCard />
            : !r ? <div className="result-mid">{err ? 'query failed — see error above' : 'run a query to see results'}</div>
            : (
              <div className={state === 'stale' ? 'result-grid-stale' : undefined}>
                <ResultTable result={r} />
                <ResultPager result={r} onPage={props.onPage} />
              </div>
            )
          ) : tab === 'chart' ? (
            props.running ? <div className="result-mid"><span className="spin" /> Running your query…</div>
            : state === 'stopped' ? <StoppedCard />
            : !r ? <div className="result-mid">run a query to chart its result</div>
            : <ResultChart result={r} />
          ) : tab === 'explain' ? (
            props.explainResult ? (
              <div className="explain-wrap" data-testid="oql-explain">
                <PlanningNotice planning={props.explainResult.planning} />
                <PlanTree
                  logicalPlan={props.explainResult.logicalPlan}
                  optimizedPlan={props.explainResult.optimizedPlan}
                  planShape={props.explainResult.planShape}
                  planShapeText={props.explainResult.planShapeText}
                />
                <div className="plan-block">
                  <div className="plan-head">
                    <span className="ochip ochip-int sm">sql</span>
                    <span className="plan-sub">generated · over int_ views · not executed</span>
                  </div>
                  <pre className="plan-pre">{props.explainResult.sql}</pre>
                </div>
              </div>
            ) : (
              <div className="result-mid">explain a query to see its plan without running it</div>
            )
          ) : mappingClasses.length > 0 ? (
            <div className="map-wrap">
              {mappingClasses.map(cls => (
                <div key={cls.name}>
                  <div className="map-head">
                    <span className="ochip ochip-int">{cls.name}</span>
                    <span className="map-iri">{cls.iri}</span>
                  </div>
                  {cls.sources.map((s, i) => (
                    <div key={i} className="map-src">
                      <div className="map-src-h"><span className="insp-db">▤</span>{s.datasetId}<span className="map-cols">{s.columns.length} cols</span></div>
                      <table className="rtable map-table">
                        <thead><tr><th>global property</th><th>source property</th></tr></thead>
                        <tbody>
                          {s.columns.map(col => (
                            <tr key={col.globalName}><td>{col.globalName}</td><td className="map-dim">{col.source}</td></tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ))}
                </div>
              ))}
            </div>
          ) : <div className="result-mid">add classes to the query to see their source mappings</div>
          }
        </div>
        {showProfiler && r && (
          <div className="result-profiler-pane">
            <div className="prof-head">Query Profiler</div>
            <div className="prof-body">
              {r.logicalPlan && r.optimizedPlan ? (
                <div className="explain-wrap">
                  <PlanningNotice planning={r.planning} />
                  <PlanTree
                    logicalPlan={r.logicalPlan}
                    optimizedPlan={r.optimizedPlan}
                    // r.planShape is already a nested object (QueryRunResult's shape,
                    // types.ts) — re-stringify once so PlanTree has one input shape
                    // regardless of which caller (run-oql vs rewrite-oql) fed it.
                    planShape={r.planShape ? JSON.stringify(r.planShape) : undefined}
                    planShapeText={r.planShapeText}
                  />
                  {props.generatedSql && (
                    <div className="plan-block">
                      <div className="plan-head">
                        <span className="ochip ochip-int sm">sql</span>
                        <span className="plan-sub">generated · over int_ views</span>
                      </div>
                      <pre className="plan-pre">{props.generatedSql}</pre>
                    </div>
                  )}
                </div>
              ) : <div className="result-mid">Run a query to see the CBO profiler</div>}
            </div>
          </div>
        )}
      </div>
      {/* Creating a data product is not a view control — it mints a durable artifact pinned to
          the active schema version — so it sits apart from the tab strip's show-me-differently
          verbs rather than as a fourth button among them. */}
      {props.onSaveAsProduct && tab === 'result' && r && (
        <div className="result-foot">
          <button
            className="rt-product"
            data-testid="oql-save-product"
            title="Save this OQL as a data product, recorded against the active schema version"
            onClick={props.onSaveAsProduct}
          >◈ save as data product</button>
        </div>
      )}
    </div>
  );
}

// ── Filter Builder ─────────────────────────────────────────────────────────
interface FilterBuilderProps {
  filters?: Predicate[];
  classes?: string[];
  catalogData: { classByName: Record<string, WorkbenchClass> };
  /** Add a WHERE condition to OR-group `groupIdx`; an index past the last group starts a new one (J5-F8). */
  onAdd: (f: Predicate, groupIdx: number) => void;
  onRemove: (id: string) => void;
  orders?: OrderClause[];
  /** The query's outputs — all ORDER BY may name (J5-F9). */
  orderOptions?: OrderOption[];
  onAddOrder?: (o: OrderClause) => void;
  onRemoveOrder?: (id: string) => void;
  onToggleOrderDir?: (id: string) => void;
  distinct?: boolean;
  onToggleDistinct?: () => void;
  selectedFields?: string[];
  aggregates?: Record<string, AggFn>;
  onSetAggregate?: (fieldKey: string, fn: AggFn | '') => void;
  /** `COUNT(*) AS n` in SELECT (J5-F9). */
  countAll?: boolean;
  onSetCountAll?: (on: boolean) => void;
  having?: HavingClause[];
  /** The SELECT list's aggregates — all HAVING may filter on (J5-F2). */
  havingOptions?: HavingOption[];
  onAddHaving?: (h: HavingClause) => void;
  onRemoveHaving?: (id: string) => void;
  /**
   * The query text was edited by hand, so these clauses are not what runs (J5-F4). The bar says
   * so, shows the chips read-only, and offers an explicit rebuild instead of silently re-writing
   * the text on the next click.
   */
  paused?: boolean;
  onRebuild?: () => void;
  /**
   * The inspector's "filter on this field": open the WHERE draft on that field and wait for a
   * value, rather than committing `= ''` — a filter nobody asked for. `nonce` tells a second click
   * on the same field from a re-render; `onSeedResolved` fires once the draft is added or cancelled.
   */
  seed?: FilterSeed | null;
  onSeedResolved?: () => void;
}

export interface FilterSeed { cls: string; field: string; type: string; nonce: number }

/** What the aggregate draft's field select means by "every row": `COUNT(*)`. */
const ALL_ROWS = '*';

/**
 * Every clause of the query as chips, plus one menu to add another.
 *
 * <p>A chip carries its own clause tag, which is what makes per-clause headers redundant.
 * Preconditions live in the add menu as disabled entries with a reason — expressed once, at the
 * moment of asking. WHERE is shown as OR-groups because that is how the parser reads it (AND binds
 * tighter than OR, J5-F8); ORDER BY and HAVING offer only what the planner accepts (J5-F9, J5-F2).
 */
export function FilterBuilder(props: FilterBuilderProps) {
  const filters = props.filters || [];
  const orders = props.orders || [];
  const distinct = props.distinct ?? false;
  const selectedFields = props.selectedFields || [];
  const aggregates = props.aggregates || {};
  const having = props.having || [];
  const orderOpts = props.orderOptions || [];
  const havingOpts = props.havingOptions || [];
  const paused = !!props.paused;
  const [draft, setDraft] = useState<{ pred: Predicate; group: number; seeded?: boolean } | null>(null);
  // Adjusting state on a new prop during render, the React-docs pattern, rather than an effect
  // that would paint the builder once without the draft.
  const [seedSeen, setSeedSeen] = useState<number | null>(null);
  if (props.seed && props.seed.nonce !== seedSeen) {
    const { cls, field, type } = props.seed;
    setSeedSeen(props.seed.nonce);
    setDraft({ group: 0, seeded: true, pred: { id: 'draft', cls, field, type, op: opsForType(type)[0], value: '' } });
  }
  const [orderDraft, setOrderDraft] = useState<string | null>(null);
  const [havingDraft, setHavingDraft] = useState<Omit<HavingClause, 'id'> | null>(null);
  const [aggDraft, setAggDraft] = useState<{ field: string; fn: AggFn } | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);

  const fieldOpts: { cls: string; field: string; type: string }[] = [];
  // A seeded draft may name a class the query does not have yet; adding the condition adds it.
  const draftClasses = [...(props.classes || [])];
  if (draft && !draftClasses.includes(draft.pred.cls)) draftClasses.push(draft.pred.cls);
  draftClasses.forEach(cn => {
    const c = props.catalogData.classByName[cn];
    if (c) c.fields.forEach(f => fieldOpts.push({ cls: cn, field: f.name, type: f.type }));
  });
  const activeAggs = Object.entries(aggregates).filter(([, fn]) => !!fn) as [string, AggFn][];
  const groups = filterGroups(filters);

  function startDraft(group: number) {
    const first = fieldOpts[0]; if (!first) return;
    if (draft?.seeded) props.onSeedResolved?.();
    const ops = opsForType(first.type);
    setDraft({ group, pred: { id: 'draft', cls: first.cls, field: first.field, type: first.type, op: ops[0], value: '' } });
  }
  function commitDraft() {
    if (!draft) return;
    props.onAdd({ ...draft.pred, id: 'f' + Date.now() }, draft.group);
    closeDraft();
  }
  function closeDraft() {
    if (draft?.seeded) props.onSeedResolved?.();
    setDraft(null);
  }
  function commitOrderDraft() {
    const opt = orderOpts.find(o => o.label === orderDraft);
    if (!opt) return;
    props.onAddOrder?.({ id: 'o' + Date.now(), cls: opt.cls, field: opt.field, alias: opt.alias, dir: opt.alias ? 'DESC' : 'ASC' });
    setOrderDraft(null);
  }
  function commitAggDraft() {
    if (!aggDraft) return;
    if (aggDraft.field === ALL_ROWS) props.onSetCountAll?.(true);
    else props.onSetAggregate?.(aggDraft.field, aggDraft.fn);
    setAggDraft(null);
  }

  const clauseOptions: Array<{ id: string; label: string; reason?: string; run: () => void }> = [
    {
      id: 'filter', label: 'WHERE — filter rows',
      reason: fieldOpts.length === 0 ? 'add a class to the query first' : undefined,
      run: () => startDraft(0),
    },
    {
      id: 'sort', label: 'ORDER BY — sort by a selected column',
      reason: orderOpts.length === 0 ? 'select a column first — only selected columns can be sorted' : undefined,
      run: () => setOrderDraft(orderOpts[0].label),
    },
    {
      id: 'aggregate', label: 'AGGREGATE — COUNT, SUM, AVG…',
      reason: fieldOpts.length === 0 ? 'add a class to the query first' : undefined,
      run: () => setAggDraft({ field: selectedFields[0] ?? ALL_ROWS, fn: 'COUNT' }),
    },
    {
      id: 'having', label: 'HAVING — filter on an aggregate',
      reason: havingOpts.length === 0 ? 'add an aggregate first — HAVING filters groups, not rows' : undefined,
      run: () => setHavingDraft({ fn: havingOpts[0].fn, field: havingOpts[0].field, op: '>', value: '0' }),
    },
    {
      id: 'distinct', label: distinct ? 'DISTINCT — turn off' : 'DISTINCT — deduplicate rows',
      reason: props.onToggleDistinct ? undefined : 'not available here',
      run: () => props.onToggleDistinct?.(),
    },
  ];

  const anyClause = filters.length > 0 || orders.length > 0 || activeAggs.length > 0
    || having.length > 0 || distinct || !!props.countAll;
  // A seeded WHERE draft shows even while paused: like every other inspector gesture, adding it
  // rebuilds the text, and the view offers Undo for the hand edits it replaces.
  const showDraft = !!draft && (!paused || !!draft.seeded);
  const anyDraft = showDraft || (!paused && (orderDraft !== null || !!havingDraft || !!aggDraft));
  const removable = !paused;

  return (
    <div className={`clausebar${paused ? ' is-paused' : ''}`} data-testid="wb-clausebar" data-paused={paused || undefined}>
      {paused && (
        <div className="cb-paused" role="status" data-testid="wb-builder-paused">
          <span>
            Clauses are in the text — the builder is paused because the query was edited by hand.
            The chips below are the builder&apos;s last state, not what runs.
          </span>
          {props.onRebuild && (
            <button type="button" className="cb-rebuild" data-testid="wb-rebuild" onClick={props.onRebuild}
              title="Replace the query text with what the builder describes. You can undo it for a few seconds.">
              Rebuild from builder
            </button>
          )}
        </div>
      )}
      <div className="cb-row">
        <div className="cb-add-wrap">
          <button
            className={`cb-add${menuOpen ? ' is-on' : ''}`}
            data-testid="wb-add-clause"
            disabled={paused}
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen(o => !o)}
          >+ clause</button>
          {menuOpen && !paused && (
            <div className="cb-menu" data-testid="wb-clause-menu">
              {clauseOptions.map(o => (
                <button
                  key={o.id}
                  className="cb-menu-item"
                  data-testid={`wb-clause-${o.id}`}
                  disabled={!!o.reason}
                  title={o.reason}
                  onClick={() => { o.run(); setMenuOpen(false); }}
                >
                  <span>{o.label}</span>
                  {o.reason && <span className="cb-menu-why">{o.reason}</span>}
                </button>
              ))}
            </div>
          )}
        </div>

        {!anyClause && !anyDraft && !paused && (
          <span className="cb-empty">no clauses — the query runs unfiltered</span>
        )}

        {groups.length > 0 && (
          <span className="cb-where" data-testid="wb-where-groups">
            {groups.map((g, gi) => (
              <React.Fragment key={g[0].id}>
                {gi > 0 && <span className="cb-or">or</span>}
                <span className="cb-group" role="group" aria-label={`Condition group ${gi + 1}: every condition in it must hold`}>
                  {gi === 0 && <span className="cb-tag">where</span>}
                  {g.map((f, fi) => (
                    <React.Fragment key={f.id}>
                      {fi > 0 && <span className="cb-and">and</span>}
                      <span className="fb-chip">
                        <span className="fb-chip-f">{f.cls}.{f.field}</span>
                        <span className="fb-chip-op">{f.op}</span>
                        {needsValue(f.op) && <span className="fb-chip-v">{String(f.value ?? '') || '…'}</span>}
                        {removable && (
                          <button className="fb-chip-x" aria-label={`Remove condition ${f.cls}.${f.field} ${f.op}`} onClick={() => props.onRemove(f.id)}>×</button>
                        )}
                      </span>
                    </React.Fragment>
                  ))}
                  {removable && (
                    <button type="button" className="cb-group-add" data-testid={`wb-where-add-${gi}`}
                      onClick={() => startDraft(gi)} title="Add a condition every row of this group must also meet">+ and</button>
                  )}
                </span>
              </React.Fragment>
            ))}
            {removable && (
              <button type="button" className="cb-group-add" data-testid="wb-where-add-group"
                onClick={() => startDraft(groups.length)} title="Also keep rows that meet a different set of conditions">+ or group</button>
            )}
          </span>
        )}

        {activeAggs.map(([fk, fn]) => (
          <span key={fk} className="fb-chip agg-chip">
            <span className="fb-chip-f">{fn}({fk})</span>
            {removable && (
              <button className="fb-chip-x" aria-label={`Remove aggregate ${fn}(${fk})`}
                onClick={() => props.onSetAggregate?.(fk, '')}>×</button>
            )}
          </span>
        ))}

        {props.countAll && (
          <span className="fb-chip agg-chip">
            <span className="fb-chip-f">COUNT(*) AS n</span>
            {removable && (
              <button className="fb-chip-x" aria-label="Remove the row count" onClick={() => props.onSetCountAll?.(false)}>×</button>
            )}
          </span>
        )}

        {having.map(h => (
          <span key={h.id} className="fb-chip">
            <span className="cb-tag">having</span>
            <span className="fb-chip-op">{h.field ? `${h.fn}(${h.field})` : `${h.fn}(*)`}</span>
            <span className="fb-chip-op">{h.op}</span>
            <span className="fb-chip-v">{h.value}</span>
            {removable && (
              <button className="fb-chip-x" aria-label="Remove this HAVING condition" onClick={() => props.onRemoveHaving?.(h.id)}>×</button>
            )}
          </span>
        ))}

        {orders.map((o, i) => (
          <span key={o.id} className="fb-chip ob-chip">
            {i === 0 && <span className="cb-tag">order</span>}
            <span className="fb-chip-f">{o.alias ?? `${o.cls}.${o.field}`}</span>
            <button className="ob-dir" disabled={!removable} onClick={() => props.onToggleOrderDir?.(o.id)}
              title="Toggle direction" aria-label={`Sort ${o.dir === 'ASC' ? 'ascending' : 'descending'}; toggle`}>
              {o.dir === 'ASC' ? '↑' : '↓'}
            </button>
            {removable && (
              <button className="fb-chip-x" aria-label="Remove this sort" onClick={() => props.onRemoveOrder?.(o.id)}>×</button>
            )}
          </span>
        ))}

        {distinct && (
          <span className="fb-chip distinct-chip">
            <span className="fb-chip-f">DISTINCT</span>
            {removable && (
              <button className="fb-chip-x" aria-label="Stop deduplicating rows"
                onClick={() => props.onToggleDistinct?.()}>×</button>
            )}
          </span>
        )}
      </div>

      {groups.length > 1 && (
        <div className="cb-where-caption" data-testid="wb-where-caption">
          Rows matching any group are kept; within a group, every condition must hold.
        </div>
      )}

      {/* Drafts get their own row: the selects are much taller than a chip, and inlining them
          made the whole bar jump a row's height every time one was opened. */}
      {anyDraft && (
        <div className="cb-row cb-drafts">
          {draft && showDraft && (
            <DraftRow key={seedSeen ?? 'menu'} draft={draft.pred} setDraft={(pred) => setDraft({ ...draft, pred })} fieldOpts={fieldOpts}
              groupLabel={draft.group >= groups.length ? (groups.length === 0 ? 'where' : 'or') : 'and'}
              focusValue={!!draft.seeded}
              onCommit={commitDraft} onCancel={closeDraft} />
          )}
          {orderDraft !== null && (
            <span className="fb-draft">
              <span className="cb-tag">order</span>
              <select className="fb-sel" aria-label="Sort by" value={orderDraft} onChange={e => setOrderDraft(e.target.value)}>
                {orderOpts.map(o => <option key={o.label} value={o.label}>{o.label}</option>)}
              </select>
              <button className="fb-ok" onClick={commitOrderDraft} aria-label="Add this sort">✓</button>
              <button className="fb-chip-x" onClick={() => setOrderDraft(null)} aria-label="Cancel">×</button>
            </span>
          )}
          {aggDraft && (
            <span className="fb-draft">
              <select className="fb-sel" aria-label="Aggregate function" value={aggDraft.field === ALL_ROWS ? 'COUNT' : aggDraft.fn}
                disabled={aggDraft.field === ALL_ROWS}
                onChange={e => setAggDraft({ ...aggDraft, fn: e.target.value as AggFn })}>
                {AGG_FNS.map(fn => <option key={fn} value={fn}>{fn}</option>)}
              </select>
              <select className="fb-sel" aria-label="Over" value={aggDraft.field}
                onChange={e => setAggDraft({ fn: e.target.value === ALL_ROWS ? 'COUNT' : aggDraft.fn, field: e.target.value })}>
                {!props.countAll && <option value={ALL_ROWS}>* — rows (COUNT(*) AS n)</option>}
                {selectedFields.map(fk => <option key={fk} value={fk}>{fk}</option>)}
              </select>
              <button className="fb-ok" onClick={commitAggDraft} aria-label="Add this aggregate">✓</button>
              <button className="fb-chip-x" onClick={() => setAggDraft(null)} aria-label="Cancel">×</button>
            </span>
          )}
          {havingDraft && (
            <HavingDraft
              draft={havingDraft}
              setDraft={setHavingDraft}
              aggOpts={havingOpts}
              onCommit={() => { props.onAddHaving?.({ ...havingDraft, id: 'h' + Date.now() }); setHavingDraft(null); }}
              onCancel={() => setHavingDraft(null)}
            />
          )}
        </div>
      )}
    </div>
  );
}

const HAVING_OPS = ['=', '!=', '<', '>', '<=', '>='];

/**
 * The HAVING draft row. Offers only the SELECT list's aggregates (J5-F2): the planner refuses a
 * HAVING aggregate the SELECT list does not compute, and one with no aggregate at all.
 */
function HavingDraft(props: {
  draft: Omit<HavingClause, 'id'>;
  setDraft: (d: Omit<HavingClause, 'id'>) => void;
  aggOpts: HavingOption[];
  onCommit: () => void;
  onCancel: () => void;
}) {
  const { draft, setDraft, aggOpts, onCommit, onCancel } = props;
  const label = draft.field ? `${draft.fn}(${draft.field})` : `${draft.fn}(*)`;
  const valid = draft.value.trim() !== '' && Number.isFinite(Number(draft.value));

  return (
    <span className="fb-draft">
      <span className="cb-tag">having</span>
      <select className="fb-sel" aria-label="Aggregate" value={label}
        onChange={e => {
          const opt = aggOpts.find(o => o.label === e.target.value);
          if (opt) setDraft({ ...draft, fn: opt.fn, field: opt.field });
        }}>
        {aggOpts.map(o => <option key={o.label} value={o.label}>{o.label}</option>)}
      </select>
      <select className="fb-sel fb-sel-op" aria-label="Comparison" value={draft.op}
        onChange={e => setDraft({ ...draft, op: e.target.value })}>
        {HAVING_OPS.map(o => <option key={o} value={o}>{o}</option>)}
      </select>
      <input
        className="fb-val" value={draft.value} placeholder="number" type="number" aria-label="Value"
        onChange={e => setDraft({ ...draft, value: e.target.value })}
        onKeyDown={e => { if (e.key === 'Enter' && valid) onCommit(); if (e.key === 'Escape') onCancel(); }}
      />
      <button className="fb-ok" onClick={onCommit} disabled={!valid} aria-label="Add this HAVING condition">✓</button>
      <button className="fb-chip-x" onClick={onCancel} aria-label="Cancel">×</button>
    </span>
  );
}

function DraftRow(props: {
  draft: Predicate;
  setDraft: (d: Predicate) => void;
  fieldOpts: { cls: string; field: string; type: string }[];
  /** How the condition joins the rest: the first WHERE, "and" into a group, or a new "or" group. */
  groupLabel: 'where' | 'and' | 'or';
  /** The field is already chosen (the inspector seeded it), so the value is the next thing to type. */
  focusValue?: boolean;
  onCommit: () => void;
  onCancel: () => void;
}) {
  const { draft: d, setDraft, fieldOpts, onCommit, onCancel, focusValue } = props;
  const ops = opsForType(d.type);
  // Focus moves to the value only for a seeded draft — a gesture elsewhere on the page opened it,
  // so the keyboard would otherwise be left behind on the inspector (jsx-a11y rules out autoFocus).
  const valueRef = useRef<HTMLInputElement>(null);
  useEffect(() => { if (focusValue) valueRef.current?.focus(); }, [focusValue]);
  const hints = valueHints(d.cls, d.field);
  // A condition that needs a value is not added without one: the builder used to write `= 0`
  // (or `= ''`) for an empty box, a filter nobody asked for.
  const valid = !needsValue(d.op) || String(d.value ?? '').trim() !== '';

  function patch(p: Partial<Predicate>) { setDraft({ ...d, ...p }); }
  function pickField(val: string) {
    const fo = fieldOpts.find(o => `${o.cls}.${o.field}` === val);
    if (!fo) return;
    const nops = opsForType(fo.type);
    setDraft({ id: d.id, cls: fo.cls, field: fo.field, type: fo.type, op: nops[0], value: '' });
  }

  return (
    <span className="fb-draft">
      <span className="cb-tag">{props.groupLabel}</span>
      <select className="fb-sel" aria-label="Column" value={`${d.cls}.${d.field}`} onChange={e => pickField(e.target.value)}>
        {fieldOpts.map(o => {
          const v = `${o.cls}.${o.field}`;
          return <option key={v} value={v}>{v}</option>;
        })}
      </select>
      <select className="fb-sel fb-sel-op" aria-label="Comparison" value={d.op} onChange={e => patch({ op: e.target.value })}>
        {ops.map(o => <option key={o} value={o}>{o}</option>)}
      </select>
      {needsValue(d.op) && (
        <input
          className="fb-val" value={d.value == null ? '' : String(d.value)} list={hints.length ? `vh-${d.field}` : undefined}
          ref={valueRef} placeholder="value" aria-label="Value"
          onChange={e => patch({ value: e.target.value })}
          onKeyDown={e => { if (e.key === 'Enter' && valid) onCommit(); if (e.key === 'Escape') onCancel(); }}
        />
      )}
      {hints.length > 0 && (
        <datalist id={`vh-${d.field}`}>
          {hints.map(v => <option key={v} value={v} />)}
        </datalist>
      )}
      <button className="fb-ok" onClick={onCommit} disabled={!valid} aria-label="Add this condition">✓</button>
      <button className="fb-chip-x" onClick={onCancel} aria-label="Cancel">×</button>
    </span>
  );
}

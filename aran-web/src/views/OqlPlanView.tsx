import React, { useState, useMemo, useEffect, useRef } from 'react';
import { api } from '../services/api';
import { QueryCatalog } from '../services/types';
import { parseQuery, buildOql, getCatalogData, MappedEdge, Predicate, OrderClause, HavingClause, AggFn, BuildState, pruneBuild, insertIntoGroup, removeFromGroup, orderOptions, havingOptions } from '../lib/workbenchModel';
import type { OqlRewriteResult, ProductAccessMode } from '../services/types';
import { GraphCanvas } from '../components/workbench/GraphCanvas';
import { OqlEditor } from '../components/workbench/OqlEditor';
import { CatalogBrowser, Inspector, ResultPanel, FilterBuilder, CmdHead, type FilterSeed } from '../components/workbench/WorkbenchPanels';
import { SaveAsProductDialog } from '../components/SaveAsProductDialog';
import { defaultProductProject } from '@/lib/productProject';
import { NoSchemaNotice } from '../components/workbench/NoSchemaNotice';
import { InfoTip } from '../components/InfoTip';
import { CONCEPTS } from '../lib/concepts';
import {
  saveHistory, loadHistory, saveWorkspace, loadWorkspace, migrateLayout,
  getUrlQuery, buildShareUrl, relTime, HistoryEntry, initialEditorText, clearShareParam, replacesDraft,
} from '../lib/workbenchStorage';
import './workbench.css';
import { errorMessage } from '@/lib/errors';
import { copyText } from '@/lib/clipboard';
import { ShareLinkFallback } from '../components/workbench/ShareLinkFallback';
import { statementFor, editorDiverged, limitOf, exportNotice, type ExportFormat, type LastRun, type QueryLang } from '@/lib/workbenchRun';
import { useProjectsStore } from '@/stores/projectsStore';

/**
 * Two modes, not three.
 *
 * `split` and `query` were the same five panels re-proportioned, and both ran the graph in
 * `mode='explore'` — the difference was only how much room the editor got, which is a
 * preference rather than a mode. They are now one `explore` layout with a collapsible graph
 * column, which covers what query-first was actually for (more editor room) without a second
 * top-level mode to choose between.
 *
 * `canvas` stays, because it is genuinely different: it runs `mode='build'`, where clicking a
 * node edits the query rather than navigating it. That is a different tool, not a different
 * arrangement.
 */
const LAYOUTS = [
  { id: 'explore', tag: 'A', name: 'Explore' },
  { id: 'canvas', tag: 'B', name: 'Visual Builder' },
] as const;

type LayoutOption = typeof LAYOUTS[number]['id'];

/**
 * OQL and raw SQL are one editor in two languages, not two layouts.
 *
 * SQL is subordinate on purpose. It is the surface you drop to when OQL cannot express the
 * query or you are debugging what a source actually contributed — the integrated model is
 * still the thing this workbench is for. Giving SQL a peer segmented control alongside the
 * layouts would say the opposite.
 */
export function OqlPlanView() {
  const [layout, setLayout] = useState<LayoutOption>(() => migrateLayout(loadWorkspace()?.layout));
  const [lang, setLang] = useState<QueryLang>('oql');
  // The graph is the biggest panel and the one people most often want out of the way; hiding it
  // is what the old 'query-first' layout existed to do.
  const [graphOpen, setGraphOpen] = useState(true);
  /**
   * A shared link (`?q=`) and the saved draft, reconciled once (J5-F7): a draft that differs stays
   * in the editor and the shared query waits for a choice, instead of overwriting it on every load.
   */
  const [initialEditor] = useState(() => initialEditorText(getUrlQuery(), loadWorkspace()?.query));
  const [query, setQuery] = useState<string>(initialEditor.text);
  const [pendingShared, setPendingShared] = useState<string | null>(initialEditor.pendingShared);
  // Consumed once: a reload must not apply the link again over what the user has typed since.
  useEffect(() => { clearShareParam(); }, []);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [build, setBuild] = useState<Required<BuildState>>({
    classes: {},
    fields: {},
    joins: [],
    filters: [],
    orders: [],
    distinct: false,
    aggregates: {},
    having: [],
    countAll: false,
  });

  const cancelRef = useRef<AbortController | null>(null);
  /**
   * Which execution the view is waiting for. A response that lands after the user stopped
   * waiting — or after a newer run started — belongs to nobody and must not be rendered, nor
   * flip `running` off under the run that replaced it.
   */
  const runSeq = useRef(0);

  /**
   * "Stop waiting", not cancel (D7: there is no server-side cancel). The server may still finish
   * the query; no result is shown because none belongs to it (J5-F11). Exemplar: BigQuery Studio
   * and Snowsight both keep a stopped query's pane empty rather than showing the previous answer.
   */
  function stopWaiting() {
    if (!cancelRef.current) return;
    runSeq.current++;
    cancelRef.current.abort();
    cancelRef.current = null;
    setRunning(false);
    setStopped(true);
    setLastRun(null);
  }

  // A query restored from storage/URL counts as hand-edited so syncQuery won't overwrite it
  const [freeEdited, setFreeEdited] = useState<boolean>(() => initialEditor.text.trim() !== '');

  /**
   * One step of undo for every gesture that replaces the query text (rule R2): clearing, a shared
   * query opened over a draft, a rebuild from the builder, and a builder gesture over hand-edited
   * text. Offered for ten seconds, then gone — the Gmail "Undo send" shape, rather than a confirm
   * dialog in front of an action that is cheap to reverse.
   */
  const [undo, setUndo] = useState<{ label: string; text: string; build: typeof build; freeEdited: boolean; lastRun: LastRun | null } | null>(null);
  const undoTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (undoTimer.current) clearTimeout(undoTimer.current); }, []);
  function offerUndo(label: string) {
    setUndo({ label, text: query, build, freeEdited, lastRun });
    if (undoTimer.current) clearTimeout(undoTimer.current);
    undoTimer.current = setTimeout(() => setUndo(null), 10_000);
  }
  function applyUndo() {
    if (!undo) return;
    setQuery(undo.text);
    setBuild(undo.build);
    setFreeEdited(undo.freeEdited);
    setLastRun(undo.lastRun);
    setUndo(null);
  }
  /**
   * A builder gesture is about to replace text the user typed (J5-F4): say so and offer the way
   * back. The builder cannot read hand-written OQL (there is no TypeScript OQL parser, D6), so its
   * chips are its own last state, not the text's.
   */
  function guardHandEdits() {
    if (freeEdited && query.trim() !== '') offerUndo('The builder replaced your hand-edited query.');
  }
  function rebuildFromBuilder() {
    offerUndo('Query rebuilt from the builder.');
    syncQuery(build);
  }
  function openShared() {
    if (pendingShared === null) return;
    // The draft stays reachable twice over: the Undo pill now, and the history after.
    saveHistory(query);
    setQueryHistory(loadHistory());
    offerUndo('Opened the shared query. Your draft is in history.');
    setQuery(pendingShared);
    setFreeEdited(true);
    setPendingShared(null);
  }
  const [queryLimit, setQueryLimit] = useState<number>(() => loadWorkspace()?.queryLimit ?? 100);

  const [queryHistory, setQueryHistory] = useState<HistoryEntry[]>(() => loadHistory());
  const [historyOpen, setHistoryOpen] = useState(false);
  const [linkCopied, setLinkCopied] = useState(false);
  const [shareFallback, setShareFallback] = useState<string | null>(null);

  // Debounced workspace persistence
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => saveWorkspace({ query, layout, queryLimit }), 600);
    return () => { if (saveTimer.current) clearTimeout(saveTimer.current); };
  }, [query, layout, queryLimit]);
  /** The run whose result is on screen — what every result action executes (R3, J5-F1). */
  const [lastRun, setLastRun] = useState<LastRun | null>(null);
  const [stopped, setStopped] = useState(false);
  const [running, setRunning] = useState(false);
  const [genSqlText, setGenSqlText] = useState('');
  const [explainResult, setExplainResult] = useState<OqlRewriteResult | null>(null);
  const [queryError, setQueryError] = useState<string | null>(null);
  const [resultTab, setResultTab] = useState('result');
  const [exporting, setExporting] = useState(false);
  /** What the last export did (J5-F14). An export failure is not a query failure: the result stays. */
  const [exportResult, setExportResult] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
  const [catalog, setCatalog] = useState<QueryCatalog | null>(null);

  // Save-as-data-product dialog (M8)
  // Selected field by field: `load()` fires several separate `set()` calls, and a
  // whole-store subscription re-renders this whole view on each one.
  const projects = useProjectsStore((s) => s.projects);
  const loadProjects = useProjectsStore((s) => s.load);
  const activeProjectId = useProjectsStore((s) => s.activeProjectId);
  const setActiveProject = useProjectsStore((s) => s.setActiveProject);
  const [productDialog, setProductDialog] = useState(false);
  const [productProjectId, setProductProjectId] = useState('');
  const [productName, setProductName] = useState('');
  const [productDescription, setProductDescription] = useState('');
  // MATERIALIZED default (Phase 5): the choice a user makes least often should be the one
  // they have to opt into.
  const [productAccessMode, setProductAccessMode] = useState<ProductAccessMode>('MATERIALIZED');
  const [productSaving, setProductSaving] = useState(false);
  const [productError, setProductError] = useState<string | null>(null);
  const [productSavedId, setProductSavedId] = useState<string | null>(null);
  // Opens on the project the breadcrumb names rather than blank (J6-F5) — derived, not copied
  // into state at open, so it also holds when the project list arrives after the dialog opened.
  const productTargetId = defaultProductProject(productProjectId, activeProjectId, projects);

  function openProductDialog() {
    setProductError(null);
    setProductSavedId(null);
    setProductAccessMode('MATERIALIZED');
    setProductDialog(true);
    if (projects.length === 0) void loadProjects();
  }

  async function saveProduct() {
    const stmt = statementFor('save', { text: query, lang }, lastRun);
    if (!stmt || stmt.lang !== 'oql' || !productTargetId || !productName.trim()) return;
    setProductSaving(true);
    setProductError(null);
    try {
      const created = await api.createProduct(productTargetId, {
        name: productName.trim(),
        description: productDescription.trim() || undefined,
        oql: stmt.text,
        accessMode: productAccessMode,
      });
      setProductSavedId(created.id);
      // The products page follows the active project, so "open data products" lands on this
      // product's project instead of whichever one was active before (J6-F5).
      setActiveProject(productTargetId);
    } catch (e: unknown) {
      setProductError(errorMessage(e, 'failed to save data product'));
    } finally {
      setProductSaving(false);
    }
  }

  // Load catalog on mount. A failed fetch used to go to console.error with zero UI,
  // leaving the workbench as dead panels — the error now renders where the catalog
  // would be, with a retry (redesign brief, workbench HIGH #2).
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const loadCatalog = () => {
    setCatalogError(null);
    api.queryEngineCatalog()
      .then((c) => { setCatalog(c); setCatalogError(null); })
      .catch((e: unknown) => setCatalogError(errorMessage(e, 'Failed to load the query catalog.')));
  };
  useEffect(() => {
    // A network fetch has no render-time equivalent; runs once on mount.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadCatalog();
  }, []);

  const catalogData = useMemo(() => getCatalogData(catalog), [catalog]);

  // Seed query from the first class once the catalog arrives
  useEffect(() => {
    if (!catalog || catalogData.classes.length === 0) return;
    // Only seed when the workbench is still blank (user hasn't touched anything)
    if (query !== '') return;
    const first = catalogData.classes[0];
    const seedFields: Record<string, boolean> = {};
    first.fields.slice(0, 3).forEach(f => { seedFields[`${first.name}.${f.name}`] = true; });
    const seedBuild = { classes: { [first.name]: true }, fields: seedFields, joins: [], filters: [], orders: [], distinct: false, aggregates: {} as Record<string, AggFn>, having: [] as HavingClause[], countAll: false };
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setBuild(seedBuild);
    setSelectedId(first.name);
    setQuery(buildOql(seedBuild, queryLimit));
    // Deliberately excludes catalogData.classes/query/queryLimit: the `query !== ''`
    // guard above means this seeds only once, while the workbench is still blank —
    // depending on `query` (which this effect itself sets) would fight the guard,
    // and depending on queryLimit/catalogData.classes would re-seed over user edits.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [catalog]);

  const parse = useMemo(() => parseQuery(query, catalog), [query, catalog]);
  const highlight = parse;
  const focus = selectedId ? catalogData.classByName[selectedId] : null;

  function cloneBuild(b: typeof build) {
    return { classes: { ...b.classes }, fields: { ...b.fields }, joins: [...b.joins], filters: [...b.filters], orders: [...b.orders], distinct: b.distinct, aggregates: { ...b.aggregates }, having: [...b.having], countAll: b.countAll };
  }

  /**
   * Land a builder change: drop the clauses it invalidated (an ORDER BY on a column no longer
   * selected, a HAVING on an aggregate that was removed — J5-F2, J5-F9), then write the text.
   */
  function commit(nb: typeof build): typeof build {
    const pruned = pruneBuild(nb, catalogData);
    syncQuery(pruned);
    return pruned;
  }

  function toggleDistinct() {
    setBuild(b => { const nb = cloneBuild(b); nb.distinct = !nb.distinct; return commit(nb); });
  }

  function setAggregate(fieldKey: string, fn: AggFn | '') {
    setBuild(b => {
      const nb = cloneBuild(b);
      if (fn) nb.aggregates[fieldKey] = fn as AggFn;
      else delete nb.aggregates[fieldKey];
      return commit(nb);
    });
  }

  function setCountAll(on: boolean) {
    setBuild(b => { const nb = cloneBuild(b); nb.countAll = on; return commit(nb); });
  }

  function addHaving(h: HavingClause) {
    setBuild(b => { const nb = cloneBuild(b); nb.having.push(h); return commit(nb); });
  }

  function removeHaving(id: string) {
    setBuild(b => { const nb = cloneBuild(b); nb.having = nb.having.filter(h => h.id !== id); return commit(nb); });
  }

  function syncQuery(nb: typeof build, lim = queryLimit) {
    setQuery(buildOql(nb, lim));
    setFreeEdited(false);
  }

  function onLimitChange(lim: number) {
    setQueryLimit(lim);
    if (!freeEdited) setQuery(buildOql(build, lim));
  }

  function addOrder(o: OrderClause) {
    setBuild(b => { const nb = cloneBuild(b); nb.orders.push(o); return commit(nb); });
  }
  function removeOrder(id: string) {
    setBuild(b => { const nb = cloneBuild(b); nb.orders = nb.orders.filter(o => o.id !== id); return commit(nb); });
  }
  function toggleOrderDir(id: string) {
    setBuild(b => {
      const nb = cloneBuild(b);
      nb.orders = nb.orders.map(o => o.id === id ? { ...o, dir: o.dir === 'ASC' ? 'DESC' : 'ASC' } as OrderClause : o);
      return commit(nb);
    });
  }

  function addClass(name: string) {
    setSelectedId(name);
    guardHandEdits();
    setBuild(b => {
      const nb = cloneBuild(b);
      nb.classes[name] = true;
      return commit(nb);
    });
  }

  function toggleField(fk: string) {
    guardHandEdits();
    const cls = fk.split('.')[0];
    setBuild(b => {
      const nb = cloneBuild(b);
      if (nb.fields[fk]) {
        delete nb.fields[fk];
        delete nb.aggregates[fk];
      } else {
        nb.fields[fk] = true;
      }
      nb.classes[cls] = true;
      return commit(nb);
    });
  }

  function addJoin(e: MappedEdge) {
    guardHandEdits();
    setBuild(b => {
      const nb = cloneBuild(b);
      const has = nb.joins.some(j => j._key === e._key);
      if (has) nb.joins = nb.joins.filter(j => j._key !== e._key);
      else nb.joins.push(e);
      nb.classes[e.from] = true; nb.classes[e.to] = true;
      return commit(nb);
    });
  }

  /** Add a WHERE condition to OR-group `groupIdx` (J5-F8); past the last group starts a new one. */
  function addFilter(f: Predicate, groupIdx = 0) {
    guardHandEdits();
    setBuild(b => {
      const nb = cloneBuild(b);
      nb.filters = insertIntoGroup(nb.filters, groupIdx, f);
      nb.classes[f.cls] = true;
      return commit(nb);
    });
  }

  function onLassoSelect(names: string[], lassoEdges: MappedEdge[]) {
    guardHandEdits();
    setBuild(b => {
      const nb = cloneBuild(b);
      names.forEach(n => { nb.classes[n] = true; });
      lassoEdges.forEach(e => {
        if (!nb.joins.some(j => j._key === e._key)) nb.joins.push(e);
      });
      return commit(nb);
    });
    if (names.length === 1) setSelectedId(names[0]);
  }

  function removeFilter(id: string) {
    setBuild(b => {
      const nb = cloneBuild(b);
      nb.filters = removeFromGroup(nb.filters, id);
      return commit(nb);
    });
  }

  /**
   * The inspector's "filter on this field" opens the WHERE draft on that field; nothing reaches the
   * query until a value is given. It used to commit `= ''` straight away — zero rows on a string
   * column, and a refusal on a number — a condition nobody had finished writing.
   */
  const [filterSeed, setFilterSeed] = useState<FilterSeed | null>(null);
  const seedCount = useRef(0);
  function seedFilter(fk: string) {
    const [cls, field] = fk.split('.');
    const c = catalogData.classByName[cls];
    if (!c) return;
    const fld = c.fields.find(x => x.name === field);
    seedCount.current += 1;
    setFilterSeed({ cls, field, type: fld ? fld.type : 'string', nonce: seedCount.current });
  }

  function clearAll() {
    offerUndo('Query cleared.');
    setBuild({ classes: {}, fields: {}, joins: [], filters: [], orders: [], distinct: false, aggregates: {}, having: [], countAll: false });
    setQuery('');
    setLastRun(null);
    setStopped(false);
    setFreeEdited(false);
    setGenSqlText('');
  }

  function onType(v: string) { 
    setQuery(v); 
    setFreeEdited(true); 
  }

  /**
   * Execute one statement at one offset and make it the run on screen.
   *
   * A failure replaces the result rather than sitting above the previous query's grid (R4): the
   * old grid answered a different question, and every action on it would have run that one.
   */
  async function execute(stmt: { text: string; lang: QueryLang }, offset: number) {
    cancelRef.current?.abort();
    const ctrl = new AbortController();
    cancelRef.current = ctrl;
    const seq = ++runSeq.current;
    setRunning(true);
    setStopped(false);
    setQueryError(null);
    setExplainResult(null);
    setExportResult(null);
    setResultTab('result');
    try {
      let result;
      let sql: string;
      if (stmt.lang === 'sql') {
        // Raw SQL is already the generated SQL — there is no rewrite to show.
        result = await api.runQuery(stmt.text, { signal: ctrl.signal, offset });
        sql = stmt.text;
      } else {
        const res = await api.runOql(stmt.text, { signal: ctrl.signal, offset });
        result = res.result;
        sql = res.sql;
      }
      if (seq !== runSeq.current) return;
      setLastRun({ text: stmt.text, lang: stmt.lang, schemaVersion: catalog?.versionNumber ?? null, result });
      setGenSqlText(sql);
      // History records the query, not the page — paging through one result should not
      // fill the history with N identical entries.
      if (offset === 0) {
        saveHistory(stmt.text);
        setQueryHistory(loadHistory());
      }
    } catch (e: unknown) {
      if (seq !== runSeq.current || errorMessage(e) === 'query cancelled') return;
      setLastRun(null);
      setQueryError(errorMessage(e, 'query failed'));
    } finally {
      if (seq === runSeq.current) {
        setRunning(false);
        cancelRef.current = null;
      }
    }
  }

  /** Run executes the editor text — the text is the query of record (R1). */
  function run() {
    const stmt = statementFor('run', { text: query, lang }, lastRun);
    if (stmt) void execute(stmt, 0);
  }

  /** The pager re-runs the query on screen at another offset, never the edited editor (J5-F1). */
  function page(offset: number) {
    const stmt = statementFor('page', { text: query, lang }, lastRun);
    if (stmt) void execute(stmt, offset);
  }

  /**
   * Run the query uncapped and download the whole result.
   *
   * Re-executes rather than assembling pages client-side: the pages are separate queries with no
   * snapshot isolation between them, so stitching them would produce a file that never existed as
   * a single consistent answer.
   */
  async function exportAll(format: ExportFormat) {
    const stmt = statementFor('export', { text: query, lang }, lastRun);
    if (!stmt || stmt.lang !== 'oql') return;
    setExporting(true);
    setExportResult(null);
    try {
      const rows = await api.exportOql(stmt.text, format);
      setExportResult({ tone: 'ok', text: exportNotice(rows, format, limitOf(stmt.text)) });
    } catch (e: unknown) {
      setExportResult({ tone: 'error', text: `Export failed: ${errorMessage(e, 'the request failed')}` });
    } finally {
      setExporting(false);
    }
  }

  // J5-F17: "copied" only when the write resolved; otherwise hand the link over to copy by hand.
  async function copyShareLink() {
    const url = buildShareUrl(query);
    if (await copyText(url)) {
      setShareFallback(null);
      setLinkCopied(true);
      setTimeout(() => setLinkCopied(false), 2000);
    } else {
      setShareFallback(url);
    }
  }

  function restoreHistory(entry: HistoryEntry) {
    // A draft that never ran is in no history, so this click would be its only copy's end (R2):
    // keep it twice over, as openShared does — the Undo pill now, and the history after.
    if (replacesDraft(query, entry.query)) {
      saveHistory(query);
      setQueryHistory(loadHistory());
      offerUndo('Restored from history. Your draft is in history.');
    }
    setQuery(entry.query);
    setFreeEdited(true);
    setHistoryOpen(false);
  }

  async function explain() {
    cancelRef.current?.abort();
    const ctrl = new AbortController();
    cancelRef.current = ctrl;
    const seq = ++runSeq.current;
    setRunning(true);
    setStopped(false);
    setQueryError(null);
    try {
      if (lang === 'sql') {
        // There is no plan to explain for a hand-written statement. What a SQL author actually
        // needs is the view stack they are querying — the real view names and column names —
        // which is what `explain` returns.
        const ddl = (await api.explainQuery()).join(';\n\n');
        if (seq !== runSeq.current) return;
        setGenSqlText(ddl);
      } else {
        const res = await api.rewriteOql(query, { signal: ctrl.signal });
        if (seq !== runSeq.current) return;
        setExplainResult(res);
        setGenSqlText(res.sql);
        setResultTab('explain');
      }
    } catch (e: unknown) {
      if (seq === runSeq.current && errorMessage(e) !== 'query cancelled') {
        setQueryError(errorMessage(e, 'explain failed'));
      }
    } finally {
      if (seq === runSeq.current) {
        setRunning(false);
        cancelRef.current = null;
      }
    }
  }

  const graphEl = (extra: Partial<React.ComponentProps<typeof GraphCanvas>> = {}) => (
    <GraphCanvas
      catalogData={catalogData}
      highlight={highlight}
      buildSelection={build}
      selectedId={selectedId}
      mode={layout === 'canvas' ? 'build' : 'explore'}
      onNodeClick={setSelectedId}
      onNodeAdd={addClass}
      onEdgeClick={addJoin}
      onLassoSelect={onLassoSelect}
      {...extra}
    />
  );

  const editorEl = (opts: { minRows?: number } = {}) => (
    <OqlEditor
      value={query} onChange={onType}
      onRun={run} onExplain={explain} onCancel={stopWaiting} running={running}
      catalog={catalog} catalogData={catalogData}
      focusClass={focus ? focus.name : null}
      minRows={opts.minRows || 5}
      limit={queryLimit} onLimitChange={onLimitChange}
      status={lang === 'oql' ? syncBadge : undefined}
    />
  );

  // The sources-tier authoring names, shown only in SQL mode: what a statement actually types.
  // Deduped by table name — a source class feeding several integrated classes is one table.
  const sourceTables = useMemo(() => {
    if (lang !== 'sql' || !catalog) return undefined;
    const byName = new Map<string, { name: string; datasetId: string; columns: string[] }>();
    for (const cls of catalog.classes) {
      for (const src of cls.sources) {
        if (src.sourceTable && !byName.has(src.sourceTable)) {
          byName.set(src.sourceTable, {
            name: src.sourceTable, datasetId: src.datasetId, columns: src.sourceColumns ?? [],
          });
        }
      }
    }
    return [...byName.values()].sort((a, b) => a.name.localeCompare(b.name));
  }, [lang, catalog]);

  const catalogEl = () =>
    catalogError && !catalog ? (
      <div className="panel" data-testid="catalog-error" style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div className="result-error"><span className="result-error-icon">⚠</span>{catalogError}</div>
        <button className="btn btn-ghost sm" onClick={loadCatalog} style={{ alignSelf: 'flex-start' }}>↻ retry</button>
      </div>
    ) : (
      <CatalogBrowser
        catalogData={catalogData}
        selectedId={selectedId} highlight={highlight} buildSelection={build}
        onSelect={setSelectedId} onAddClass={addClass}
        sourceTables={sourceTables}
      />
    );

  const inspectorEl = () => (
    <Inspector
      focus={focus} mode={layout === 'canvas' ? 'build' : 'explore'}
      buildSelection={build} catalogEdges={catalogData.edges}
      onToggleField={toggleField} onAddJoin={addJoin} onFilterField={lang === 'oql' ? seedFilter : undefined}
    />
  );

  const stale = editorDiverged(lastRun, query, lang);
  const resultEl = () => (
    <ResultPanel result={lastRun?.result ?? null} running={running} generatedSql={genSqlText} explainResult={explainResult}
      focus={focus} queryClasses={Object.keys(build.classes).map(n => catalogData.classByName[n]).filter(Boolean)}
      error={queryError} activeTab={resultTab} onTabChange={setResultTab}
      stale={stale} ranText={lastRun?.text} onRunCurrent={run} stopped={stopped}
      // Both of these are OQL-only paths: /api/query/export takes `oql`, and a data product is
      // materialised by `materialize-oql`. Withholding the callbacks removes the affordances
      // rather than leaving buttons that fail on click. They key off the language of the run on
      // screen, not the editor's toggle: the actions execute that run (R3).
      onSaveAsProduct={lastRun?.lang === 'oql' ? openProductDialog : undefined}
      onPage={page}
      onExportAll={lastRun?.lang === 'oql' ? (format) => void exportAll(format) : undefined} exporting={exporting}
      exportLimit={lastRun ? limitOf(lastRun.text) : null} exportNotice={exportResult} />
  );

  const filterEl = () => (
    <FilterBuilder
      filters={build.filters} classes={Object.keys(build.classes)}
      catalogData={catalogData} onAdd={addFilter} onRemove={removeFilter}
      orders={build.orders} orderOptions={orderOptions(build, catalogData)}
      onAddOrder={addOrder} onRemoveOrder={removeOrder} onToggleOrderDir={toggleOrderDir}
      distinct={build.distinct} onToggleDistinct={toggleDistinct}
      selectedFields={Object.keys(build.fields)}
      aggregates={build.aggregates} onSetAggregate={setAggregate}
      countAll={build.countAll} onSetCountAll={setCountAll}
      having={build.having} havingOptions={havingOptions(build)} onAddHaving={addHaving} onRemoveHaving={removeHaving}
      paused={freeEdited}
      onRebuild={Object.keys(build.classes).length > 0 ? rebuildFromBuilder : undefined}
      seed={filterSeed} onSeedResolved={() => setFilterSeed(null)}
    />
  );

  // Reuses the segmented-control classes rather than inventing a style, and sits inside the
  // editor panel — a language is a property of the editor, not of the workbench.
  const langToggle = (
    <div className="seg seg-lang" data-testid="wb-lang">
      {(['oql', 'sql'] as const).map(l => (
        <button
          key={l}
          className={`lay-btn${lang === l ? ' is-on' : ''}`}
          data-testid={`wb-lang-${l}`}
          title={l === 'oql' ? 'Schema-aware OQL over integrated classes' : 'Raw read-only DuckDB SQL over the source tables and int_ views'}
          onClick={() => setLang(l)}
        >{l.toUpperCase()}</button>
      ))}
    </div>
  );

  /** The shared-link choice and the undo pill, just above the editor they concern (J5-F4, J5-F7). */
  const editorNotices = (
    <>
      {pendingShared !== null && (
        <div className="wb-notice" role="status" data-testid="wb-shared-choice">
          <span>A shared query was opened. Your own draft is still in the editor.</span>
          <button type="button" className="btn btn-ghost sm" data-testid="wb-shared-open" onClick={openShared}>Open shared query</button>
          <button type="button" className="btn btn-ghost sm" data-testid="wb-shared-keep" onClick={() => setPendingShared(null)}>Keep my draft</button>
        </div>
      )}
      {undo && (
        <div className="wb-notice" role="status" data-testid="wb-undo">
          <span>{undo.label}</span>
          <button type="button" className="btn btn-ghost sm" data-testid="wb-undo-button" onClick={applyUndo}>Undo</button>
        </div>
      )}
    </>
  );

  const syncBadge = (
    <span className={`sync-badge${freeEdited ? ' edited' : ''}`}>
      <span className={`dot ${freeEdited ? 'dot-warn' : 'dot-ok'}`} />
      {freeEdited ? 'hand-edited — builder paused' : 'synced ↔ builder'}
    </span>
  );

  let body;
  if (layout === 'explore') {
    body = (
      <div className={`lay lay-explore${graphOpen ? '' : ' no-graph'}`}>
        <div className="col col-left">{catalogEl()}{inspectorEl()}</div>
        {graphOpen && (
          <div className="col col-graph">
            <div className="panel graph-panel">
              <CmdHead cmd="schema graph" />
              {graphEl()}
            </div>
          </div>
        )}
        <div className="col col-right">
          <div className="panel editor-panel">{langToggle}{editorNotices}{editorEl({ minRows: graphOpen ? 5 : 9 })}{lang === 'oql' && filterEl()}</div>
          {resultEl()}
        </div>
      </div>
    );
  } else {
    body = (
      <div className="lay lay-canvas">
        <div className="col col-left">{catalogEl()}{inspectorEl()}</div>
        <div className="col col-canvas">
          <div className="panel graph-panel hero">
            <CmdHead cmd="visual builder" right={<span>{Object.keys(build.classes).length} in query</span>} />
            {graphEl()}
          </div>
          <div className="canvas-dock">
            <div className="panel editor-panel gen">{langToggle}{editorNotices}{editorEl({ minRows: 4 })}{lang === 'oql' && filterEl()}</div>
            {resultEl()}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="workbench-app">
      <div className="workbench">
        <div className="wb-head">
          <div className="wb-title">
            <span className="wb-name">Query Workbench</span>
            <span className="wb-sub" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
              schema-aware OQL
              <InfoTip text={CONCEPTS.oql} label="OQL" />
            </span>
          </div>
          <div className="seg seg-lay">
            {LAYOUTS.map(l => (
              <button key={l.id} className={`lay-btn${layout === l.id ? ' is-on' : ''}`} onClick={() => setLayout(l.id)}>
                <span className="lay-tag">{l.tag}</span>{l.name}
              </button>
            ))}
          </div>
          {layout === 'explore' && (
            <button
              className={`btn btn-ghost sm${graphOpen ? ' is-active' : ''}`}
              data-testid="wb-toggle-graph"
              title="Show or hide the schema graph"
              onClick={() => setGraphOpen(o => !o)}
            >⬡ graph</button>
          )}
          <div style={{ flex: 1 }} />
          <span className="wb-stat">
            <span className={`dot ${catalog && catalog.versionNumber !== null ? 'dot-ok' : 'dot-warn'}`} />
            {catalog
              ? catalog.versionNumber !== null
                ? `schema v${catalog.versionNumber} · active`
                : 'schema — · none promoted'
              : 'schema — · loading'}
          </span>
          <button className={`btn btn-ghost sm${historyOpen ? ' is-active' : ''}`} onClick={() => setHistoryOpen(o => !o)}>
            ⎗ history{queryHistory.length > 0 ? ` · ${queryHistory.length}` : ''}
          </button>
          <button className="btn btn-ghost sm" onClick={() => void copyShareLink()}>
            {linkCopied ? '✓ copied' : '⎘ share'}
          </button>
          {shareFallback && (
            <ShareLinkFallback url={shareFallback} onDismiss={() => setShareFallback(null)} />
          )}
          <button className="btn btn-ghost sm" onClick={clearAll}>⌫ clear</button>
        </div>
        {catalog?.empty && <NoSchemaNotice />}
        {body}
        {productDialog && (
          <SaveAsProductDialog
            projects={projects}
            projectId={productTargetId}
            onProjectIdChange={setProductProjectId}
            name={productName}
            onNameChange={setProductName}
            description={productDescription}
            onDescriptionChange={setProductDescription}
            accessMode={productAccessMode}
            onAccessModeChange={setProductAccessMode}
            schemaVersionNumber={lastRun?.schemaVersion ?? catalog?.versionNumber ?? null}
            definition={lastRun?.lang === 'oql' ? lastRun.text : undefined}
            saving={productSaving}
            error={productError}
            savedId={productSavedId}
            onSave={() => void saveProduct()}
            onClose={() => setProductDialog(false)}
          />
        )}
        {historyOpen && (
          <>
            <div className="hist-backdrop" onClick={() => setHistoryOpen(false)} role="button" tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setHistoryOpen(false); }} />
            <div className="hist-panel">
              <div className="hist-head">
                <span className="hist-title">⎗ recent queries</span>
                <button className="hist-close" onClick={() => setHistoryOpen(false)}>×</button>
              </div>
              {queryHistory.length === 0 ? (
                <div className="hist-empty">no history yet — queries are saved after each run</div>
              ) : (
                <div className="hist-scroll">
                  {queryHistory.map((e, i) => (
                    <div key={i} className="hist-item" onClick={() => restoreHistory(e)} role="button" tabIndex={0} onKeyDown={(ev) => { if (ev.key === 'Enter' || ev.key === ' ') restoreHistory(e); }}>
                      <span className="hist-label">{e.label}</span>
                      <span className="hist-ts">{relTime(e.ts)}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

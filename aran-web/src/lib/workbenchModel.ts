import { QueryCatalog, CatalogClass } from '../services/types';

export interface WorkbenchField {
  name: string;
  type: string;
  key: boolean;
}

export interface WorkbenchClass extends CatalogClass {
  fields: WorkbenchField[];
}

export interface OqlParseResult {
  classes: Record<string, boolean>;
  fields: Record<string, boolean>;
  edges: Record<string, boolean>;
}

export interface Suggestion {
  label: string;
  kind: string;
  detail: string;
  replace: number;
  insert: string;
}

export interface Predicate {
  id: string;
  cls: string;
  field: string;
  type: string;
  op: string;
  value: string | number | boolean | null;
  or?: boolean; // true → OR with preceding group; false/undefined → AND
}

export interface OrderClause {
  id: string;
  cls: string;
  field: string;
  dir: 'ASC' | 'DESC';
  /**
   * Order by an aggregate's output alias (`ORDER BY n DESC`) rather than a class property. When
   * set, `cls`/`field` are ignored — an aggregate has no class column to name (J5-F9).
   */
  alias?: string;
}

export const AGG_FNS = ['COUNT', 'SUM', 'AVG', 'MIN', 'MAX'] as const;
export type AggFn = typeof AGG_FNS[number];

export interface HavingClause {
  id: string;
  fn: AggFn;
  field: string | null; // 'ClassName.field' or null for COUNT(*)
  op: string;
  value: string;
}

export interface BuildState {
  classes: Record<string, boolean>;
  fields: Record<string, boolean>;
  joins: MappedEdge[];
  filters: Predicate[];
  orders: OrderClause[];
  distinct: boolean;
  aggregates: Record<string, AggFn>; // fieldKey → aggregate function
  having: HavingClause[];
  /** `COUNT(*) AS n` in SELECT: rows per group, or the row total (J5-F9). */
  countAll?: boolean;
}

export interface MappedEdge {
  name: string;
  from: string;
  to: string;
  leftCol: string;
  rightCol: string;
  card?: string;
  _key?: string;
}

const KEYWORDS = ['SELECT', 'DISTINCT', 'FROM', 'WHERE', 'LIMIT', 'AND', 'OR', 'ORDER BY', 'GROUP BY', 'HAVING', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX'];

// Map QueryCatalog to unified classes and edges arrays for the UI
export function getCatalogData(catalog: QueryCatalog | null) {
  if (!catalog || catalog.empty) {
    return { classes: [] as WorkbenchClass[], edges: [], classByName: {} as Record<string, WorkbenchClass> };
  }
  const classByName: Record<string, WorkbenchClass> = {};
  const classes: WorkbenchClass[] = catalog.classes.map(c => {
    // Derive a flat fields list from the union of global columns across all sources.
    const seen = new Set<string>();
    const fields: WorkbenchField[] = [];
    c.sources.forEach(sv => {
      sv.columns.forEach(col => {
        if (!seen.has(col.globalName)) {
          seen.add(col.globalName);
          fields.push({ name: col.globalName, type: 'string', key: col.globalName === 'id' });
        }
      });
    });
    return { ...c, fields };
  });
  classes.forEach(c => classByName[c.name] = c);

  const edges: MappedEdge[] = [];
  // Removed duplicate: classes already built above
  catalog.objectProperties.forEach(op => {
    edges.push({
      name: op.name,
      from: op.domainClass,
      to: op.rangeClass,
      leftCol: op.name, // Mocked leftCol/rightCol for object properties if not strictly known, but we only use them for joins anyway
      rightCol: 'id',
      card: 'n..1'
    });
  });
  catalog.joinProperties.forEach(jp => {
    edges.push({
      name: jp.name,
      from: jp.leftClass,
      to: jp.rightClass,
      leftCol: jp.leftCol,
      rightCol: jp.rightCol,
      card: 'n..1'
    });
  });

  // Assign _key to edges
  edges.forEach((e, i) => {
    e._key = edgeKey(e, i);
  });

  return { classes, edges, classByName };
}

export function edgeKey(e: MappedEdge, i: number) {
  return `${e.from}.${e.name}.${e.to}#${i}`;
}

export function parseQuery(text: string, catalog: QueryCatalog | null): OqlParseResult {
  const refClasses: Record<string, boolean> = {};
  const refFields: Record<string, boolean> = {};
  const refEdges: Record<string, boolean> = {};
  if (!text || !catalog) return { classes: refClasses, fields: refFields, edges: refEdges };

  const { classByName, edges } = getCatalogData(catalog);

  // Class.field references
  const fieldRe = /\b([A-Z][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*|\*)/g;
  let m;
  while ((m = fieldRe.exec(text)) !== null) {
    const cls = m[1], fld = m[2];
    if (classByName[cls]) {
      refClasses[cls] = true;
      if (fld !== '*') refFields[`${cls}.${fld}`] = true;
    }
  }

  // Bare class references
  catalog.classes.forEach(c => {
    const re = new RegExp(`\\b${c.name}\\b`);
    if (re.test(text)) refClasses[c.name] = true;
  });

  // Relationship hops
  const hopRe = /([A-Z][A-Za-z0-9_]*)\s*(?:<-\[|-\[)\s*([a-zA-Z_][A-Za-z0-9_]*)\s*\]?(?:->|-)?\s*([A-Z][A-Za-z0-9_]*)?/g;
  while ((m = hopRe.exec(text)) !== null) {
    const a = m[1], prop = m[2], b = m[3];
    edges.forEach((e, i) => {
      if (e.name !== prop) return;
      const pair = [e.from, e.to];
      if ((pair.includes(a)) && (!b || pair.includes(b))) {
        refEdges[edgeKey(e, i)] = true;
      }
    });
  }
  return { classes: refClasses, fields: refFields, edges: refEdges };
}

export function suggest(text: string, caret: number, catalog: QueryCatalog | null): Suggestion[] {
  if (!catalog) return [];
  const { classes, edges, classByName } = getCatalogData(catalog);
  
  const before = text.slice(0, caret);
  const sugg: Suggestion[] = [];

  const dotMatch = before.match(/([A-Z][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)?$/);
  if (dotMatch && classByName[dotMatch[1]]) {
    const cls = classByName[dotMatch[1]];
    const frag = (dotMatch[2] || '').toLowerCase();
    // `*` last and only before a name is being typed: it is the rarest pick, and as the first item
    // it was what Tab or Enter inserted by default (J5-F3).
    const items = cls.fields
      .map(f => ({ name: f.name, type: f.key ? 'key' : 'field', detail: f.type }))
      .filter(it => it.name.toLowerCase().indexOf(frag) === 0)
      .concat(frag === '' ? [{ name: '*', type: 'all', detail: 'all properties' }] : []);
    return items
      .map(it => ({ label: it.name, kind: it.type, detail: it.detail, replace: frag.length, insert: it.name === '*' ? '*' : ident(it.name) }));
  }

  const relMatch = before.match(/([A-Z][A-Za-z0-9_]*)\s*<?-\[\s*([a-zA-Z_]*)$/);
  if (relMatch && classByName[relMatch[1]]) {
    const rfrag = (relMatch[2] || '').toLowerCase();
    const rels: { name: string, to: string, dir: string }[] = [];
    edges.forEach(e => {
      if (e.from === relMatch[1]) rels.push({ name: e.name, to: e.to, dir: '->' });
      if (e.to === relMatch[1]) rels.push({ name: e.name, to: e.from, dir: '<-' });
    });
    return rels
      .filter(r => r.name.toLowerCase().indexOf(rfrag) === 0)
      .map(r => ({ label: r.name, kind: 'relation', detail: r.dir + ' ' + r.to, replace: rfrag.length, insert: ident(r.name) }));
  }

  const wordMatch = before.match(/([A-Za-z_][A-Za-z0-9_]*)$/);
  const word = wordMatch ? wordMatch[1] : '';
  const wl = word.toLowerCase();

  KEYWORDS.forEach(k => {
    if (k.toLowerCase().indexOf(wl) === 0 && wl.length > 0) {
      sugg.push({ label: k, kind: 'keyword', detail: 'keyword', replace: word.length, insert: k + ' ' });
    }
  });

  classes.forEach(c => {
    if (c.name.toLowerCase().indexOf(wl) === 0) {
      sugg.push({ label: c.name, kind: 'class', detail: c.fields.length + ' props', replace: word.length, insert: ident(c.name) });
    }
  });

  if (sugg.length === 0 && wl.length === 0) {
    classes.forEach(c => {
      sugg.push({ label: c.name, kind: 'class', detail: c.fields.length + ' props', replace: 0, insert: ident(c.name) });
    });
  }

  return sugg;
}

const BARE_IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * A class, property or alias name as OQL spells it: bare when it is an identifier, double-quoted
 * (with `""` for a quote) otherwise. Keywords are contextual in OQL, so a keyword-spelled name
 * needs no quoting; a header like `first-name` does, or the builder writes text that cannot parse.
 */
export function ident(name: string): string {
  return BARE_IDENT.test(name) ? name : `"${name.replace(/"/g, '""')}"`;
}

function ref(cls: string, field: string): string {
  return `${ident(cls)}.${ident(field)}`;
}

/** The alias `COUNT(*)` is selected under. */
export const COUNT_ALL_ALIAS = 'n';

/** One aggregate the SELECT list computes, under the alias it is emitted with. */
export interface AggregateOutput {
  fn: AggFn;
  /** `Class.field`, or null for `COUNT(*)`. */
  field: string | null;
  alias: string;
}

/**
 * The SELECT list's aggregates, in emission order, each with a unique alias.
 *
 * Aliased because an un-aliased aggregate's output is named after its function — two `COUNT`s
 * collide — and because the grammar can order only by a projection's name, so an aggregate
 * without one cannot be sorted on (J5-F9: "venues by paper count, most first" was unbuildable).
 * The one definition `buildOql`, `orderOptions` and `havingOptions` all read, so the alias a sort
 * names is the alias the SELECT list emits.
 */
export function aggregateOutputs(state: BuildState): AggregateOutput[] {
  const taken = new Set<string>();
  for (const k of Object.keys(state.fields || {})) {
    if (!(state.aggregates || {})[k]) taken.add(k.split('.')[1]);
  }
  const unique = (base: string) => {
    let alias = base;
    for (let i = 2; taken.has(alias); i++) alias = `${base}_${i}`;
    taken.add(alias);
    return alias;
  };
  const out: AggregateOutput[] = [];
  for (const cls of Object.keys(state.classes || {})) {
    for (const fk of Object.keys(state.fields || {}).filter(k => k.startsWith(cls + '.'))) {
      const fn = (state.aggregates || {})[fk];
      if (!fn) continue;
      const base = `${fn.toLowerCase()}_${fk.split('.')[1]}`.replace(/[^A-Za-z0-9_]/g, '_');
      out.push({ fn, field: fk, alias: unique(base) });
    }
  }
  if (state.countAll) out.push({ fn: 'COUNT', field: null, alias: unique(COUNT_ALL_ALIAS) });
  return out;
}

function aggregates(state: BuildState): boolean {
  return Object.keys(state.aggregates || {}).length > 0 || !!state.countAll;
}

/**
 * The completion list's state. `moved` records that the user moved the highlight — the only
 * condition under which Enter accepts, so an Enter typed to end a line is a newline (J5-F3).
 */
export interface CompletionState {
  open: boolean;
  items: Suggestion[];
  idx: number;
  moved: boolean;
}

export const CLOSED_COMPLETION: CompletionState = { open: false, items: [], idx: 0, moved: false };

/**
 * The list after an edit, computed from THAT edit's text and caret.
 *
 * It used to be computed a tick later from the previous render's text, so typing `IPaper` then
 * Enter accepted a suggestion for `IPape` and wrote `IIPaper`, and `-[` offered classes because
 * the list was built before the `[` landed (J5-F3). Opens by itself only after `Class.` or `-[`;
 * otherwise it only narrows a list already open, and closes at whitespace.
 */
export function completeOnInput(
  prev: CompletionState, value: string, caret: number, catalog: QueryCatalog | null,
): CompletionState {
  const before = value.slice(0, caret);
  const trigger = /[A-Za-z0-9_"]\.$/.test(before) || /-\[\s*$/.test(before);
  if (!trigger && (!prev.open || /\s$/.test(before))) return CLOSED_COMPLETION;
  const items = suggest(value, caret, catalog);
  return items.length > 0 ? { open: true, items, idx: 0, moved: false } : CLOSED_COMPLETION;
}

/** An explicit request (Ctrl+Space) opens the list in any context. */
export function completeOnRequest(value: string, caret: number, catalog: QueryCatalog | null): CompletionState {
  const items = suggest(value, caret, catalog);
  return items.length > 0 ? { open: true, items, idx: 0, moved: false } : CLOSED_COMPLETION;
}

/** What a key does to an open list; `pass` lets the key reach the text as typed. */
export function completionKey(
  state: CompletionState, key: string,
): 'next' | 'prev' | 'accept' | 'close' | 'pass' {
  if (!state.open) return 'pass';
  switch (key) {
    case 'ArrowDown': return 'next';
    case 'ArrowUp': return 'prev';
    case 'Tab': return 'accept';
    case 'Enter': return state.moved ? 'accept' : 'close';
    case 'Escape': return 'close';
    default: return 'pass';
  }
}

export function buildOql(state: BuildState, limit = 100): string {
  const classes = Object.keys(state.classes || {});
  const fields = state.fields || {};
  const joins = state.joins || [];
  const filters = state.filters || [];
  const orders = state.orders || [];
  const distinct = state.distinct || false;
  const having = state.having || [];
  if (classes.length === 0) return '';

  const selParts: string[] = [];
  const groupByParts: string[] = [];
  const hasAgg = aggregates(state);
  const aliasOf = new Map(aggregateOutputs(state).filter(a => a.field).map(a => [a.field as string, a.alias]));

  classes.forEach(cn => {
    const picked = Object.keys(fields).filter(k => k.startsWith(cn + '.'));
    if (picked.length === 0) {
      // A join or a filter puts a class on the canvas without picking any of its fields. Beside
      // an aggregate its star is neither aggregated nor grouped, which the planner refuses, so
      // the class stays in FROM/WHERE and contributes no columns.
      if (!hasAgg) selParts.push(`${ident(cn)}.*`);
    } else {
      picked.forEach(p => {
        const [pc, pf] = p.split('.');
        const fn = (state.aggregates || {})[p];
        if (fn) {
          selParts.push(`${fn}(${ref(pc, pf)}) AS ${ident(aliasOf.get(p) as string)}`);
        } else {
          selParts.push(ref(pc, pf));
          if (hasAgg) groupByParts.push(ref(pc, pf));
        }
      });
    }
  });
  if (state.countAll) {
    const countAll = aggregateOutputs(state).find(a => a.field === null);
    selParts.push(`COUNT(*) AS ${ident(countAll ? countAll.alias : COUNT_ALL_ALIAS)}`);
  }

  // Build FROM with topologically-sorted join chain
  let fromStr: string;
  if (joins.length === 0) {
    fromStr = classes.map(ident).join(', ');
  } else {
    const chain = sortJoinChain(joins);
    const anchor = chain[0].from;
    fromStr = ident(anchor);
    chain.forEach(j => { fromStr += ` -[${ident(j.name)}]-> ${ident(j.to)}`; });
  }

  const lines = [];
  lines.push(`SELECT${distinct ? ' DISTINCT' : ''} ` + selParts.join(', '));
  lines.push('FROM ' + fromStr);

  if (filters.length) {
    let whereStr = formatPredicate(filters[0]);
    for (let i = 1; i < filters.length; i++) {
      whereStr += (filters[i].or ? '\n  OR ' : '\n  AND ') + formatPredicate(filters[i]);
    }
    lines.push('WHERE ' + whereStr);
  }

  if (hasAgg && groupByParts.length > 0) {
    lines.push('GROUP BY ' + groupByParts.join(', '));
  }

  if (having.length > 0) {
    lines.push('HAVING ' + having.map(fmtHaving).join('\n  AND '));
  }

  if (orders.length) {
    lines.push('ORDER BY ' + orders.map(o => `${o.alias ? ident(o.alias) : ref(o.cls, o.field)} ${o.dir}`).join(', '));
  }

  lines.push('LIMIT ' + limit);
  return lines.join('\n');
}

/** One column the query outputs, which is all ORDER BY can name (the planner refuses the rest). */
export interface OrderOption {
  label: string;
  cls: string;
  field: string;
  alias?: string;
}

/**
 * What ORDER BY may offer: the query's outputs and nothing else (J5-F9). The planner refuses an
 * ORDER BY key that is not projected ("not found in SELECT output"), and the picker used to list
 * every field of every class in the query while never listing an aggregate.
 *
 * Grouped: the group keys, then the aggregate aliases. Ungrouped: each picked field, and every
 * field of a class selected whole (`Class.*`).
 */
export function orderOptions(
  state: BuildState,
  catalogData: { classByName: Record<string, WorkbenchClass> },
): OrderOption[] {
  const out: OrderOption[] = [];
  const hasAgg = aggregates(state);
  for (const cls of Object.keys(state.classes || {})) {
    const picked = Object.keys(state.fields || {}).filter(k => k.startsWith(cls + '.'));
    if (picked.length === 0) {
      if (hasAgg) continue;
      for (const f of catalogData.classByName[cls]?.fields ?? []) {
        out.push({ label: `${cls}.${f.name}`, cls, field: f.name });
      }
      continue;
    }
    for (const fk of picked) {
      if ((state.aggregates || {})[fk]) continue;
      out.push({ label: fk, cls, field: fk.split('.')[1] });
    }
  }
  for (const a of aggregateOutputs(state)) out.push({ label: a.alias, cls: '', field: '', alias: a.alias });
  return out;
}

/** One aggregate a HAVING predicate may filter on. */
export interface HavingOption {
  label: string;
  fn: AggFn;
  field: string | null;
}

/**
 * What HAVING may filter on: exactly the SELECT list's aggregates (J5-F2). Without one there are
 * no groups — the planner used to drop such a HAVING and return every row under a chip that read
 * "having COUNT(*) > 5", and now refuses it. `COUNT(*)` is offered only when it is selected: the
 * planner refuses a HAVING aggregate the SELECT list does not compute.
 */
export function havingOptions(state: BuildState): HavingOption[] {
  return aggregateOutputs(state).map(a => ({
    label: a.field ? `${a.fn}(${a.field})` : `${a.fn}(*)`,
    fn: a.fn,
    field: a.field,
  }));
}

/**
 * Drop the ORDER BY and HAVING clauses the current selection no longer supports — an order on a
 * column that stopped being an output, a HAVING on an aggregate that was removed. Applied after
 * every builder change, so a clause leaves with its cause instead of turning into a query the
 * planner refuses.
 */
export function pruneBuild<T extends BuildState>(
  state: T,
  catalogData: { classByName: Record<string, WorkbenchClass> },
): T {
  const outputs = orderOptions(state, catalogData);
  const validOrder = (o: OrderClause) => outputs.some(opt =>
    o.alias ? opt.alias === o.alias : (!opt.alias && opt.cls === o.cls && opt.field === o.field));
  const aggs = havingOptions(state);
  const validHaving = (h: HavingClause) => aggs.some(a => a.fn === h.fn && a.field === h.field);
  return {
    ...state,
    orders: (state.orders || []).filter(validOrder),
    having: (state.having || []).filter(validHaving),
  };
}

/**
 * The WHERE filters as the parser reads them: AND binds tighter than OR, so each OR starts a new
 * group and a row matches when every condition of any one group holds (J5-F8). The chips used to
 * read left to right with no grouping on screen, so "venue = VLDB AND year = 2023 OR year = 2024"
 * looked like a venue filter while it returned every 2024 paper of every venue.
 */
export function filterGroups(filters: Predicate[]): Predicate[][] {
  const groups: Predicate[][] = [];
  filters.forEach((f, i) => {
    if (i === 0 || f.or) groups.push([f]);
    else groups[groups.length - 1].push(f);
  });
  return groups;
}

/** Insert a condition at the end of OR-group `groupIdx`; an index past the last group starts a new one. */
export function insertIntoGroup(filters: Predicate[], groupIdx: number, pred: Predicate): Predicate[] {
  const groups = filterGroups(filters);
  if (groupIdx >= groups.length) {
    return [...filters, { ...pred, or: filters.length > 0 }];
  }
  groups[groupIdx] = [...groups[groupIdx], { ...pred, or: false }];
  return groups.flatMap((g, gi) => g.map((p, pi) => ({ ...p, or: gi > 0 && pi === 0 })));
}

/**
 * Remove one condition, keeping every group's boundary where it was. A group starts at the
 * predicate whose `or` flag is set, so removing that head by id alone would fold the rest of its
 * group into the previous one — an OR the user never touched silently becoming an AND (J5-F8).
 */
export function removeFromGroup(filters: Predicate[], id: string): Predicate[] {
  return filterGroups(filters)
    .map(g => g.filter(p => p.id !== id))
    .filter(g => g.length > 0)
    .flatMap((g, gi) => g.map((p, pi) => ({ ...p, or: gi > 0 && pi === 0 })));
}

function sortJoinChain(joins: MappedEdge[]): MappedEdge[] {
  if (joins.length <= 1) return joins;
  const toSet = new Set(joins.map(j => j.to));
  const roots = joins.filter(j => !toSet.has(j.from));
  if (roots.length === 0) return joins; // cyclic — return as-is
  const sorted: MappedEdge[] = [];
  const visited = new Set<string>();
  let cur: MappedEdge | undefined = roots[0];
  while (cur) {
    if (visited.has(cur._key ?? cur.name)) break;
    visited.add(cur._key ?? cur.name);
    sorted.push(cur);
    const prev: MappedEdge = cur;
    cur = joins.find(j => j.from === prev.to && !(visited.has(j._key ?? j.name)));
  }
  return sorted.length === joins.length ? sorted : joins;
}

export function fmtHaving(h: HavingClause): string {
  const expr = h.field ? `${h.fn}(${ref(h.field.split('.')[0], h.field.split('.')[1])})` : `${h.fn}(*)`;
  return `${expr} ${h.op} ${h.value}`;
}

const NUMERIC = /^-?\d+(\.\d+)?$/;

/**
 * One WHERE condition as OQL text. A string literal doubles its quotes (`''`) instead of ending
 * early at the first one; a number is written bare only when it is one; a boolean is a keyword.
 * Anything else is quoted as written and left for the server to refuse by name ("literal '12abc'
 * is not a valid BIGINT value") — the builder used to write `0` for an empty or garbled number,
 * a filter nobody asked for.
 */
export function formatPredicate(f: Predicate): string {
  const lhs = ref(f.cls, f.field);
  if (f.op === 'IS NULL' || f.op === 'IS NOT NULL') return `${lhs} ${f.op}`;
  const v = f.value == null ? '' : String(f.value).trim();
  const quoted = `'${v.replace(/'/g, "''")}'`;
  let literal = quoted;
  if ((f.type === 'decimal' || f.type === 'integer') && NUMERIC.test(v)) literal = v;
  else if (f.type === 'boolean' && /^(true|false)$/i.test(v)) literal = v.toUpperCase();
  return `${lhs} ${f.op} ${literal}`;
}

// Only operators OqlParser accepts. LIKE, IN and BETWEEN used to be offered here and were
// refused by the backend on Run — D6 keeps them out of the grammar until v1.1, so the builder
// must not offer them either.
export const OPS_BY_TYPE: Record<string, string[]> = {
  string:   ['=', '!=', 'IS NULL', 'IS NOT NULL'],
  date:     ['=', '>', '<', '>=', '<=', 'IS NULL', 'IS NOT NULL'],
  datetime: ['=', '>', '<', '>=', '<=', 'IS NULL', 'IS NOT NULL'],
  decimal:  ['=', '!=', '>', '<', '>=', '<=', 'IS NULL', 'IS NOT NULL'],
  boolean:  ['=', '!=', 'IS NULL', 'IS NOT NULL'],
};

export function opsForType(type: string): string[] {
  return OPS_BY_TYPE[type] || ['=', '!='];
}

export function valueHints(_className: string, _fieldName: string): string[] {
  return [];
}

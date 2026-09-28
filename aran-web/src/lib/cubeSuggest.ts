import type {
  Additivity,
  CubeCalendarDto,
  CubeDefinitionDto,
  CubeEdgeTier,
  CubeEvidence,
  CubeEvidenceClass,
  CubeEvidenceEdge,
} from '@/services/types';

/**
 * The evidence → suggestion half of the "shape as cube" wizard.
 *
 * Everything here is pure and total, so the ranking rules, the additivity guesses and the
 * hierarchy screen are unit-testable without a DOM, a backend or a click — the dialog is a
 * shell around these functions. The `DatasetConstraintsTab` precedent governs all of it: a
 * suggestion is display-only until a person ticks it, and each carries the reason it was
 * made *in words*, so the UI never has to invent one.
 *
 * A suggestion is also never a silent exclusion. A join key is returned as an untickable
 * measure with its reason rather than filtered out of the list, because "where did my
 * column go" is the question a modelling tool must not leave a user holding.
 */

/** The server's own rule for a cube or dimension name — restated so the wizard can refuse
 *  before it POSTs rather than turning a 400 into a mystery at the last step. */
export const CUBE_NAME_PATTERN = /^[a-z][a-z0-9_]{0,62}$/;

export function isValidCubeName(name: string): boolean {
  return CUBE_NAME_PATTERN.test(name);
}

/**
 * Best-effort coercion of a label or IRI local name into a legal cube/dimension name.
 * Returns `''` when nothing legal survives (a name that is all digits, say) — the caller
 * asks the person rather than inventing something they never chose.
 */
export function toCubeName(raw: string): string {
  const slug = raw
    // camelCase carries the word boundary the rest of the pipeline would otherwise lose:
    // orderDate → order_date, not orderdate.
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/_{2,}/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 63)
    .replace(/_+$/, '');
  return /^[a-z]/.test(slug) ? slug : '';
}

// ── Step 1: which class is the fact ──────────────────────────────────────────

export interface FactCandidate {
  cls: CubeEvidenceClass;
  /** Higher is more fact-shaped. Comparable only within one evidence payload. */
  score: number;
  rowCount: number | null;
  /** Numeric columns that are not join keys — the columns that could be measures. */
  numericCount: number;
  edgesOut: number;
  edgesIn: number;
  /** Set only when the class was demoted, in the words the UI shows. */
  demotedReason: string | null;
}

/** Log-scaled so a 10M-row class cannot swamp the two signals that actually separate a fact
 *  from a merely large dimension. A class with no profile scores 0 — no evidence, not zero
 *  rows. */
function rowScore(rowCount: number | null): number {
  return rowCount == null ? 0 : Math.log10(Math.max(rowCount, 1));
}

/** Every column that some edge joins on, per class — the columns that are keys rather than
 *  measures or levels. */
function joinColumnsByClass(edges: readonly CubeEvidenceEdge[]): Map<string, Set<string>> {
  const byClass = new Map<string, Set<string>>();
  const add = (classIri: string, colIri: string) => {
    const set = byClass.get(classIri) ?? new Set<string>();
    set.add(colIri);
    byClass.set(classIri, set);
  };
  edges.forEach((e) => {
    add(e.leftClassIri, e.leftColIri);
    add(e.rightClassIri, e.rightColIri);
  });
  return byClass;
}

/**
 * Rank the integrated classes by how fact-shaped they are — volume, measurable columns and
 * reach, in that order of weight.
 *
 * A class that is mostly an edge *target* is demoted rather than hidden, and carries the
 * reason ("referenced by 3 classes — looks like a dimension"): the heuristic is a hint, and
 * a user who knows their model must be able to overrule it without hunting for the row.
 */
export function rankFactCandidates(evidence: CubeEvidence): FactCandidate[] {
  const joinCols = joinColumnsByClass(evidence.edges);
  return evidence.classes
    .map((cls) => {
      const keys = joinCols.get(cls.iri) ?? new Set<string>();
      const numericCount = cls.properties.filter((p) => p.numeric && !keys.has(p.iri)).length;
      // Self-edges count for neither direction: they say nothing about whether this class
      // is the one others point at.
      const edgesOut = evidence.edges.filter(
        (e) => e.leftClassIri === cls.iri && e.rightClassIri !== cls.iri,
      ).length;
      const edgesIn = evidence.edges.filter(
        (e) => e.rightClassIri === cls.iri && e.leftClassIri !== cls.iri,
      ).length;
      const base = rowScore(cls.rowCount) + numericCount * 2 + edgesOut * 3;
      const demoted = edgesIn > 0 && edgesIn > edgesOut;
      return {
        cls,
        score: demoted ? base / 4 : base,
        rowCount: cls.rowCount,
        numericCount,
        edgesOut,
        edgesIn,
        demotedReason: demoted
          ? `referenced by ${edgesIn} class${edgesIn === 1 ? '' : 'es'} — looks like a dimension`
          : null,
      };
    })
    // Name breaks ties so the list is stable across reloads — a ranking that reshuffles
    // itself between two identical payloads is not a ranking a user can trust.
    .sort((a, b) => b.score - a.score || a.cls.name.localeCompare(b.cls.name));
}

// ── Step 2: measures ─────────────────────────────────────────────────────────

export interface MeasureDraft {
  columnIri: string;
  name: string;
  aggregator: string;
  additivity: Additivity;
  selected: boolean;
  /** True for a column offered only so its exclusion is visible (a join key). */
  excluded: boolean;
  /** Why this column is offered the way it is — null when it is the plain SUM case. */
  reason: string | null;
}

/** The unit-price shape: a per-row rate whose SUM means nothing and whose AVG is the
 *  average-of-averages trap. Offered unticked with the reason, not hidden. */
const RATIO_SHAPED = /_price$|_pct$|_rate$/;

export const AGGREGATORS = ['SUM', 'AVG', 'MIN', 'MAX', 'COUNT'] as const;

/** RATIO is deliberately absent: a RATIO measure is column-less and carries a
 *  post-aggregation expr the wizard has no input for, so offering it here could only
 *  produce a definition the server rejects. The wire type keeps RATIO for cubes
 *  authored elsewhere. */
export const ADDITIVITIES: readonly Additivity[] = [
  'ADDITIVE',
  'SEMI_ADDITIVE',
  'NON_ADDITIVE',
];

/** At least this share of distinct values over rows reads as an identifier (J6-F21). */
const IDENTIFIER_SHARE = 0.95;

function looksLikeIdentifier(p: CubeEvidenceClass['properties'][number], rowCount: number | null): boolean {
  return rowCount !== null && rowCount > 0 && p.ndv !== null && p.ndv >= IDENTIFIER_SHARE * rowCount;
}

/**
 * The column a row count is taken over: one profiled as never NULL, so COUNT(column) IS the
 * number of fact rows (J6-F14). Preference: an identifier-shaped column, then any other column
 * that is not a plain numeric measure (a ticked SUM is never consumed), then a join key.
 * Null — no row count offered — when no column is provably never NULL: a COUNT over a column
 * with NULLs is not a row count, and calling it one would be a wrong number with a right name.
 */
function rowCountColumn(cls: CubeEvidenceClass, keys: ReadonlySet<string>): string | null {
  const neverNull = cls.properties.filter((p) => p.nullFraction === 0);
  const isPlainMeasure = (p: CubeEvidenceClass['properties'][number]) =>
    p.numeric && !keys.has(p.iri) && !looksLikeIdentifier(p, cls.rowCount);
  const ranked = [
    ...neverNull.filter((p) => !keys.has(p.iri) && looksLikeIdentifier(p, cls.rowCount)),
    ...neverNull.filter((p) => !keys.has(p.iri) && !isPlainMeasure(p)),
    ...neverNull.filter((p) => keys.has(p.iri)),
  ];
  return ranked[0]?.iri ?? null;
}

/**
 * Measure candidates for a fact class.
 *
 * - A plain numeric column is pre-ticked as SUM / ADDITIVE.
 * - An identifier-shaped numeric column (≥ 95 % distinct) is offered as COUNT, unticked: a SUM
 *   of ids is a number and a meaningless one (J6-F21).
 * - A non-numeric column is offered as COUNT only, unticked — the one aggregator the server
 *   defines over it (CubeService.validate). A fact made only of CSV columns (all xsd:string)
 *   used to offer nothing at all and block Continue (J6-F14).
 * - One never-NULL column carries `row_count`, pre-ticked only when nothing else is.
 * - A numeric join key is shown excluded, with the reason, rather than omitted.
 *
 * `joinColumnIris` is what makes "non-key" real; called without it every column is a
 * candidate, which is the right answer for a class with no edges at all.
 */
export function suggestMeasures(
  cls: CubeEvidenceClass,
  joinColumnIris: readonly string[] = [],
): MeasureDraft[] {
  const keys = new Set(joinColumnIris);
  const rowCountIri = rowCountColumn(cls, keys);
  const drafts: MeasureDraft[] = [];
  for (const p of cls.properties) {
    const slug = toCubeName(p.name) || p.name;
    if (p.iri === rowCountIri) {
      drafts.push({
        columnIri: p.iri,
        name: 'row_count',
        aggregator: 'COUNT',
        additivity: 'ADDITIVE',
        selected: false,
        excluded: false,
        reason: `counts fact rows — ${p.name} is never NULL`,
      });
    } else if (keys.has(p.iri)) {
      if (!p.numeric) continue;
      drafts.push({
        columnIri: p.iri,
        name: slug,
        aggregator: 'SUM',
        additivity: 'ADDITIVE',
        selected: false,
        excluded: true,
        reason: 'join key — summing it would add identifiers, not amounts',
      });
    } else if (!p.numeric) {
      drafts.push({
        columnIri: p.iri,
        name: `${slug}_count`,
        aggregator: 'COUNT',
        additivity: 'ADDITIVE',
        selected: false,
        excluded: false,
        reason: 'not numeric — only COUNT (of its non-empty values) is defined over it',
      });
    } else if (looksLikeIdentifier(p, cls.rowCount)) {
      drafts.push({
        columnIri: p.iri,
        name: `${slug}_count`,
        aggregator: 'COUNT',
        additivity: 'ADDITIVE',
        selected: false,
        excluded: false,
        reason: 'looks like an identifier (≥ 95 % distinct) — a SUM of ids is meaningless, so COUNT is offered instead',
      });
    } else if (RATIO_SHAPED.test(p.name)) {
      drafts.push({
        columnIri: p.iri,
        name: slug,
        aggregator: 'AVG',
        additivity: 'NON_ADDITIVE',
        selected: false,
        excluded: false,
        reason: 'per-unit or ratio-shaped — a SUM is meaningless and an AVG averages averages; a true ratio needs a post-aggregation expression this wizard cannot author yet',
      });
    } else {
      drafts.push({
        columnIri: p.iri,
        name: slug,
        aggregator: 'SUM',
        additivity: 'ADDITIVE',
        selected: true,
        excluded: false,
        reason: null,
      });
    }
  }
  // A cube needs at least one measure; when no real one is pre-ticked, the row count is the
  // honest default — COUNT is meaningful over any fact.
  const withDefault = drafts.some((d) => d.selected)
    ? drafts
    : drafts.map((d) => (d.name === 'row_count' ? { ...d, selected: true } : d));
  // Two columns can slug to one measure name (totalAmount / total_amount) and the server
  // rejects duplicates with a 400 the dialog has no name field to recover from — suffix
  // the later one instead.
  return withDefault.map((draft, i, all) => {
    const clashes = all.slice(0, i).filter((d) => d.name === draft.name).length;
    return clashes === 0 ? draft : { ...draft, name: `${draft.name}_${clashes + 1}` };
  });
}

// ── Step 2: dimensions ───────────────────────────────────────────────────────

export interface HierarchyLevel {
  iri: string;
  name: string;
  ndv: number;
}

export interface DimensionDraft {
  /** Stable identity for a draft: the same class reached by two joins is two dimensions. */
  key: string;
  classIri: string;
  className: string;
  name: string;
  joinPropertyIri: string;
  joinPropertyName: string;
  tier: CubeEdgeTier;
  /**
   * Whether the join is provably to-one *from the fact* — tier AND direction, mirroring the
   * server's `EvidenceEdge.provablyToOneFrom`. This, not the tier, is what pre-ticks a row.
   */
  provablyToOne: boolean;
  /** The fact-side join column — its null fraction is what the unknown member is for. */
  factColIri: string;
  /** The dimension-side join column — never offered as a hierarchy level. */
  dimColIri: string;
  nullFraction: number | null;
  unknownMember: boolean;
  selected: boolean;
  hierarchy: string[];
  levelOptions: HierarchyLevel[];
  /** The trust tier said in words — the same sentence the UI shows under the checkbox. */
  reason: string;
}

const TIER_RANK: Record<CubeEdgeTier, number> = { VERIFIED: 0, DECLARED: 1, HYPOTHESIS: 2 };

const TIER_REASON: Record<CubeEdgeTier, string> = {
  VERIFIED: 'verified to-one — the data itself was checked',
  DECLARED: 'declared foreign key — the source says it is to-one',
  HYPOTHESIS: 'not provably to-one — joining on it can double-count fact rows',
};

/**
 * Said in words when a trusted edge is read from its referenced end: the tier is fine, the
 * direction is not, and the consequence is the same double-counting `HYPOTHESIS` warns about.
 */
const REVERSED_REASON =
  'the fact is the referenced side of this foreign key — one fact row matches many, so every ' +
  'measure sliced by it is counted once per match';

/** The tiers a dimension is pre-ticked for. `HYPOTHESIS` ships unticked: the stated reason
 *  is double-counting, not vague mistrust. */
export function isTrustedTier(tier: CubeEdgeTier): boolean {
  // Allowlist, not a blocklist: the server keeps tier an open string so a future tier
  // can appear without a redeploy — and an unknown tier must fail CLOSED (unticked),
  // never arrive pre-trusted.
  return tier === 'DECLARED' || tier === 'VERIFIED';
}

/**
 * Dimension candidates for a fact: every accepted join edge that touches it, ordered by
 * trust tier so the UI can group under `VERIFIED`/`DECLARED`/`HYPOTHESIS` headers.
 *
 * A self-edge is skipped — a class joined to itself is a recursive hierarchy, which this
 * wizard has no way to express and would otherwise offer as a nonsense dimension.
 */
export function suggestDimensions(evidence: CubeEvidence, factIri: string): DimensionDraft[] {
  const classByIri = new Map(evidence.classes.map((c) => [c.iri, c]));
  const fact = classByIri.get(factIri);
  const nullFractionOf = (colIri: string) =>
    fact?.properties.find((p) => p.iri === colIri)?.nullFraction ?? null;

  const drafts = evidence.edges
    .filter((e) => e.leftClassIri === factIri || e.rightClassIri === factIri)
    .filter((e) => e.leftClassIri !== e.rightClassIri)
    .map((e) => {
      const outgoing = e.leftClassIri === factIri;
      const dimIri = outgoing ? e.rightClassIri : e.leftClassIri;
      const factColIri = outgoing ? e.leftColIri : e.rightColIri;
      const dimColIri = outgoing ? e.rightColIri : e.leftColIri;
      const dimClass = classByIri.get(dimIri);
      // S17-1: an edge is FK → PK, so it is to-one only when the FACT holds the foreign key.
      // A DECLARED edge traversed the other way multiplies fact rows exactly as a HYPOTHESIS
      // one does, and the tier alone cannot say which end you are standing on.
      const provablyToOne = outgoing && isTrustedTier(e.tier);
      const nullFraction = nullFractionOf(factColIri);
      const levels = dimClass ? suggestHierarchy(dimClass, dimColIri) : [];
      return {
        key: `${dimIri}|${e.propertyIri}`,
        classIri: dimIri,
        className: dimClass?.name ?? dimIri,
        name: '',
        joinPropertyIri: e.propertyIri,
        joinPropertyName: e.propertyName,
        tier: e.tier,
        provablyToOne,
        factColIri,
        dimColIri,
        nullFraction,
        // A NULL FK is a fact row with no dimension member. Without an unknown member it
        // would vanish from every rollup and the totals would stop reconciling.
        unknownMember: nullFraction != null && nullFraction > 0,
        selected: provablyToOne,
        hierarchy: levels.map((l) => l.iri),
        levelOptions: levels,
        reason: !outgoing && isTrustedTier(e.tier)
          ? REVERSED_REASON
          : TIER_REASON[e.tier] ?? 'unrecognized trust tier — treated as unverified',
      };
    })
    .sort((a, b) =>
      (TIER_RANK[a.tier] ?? 3) - (TIER_RANK[b.tier] ?? 3) || a.className.localeCompare(b.className));

  // Names are assigned after the sort so the same payload always names the same draft the
  // same way. Two joins to one class (ship_to / bill_to) are two dimensions with two names,
  // not one name silently applied twice.
  const taken = new Set<string>();
  return drafts.map((d) => {
    const base = toCubeName(d.className) || 'dimension';
    let name = base;
    if (taken.has(name)) {
      const via = toCubeName(d.joinPropertyName);
      if (via) name = `${base}_via_${via}`;
    }
    let n = 2;
    while (taken.has(name)) name = `${base}_${n++}`;
    taken.add(name);
    return { ...d, name: name.slice(0, 63) };
  });
}

// ── Step 3: hierarchies ──────────────────────────────────────────────────────

/** How far apart two adjacent levels' distinct counts must be before we call the pair a
 *  hierarchy rather than two unrelated attributes. city→country is orders of magnitude;
 *  two columns with similar NDV are almost never levels of one another. */
const NDV_SEPARATION = 5;

/**
 * Suggested levels for one dimension, coarsest first.
 *
 * Deliberately conservative and deliberately quiet: the join column is never a level (it is
 * the key, its NDV is the row count), and a chain is only offered when at least two columns
 * are each separated from the next by {@link NDV_SEPARATION}×. Anything looser proposes
 * `first_name → last_name` as a hierarchy, which is worse than proposing nothing — a flat
 * dimension is legitimate and the wizard says so.
 */
export function suggestHierarchy(
  dimClass: CubeEvidenceClass,
  joinColIri: string,
): HierarchyLevel[] {
  const ordered = dimClass.properties
    .filter((p) => !p.numeric && p.iri !== joinColIri && p.ndv != null && p.ndv > 0)
    .map((p) => ({ iri: p.iri, name: p.name, ndv: p.ndv as number }))
    .sort((a, b) => a.ndv - b.ndv || a.name.localeCompare(b.name));
  if (ordered.length < 2) return [];

  const chain: HierarchyLevel[] = [ordered[0]];
  for (let i = 1; i < ordered.length; i++) {
    if (ordered[i].ndv < chain[chain.length - 1].ndv * NDV_SEPARATION) break;
    chain.push(ordered[i]);
  }
  return chain.length >= 2 ? chain : [];
}

// ── Step 2: the generated calendar ───────────────────────────────────────────

/** Power BI's auto date hierarchy, made explicit instead of silent — offered, never
 *  applied behind the user's back. */
export const CALENDAR_LEVELS = ['year', 'quarter', 'month', 'day'];

/**
 * Whether the calendar offer arrives ticked: no (J6-F21). v1 records it in the definition and its
 * exports and generates no calendar table, so a pre-ticked calendar is a default that builds
 * nothing — offered, labelled definition-only, and the person's own choice.
 */
export const CALENDAR_PRESELECTED = false;

/** Local name of an XSD range, which may be written `xsd:date` or as a full IRI. */
function xsdLocal(xsd: string): string {
  const cut = Math.max(xsd.lastIndexOf('#'), xsd.lastIndexOf('/'), xsd.lastIndexOf(':'));
  return xsd.slice(cut + 1).toLowerCase();
}

/** The first date-typed column of a fact, as a generated-calendar offer; null when the fact
 *  has no date at all, in which case the wizard offers nothing rather than an empty card. */
export function suggestCalendar(cls: CubeEvidenceClass): CubeCalendarDto | null {
  const col = cls.properties.find((p) => {
    if (!p.xsd) return false;
    const local = xsdLocal(p.xsd);
    return local === 'date' || local === 'datetime';
  });
  return col ? { fromColumnIri: col.iri, levels: [...CALENDAR_LEVELS] } : null;
}

// ── Step 4: what actually gets POSTed ────────────────────────────────────────

/**
 * The drafts, reduced to the wire definition — the single act of step 4.
 *
 * Only ticked rows survive. `allowUnverified` is computed HERE, from the gesture: a person
 * ticked a row labelled "not provably to-one", which is what taking responsibility looks like
 * in this dialog. A row the platform can prove to-one never sends the flag — the server's gate
 * stays meaningful. Note that "provable" is tier AND direction: a reversed DECLARED edge needs
 * the flag as much as a HYPOTHESIS one does, or the POST is a guaranteed 400.
 */
export function buildCubeDefinition(
  factClassIri: string,
  measures: readonly MeasureDraft[],
  dimensions: readonly DimensionDraft[],
  calendar: CubeCalendarDto | null,
): CubeDefinitionDto {
  return {
    factClassIri,
    measures: measures
      .filter((m) => m.selected)
      .map((m) => ({
        name: m.name,
        columnIri: m.columnIri,
        expr: null,
        aggregator: m.aggregator,
        additivity: m.additivity,
      })),
    dimensions: dimensions
      .filter((d) => d.selected)
      .map((d) => ({
        name: d.name,
        classIri: d.classIri,
        joinPropertyIri: d.joinPropertyIri,
        unknownMember: d.unknownMember,
        hierarchy: [...d.hierarchy],
        allowUnverified: !d.provablyToOne,
      })),
    calendar,
  };
}

import { tokenParsed } from '../keycloak'
import {
  DEMO_DATASET,
  DEMO_DATASETS,
  DEMO_MODEL_BUNDLE,
  DEMO_PREVIEWS,
  DEMO_PROFILES,
  DEMO_PRODUCTS,
  DEMO_PROJECT,
  DEMO_PROJECT_ID,
  DEMO_REPOSITORIES,
  DEMO_SCHEMA_DIFF,
  DEMO_SCHEMA_VERSIONS,
  DEMO_TENANT_ID,
  demoJoinCandidates,
} from './demoWorld'
import { DEMO_SCHEMA_GRAPH, DEMO_SOURCE_GRAPHS } from './demoGraphs'
import type { CubeEvidence, LicenseSummary, Me, ProjectMember } from '../types'

/**
 * The demo tenant's entire backend, mocked at the HTTP boundary — and deliberately
 * **read-only**.
 *
 * The `demo` Keycloak user (see `keycloak/import/aran-realm.json`) has no seeded
 * rows in Postgres — every request would 401/404 against a real backend — so
 * `api.ts`'s `request()` calls `resolveDemoRequest` before ever touching `fetch`.
 * Every other user's traffic is untouched.
 *
 * Reads return a rich, internally-consistent scenario (`demoWorld.ts` — the exact
 * one narrated in `demo/seminar/DEMO.md`): three sources integrated into IPaper/
 * Int_researchers, joined on authorship, with a promoted schema and two materialized
 * products. Writes and data-intensive operations — creating or deleting a project,
 * repository, dataset, alignment, or product; bootstrapping, profiling, compiling,
 * promoting, or materializing anything — are rejected with {@link DemoReadOnlyError}
 * rather than faked, so the demo account can never create load or drift into a
 * state a shared tenant would actually have to carry. The one deliberate exception
 * is query execution and join-discovery/wrapper preview: those only compute and
 * display, they persist nothing, and they are the point of the tour.
 */

/**
 * True only in a build that opted into demo mode AND for the demo identity.
 *
 * The identity half alone is not a gate: this module ships in every bundle, so a
 * real customer whose username happens to be `demo` — or whose address happens to be
 * `demo@example.com` — would have had their own data replaced by this fabricated
 * scenario, silently and read-only. `VITE_DEMO` is compiled in at build/serve time
 * (`import.meta.env`), and no checked-in env file sets it: a run opts in explicitly
 * (`npm run dev:demo`, or VITE_DEMO=true on a build), and every other bundle — dev,
 * e2e and production alike — cannot enter demo mode for any identity at all.
 */
export function isDemoUser(): boolean {
  if (import.meta.env.VITE_DEMO !== 'true') return false
  const parsed = tokenParsed()
  return parsed?.preferred_username === 'demo' || parsed?.email === 'demo@example.com'
}

/**
 * Whether "Change password" is offered. Not to the shared demo account: its password is published
 * so anyone can sign in, and the first visitor to change it would lock every other one out.
 */
export function canChangeOwnPassword(): boolean {
  return !isDemoUser()
}

export const DEMO_PASSTHROUGH = Symbol('demo-passthrough')

/** Thrown for any demo-user request this mock treats as a write or a data-intensive
 *  operation. `errorMessage()` (lib/errors.ts) reads `.message` straight off it, so
 *  every view's existing try/catch surfaces this with no special-casing. */
export class DemoReadOnlyError extends Error {
  constructor() {
    super('This is a read-only demo account — creating, editing, and running heavy operations are disabled. Sign in as yourself to try it for real.')
    this.name = 'DemoReadOnlyError'
  }
}

interface RequestLike {
  method?: string
  body?: unknown
}

function segments(path: string): string[] {
  return path.split('?')[0].split('/').filter(Boolean)
}

function query(path: string, key: string): string | undefined {
  const qs = path.split('?')[1]
  if (!qs) return undefined
  return new URLSearchParams(qs).get(key) ?? undefined
}

/**
 * The only non-GET calls a demo request may make: they compute or preview, never
 * persist. Everything else that isn't a GET is a write or a data-intensive
 * operation (bootstrap, profile, discover-constraints, compile, promote,
 * materialize, or any create/update/delete) and is rejected outright.
 */
const READ_EFFECT_ONLY_PATTERNS = [
  /^\/operators\/query-engine\/invoke$/,
  /^\/operators\/join-discovery\/invoke$/,
  /\/preview-wrapper$/,
]

function isBlockedWrite(path: string, method: string): boolean {
  if (method === 'GET') return false
  return !READ_EFFECT_ONLY_PATTERNS.some((re) => re.test(path))
}

/** Resolves one demo-tenant request, or returns {@link DEMO_PASSTHROUGH} for a read this
 *  mock does not model — which then falls through to the real (and, for `demo`, always
 *  unreachable) API. Throws {@link DemoReadOnlyError} for a blocked write. */
export function resolveDemoRequest(path: string, options: RequestLike): unknown {
  const method = options.method ?? 'GET'
  if (isBlockedWrite(path, method)) {
    throw new DemoReadOnlyError()
  }

  const parts = segments(path)
  const body = (options.body ?? {}) as Record<string, unknown>

  // ── Identity & dashboard ────────────────────────────────────────────────
  if (path === '/me') {
    // Typed against `Me` deliberately: this mock had drifted to `id` (the field is
    // `userId`) with `slug`/`joinedAt` missing from the membership, because
    // `resolveDemoRequest` returns `unknown` and nothing checked it. The annotation
    // is what makes the next drift a compile error instead of a demo-only bug.
    const me: Me = {
      userId: 'demo', subject: 'demo', email: 'demo@example.com',
      displayName: 'Demo User', createdAt: '2026-07-01T09:00:00Z',
      tenants: [{
        tenantId: DEMO_TENANT_ID, slug: 'demo-org', name: 'Demo Organization',
        role: 'OWNER', joinedAt: '2026-07-01T09:00:00Z',
      }],
      // Same answer a default deployment gives (aran.cubes.enabled is off), and the only
      // honest one here: this mock models no cubes, so the wizard would reach a passthrough
      // to an API the demo user cannot call.
      features: { cubes: false },
    }
    return me
  }
  if (path === '/dashboard/summary') {
    return {
      projects: 1, repositories: 3, datasets: 3, readyDatasets: 3, activeSchemaVersion: 2, draftSchemaVersion: null,
      // Only identifiers AuditService really writes, in AuditLine's own target shape
      // ("type:id") — the dashboard prints both raw, and an invented line such as "Compiled
      // integration" claimed a record the product never makes (J7-F1).
      recentAudit: [
        { ts: '2026-07-10T16:02:00Z', actor: 'demo', action: 'schema.promoted', target: 'schema_version:v2' },
        { ts: '2026-07-02T09:05:00Z', actor: 'demo', action: 'project.member.added', target: `project:${DEMO_PROJECT_ID}` },
        { ts: '2026-07-01T09:10:00Z', actor: 'demo', action: 'project.created', target: `project:${DEMO_PROJECT_ID}` },
        { ts: '2026-07-01T09:00:00Z', actor: 'demo', action: 'tenant.member.added', target: `tenant:${DEMO_TENANT_ID}` },
      ],
    }
  }

  // ── Projects ─────────────────────────────────────────────────────────────
  if (path === '/projects') return [DEMO_PROJECT]
  if (parts.length === 2 && parts[0] === 'projects' && parts[1] === DEMO_PROJECT_ID) return DEMO_PROJECT
  if (path === `/projects/${DEMO_PROJECT_ID}/members`) {
    // `ProjectMemberResponse` is (userId, addedAt) — not the email/name/role row this
    // mock used to return, which is `TenantMember`'s shape from a different endpoint.
    const members: ProjectMember[] = [{ userId: 'demo', addedAt: '2026-07-01T09:00:00Z' }]
    return members
  }
  if (path === `/tenants/${DEMO_TENANT_ID}/license`) {
    // seatsUsed/seatsAllowed were invented: `LicenseSummary` counts users and projects
    // separately, and TenantSelectView renders both meters off currentUsers/maxUsers and
    // currentProjects/maxProjects — so the old payload drew `undefined / undefined`
    // meters at NaN% width for every demo visitor.
    const license: LicenseSummary = {
      tenantId: DEMO_TENANT_ID, plan: 'PRO', status: 'ACTIVE', mutationsAllowed: true,
      maxUsers: 10, currentUsers: 1,
      maxProjects: 5, currentProjects: 1,
      features: ['AUDIT_LOG_EXPORT'], expiresAt: null,
    }
    return license
  }

  // ── Jobs ──────────────────────────────────────────────────────────────────
  // The demo account never creates a job (every submit is a blocked write), so the top bar's
  // jobs list is honestly empty — answered here rather than passed through to an API this
  // identity cannot reach, which would show every demo visitor "Couldn't load jobs".
  if (parts[0] === 'jobs' && parts.length === 1) return []

  // ── Repositories & datasets (reads only — see isBlockedWrite) ────────────
  if (parts[0] === 'projects' && parts[1] === DEMO_PROJECT_ID && parts[2] === 'repositories') {
    if (parts.length === 3) return DEMO_REPOSITORIES
    if (parts.length >= 4) {
      const repoId = parts[3]
      if (parts[4] === 'scan') {
        return Object.values(DEMO_DATASETS).filter((d) => d.repositoryId === repoId).map((d) => d.name)
      }
      if (parts[4] === 'datasets') {
        const datasets = Object.values(DEMO_DATASETS).filter((d) => d.repositoryId === repoId)
        if (parts.length === 5) return datasets
        if (parts.length >= 6) {
          const datasetId = parts[5]
          if (parts[6] === 'preview') return DEMO_PREVIEWS[datasetId] ?? { headers: [], rows: [] }
          if (parts[6] === 'profiles') return DEMO_PROFILES[datasetId] ?? { datasetId, groups: [] }
          if (parts[6] === 'preview-wrapper') return DEMO_PREVIEWS[datasetId] ?? { headers: [], rows: [] }
        }
      }
      if (parts[4] === 'preview') {
        const datasetName = query(path, 'datasetName')
        const match = Object.values(DEMO_DATASETS).find((d) => d.name === datasetName)
        return match ? DEMO_PREVIEWS[match.id] : { headers: [], rows: [] }
      }
    }
  }

  // ── Source & schema graphs ────────────────────────────────────────────────
  if (parts[0] === 'graph' && parts[1] === 'source' && parts.length === 3) {
    return DEMO_SOURCE_GRAPHS[parts[2]] ?? { datasetId: parts[2], graphUri: '', tripleCount: 0, empty: true, nodes: [], edges: [] }
  }
  if (parts[0] === 'graph' && parts[1] === 'schema' && parts.length === 3) {
    return DEMO_SCHEMA_GRAPH
  }

  // ── Integration model (read-only — see isBlockedWrite for the writes) ────
  if (parts[0] === 'projects' && parts[1] === DEMO_PROJECT_ID && parts[2] === 'model' && parts.length === 3) {
    return DEMO_MODEL_BUNDLE
  }

  // ── Schema versions ──────────────────────────────────────────────────────
  if (path === '/schema/versions') return DEMO_SCHEMA_VERSIONS
  if (parts[0] === 'schema' && parts[1] === 'versions' && parts[3] === 'diff') return DEMO_SCHEMA_DIFF

  // ── Data products ─────────────────────────────────────────────────────────
  if (parts[0] === 'projects' && parts[1] === DEMO_PROJECT_ID && parts[2] === 'products') {
    if (parts.length === 3) return DEMO_PRODUCTS
    if (parts.length >= 4) {
      const productId = parts[3]
      if (parts.length === 4) return DEMO_PRODUCTS.find((p) => p.id === productId) ?? DEMO_PRODUCTS[0]
      if (parts[4] === 'preview') return demoProductPreview(productId)
    }
  }

  // ── Cubes ─────────────────────────────────────────────────────────────────
  // The demo scenario has no cubes, and that is the answer rather than a passthrough:
  // an unmodelled read falls through to a real API this identity can never reach, so
  // ProductsView's cubes section would render a network error to every demo visitor.
  // Creating one is a write, already rejected by isBlockedWrite.
  if (parts[0] === 'projects' && parts[1] === DEMO_PROJECT_ID && parts[2] === 'cubes') {
    if (parts.length === 3) return []
    if (parts[3] === 'evidence') {
      const evidence: CubeEvidence = { classes: [], edges: [] }
      return evidence
    }
  }

  // ── Operators: query-engine (any op) and join-discovery's "discover" only ──
  if (parts[0] === 'operators' && parts[2] === 'invoke') {
    return demoOperatorInvoke(parts[1], body as { operation?: string; parameters?: Record<string, string> })
  }

  return DEMO_PASSTHROUGH
}

function demoProductPreview(productId: string) {
  if (productId === 'prod-demo-1') {
    return {
      headers: ['http://aran.com/schema#title', 'http://aran.com/schema#year', 'http://aran.com/schema#name', 'http://aran.com/schema#hIndex'],
      headerNames: ['title', 'year', 'name', 'hIndex'],
      rows: [
        ['Open Data Integration at Scale', 2024, 'James Chen', 24],
        ['Ontology-Based Data Integration: A Decade of Progress', 2020, 'James Chen', 24],
        ['Federated Query Processing over Heterogeneous RDF Graphs', 2022, 'Maria Garcia', 18],
        ['Knowledge Base Construction from Web Tables', 2021, 'Mei Liu', 17],
      ],
      rowCount: 4, truncated: false, elapsedMs: 38,
    }
  }
  return {
    headers: ['http://aran.com/schema#title', 'http://aran.com/schema#name', 'http://aran.com/schema#affiliation'],
    headerNames: ['title', 'name', 'affiliation'],
    rows: [
      ['Open Data Integration at Scale', 'Carlos Ruiz', 'UPC'],
      ['Declarative Data Integration with Denial Constraints', 'Maria Garcia', 'UPC'],
    ],
    rowCount: 2, truncated: false, elapsedMs: 31,
  }
}

// ── query-engine / join-discovery operator invocations ─────────────────────

/**
 * Mirrors the demo's `optimizedPlan` string above (`EnumerableProject` over an
 * `EnumerableHashJoin` of two `EnumerableTableScan`s) as a `PlanShape.Node` tree, so the
 * plan view's structured render matches the plain-text plan a demo user can already see.
 */
const DEMO_PLAN_SHAPE = {
  kind: 'PROJECT',
  detail: 'title, name',
  inputs: [
    {
      kind: 'JOIN',
      detail: 'inner, authorId = researcherId',
      inputs: [
        { kind: 'SCAN', detail: 'int_IPaper, access=MATERIALIZED', inputs: [] },
        { kind: 'SCAN', detail: 'int_Int_researchers, access=MATERIALIZED', inputs: [] },
      ],
    },
  ],
}

function demoOperatorInvoke(operatorId: string, body: { operation?: string; parameters?: Record<string, string> }): unknown {
  const op = body.operation
  const params = body.parameters ?? {}

  if (operatorId === 'join-discovery' && op === 'discover') {
    const threshold = Number(params.threshold ?? '0')
    const candidates = demoJoinCandidates(
      params.datasetIdA ?? '', params.datasetIdB ?? '',
      params.method === 'ml' ? 'ml' : 'exact',
      Number.isFinite(threshold) ? threshold : 0,
    )
    return { success: true, data: { candidates: JSON.stringify(candidates) } }
  }
  if (operatorId !== 'query-engine') {
    // Only reachable if isBlockedWrite's allowlist and this dispatcher ever disagree —
    // fail closed rather than silently invent a payload for an unmodeled operator.
    throw new DemoReadOnlyError()
  }

  if (op === 'catalog') return { success: true, data: { catalog: JSON.stringify(demoCatalog()) } }
  // The tier chip on accepted joins (J4-F17): the demo declares no keys, so every join reads as
  // the hypothesis it is — the same evidence the demo cubes route already answers.
  if (op === 'cube-evidence') return { success: true, data: { status: 'ok', evidence: JSON.stringify({ classes: [], edges: [] }) } }
  if (op === 'explain') return { success: true, data: { ddl: JSON.stringify(demoDdl()) } }
  if (op === 'rewrite-oql') {
    return {
      success: true,
      data: {
        sql: demoSqlFor(params.oql ?? ''),
        logicalPlan: 'LogicalProject(...)\n  LogicalJoin(...)\n    LogicalTableScan(int_IPaper)\n    LogicalTableScan(int_Int_researchers)',
        optimizedPlan: 'EnumerableProject(...)\n  EnumerableHashJoin(...)\n    EnumerableTableScan(int_IPaper)\n    EnumerableTableScan(int_Int_researchers)',
        // Real rewrite-oql's operator response is a Map<String,String>, so planShape is a
        // JSON-encoded STRING field there — mirror that encoding rather than a bare object.
        planShape: JSON.stringify(DEMO_PLAN_SHAPE),
        planShapeText: 'PROJECT[title, name]\n  JOIN[inner, authorId = researcherId]\n    SCAN[int_IPaper, access=MATERIALIZED]\n    SCAN[int_Int_researchers, access=MATERIALIZED]',
      },
    }
  }
  if (op === 'run' || op === 'run-oql') {
    const queryText = params.sql ?? params.oql ?? ''
    const result = demoQueryResult(queryText)
    return { success: true, data: { sql: demoSqlFor(queryText), result: JSON.stringify(result) } }
  }
  if (op === 'validate-constraint') return { success: true, data: { status: 'ok', validated: 'true' } }
  // materialize-oql / preview-parquet are internal-only (never invoked from the frontend);
  // anything else reaching here is a write the query-engine manifest wouldn't call read-only.
  throw new DemoReadOnlyError()
}

function demoCatalog() {
  return {
    empty: false,
    schemaVersionId: 'v2',
    versionNumber: 2,
    classes: [
      {
        iri: 'http://aran.com/schema#IPaper', name: 'IPaper', intView: 'int_IPaper',
        rowCount: 55, enforcedConstraintCount: 2,
        sources: [
          { datasetId: DEMO_DATASET.acm, wrapperSql: DEMO_DATASETS[DEMO_DATASET.acm].wrapperSql, intView: 'int_IPaper', rowCount: 40, columns: [
            { global: 'http://aran.com/schema#pid', globalName: 'pid', source: 'paper_id' },
            { global: 'http://aran.com/schema#title', globalName: 'title', source: 'title' },
            { global: 'http://aran.com/schema#year', globalName: 'year', source: 'year' },
            { global: 'http://aran.com/schema#venue', globalName: 'venue', source: 'venue' },
            { global: 'http://aran.com/schema#authorId', globalName: 'authorId', source: 'author_id' },
          ] },
          { datasetId: DEMO_DATASET.semantic, wrapperSql: DEMO_DATASETS[DEMO_DATASET.semantic].wrapperSql, intView: 'int_IPaper', rowCount: 15, columns: [
            { global: 'http://aran.com/schema#pid', globalName: 'pid', source: 'doi' },
            { global: 'http://aran.com/schema#title', globalName: 'title', source: 'title' },
            { global: 'http://aran.com/schema#year', globalName: 'year', source: 'year' },
            { global: 'http://aran.com/schema#citation_count', globalName: 'citation_count', source: 'citation_count' },
            { global: 'http://aran.com/schema#authorId', globalName: 'authorId', source: 'authors' },
          ] },
        ],
      },
      {
        iri: 'http://aran.com/schema#Int_researchers', name: 'Int_researchers', intView: 'int_Int_researchers',
        rowCount: 20, enforcedConstraintCount: 1,
        sources: [
          { datasetId: DEMO_DATASET.researchers, wrapperSql: DEMO_DATASETS[DEMO_DATASET.researchers].wrapperSql, intView: 'int_Int_researchers', rowCount: 20, columns: [
            { global: 'http://aran.com/schema#researcherId', globalName: 'researcherId', source: 'researcher_id' },
            { global: 'http://aran.com/schema#name', globalName: 'name', source: 'full_name' },
            { global: 'http://aran.com/schema#affiliation', globalName: 'affiliation', source: 'affiliation' },
            { global: 'http://aran.com/schema#country', globalName: 'country', source: 'country' },
            { global: 'http://aran.com/schema#hIndex', globalName: 'hIndex', source: 'h_index' },
          ] },
        ],
      },
    ],
    joinProperties: [
      { iri: 'http://aran.com/schema#hasAuthor', name: 'hasAuthor', leftClass: 'IPaper', rightClass: 'Int_researchers', leftCol: 'authorId', rightCol: 'researcherId' },
    ],
    objectProperties: [],
  }
}

function demoDdl(): string[] {
  return [
    `CREATE VIEW src_${DEMO_DATASET.acm} AS SELECT * FROM read_csv_auto('snapshot://snap-acm-1');`,
    `CREATE VIEW src_${DEMO_DATASET.semantic} AS SELECT * FROM read_json_auto('snapshot://snap-semantic-1');`,
    `CREATE VIEW src_${DEMO_DATASET.researchers} AS SELECT * FROM read_json_auto('snapshot://snap-researchers-1');`,
    'CREATE VIEW w_ds_acm_IPaper AS SELECT paper_id AS pid, title, year, venue, author_id AS "authorId" FROM src_ds_acm;',
    "CREATE VIEW w_ds_semantic_IPaper AS SELECT doi AS pid, title, year, citation_count, unnest(authors) AS \"authorId\" FROM src_ds_semantic;",
    'CREATE VIEW int_IPaper AS SELECT * FROM w_ds_acm_IPaper UNION ALL SELECT * FROM w_ds_semantic_IPaper;',
    'CREATE VIEW w_ds_researchers_Int_researchers AS SELECT researcher_id AS "researcherId", full_name AS name, affiliation, country, h_index AS "hIndex" FROM src_ds_researchers;',
    'CREATE VIEW int_Int_researchers AS SELECT * FROM w_ds_researchers_Int_researchers;',
  ]
}

function demoSqlFor(queryText: string): string {
  const t = queryText.toUpperCase()
  if (t.includes('HASAUTHOR') || t.includes('INT_RESEARCHERS')) {
    return 'SELECT p."title", p."year", r."name", r."affiliation" FROM int_IPaper p JOIN int_Int_researchers r ON p."authorId" = r."researcherId"'
  }
  return 'SELECT * FROM int_IPaper'
}

/**
 * Pattern-matches the same query text `demo/seminar/DEMO.md` walks through (Q1–Q6, A1–A5, and the
 * SQL-mode aggregates) so typing any of that script's queries in the workbench returns the exact
 * row shape the script describes — the query workbench half of "showcase many different scenarios".
 */
function demoQueryResult(queryText: string) {
  const t = queryText.toUpperCase()
  let headers: string[]
  let headerNames: string[]
  let rows: (string | number | null)[][]

  if (t.includes('COUNT') || t.includes('AVG')) {
    if (t.includes('AFFILIATION')) {
      headers = ['http://aran.com/schema#affiliation', 'paper_count']
      headerNames = ['affiliation', 'paper_count']
      rows = [['UPC', 8], ['MIT', 3], ['CMU', 4], ['Oxford', 2]]
    } else if (t.includes('COUNTRY')) {
      headers = ['http://aran.com/schema#country', 'avg_h_index', 'researcher_count']
      headerNames = ['country', 'avg_h_index', 'researcher_count']
      rows = [['USA', 22.5, 2], ['UK', 15.0, 2], ['Spain', 12.5, 2], ['Portugal', 6.7, 3]]
    } else if (t.includes('YEAR')) {
      headers = ['http://aran.com/schema#year', 'papers']
      headerNames = ['year', 'papers']
      rows = [[2020, 4], [2021, 5], [2022, 6], [2023, 8], [2024, 7]]
    } else {
      headers = ['http://aran.com/schema#name', 'paper_count']
      headerNames = ['name', 'paper_count']
      rows = [['Maria Garcia', 7], ['David Kim', 5], ['Emma Wilson', 4]]
    }
  } else if (t.includes('HASAUTHOR') || t.includes('INT_RESEARCHERS')) {
    if (t.includes('UPC')) {
      headers = ['http://aran.com/schema#title', 'http://aran.com/schema#year', 'http://aran.com/schema#name']
      headerNames = ['title', 'year', 'name']
      rows = [
        ['Federated Query Processing over Heterogeneous RDF Graphs', 2022, 'Maria Garcia'],
        ['Open Data Integration at Scale', 2024, 'Carlos Ruiz'],
        ['Efficient SPARQL Query Optimisation via Cost Models', 2021, 'Maria Garcia'],
        ['Declarative Data Integration with Denial Constraints', 2024, 'Maria Garcia'],
      ]
    } else if (t.includes('2023') || t.includes('2024')) {
      headers = ['http://aran.com/schema#title', 'http://aran.com/schema#name', 'http://aran.com/schema#affiliation']
      headerNames = ['title', 'name', 'affiliation']
      rows = [
        ['Open Data Integration at Scale', 'Carlos Ruiz', 'UPC'],
        ['Declarative Data Integration with Denial Constraints', 'Maria Garcia', 'UPC'],
      ]
    } else if (t.includes('15')) {
      headers = ['http://aran.com/schema#title', 'http://aran.com/schema#year', 'http://aran.com/schema#name', 'http://aran.com/schema#hIndex']
      headerNames = ['title', 'year', 'name', 'hIndex']
      rows = [
        ['Knowledge Base Construction from Web Tables', 2021, 'Mei Liu', 17],
        ['Declarative Data Integration with Denial Constraints', 2024, 'Maria Garcia', 18],
      ]
    } else if (t.includes('PORTUGAL')) {
      headers = ['http://aran.com/schema#name', 'http://aran.com/schema#title', 'http://aran.com/schema#year']
      headerNames = ['name', 'title', 'year']
      rows = [
        ['Ana Silva', 'Scalable Ontology Alignment Using Neural Embeddings', 2023],
        ['Pedro Martins', 'Conflict Resolution in Heterogeneous Knowledge Bases', 2022],
      ]
    } else {
      headers = ['http://aran.com/schema#title', 'http://aran.com/schema#name', 'http://aran.com/schema#affiliation']
      headerNames = ['title', 'name', 'affiliation']
      rows = [
        ['Federated Query Processing over Heterogeneous RDF Graphs', 'Maria Garcia', 'UPC'],
        ['Open Data Integration at Scale', 'James Chen', 'MIT'],
      ]
    }
  } else {
    headers = ['http://aran.com/schema#pid', 'http://aran.com/schema#title', 'http://aran.com/schema#year', 'http://aran.com/schema#authorId', 'http://aran.com/schema#venue', 'http://aran.com/schema#citation_count']
    headerNames = ['pid', 'title', 'year', 'authorId', 'venue', 'citation_count']
    rows = [
      ['ACM001', 'Federated Query Processing over Heterogeneous RDF Graphs', 2022, 'R001', 'ISWC', null],
      ['ACM002', 'Open Data Integration at Scale', 2024, 'R002', 'VLDB', null],
      ['10.1145/SS001', 'Ontology-Based Data Integration: A Decade of Progress', 2020, 'R002', null, 143],
      ['10.1145/SS002', 'Reasoning over Federated Knowledge Graphs', 2021, 'R001', null, 89],
    ]
  }

  return { headers, headerNames, rows, rowCount: rows.length, truncated: false, elapsedMs: 42 }
}

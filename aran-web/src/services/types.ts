/**
 * Wire types — the shapes the aran-application REST API returns.
 * These mirror the backend DTOs (ProjectDtos, MeController.*). Kept in one
 * place so a backend contract change surfaces as a TypeScript error here.
 */

export type TenantRole = 'OWNER' | 'MEMBER'

/** One tenant the current user belongs to — from GET /api/me. */
export interface TenantMembership {
  tenantId: string
  slug: string
  name: string
  role: TenantRole
  joinedAt: string
}

/**
 * Optional surfaces THIS deployment has turned on — from GET /api/me.
 *
 * One bundle is served by every deployment, so which features exist cannot be a build-time
 * env: the server decides and says so here. A flag means the feature exists at all, never that
 * this caller may use it — authorization is unchanged and still server-side.
 *
 * Read every flag as `=== true`. Optional on the wire (below) so a payload from a server
 * predating it is read as "off", which is the safe direction: the routes behind a flag that is
 * absent answer 404, and offering a button that can only 404 is worse than hiding it.
 */
export interface MeFeatures {
  /** `aran.cubes.enabled` — the shape-as-cube wizard and the Cubes panel. Off by default. */
  cubes: boolean
}

/** The authenticated user plus the tenants they can act within — GET /api/me. */
export interface Me {
  userId: string
  subject: string
  email: string
  displayName: string
  createdAt: string
  tenants: TenantMembership[]
  features?: MeFeatures
}

/**
 * A project — from GET/POST /api/projects.
 *
 * Mirrors `ProjectDtos.ProjectResponse`. This was the one type in this file sourced from a
 * checked-in `openapi.d.ts`; that file was 2,779 lines serving this alone, and had gone stale
 * enough to be wrong (it still declared `bootstrapStatus` without `DRIFTED`) with nothing
 * detecting the drift. Hand-written like every other type here, so there is one convention
 * rather than two and no generated artifact quietly contradicting the backend. `npm run
 * generate:api` still exists for cross-checking the contract, and now reads the live endpoint
 * instead of a stale file.
 */
export interface Project {
  id: string
  tenantId: string
  name: string
  description: string
  createdBy: string
  /** ISO-8601; `Instant` on the wire. */
  createdAt: string
  color: string
  privacy: string
  status: string
  progress: number
}

export type LicensePlan = 'FREE' | 'PRO' | 'ENTERPRISE'
export type LicenseStatus = 'ACTIVE' | 'SUSPENDED'
export type LicenseFeature = 'AUDIT_LOG_EXPORT'

/** GET /api/tenants/{id}/license — entitlements + current usage. */
export interface LicenseSummary {
  tenantId: string
  plan: LicensePlan
  status: LicenseStatus
  mutationsAllowed: boolean
  maxUsers: number
  currentUsers: number
  maxProjects: number
  currentProjects: number
  features: LicenseFeature[]
  expiresAt: string | null
}

/** A project-member visibility row — from GET /api/projects/{id}/members. */
export interface ProjectMember {
  userId: string
  addedAt: string
}

/** RFC 7807 problem detail — the API's error shape. */
export interface ProblemDetail {
  title: string
  detail: string
  status: number
  /** Set on 402 license-limit-exceeded responses. */
  limit?: string
  currentUsage?: number
  maxAllowed?: number
  /**
   * Set when the body is an OperatorResult rather than an RFC-7807 document: a
   * rejected operator invocation answers 422 with {success:false, errorCode,
   * errorMessage}, and the operator's own message is the one worth showing.
   */
  errorCode?: string
  errorMessage?: string
  /** On a 409 promote refusal (UI-OD2): the class IRIs another project's model promoted. */
  classes?: string[]
  /** On the same refusal: the owning projects' names — only those the caller is a member of. */
  projects?: string[]
}

/**
 * The envelope every operator invocation answers with, on success and on a
 * rejected run alike. `data` holds the operator's own payload, whose shape is
 * per-operator — callers narrow it themselves, usually by parsing a JSON string
 * out of a known key.
 */
export interface OperatorResult {
  success: boolean
  errorCode?: string
  errorMessage?: string
  data?: Record<string, string>
}

/** One entry of an {@link OperationSpec}'s parameter schema. */
export interface ParameterSpec {
  name: string
  type: string
  required: boolean
  description: string | null
  defaultValue: string | null
}

/** A single operation an operator supports, with its parameter schema. */
export interface OperationSpec {
  name: string
  description: string | null
  longRunning: boolean
  parameters: ParameterSpec[]
}

/** Self-describing manifest for one registered operator — see `GET /api/operators`. */
export interface OperatorManifest {
  operatorId: string
  displayName: string
  operations: OperationSpec[]
}

export type RepositoryType = 'RELATIONAL_DB' | 'OBJECT_STORAGE' | 'FILE_SYSTEM' | 'SINGER_TAP' | 'ICEBERG_REST';

/**
 * One entry of `GET /api/relational-engines` — what a database engine can actually do.
 *
 * Every repository kind can be ingested from, but not every engine under RELATIONAL_DB can move
 * a row — HBase, for one, registers and introspects and ingests nothing.
 *
 * `bulkExtract` names the mechanism an engine *would* use; `bulkExtractReady` says whether this
 * release implements it. They are separate on purpose — merging them is how a capability comes to
 * be advertised before it exists.
 */
export interface RelationalEngineInfo {
  id: string
  displayName: string
  defaultPort: number
  supportsFederation: boolean
  bulkExtract: 'NONE' | 'LAKE_NATIVE' | 'JDBC_GENERIC'
  bulkExtractReady: boolean
}

/** Whether a dataset under this engine can be filled at all today. */
export function engineCanIngest(engine: RelationalEngineInfo): boolean {
  return engine.supportsFederation || engine.bulkExtractReady
}

/**
 * How a user can get data in under this engine, in the order they should consider it.
 *
 * Derived from the published capability rather than written out per engine: the sentence this
 * replaces said "table import (PostgreSQL)" and was quietly wrong from the day a second engine
 * gained an extract. File upload is last and unconditional — it needs nothing from the engine,
 * which is exactly why it is the fallback when the list would otherwise be empty.
 */
export function relationalIngestionModes(engine: RelationalEngineInfo | null): string[] {
  const modes: string[] = []
  if (engine === null || engine.bulkExtractReady) modes.push('table import')
  if (engine === null || engine.supportsFederation) modes.push('live federation (federated repositories)')
  modes.push('file upload')
  return modes
}

export interface BaseDataRepository {
  id: string
  projectId: string
  tenantId: string
  name: string
  description: string
  type: RepositoryType
  /**
   * The last connection test someone ran against this repository as stored (V46), or null when
   * none has run since it was registered or last saved. Listing never dials a source — this is
   * what the list shows instead (UI audit J2-F19). Absent on a payload predating V46.
   */
  lastConnectionCheck?: ConnectionCheck | null
}

/** The outcome of a connection test — `ConnectionCheckDto`. */
export interface ConnectionCheck {
  success: boolean
  /** The probe's one-line summary, e.g. "Connected to POSTGRESQL at db:5432. Server: …". */
  message: string
  /** On failure, the driver's or protocol's own diagnostics. */
  details: string | null
  testedAt: string
}

/**
 * The answer of either test route — `ConnectionTestResponse`. `POST …/repositories/test-connection`
 * tests unsaved settings and refuses a masked credential; `POST …/repositories/{id}/test-connection`
 * tests the stored repository (optionally with the edit form's settings), masked credentials
 * resolved server-side.
 */
export interface ConnectionTestResult {
  success: boolean
  message: string
  details?: string | null
  testedAt: string
}

export interface RelationalDatabaseRepository extends BaseDataRepository {
  type: 'RELATIONAL_DB'
  engine: string
  host: string
  port: number
  database: string
  username?: string
  password?: string
  schema?: string
  federated?: boolean
}

export interface ObjectStorageRepository extends BaseDataRepository {
  type: 'OBJECT_STORAGE'
  provider: string
  endpoint?: string
  bucketName: string
  accessKey?: string
  secretKey?: string
  region?: string
}

/** Upload-only: it holds the files uploaded to it, so it has nothing to connect to (D1). */
export interface FileSystemRepository extends BaseDataRepository {
  type: 'FILE_SYSTEM'
}

/**
 * One entry of `GET /api/singer-taps` — a tap this deployment is willing to run.
 *
 * No command: it is an absolute path on the server, and a form only needs to know what exists
 * and which settings it wants. An EMPTY list means the feature is off, which is the default —
 * render that as "no taps configured", never as an empty dropdown.
 */
export interface SingerTapInfo {
  id: string
  label: string
  configKeys: string[]
  /** Which of `configKeys` hold credentials: masked in the form and in every response. */
  secretKeys: string[]
}

/**
 * A source Aran reads by running a Singer tap.
 *
 * `config` values for keys the tap declared secret come back as `********`. Sending that value
 * back on an update means "keep the stored one", per key — so a form can be re-submitted after a
 * GET without wiping the credential, and can still rotate it by typing a new one.
 */
export interface SingerTapRepository extends BaseDataRepository {
  type: 'SINGER_TAP'
  tapId: string
  config: Record<string, string>
}

/**
 * An Apache Iceberg REST catalog (Gap 2). Two independent credentials, both masked as
 * `********` in responses: `token` authenticates the catalog itself (control plane),
 * `secretKey` the object store its metadata points at (data plane). Sending a masked value
 * back on an update means "keep the stored one", per credential.
 */
export interface IcebergRestRepository extends BaseDataRepository {
  type: 'ICEBERG_REST'
  catalogUri: string
  warehouse?: string
  token?: string
  provider: string
  endpoint?: string
  accessKey?: string
  secretKey?: string
  region?: string
}

export type DataRepository =
  | RelationalDatabaseRepository
  | ObjectStorageRepository
  | FileSystemRepository
  | SingerTapRepository
  | IcebergRestRepository;

/**
 * A repository as the client supplies it — create, update, and test-connection
 * all post the same shape. `id`, `projectId`, and `tenantId` are assigned by the
 * server, so a caller building one from a form does not have them yet.
 */
export type RepositoryInput =
  | Omit<RelationalDatabaseRepository, 'id' | 'projectId' | 'tenantId'>
  | Omit<ObjectStorageRepository, 'id' | 'projectId' | 'tenantId'>
  | Omit<FileSystemRepository, 'id' | 'projectId' | 'tenantId'>
  | Omit<SingerTapRepository, 'id' | 'projectId' | 'tenantId'>
  | Omit<IcebergRestRepository, 'id' | 'projectId' | 'tenantId'>;

/**
 * One stored column profile — `DatasetDtos.StoredColumnProfile` (J3-F1). What freyja measured for
 * a column, as the registry holds it. `emptyColumn`/`binaryColumn` are the domain's
 * `isEmpty`/`isBinary`, renamed on the wire (see the DTO's javadoc).
 */
export interface StoredColumnProfile {
  columnName: string
  columnIndex: number
  sourceType: string
  cardinality: number
  entropy: number | null
  uniqueness: number
  incompleteness: number
  frequencyAvg: number
  frequencyMin: number
  frequencyMax: number
  frequencySd: number
  frequencyIqr: number
  valPctMin: number
  valPctMax: number
  valPctStd: number
  constancy: number
  lenMaxWord: number
  lenMinWord: number
  lenAvgWord: number
  numberWords: number
  wordsCntMax: number
  wordsCntMin: number
  wordsCntAvg: number
  wordsCntSd: number
  firstWord: string | null
  lastWord: string | null
  emptyColumn: boolean
  binaryColumn: boolean
  commonValues: string[]
  commonValuesSoundex: string[]
}

/**
 * What a profile generation measured (`ColumnProfile.subject`): freyja over the raw snapshot, over
 * one source class's wrapper output or live binding, or the relational catalog's own statistics.
 */
export type ProfileSubject = 'SNAPSHOT' | 'WRAPPER_OUTPUT' | 'FEDERATED_LIVE' | 'HARVESTED'

/** One stored generation — `DatasetDtos.ProfileGroup`. */
export interface ProfileGroup {
  subject: ProfileSubject | string
  /** The source class a WRAPPER_OUTPUT / FEDERATED_LIVE generation belongs to; null otherwise. */
  classIri: string | null
  /** Whether the planner may read these rows as proof of a key (`ColumnProfile.admissibleAsKeyEvidence`). */
  keyEvidence: boolean
  columns: StoredColumnProfile[]
}

/** `GET …/datasets/{datasetId}/profiles` — `DatasetDtos.DatasetProfilesResponse`. */
export interface DatasetProfiles {
  datasetId: string
  groups: ProfileGroup[]
}

/**
 * How queries read a dataset (`DatasetAccessMode`, J2-F3): its lake snapshot, the source live, or
 * the source live with a lake copy the planner may serve while an interval refresh keeps it fresh.
 */
export type DatasetAccessMode = 'SNAPSHOT' | 'LIVE' | 'LIVE_WITH_COPY'

export interface Dataset {
  id: string
  repositoryId: string
  name: string
  description: string
  snapshotId?: string
  bootstrapStatus?: string
  /** On list and update responses; a create response does not carry it. */
  accessMode?: DatasetAccessMode
}

/**
 * A dataset just registered with a file — always `PENDING`, because its schema is
 * still being bootstrapped by the job named here. Null only when no bootstrap ran.
 */
export interface DatasetCreated extends Dataset {
  bootstrapJobId: string | null
}

// ── schema drift ────────────────────────────────────────────────────────────

export type DriftSeverity = 'INFO' | 'WARNING' | 'BREAKING'

export type SchemaChangeKind =
  | 'CLASS_ADDED' | 'CLASS_REMOVED' | 'CLASS_IRI_CHANGED'
  | 'COLUMN_ADDED' | 'COLUMN_REMOVED' | 'COLUMN_RENAMED'
  | 'TYPE_CHANGED' | 'FORMAT_CHANGED'

/** One difference between the recorded baseline and the source as it stands now. */
export interface SchemaChange {
  kind: SchemaChangeKind
  classIri: string
  propertyIri: string | null
  /** Where the moved IRI went — present for renames, and what a repair rewrites to. */
  replacementIri: string | null
  before: string | null
  after: string | null
  /** How strongly the evidence supports a proposed rename (0–1). Null for everything else. */
  confidence: number | null
  detail: string
}

/** A mapping that points at an IRI the change moved. */
export interface AffectedMapping {
  mappingId: string
  kind: 'class' | 'datatype' | 'object' | 'join'
  status: 'SUGGESTED' | 'ACCEPTED' | 'REJECTED'
  matchedIri: string
}

export interface DriftImpact {
  change: SchemaChange
  severity: DriftSeverity
  affected: AffectedMapping[]
  reason: string
}

/**
 * The verdict on one dataset. `baselineHash` is null the first time a source is seen —
 * nothing to compare against, so nothing can have drifted.
 */
export interface DriftReport {
  datasetId: string
  baselineHash: string | null
  candidateHash: string
  impacts: DriftImpact[]
}

export interface DriftRepairResult {
  rewritten: number
  models: string[]
}

/** A node in a schema graph — GET /api/graph/source/{datasetId}. */
export interface GraphNode {
  id: string
  label?: string
  iri: string
  type: string
}

/** An edge in a schema graph, as the API returns it (the renderer wants tuples). */
export interface GraphEdge {
  source: string
  target: string
  label: string
}

export interface DatasetPreview {
  headers: string[]
  /** One cell per header; the driver decides the runtime type, so it stays opaque. */
  rows: unknown[][]
}

// ── Query engine (GAV mediator, phases 5–6) ────────────────────────────────
// Shapes returned by the `query-engine` operator. The operator serialises each
// payload as a JSON string under an OperatorResult.data key (`catalog`, `ddl`,
// `result`); the api client parses it back into these.

/** One global-property → source-property mapping inside a source view. */
export interface CatalogColumn {
  /** The global property IRI — also the column name in the int_ view. */
  global: string
  /** Local name of the global property, for display. */
  globalName: string
  /** The source property IRI this global column unfolds to. */
  source: string
}

/** A single source contributing to an integrated class. */
export interface CatalogSourceView {
  datasetId: string
  wrapperSql: string
  intView: string
  columns: CatalogColumn[]
  /** Estimated row count for this source, derived from column profiles. 0 if profiling unavailable. */
  rowCount: number
  /** The sources-tier table name (SourceCatalog) — what plain SQL types. Absent pre-tier. */
  sourceTable?: string
  /** The sources-tier column names, as the alias view projects them. Absent pre-tier. */
  sourceColumns?: string[]
}

/** An integrated class in the global schema G and the sources it unfolds to. */
export interface CatalogClass {
  iri: string
  name: string
  /** The DuckDB view the engine materialises for this class, e.g. int_Artworks. */
  intView: string
  /** Estimated total row count across all sources. 0 if profiling unavailable. */
  rowCount: number
  /** Number of enforced denial constraints filtering this class's int_ view (M6). */
  enforcedConstraintCount: number
  sources: CatalogSourceView[]
}

export interface CatalogJoinProperty {
  iri: string
  name: string
  leftClass: string
  rightClass: string
  leftCol: string
  rightCol: string
}

export interface CatalogObjectProperty {
  iri: string
  name: string
  domainClass: string
  rangeClass: string
}

/** The derived GAV mapping catalog for the active global schema. */
export interface QueryCatalog {
  empty: boolean
  schemaVersionId: string | null
  versionNumber: number | null
  classes: CatalogClass[]
  joinProperties: CatalogJoinProperty[]
  objectProperties: CatalogObjectProperty[]
}

/**
 * One operator of the query engine's structured, optimized plan (query-engine's
 * `PlanShape.Node`). `kind` is the stable contract (`SCAN`/`JOIN`/`FILTER`/`PROJECT`/
 * `UNION`/`AGGREGATE`/`SORT`, else `OTHER`); `detail` is human-oriented and unparsed
 * except for a `SCAN`'s `", access="` suffix, which the plan view splits out as a chip.
 */
export interface PlanShapeNode {
  kind: string
  detail: string
  inputs: PlanShapeNode[]
}

/** Result of OQL rewrite: logical plan, optimized plan, and generated SQL. */
export interface OqlRewriteResult {
  sql: string
  logicalPlan: string
  optimizedPlan: string
  /**
   * The optimized plan, structured. rewrite-oql's operator response is a
   * `Map<String,String>`, so the backend JSON-encodes the `PlanShape.Node` tree into this
   * field as a STRING (unlike `QueryRunResult.planShape` below, which arrives as a nested
   * object) — `JSON.parse` it defensively, same as the plan view does.
   */
  planShape?: string
  planShapeText?: string
  /**
   * Physical-placement decisions (one per dual-access dataset the plan touches): which access
   * mode the cost model chose and why. Present only when at least one dataset had a real
   * snapshot-vs-live choice. JSON-encoded STRING here (rewrite-oql's data map is
   * `Map<String,String>`), a nested array on `QueryRunResult.placement` — the same asymmetry
   * as `planShape` above.
   */
  placement?: string
  /**
   * How the optimizer reached this plan, and whether it is the plan it would have chosen given
   * unlimited time (the D3 degradation ladder). Always present. JSON-encoded STRING here, an
   * object on `QueryRunResult.planning` — the same asymmetry as `planShape`.
   */
  planning?: string
  /** The row shape the plan produces (`ProductColumn` JSON), as a JSON-encoded STRING. */
  columns?: string
}

/**
 * The planner's account of one query — mirrors the backend's `Planning.toMap()`.
 * `degradation` is `NONE` (the chosen plan), `BEST_SO_FAR` (the production budget cut planning
 * short; the cheapest plan the memo held is served — correct, possibly worse) or
 * `STRUCTURAL_FALLBACK` (no implemented plan existed when the budget tripped, so the query was
 * planned structurally — correct, never costed). `tripped` names the guard that ended planning.
 */
export interface PlanningReport {
  productions: number
  budget: number
  elapsedMillis: number
  degradation: 'NONE' | 'BEST_SO_FAR' | 'STRUCTURAL_FALLBACK' | string
  tripped: 'PRODUCTION_BUDGET' | 'WALL_CLOCK' | null
  productionsByRule: Record<string, number>
}

/**
 * One dataset's physical-placement outcome — mirrors the backend's
 * `PlacementDecider.report` entries. Costs are in λ-weighted row units (see
 * `aran.query.placement.*`); `reason` is the human-readable verdict, including forced
 * choices when the copy's refresh policy makes the snapshot leaf illegal.
 */
export interface PlacementDecision {
  datasetId: string
  chosen: 'MATERIALIZED' | 'FEDERATED'
  liveCost: number
  snapshotCost: number
  effectiveRows: number
  fullRows: number
  reason: string
}

/** Result of running a read-only SQL statement over the int_ views. */
export interface QueryRunResult {
  /** Full column names (global property IRIs). */
  headers: string[]
  /** Local-name version of each header, for display. */
  headerNames: string[]
  rows: (string | null)[][]
  /** Rows in THIS page — not the query's total, which the engine never counts. */
  rowCount: number
  /** Row offset this page starts at. */
  offset?: number
  /** Rows per page (the server's cap). */
  pageSize?: number
  /** Whether a further page exists. */
  hasMore?: boolean
  /**
   * What each column IS, as the query declared it — `KEY` (a GROUP BY key), `MEASURE` (an
   * aggregate) or `VALUE` (a plain projection). Present only for OQL queries; raw SQL has no
   * query semantics to report, and the chart recommender falls back to inferring from types.
   */
  columnRoles?: ('KEY' | 'MEASURE' | 'VALUE')[]
  /** Same meaning as `hasMore`; kept because it predates paging. */
  truncated: boolean
  elapsedMs: number
  logicalPlan?: string
  optimizedPlan?: string
  /**
   * The optimized plan, structured — already a nested object here (run-oql's whole
   * `result` payload is JSON-encoded once as a unit; `planShape` is a plain field inside
   * it, not re-stringified), unlike `OqlRewriteResult.planShape` above. The plan view
   * normalizes both to one string-in shape rather than branching on the caller.
   */
  planShape?: PlanShapeNode
  planShapeText?: string
  /** As `OqlRewriteResult.placement`, but already a nested array (run-oql's payload is
   * JSON-encoded once as a unit). */
  placement?: PlacementDecision[]
  /** As `OqlRewriteResult.planning`, but already an object. */
  planning?: PlanningReport
}

// ── Dashboard ──────────────────────────────────────────────────────────────

/** One line of the activity feed — an audit row, not a rendered log message. */
export interface AuditLine {
  ts: string
  /** The acting user's id; null for system actions. */
  actor: string | null
  /** Stable action identifier, e.g. `project.created`. */
  action: string
  /** `targetType:targetId`, e.g. `project:1f0e…`. */
  target: string
}

/** GET /api/dashboard/summary — every number here is one the backend can count. */
export interface DashboardSummary {
  projects: number
  repositories: number
  datasets: number
  readyDatasets: number
  /** The promoted GraphVersion's number; null when no schema is promoted yet. */
  activeSchemaVersion: number | null
  /** A compiled DRAFT newer than the active version, waiting to be promoted; null when none. */
  draftSchemaVersion: number | null
  recentAudit: AuditLine[]
}

// ── Jobs ───────────────────────────────────────────────────────────────────

/** The states a job can be in. Terminal: SUCCEEDED, FAILED, CANCELLED. */
export type JobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

/**
 * A durable unit of background work — `GET /api/jobs/{id}`.
 *
 * Every field the SSE stream can tell you is also on this row, so a client that
 * missed every event recovers the whole truth with one GET. `progressStage` is
 * the phase the task actually reported; it is null when the task has nothing
 * true to say yet, and the UI shows a spinner rather than inventing one.
 */
export interface Job {
  id: string
  projectId: string | null
  jobType: string
  status: JobStatus
  parameters: Record<string, string>
  progressStage: string | null
  progressPercent: number | null
  /** The task's payload; present only once SUCCEEDED. */
  result: Record<string, string> | null
  errorCode: string | null
  errorMessage: string | null
  createdBy: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  /**
   * What the job is about, named — resolved by `GET /api/jobs` and `GET /api/jobs/{id}`, absent
   * (null) on the event stream and for a job naming nothing (`JobSubjectDto`).
   */
  subject?: JobSubject | null
}

/** The dataset, else product, else project a job's parameters name. */
export interface JobSubject {
  kind: 'dataset' | 'product' | 'project'
  id: string
  /** Null when the object has since been deleted. */
  name: string | null
  /** A dataset's repository — what re-running an import or refresh needs; null otherwise. */
  repositoryId: string | null
}

// ── Integration model (V16 — the durable, project-owned alignment store) ─────
// Mirrors `ModelBundleDto` from the backend. `GET /api/projects/{id}/model` is
// rendered straight from `IntegrationModelBundle`; the alignments view no longer
// keeps any of this in React state.

/** The four allowed mapping kinds — the exact alignment `type` strings the
 *  schema-integration operator round-trips (`core/domain/MappingKinds.java`). */
export type MappingKind = 'class' | 'datatype' | 'object' | 'join'

/** A mapping's lifecycle. Suggestions land as SUGGESTED; manual and accepted
 *  decisions are ACCEPTED; rejected suggestions are kept as REJECTED. */
export type MappingStatus = 'SUGGESTED' | 'ACCEPTED' | 'REJECTED'

/** Scores a `suggest-alignments` candidate carried, kept verbatim as a mapping's
 *  `evidence`. All fields optional — a manually added mapping has no evidence. */
export interface MappingEvidence {
  score?: number
  linguisticScore?: number
  valueScore?: number
  columnA?: string
  columnB?: string
  [key: string]: unknown
}

/** The integration-model header row. */
export interface IntegrationModelInfo {
  id: string
  projectId: string
  createdAt: string
  updatedAt: string
  /** The run whose draft is the active graph. `null` until a run has been promoted — which is
   *  exactly the "no baseline yet" state, so an incremental add is impossible until then. */
  activeIntegrationRunId: string | null
  /** Integrated material changed underneath the baseline (a source or an applied mapping was
   *  edited), so the next integration must be a full rebuild rather than an incremental add. */
  rebuildRequired: boolean
}

/** One enrolled `(dataset, class)` with its (optionally overridden) wrapper SQL. */
export interface ModelSource {
  id: string
  datasetId: string
  classIri: string
  wrapperSql: string | null
  /** Phase 3A compile-time verify-wrapper verdict for `wrapperSql`, computed and persisted
   *  together with it by `IntegrationModelService.setWrapper` (never written separately).
   *  Optional because existing fixtures/mocks built before Phase 3A omit it; `null`/absent both
   *  mean "never verified" and render the same as an explicit OPAQUE with no reason. */
  expansionStatus?: 'EXPANDED' | 'OPAQUE' | null
  expansionReason?: string | null
  /** False while `wrapperSql` is still the copy bootstrap produced — such a wrapper is re-seeded
   *  from the source graph on the dataset's next refresh, so it follows a schema change (V47).
   *  True once a person has saved their own through the Wrapper tab: it is never overwritten, and
   *  a compile fails with a clear error if it references a column the source has lost. Optional
   *  because fixtures written before V47 omit it; absent means "not edited". */
  wrapperEdited?: boolean
}

/** One alignment/correspondence owned by the project's model. */
export interface ModelMapping {
  id: string
  kind: MappingKind
  sourceIri: string
  targetIri: string
  label: string | null
  status: MappingStatus
  evidence: MappingEvidence | null
  author: string | null
  comment: string | null
  createdAt: string
  /** Where an accepted alignment stands (V19's application row), on the bundle read only:
   *  `PENDING` in no compiled draft yet, `APPLIED` compiled into `versionId` (the version that
   *  compiled it — whether it is live is `lib/alignmentStanding`'s lineage rule), or `DEFERRED`
   *  with the compiler's `reason`. Null for a mapping never accepted, and on a write's echo.
   *  Optional for fixtures that predate it. */
  application?: MappingApplicationInfo | null
}

export interface MappingApplicationInfo {
  state: 'PENDING' | 'APPLIED' | 'DEFERRED'
  versionId: string | null
  reason: string | null
}

/** A compiled draft still awaiting promotion (`GET /model`'s `latestDraftRun`). */
export interface DraftRunInfo {
  runId: string
  /** "REBUILD" | "INCREMENTAL". */
  mode: string
  draftVersionId: string
  draftVersionNumber: number
  compiledAt: string
}

export interface VersionRef {
  id: string
  versionNumber: number
}

/** A denial/boolean constraint over a class's global columns (enforced in M6). */
export interface ModelConstraint {
  id: string
  classIri: string
  expression: string
  /** Enforced **and live**, not the stored flag: the server reports false for an orphaned row
   *  (V42), because the query engine skips it and it filters nothing. */
  enforced: boolean
  createdAt: string
  /** "MANUAL" (typed by a person) or "DISCOVERED" (a LIMA candidate persisted unenforced by
   *  `POST .../constraints/discover`, Phase 3B) — a label for the review UI, not a different
   *  code path: a DISCOVERED row is enforced through the same PATCH as a MANUAL one. Optional
   *  because fixtures/mocks written before Phase 3B (e.g. services/demo/demoWorld.ts) construct
   *  a ModelConstraint without it; null/absent both mean "MANUAL" (the server itself defaults a
   *  null origin to MANUAL — see ModelConstraint.java), so they render identically: no chip. */
  origin?: 'MANUAL' | 'DISCOVERED' | null
  /** True when `classIri` is no longer an integrated class of the ACTIVE catalog — usually an
   *  alignment merged that class into a new one and retired it (V42). Optional because fixtures
   *  written before V42 (e.g. services/demo/demoWorld.ts) construct a ModelConstraint without it;
   *  absent means "not orphaned", the overwhelmingly common case. */
  orphaned?: boolean
}

/** The whole model in one shape — what `GET /model` returns. */
export interface IntegrationModelBundle {
  model: IntegrationModelInfo
  sources: ModelSource[]
  mappings: ModelMapping[]
  constraints: ModelConstraint[]
  /** Datasets already represented in the active integrated graph. Adding one of these
   *  incrementally is rejected as SOURCE_ALREADY_INTEGRATED, so the picker excludes them.
   *  Server-computed from the graph — the query catalog is not equivalent, because it only
   *  lists sources that ended up beneath an integrated class. */
  integratedDatasetIds: string[]
  /** The draft the model's last compile left unpromoted — survives a reload (J4-F2). */
  latestDraftRun?: DraftRunInfo | null
  /** The version the model's last promote made ACTIVE. An incremental add is refused
   *  STALE_INCREMENTAL_BASE unless it still is `activeVersion` (a rollback or another
   *  project's promote moved it). */
  baselineVersionId?: string | null
  /** The tenant's ACTIVE schema version — tenant-wide, not this project's. */
  activeVersion?: VersionRef | null
  /** This project's compile still in flight, so a reload re-attaches to it (review UA5). */
  compileJob?: CompileJobInfo | null
}

/** A compile still running — `GET /model`'s `compileJob` (`ModelDtos.CompileJobDto`). */
export interface CompileJobInfo {
  jobId: string
  /** "model:compile" | "model:incremental-integrate". */
  jobType: string
  /** "PENDING" | "RUNNING". */
  status: string
}

// ── Schema versions (M7) ─────────────────────────────────────────────────────
// The GraphVersion history made visible: list, diff, promote-with-notes, rollback.

/** One row of the schema-version history — `GET /api/schema/versions`. */
export interface SchemaVersion {
  id: string
  versionNumber: number
  /** "DRAFT" | "ACTIVE" | "HISTORICAL". */
  status: string
  createdAt: string
  createdBy: string | null
  promotedAt: string | null
  notes: string | null
}

/** A class present in both versions whose properties or wrapper changed. */
export interface ChangedClass {
  classIri: string
  addedProperties: string[]
  removedProperties: string[]
  wrapperChanged: boolean
}

/** Class/property-level diff between two versions — `GET .../{a}/diff/{b}`.
 *  "added" = present in b but not a; "removed" = present in a but not b. */
export interface SchemaDiff {
  addedClasses: string[]
  removedClasses: string[]
  changedClasses: ChangedClass[]
}

// ── Data products (M8) ───────────────────────────────────────────────────────
// A named OQL view defined against a schema version (provenance, not a pin: a
// refresh re-plans it under the ACTIVE version). STALE means a newer schema
// version went ACTIVE after its snapshot was built; changing the OQL = a new product (v1).

/** A data product's lifecycle. */
export type DataProductStatus = 'DRAFT' | 'MATERIALIZING' | 'READY' | 'STALE' | 'FAILED'

/**
 * How a product serves its rows (Phase 5).
 *
 * `MATERIALIZED` — a parquet snapshot, computed once and served until refreshed.
 * `VIRTUAL` — no snapshot: a read expands the product's OQL through the GAV pipeline under
 * the ACTIVE schema, so it recomputes from whatever that schema points at (a live source as it
 * is now, an upload as of its last refresh) and enforces the denial constraints active now.
 */
export type ProductAccessMode = 'MATERIALIZED' | 'VIRTUAL'

/** One data product — `GET /api/projects/{projectId}/products`. */
export interface DataProduct {
  id: string
  projectId: string
  name: string
  description: string | null
  oql: string
  /** The GraphVersion id the product was defined against, recorded at creation — never
   *  changes (v1). Provenance, not an execution pin. */
  schemaVersionId: string
  /** Optional only for a payload predating Phase 5 (or an older fixture) — absent means
   *  MATERIALIZED, its only meaning before this field existed. Read it through
   *  `accessModeOf` (lib/productAccessMode.ts) rather than directly, so "missing" can't
   *  diverge from "MATERIALIZED". */
  accessMode?: ProductAccessMode
  status: DataProductStatus
  /** The lake snapshot serving previews; null until first materialization, and always null
   *  for a VIRTUAL product (it has none). */
  snapshotId: string | null
  rowCount: number | null
  materializedAt: string | null
  lastError: string | null
  createdBy: string | null
  createdAt: string
  /** The GraphVersion id the last successful build ran under (UI-OD12, V46) — provenance, not a
   *  pin: what a MATERIALIZED product's rows mean. Null when never built, VIRTUAL, or built before
   *  V46 recorded it; optional only for an older payload. */
  builtAgainstVersionId?: string | null
  /** The table it is served as in the JDBC `products` schema and the Iceberg `products` namespace
   *  (ProductCatalog — normalised, possibly suffixed). Optional only for an older payload. */
  tableName?: string
  /** Whether the read-only Iceberg catalog serves it (`ProductTable.servedOverIceberg`, the rule the
   *  catalog filters on): false for VIRTUAL, never built, and a build from before V29 recorded its
   *  columns. Optional only for an older payload. */
  servedOverIceberg?: boolean
  /** The cube whose star holds this product; such a product is deleted with its cube. */
  memberOfCube?: string | null
}

// ── API tokens ─────────────────────────────────────────────────────────────

/** A token as the API describes it. The secret is never in this shape. */
export interface ApiTokenSummary {
  id: string
  name: string
  /** Displayable head of the token, e.g. `aran_pat_3f2a`. */
  prefix: string
  scope: 'READ' | 'FULL'
  status: 'active' | 'expired' | 'revoked'
  expiresAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  createdAt: string
}

/** A member of the caller's tenant. `GET /api/tenant/members`. */
export interface TenantMember {
  userId: string
  email: string
  displayName: string
  role: 'OWNER' | 'MEMBER'
  joinedAt: string
  /**
   * Last authenticated request, to the minute; null when never seen — not "long ago" — and always
   * null for a caller who is not an owner (per-person activity is an administrator's view).
   */
  lastSeenAt?: string | null
}

/**
 * A member plus a temporary password.
 *
 * The password is present exactly once — in the response that created or reset it. Aran
 * keeps no copy and cannot show it again.
 */
export interface CreatedTenantMember {
  member: TenantMember
  /** A new one on a reinstatement too: no credential from before the removal works again (UI-OD9). */
  temporaryPassword: string
  /** True when the email was of someone this organization removed, whose account came back instead of being created. */
  reinstated?: boolean
}

/** A project named in a removal's result. */
export interface ProjectRef {
  id: string
  name: string
}

/** `DELETE /api/tenant/members/{userId}` — what the removal took away, and what changed hands. */
export interface RemovedTenantMember {
  tokensRevoked: number
  projectAccessRemoved: number
  /** Projects only the removed member belonged to, now shared with the owner who removed them. */
  projectsHandedOver: ProjectRef[]
}

/** The response to creating a token — the only place `secret` is ever populated. */
export interface CreatedApiToken {
  token: ApiTokenSummary
  secret: string
}

/* ── Tenant administration (`/api/tenant/admin/**`, OWNER only) ──────────────
   Read-only shapes. Every write an owner performs goes through
   `/api/tenant/members`; nothing below can edit what it displays. */

/**
 * One member's consumption over the reporting window.
 *
 * `userId` is null for the single synthetic row covering work with no attributable
 * human — a scheduled refresh, a system job. It is shown rather than hidden, so a
 * tenant whose usage is mostly unattributed can see that it is.
 */
export interface MemberUsage {
  userId: string | null
  displayName: string
  email: string
  role: 'OWNER' | 'MEMBER' | null
  lastSeenAt: string | null
  events: number
  rows: number
  /** Bytes this person sent to Aran — uploads. */
  bytesIn: number
  /** Bytes Aran sent back to them. The egress a cloud bill charges for. */
  bytesOut: number
  /** Null when the member ran nothing in the window — not zero, which would read as "instant". */
  p95DurationMs: number | null
}

/** `GET /api/tenant/admin/overview?days=N`. */
export interface TenantOverview {
  tenantSlug: string
  tenantName: string
  /** Start of the window every figure here is measured over. */
  since: string
  memberCount: number
  activeTokenCount: number
  totalEvents: number
  totalRows: number
  totalBytesIn: number
  totalBytesOut: number
  /** Bytes written to the lake on the tenant's behalf — the flow that grows the storage bill. */
  totalBytesStored: number
  members: MemberUsage[]
}

/**
 * What a token can do right now, computed by the server (audit J7-F8). `owner_removed` is a token
 * whose holder left the tenant without it being revoked — only removals made before UI-OD9 left
 * such rows — and it cannot authenticate.
 */
export type AdminTokenStatus = 'active' | 'expired' | 'revoked' | 'owner_removed'

/**
 * A token belonging to anyone in the tenant. `GET /api/tenant/admin/tokens`.
 *
 * Distinct from {@link ApiTokenSummary}, which is the caller's own view of their own
 * tokens: this one names the owner. An owner revokes one through
 * `DELETE /api/tenant/admin/tokens/{id}` — audited, owner-only (J7-F8).
 */
export interface AdminTokenSummary {
  id: string
  userId: string
  ownerName: string
  name: string
  /** Displayable head only — Aran stores a hash and cannot reproduce the secret. */
  prefix: string
  scope: 'READ' | 'FULL'
  lastUsedAt: string | null
  expiresAt: string | null
  revoked: boolean
  createdAt: string
  /** Optional on the wire only for a server predating it; read absent as unknown, never as active. */
  status?: AdminTokenStatus
}

/** An append-only governance event. `GET /api/tenant/admin/activity?limit=N`. */
export interface AuditEntry {
  id: string
  tenantId: string
  actorId: string | null
  action: string
  targetType: string
  targetId: string | null
  metadata: Record<string, string>
  occurredAt: string
}

/**
 * What the tenant is holding. `GET /api/tenant/admin/storage`.
 *
 * Read live from the lake on every request, not served from the newest sample: someone
 * opening this is usually asking whether they are about to fill the disk, and yesterday's
 * number is the wrong answer to that.
 */
export interface StorageReport {
  snapshotCount: number
  /** Sum over every snapshot — what the tenant believes it stored. */
  logicalBytes: number
  /** Sum over distinct content hashes — what actually occupies the bucket. */
  physicalBytes: number
  oldestSnapshotAt: string | null
  newestSnapshotAt: string | null
  datasets: DatasetStorage[]
  /** What was held at the start of the growth window; null when never measured that far back. */
  baselinePhysicalBytes: number | null
  baselineMeasuredAt: string | null
  /** True when the breakdown hit its limit, so it is the largest N rather than all of them. */
  datasetsTruncated: boolean
  unreferenced: Orphans
}

export interface DatasetStorage {
  datasetId: string
  snapshotCount: number
  logicalBytes: number
}

/**
 * Snapshots nothing points at — reclaimable disk.
 *
 * `known: false` means the scan could not run, which is emphatically not the same as zero and
 * must never render as "nothing to reclaim".
 */
export interface Orphans {
  bytes: number
  snapshots: number
  scanned: number
  /** False when the scan hit its bound, so `bytes` is a lower bound rather than the total. */
  complete: boolean
  known: boolean
}

/**
 * Version and component health. `GET /api/tenant/admin/system`.
 *
 * Relayed from actuator rather than probed separately: Aran's management port is
 * internal-only, and a second set of probes written for the UI would drift from the ones
 * an orchestrator actually acts on.
 */
export interface SystemStatus {
  version: string
  /** Null when built without git available. */
  commit: string | null
  status: string
  /** Not-UP first — the reason to open this page is to find what is wrong. */
  components: ComponentStatus[]
}

export interface ComponentStatus {
  name: string
  status: string
  details: Record<string, string>
}

/**
 * One entry of `GET .../datasets/refresh-policies` — a dataset's upstream and how often Aran goes
 * back to it.
 *
 * **A dataset with no upstream has no entry at all**, and that is the wire shape on purpose: an
 * uploaded file *is* its own source, so "not refreshable" is the absence of a record rather than a
 * policy of `NEVER`. A UI that renders a missing entry as a disabled schedule is reading it right.
 *
 * `stalenessSeconds` is null when the dataset has never been refreshed — distinct from 0, which
 * means "refreshed just now". Computed server-side so the two cannot be conflated by a default.
 */
export interface DatasetRefreshPolicy {
  datasetId: string
  sourceTable: string
  policy: 'MANUAL' | 'EVERY'
  intervalSeconds: number | null
  lastRefreshAt: string | null
  nextRunAt: string | null
  stalenessSeconds: number | null
  lastError: string | null
}

/** The shortest interval the backend accepts (RefreshPolicy.Every.MINIMUM). */
export const MIN_REFRESH_INTERVAL_SECONDS = 300

// ── Cubes (OLAP shaping) ──────────────────────────────────────────────────────

/**
 * How much a measure survives being summed.
 *
 * Not decoration: `NON_ADDITIVE` is the unit-price shape, where a SUM is meaningless and an
 * AVG-of-AVGs is the classic reporting trap, and `RATIO` is the honest way to express one
 * (aggregate numerator and denominator, then divide). The wizard shows the chip so a wrong
 * aggregator is visible at modelling time rather than in a BI tool six weeks later.
 */
export type Additivity = 'ADDITIVE' | 'SEMI_ADDITIVE' | 'NON_ADDITIVE' | 'RATIO'

/**
 * Trust tier of the referential edge a dimension joins on — the same
 * `MappingCatalog.ReferentialEdge` tiers the planner costs off (Phase 4B), surfaced verbatim
 * so the join-trust language stays one language everywhere. `HYPOTHESIS` is not "probably
 * fine": nothing has shown the edge is to-one, so a join on it can double-count fact rows.
 */
export type CubeEdgeTier = 'HYPOTHESIS' | 'DECLARED' | 'VERIFIED'

/** One column of a candidate class, with the profile evidence the suggestions are made from. */
export interface CubeEvidenceProperty {
  iri: string
  name: string
  /** The XSD range, e.g. `http://www.w3.org/2001/XMLSchema#date`; null when unprofiled. */
  xsd: string | null
  numeric: boolean
  /** Distinct values, null when the column has no profile — never conflate with 0. */
  ndv: number | null
  nullFraction: number | null
}

export interface CubeEvidenceClass {
  iri: string
  name: string
  /** Null when nothing profiled the class; the fact ranking treats it as no evidence, not zero. */
  rowCount: number | null
  properties: CubeEvidenceProperty[]
}

/** An accepted join between two integrated classes — a candidate fact→dimension edge. */
export interface CubeEvidenceEdge {
  propertyIri: string
  propertyName: string
  leftClassIri: string
  rightClassIri: string
  /** Join key columns, as the full global property IRIs the `int_` views are keyed by. */
  leftColIri: string
  rightColIri: string
  tier: CubeEdgeTier
}

/** `GET /api/projects/{projectId}/cubes/evidence` — everything the wizard pre-fills from. */
export interface CubeEvidence {
  classes: CubeEvidenceClass[]
  edges: CubeEvidenceEdge[]
}

/** Either a column (`columnIri`) or a derived expression (`expr`) — never both. */
export interface CubeMeasureDto {
  name: string
  columnIri: string | null
  expr: string | null
  aggregator: string | null
  additivity: Additivity
}

export interface CubeDimensionDto {
  name: string
  classIri: string
  joinPropertyIri: string
  /** Keep fact rows whose FK is NULL under an "unknown" member, so totals still reconcile. */
  unknownMember: boolean
  /** Level columns, coarsest first. Empty means a flat dimension, which is legitimate. */
  hierarchy: string[]
  /** The explicit acknowledgement that a `HYPOTHESIS` edge may double-count. */
  allowUnverified: boolean
}

export interface CubeCalendarDto {
  fromColumnIri: string
  levels: string[]
}

export interface CubeDefinitionDto {
  factClassIri: string
  measures: CubeMeasureDto[]
  dimensions: CubeDimensionDto[]
  calendar: CubeCalendarDto | null
}

/** A data product a cube created, and which half of the star it is. */
export interface CubeProductRef {
  id: string
  role: 'FACT' | 'DIMENSION'
  name: string
}

export interface CubeDto {
  id: string
  name: string
  description: string | null
  factClassIri: string
  createdAt: string
  products: CubeProductRef[]
  definition: CubeDefinitionDto
  jobIds: string[]
}

export interface CreateCubeRequest {
  name: string
  description?: string
  definition: CubeDefinitionDto
}

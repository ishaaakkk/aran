import type { DataProduct } from '@/services/types'
import { accessModeOf } from '@/lib/productAccessMode'
import { AGENT_READABLE_STATUSES } from '@/lib/agentSidecar'
import { errorMessage } from '@/lib/errors'

/**
 * What the products screen can say about a product's readers without asking anything: the table it
 * is served as, which doors serve it, what a delete breaks, and which schema version its rows mean.
 * One place, so the detail panel's "Connect" block and the delete dialog cannot disagree (A12).
 */

const shortId = (id: string) => id.slice(0, 8)

function versionLabel(id: string, versionNumberById: Record<string, number>): string {
  const n = versionNumberById[id]
  return n !== undefined ? `v${n}` : shortId(id)
}

/**
 * Provenance (UI-OD12): the version a MATERIALIZED product's rows were built under, beside the
 * version it was defined against when the two differ. A refresh always runs under ACTIVE, so the
 * build version — not the definition-time one — is what the rows mean. Null for VIRTUAL (no build).
 */
export function builtAgainstLabel(p: DataProduct, versionNumberById: Record<string, number>): string | null {
  if (accessModeOf(p) === 'VIRTUAL') return null
  if (!p.snapshotId) return 'not built yet'
  if (!p.builtAgainstVersionId) return 'built before builds recorded their schema version'
  const built = `built against ${versionLabel(p.builtAgainstVersionId, versionNumberById)}`
  return p.builtAgainstVersionId === p.schemaVersionId
    ? built
    : `${built} · defined against ${versionLabel(p.schemaVersionId, versionNumberById)}`
}

/** A product's served table, as ProductCatalog names it; the plain name only for an older payload. */
export function tableNameOf(p: DataProduct): string {
  return p.tableName ?? p.name
}

/** Whether anything can read it now: a built MATERIALIZED product, or any VIRTUAL one. */
function readable(p: DataProduct): boolean {
  return accessModeOf(p) === 'VIRTUAL' || !!p.snapshotId
}

/**
 * The client-side half of a connection, the same for every product: what a JDBC tool is pointed
 * at and which driver it loads, and the Iceberg REST catalog a table name resolves against. The
 * shapes are docs-site's jdbc.md and iceberg.md, which is where the links from the Connect block
 * go; the driver version is the avatica-core the server is built with (pinned by a test against
 * aran-application/build.gradle).
 */
export interface ConnectionDetails {
  jdbcUrl: string
  jdbcDriverClass: string
  /** The Maven artifact and version a JDBC tool loads the driver class from. */
  jdbcDriverArtifact: string
  /** The base an Iceberg client appends `/v1/…` to — DuckDB's ENDPOINT, iceberg-core's `uri`. */
  icebergCatalogUri: string
  icebergNamespace: string
}

export const JDBC_DRIVER_CLASS = 'org.apache.calcite.avatica.remote.Driver'
export const JDBC_DRIVER_ARTIFACT = 'avatica-core 1.27.0'
/** Where the token goes in the URL. A placeholder, never a secret — this screen has none. */
export const TOKEN_IN_URL_PLACEHOLDER = '<your-token>'

/** @param baseUrl the backend's own origin as an external client reaches it (`suggestBackendUrl`). */
export function connectionDetails(baseUrl: string): ConnectionDetails {
  const base = baseUrl.replace(/\/+$/, '')
  return {
    jdbcUrl: `jdbc:avatica:remote:url=${base}/api/jdbc;serialization=json;authentication=BASIC;`
      + `avatica_user=aran;avatica_password=${TOKEN_IN_URL_PLACEHOLDER}`,
    jdbcDriverClass: JDBC_DRIVER_CLASS,
    jdbcDriverArtifact: JDBC_DRIVER_ARTIFACT,
    icebergCatalogUri: `${base}/api/iceberg`,
    icebergNamespace: 'products',
  }
}

export interface ProductDoors {
  /** The statement a JDBC client runs, or null when the product is not a table yet. */
  jdbc: string | null
  iceberg: { served: true; table: string } | { served: false; reason: string }
}

export function productDoors(p: DataProduct): ProductDoors {
  const table = `products.${tableNameOf(p)}`
  const jdbc = readable(p) ? `SELECT * FROM ${table}` : null
  if (accessModeOf(p) === 'VIRTUAL') {
    return { jdbc, iceberg: { served: false, reason: 'A virtual product has no files for an Iceberg reader — it is recomputed on every read.' } }
  }
  if (!p.snapshotId) {
    return { jdbc, iceberg: { served: false, reason: 'Not built yet — refresh it to create the snapshot every reader serves.' } }
  }
  // The server's own answer (the catalog's filter); absent only from a payload older than the flag.
  if (p.servedOverIceberg === false) {
    return {
      jdbc,
      iceberg: {
        served: false,
        reason: 'Built before builds recorded their columns, which Iceberg metadata needs — refresh it once and the catalog serves it.',
      },
    }
  }
  return { jdbc, iceberg: { served: true, table } }
}

/**
 * Whether "all rows" can be downloaded, and why not. A VIRTUAL product exports its OQL live; a
 * MATERIALIZED one's rows are its snapshot, fetched over the same gated file route the Iceberg
 * catalog hands engines — so only a product that catalog serves has one to fetch.
 */
export function allRowsDownload(p: DataProduct): { available: true } | { available: false; reason: string } {
  if (accessModeOf(p) === 'VIRTUAL') return { available: true }
  if (!p.snapshotId) return { available: false, reason: 'Not built yet — refresh it first' }
  if (p.servedOverIceberg === false) {
    return { available: false, reason: 'Built before builds recorded their columns — refresh it once to download every row' }
  }
  return { available: true }
}

/**
 * What a failed "all rows" download says. The file route answers 404 when the snapshot asked for
 * is no longer the product's current one (a refresh replaced it while the page was open) or the
 * product is not served as a table — "download failed: 404" told the user neither (J6-F4).
 */
export function downloadFailureMessage(err: unknown): string {
  const status = typeof err === 'object' && err !== null ? (err as { status?: unknown }).status : undefined
  if (status === 404) {
    return 'This snapshot is no longer served — reload the list to pick up the current one, or refresh the product to rebuild it.'
  }
  return errorMessage(err, 'Download failed.')
}

/**
 * Whether opening a product loads its preview at once (J6-F19). A MATERIALIZED preview reads the
 * stored snapshot — cheap and side-effect free. A VIRTUAL one re-runs the product's whole OQL
 * against its sources, possibly production, so it waits for an explicit "run live preview".
 */
export function previewsOnOpen(p: DataProduct): boolean {
  return accessModeOf(p) === 'MATERIALIZED' && !!p.snapshotId
}

/** The testid of the button that opens a product from the list — where focus returns. */
export const openButtonTestId = (name: string) => `product-open-${name}`

/**
 * Whose open button takes focus when the detail panel closes, so a keyboard user is not dropped on
 * the page body: the product itself, or — when it was just deleted and its row is going — the next
 * row (the previous one at the end of the list). Null when nothing is left to land on.
 */
export function focusAfterClose(closedId: string, products: DataProduct[], deleted: boolean): string | null {
  const i = products.findIndex(p => p.id === closedId)
  if (i < 0) return null
  if (!deleted) return products[i].name
  return (products[i + 1] ?? products[i - 1])?.name ?? null
}

/**
 * A product anything can read is deleted only after its name is typed back (J6-F17). Readable is
 * the JDBC rule (`ProductTable.queryable`), whatever the status: a product whose last refresh
 * FAILED still serves its previous snapshot to a dashboard, and deleting it is just as final.
 */
export function deleteNeedsTypedName(p: DataProduct): boolean {
  return readable(p)
}

/** The readers a delete breaks, each named, for the confirmation dialog — only doors that serve it now. */
export function deleteImpact(p: DataProduct): string[] {
  if (!readable(p)) {
    return ['Nothing reads it yet — it has never been built.']
  }
  const doors = productDoors(p)
  const lines = [`JDBC clients: ${doors.jdbc} stops resolving.`]
  if (doors.iceberg.served) lines.push(`Iceberg readers: the table ${doors.iceberg.table} disappears from the catalog.`)
  // The sidecar already hides a FAILED or MATERIALIZING product, so a delete takes nothing from agents.
  if (AGENT_READABLE_STATUSES.has(p.status)) lines.push(`Agents (MCP): "${p.name}" is no longer listed or readable.`)
  if (p.snapshotId) lines.push('Its last snapshot stays in the lake, but nothing will serve it.')
  return lines
}

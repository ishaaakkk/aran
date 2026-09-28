/**
 * What a destructive or effectful click actually does, in one sentence per effect.
 *
 * Each of these replaced copy that understated its click (UI audit §3, A1 truth pass): a
 * repository delete said its datasets "stop refreshing" while `DataRepositoryService`
 * deletes them (V7 cascade) after rejecting their mappings; a dataset delete removed
 * "metadata"; enforcing a denial constraint said "recompile to apply" for a rule the query
 * engine applies on the very next query. Kept out of the view components so the wording is
 * asserted without a DOM (`impactCopy.test.ts`) and so each effect has one sentence, not one
 * per screen that can trigger it.
 *
 * The facts, so the sentences can be checked against code rather than memory:
 * - repository delete: `DataRepositoryService.deleteRepository` calls
 *   `IntegrationModelService.detachDataset` per dataset (reject its mappings, flag a rebuild,
 *   clear its source graph), then the V7 FK cascades the dataset rows away. Lake objects are
 *   not deleted.
 * - dataset delete: `DatasetService.deleteDataset` — the same detach, then the row.
 * - product delete: `DataProductService.delete` removes the registry row only; readers resolve
 *   products from the registry, so JDBC, MCP and the Iceberg catalog lose it at once.
 * - constraint enforce/disable: `QueryEngineOperator` reads enforced constraints per query into
 *   the `u_`/`int_` views (graph tier), so VIRTUAL products follow at once and a MATERIALIZED
 *   snapshot only at its next refresh; the `sources` tier is never filtered.
 */

/** The confirm shown before a repository delete. `datasetCount` is undefined while unknown. */
export function repositoryDeleteConfirm(name: string, datasetCount: number | undefined): string {
  const datasets =
    datasetCount === undefined
      ? 'every dataset registered in it'
      : datasetCount === 0
        ? 'no datasets (none are registered in it)'
        : `its ${datasetCount} dataset${datasetCount === 1 ? '' : 's'}`
  return (
    `Delete the repository "${name}"? This also deletes ${datasets}. ` +
    'Their mappings are rejected and the integration model is flagged for a rebuild; ' +
    'classes left without a source drop out at the next compile. Lake copies are kept.'
  )
}

/** The confirm shown before a dataset delete. */
export function datasetDeleteConfirm(name: string): string {
  return (
    `Delete the dataset "${name}"? Its mappings are rejected and the integration model is ` +
    'flagged for a rebuild; classes it was the last source of drop out at the next compile. ' +
    'Its lake copy is kept.'
  )
}

/** The confirm shown before a data product delete. */
export function productDeleteConfirm(name: string): string {
  return (
    `Delete the data product "${name}"? JDBC clients, agents and Iceberg readers lose it ` +
    'immediately. Its lake snapshot is kept.'
  )
}

/**
 * The toast after a constraint's enforced flag flips. `enforcedNow` is the NEW state.
 * `className` is the integrated class's display name, when known.
 */
export function constraintToggleToast(enforcedNow: boolean, className?: string | null): string {
  const cls = className ? ` of ${className}` : ''
  if (enforcedNow) {
    return (
      `Constraint enforced — it filters the next query${cls} in every project, VIRTUAL ` +
      'products included; MATERIALIZED products change at their next refresh.'
    )
  }
  return `Constraint disabled — the next query${cls} includes the rows it filtered.`
}

/** What an enforced constraint does, for the Constraints tab's tooltip and empty state. */
export const CONSTRAINT_ENFORCE_EFFECT =
  'Once enforced, rows that break it are kept out of graph-tier queries of this class from the ' +
  'next query on — OQL, the JDBC graph schema and VIRTUAL products — and set aside for review. ' +
  'MATERIALIZED products change at their next refresh; the sources tier is never filtered.'

/**
 * The toast after a wrapper save. The prefix is an e2e contract (`DatasetsPage.saveWrapper`
 * waits on it) — extend after it, never reword it.
 */
export const WRAPPER_SAVED_TOAST =
  'Wrapper SQL saved to the integration model. Queries use it after the next Rebuild and Promote.'

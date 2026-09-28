-- Phase 4B (Task 3): foreign keys a relational source's own catalog declares, harvested live
-- (RelationalCatalogHarvestService, Task 2) and persisted here so a catalog build can resolve them
-- into DECLARED-tier MappingCatalog.ReferentialEdges without a live JDBC round trip.
--
-- Endpoints are Aran dataset ids, not table-name strings: a table name is only unique within one
-- repository's own catalog, and this table is read across every repository in a tenant at once
-- (DeclaredForeignKeyRepository.findAllByTenant), so two repositories that each happen to have a
-- table called "orders" must never be able to collide here. See the domain record's javadoc for
-- the full reasoning, including why fromCols/toCols stay physical column names rather than being
-- resolved to property IRIs at this layer.
CREATE TABLE declared_foreign_key (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    -- Denormalised from the endpoint datasets so a fresh harvest can replace exactly what an
    -- earlier harvest of THIS repository produced (HarvestPersistenceService.persist) without a
    -- join, mirroring dataset_refresh.repository_id's own reasoning.
    repository_id UUID NOT NULL,
    from_dataset_id UUID NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    -- Ordinal-paired with to_cols — position i of from_cols references position i of to_cols.
    -- jsonb, not a join table: nothing ever queries by an individual column name, only reads the
    -- pairing back whole (same choice dataset.denial_constraints already made for its own
    -- small ordered string list).
    from_cols JSONB NOT NULL,
    to_dataset_id UUID NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    to_cols JSONB NOT NULL
);

-- Serves both hot paths: HarvestPersistenceService.persist's per-repository replace (tenant_id,
-- repository_id) and MappingCatalogOperator.buildCatalog's per-tenant read at every catalog build
-- (tenant_id alone, the index's own leftmost-prefix) — one index, not two.
CREATE INDEX idx_declared_foreign_key_repository
    ON declared_foreign_key (tenant_id, repository_id);

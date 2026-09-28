-- Phase 3A: the compile-time verify-wrapper verdict, persisted alongside the wrapper SQL it was
-- computed for (IntegrationModelService.setWrapper writes all three together, one row, one
-- write). Both columns are nullable and NULL together on every row that predates this migration
-- and on any source enrolled but never edited through the wrapper endpoint: NULL means "never
-- verified", which is exactly the OPAQUE default MappingCatalogOperator's QUERY 1 already applies
-- when a source class carries no nx:wrapperExpansion triple — not a third state anything has to
-- special-case.
ALTER TABLE model_source ADD COLUMN expansion_status VARCHAR(16);
ALTER TABLE model_source ADD COLUMN expansion_reason VARCHAR(512);

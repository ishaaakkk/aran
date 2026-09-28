-- Indexes for columns that are filtered on hot paths but were never indexed.
--
-- model_constraint got none in V16 (which indexed model_mapping and model_source only),
-- yet QueryEngineOperator.enforcedByClass reads it via findAllByTenantIdAndEnforcedTrue on
-- EVERY run, run-oql, explain, catalog and materialize-oql invocation — a sequential scan
-- per query, growing with every constraint any tenant has ever created.
CREATE INDEX IF NOT EXISTS idx_model_constraint_tenant_enforced
    ON model_constraint (tenant_id, enforced);

-- Constraints are also fetched per model when the model is loaded or recompiled.
CREATE INDEX IF NOT EXISTS idx_model_constraint_model
    ON model_constraint (model_id);

-- membership has uq_membership_user_tenant UNIQUE (user_id, tenant_id) and
-- idx_membership_user_id, both led by user_id. The licensing UsageCounter's
-- countByTenantId / countByTenantIdAndRole filter on tenant_id alone, which neither
-- index can serve efficiently.
CREATE INDEX IF NOT EXISTS idx_membership_tenant_id
    ON membership (tenant_id);

-- Schema drift detection: the comparable shape of each dataset's bootstrapped source schema,
-- captured over time.
--
-- Why store this rather than re-derive it from the source graph: re-bootstrapping REPLACES that
-- graph (BootstrappingOperator clears the named graph before writing), so by the time you want to
-- know what changed, the "before" is already gone. Federated datasets are worse — they have no
-- graph refresh at all, because postgres_scan/mysql_scan read the remote table live at query
-- time, so there is no ingest event to derive anything from.
--
-- One row per capture, never updated. History is the point: "this column has changed type three
-- times this month" is a different and more useful statement than "it is VARCHAR now".
CREATE TABLE dataset_schema_fingerprint (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    dataset_id UUID NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    -- Hex SHA-256 over the canonical rendering. The cheap "did anything change at all?" test,
    -- so the overwhelmingly common no-drift case costs one string comparison, not a full diff.
    fingerprint_hash CHAR(64) NOT NULL,
    -- The full SchemaFingerprint as JSON. Needed because the hash alone says THAT something
    -- changed, never what — and the diff needs both sides.
    fingerprint JSONB NOT NULL,
    -- Which source the fingerprint was taken from: the lake snapshot id for file-backed
    -- datasets, null for federated ones (a live probe has no snapshot to name).
    snapshot_id VARCHAR(128),
    -- SNAPSHOT (introspected from bytes) or LIVE_PROBE (DESCRIBE against a federated source).
    captured_via VARCHAR(16) NOT NULL DEFAULT 'SNAPSHOT',
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The hot query is "the newest fingerprint for this dataset", run on every bootstrap and every
-- drift check. DESC on captured_at so that read is an index scan of one row.
CREATE INDEX idx_dataset_fingerprint_latest
    ON dataset_schema_fingerprint (tenant_id, dataset_id, captured_at DESC);

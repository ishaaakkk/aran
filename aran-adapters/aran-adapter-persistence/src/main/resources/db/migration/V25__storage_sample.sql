-- Storage is a level, not a flow, and the two cannot share a table.
--
-- usage_event records what moved: every row is an independent quantity and summing them is
-- the whole point. Storage records what remains, and summing two readings of it is
-- meaningless — a tenant holding 6 GB yesterday and 6 GB today holds 6 GB, not 12. Putting a
-- level into an event log is the mistake that produces dashboards where "total storage"
-- climbs forever because nobody noticed they were adding up snapshots of the same number.
--
-- So: one row per tenant per measurement, and every query against it takes the *latest* row
-- or compares two, never a SUM.
--
-- Why keep samples at all, when the lake can be asked for the current figure at any time?
-- Because the current figure does not answer the question an operator actually has. "6.1 GB"
-- is not actionable; "6.1 GB, and it was 4.9 GB a month ago" is. Growth rate is what predicts
-- a full disk, and it can only come from history.

CREATE TABLE storage_sample (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    measured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Sum over every snapshot row: what the tenant believes it stored.
    logical_bytes   BIGINT      NOT NULL,
    -- Sum over distinct content hashes: what actually occupies the bucket, and therefore what
    -- a storage bill is drawn against. Snapshots are content-addressed, so re-uploading the
    -- same bytes shows up in the first number and not the second.
    physical_bytes  BIGINT      NOT NULL,
    snapshot_count  BIGINT      NOT NULL,
    CONSTRAINT pk_storage_sample        PRIMARY KEY (id),
    CONSTRAINT fk_storage_sample_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
        -- CASCADE, unlike usage_event's user reference: a deleted tenant's storage history
        -- describes bytes that are also gone. There is nothing left for it to be evidence of.
        ON DELETE CASCADE,
    CONSTRAINT ck_storage_sample_nonneg CHECK (
        logical_bytes >= 0 AND physical_bytes >= 0 AND snapshot_count >= 0
    )
);

-- The only two access patterns: the latest reading for a tenant, and the readings since a
-- date to draw a trend. Both are this index, read in the same direction.
CREATE INDEX idx_storage_sample_tenant_time ON storage_sample (tenant_id, measured_at DESC);

COMMENT ON TABLE storage_sample IS
    'Periodic readings of tenant storage. A level, never summed — take the latest row, or diff two.';

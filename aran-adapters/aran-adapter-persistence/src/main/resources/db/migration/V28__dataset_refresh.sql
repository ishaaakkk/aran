-- A materialized dataset's upstream, and how often Aran goes back to it.
--
-- One row per dataset that HAS an upstream — which is far from all of them. An uploaded CSV is its
-- own source: there is nothing to refresh from, and that is expressed here as the absence of a row
-- rather than as a NULL-heavy row with a 'NEVER' policy. Two things follow. The contradiction "no
-- upstream, hourly schedule" becomes unrepresentable instead of needing a check constraint, and
-- the scheduler's hot query scans a small table instead of every dataset in the installation.
--
-- Federated datasets have no row either: there is no copy to remake. That is structural, not a
-- rule someone has to remember.
CREATE TABLE dataset_refresh (
    -- One refresh policy per dataset, hence the PK on dataset_id rather than a surrogate.
    dataset_id UUID PRIMARY KEY REFERENCES dataset(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    -- Denormalised from the dataset's repository so a refresh can re-open the source connection
    -- without a join, and so the scheduler (which runs outside any tenant) can hand a complete
    -- work item to the service without a second query.
    repository_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    -- WHO a scheduled refresh runs as. A schedule has no logged-in user, and the alternative to
    -- naming one is a system identity that bypasses authorization — an ambient superuser that
    -- exists solely because nobody wanted to answer this question. Running as the person who set
    -- the schedule means the refresh has exactly the access they have: if they lose the project or
    -- the account is deleted, the refresh starts failing visibly instead of continuing to read a
    -- source its owner can no longer see.
    created_by UUID NOT NULL REFERENCES app_user(id),
    -- The relation in the upstream, as listAvailableDatasets reports it. Re-checked against that
    -- list on every refresh rather than trusted: a table can be dropped between import and
    -- refresh, and this string must never reach SQL unvalidated.
    source_table VARCHAR(512) NOT NULL,
    -- MANUAL or EVERY. Never 'NEVER' — that is the absent row, see above.
    policy VARCHAR(32) NOT NULL,
    -- Non-null exactly when policy = 'EVERY'.
    interval_seconds BIGINT,
    -- When the last refresh that actually LANDED completed. Not moved by a failure: the snapshot
    -- being served is still the one from this timestamp, so moving it would report the data as
    -- fresher than it is.
    last_refresh_at TIMESTAMPTZ,
    -- Non-null exactly when policy = 'EVERY'. NULL means "never due", which is what lets the
    -- scheduler's query be a plain next_run_at <= now() with no policy inspection at all.
    next_run_at TIMESTAMPTZ,
    -- Why the last refresh failed. On the row rather than only in the job log because a schedule
    -- that has been quietly failing for a week is exactly what a user needs to see, and job rows
    -- are pruned.
    last_error TEXT,

    -- The two halves of the policy kept in step in the database, not only in the record. The
    -- record's compact constructor enforces the same thing for anything that goes through the
    -- domain; this catches a migration or a direct UPDATE that does not.
    CONSTRAINT ck_dataset_refresh_policy CHECK (
        (policy = 'MANUAL' AND interval_seconds IS NULL AND next_run_at IS NULL)
        OR (policy = 'EVERY' AND interval_seconds IS NOT NULL AND next_run_at IS NOT NULL)
    ),
    -- Matches RefreshPolicy.Every.MINIMUM. An extract copies the whole table, so a short interval
    -- is a load generator rather than a freshness setting.
    CONSTRAINT ck_dataset_refresh_interval CHECK (
        interval_seconds IS NULL OR interval_seconds >= 300
    )
);

-- The scheduler's only query: "what is due?", installation-wide. Partial, because the rows it must
-- never look at are the majority — every manual policy has next_run_at NULL and is excluded from
-- the index entirely rather than scanned past.
CREATE INDEX idx_dataset_refresh_due
    ON dataset_refresh (next_run_at)
    WHERE next_run_at IS NOT NULL;

-- Listing a repository's datasets with their freshness.
CREATE INDEX idx_dataset_refresh_repository
    ON dataset_refresh (tenant_id, repository_id);

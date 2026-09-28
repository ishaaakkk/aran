-- V44: the three connector-less repository kinds are gone (D1, 2026-09-02 analysis §4, Stage 0
-- item 5). NOSQL_DOCUMENT, KEY_VALUE and WEB_API could be registered and connection-tested and
-- nothing else: no SourceConnector ever read them, DatasetService refused a dataset under them, and
-- each carried a credential-bearing probe to a caller-chosen host. Their enum constants, records,
-- entities, DTOs, probes and forms were deleted in the same change, so a surviving row would name a
-- discriminator value Hibernate can no longer map, and every read of that project's repositories
-- would fail rather than skip it.
--
-- Rows only. The V6 columns only these kinds wrote (connection_string, collection_name,
-- database_index, base_url, auth_type, credentials) stay: ddl-auto: validate ignores extra columns,
-- they are NULL on every surviving row, and dropping credential columns is a step with a failure
-- mode (lost ciphertext) that buys nothing here.
--
-- Datasets first, although dataset.repository_id is ON DELETE CASCADE: explicit about what goes,
-- and every table that references dataset (column_profile, dataset_schema_fingerprint,
-- dataset_refresh, declared_foreign_key, model_source) cascades from it in turn. A Fuseki source
-- graph cannot exist for these datasets — none could ever be bootstrapped — so there is nothing to
-- clean outside Postgres.

DELETE FROM dataset
WHERE repository_id IN (
    SELECT id FROM data_repository WHERE type IN ('NOSQL_DOCUMENT', 'KEY_VALUE', 'WEB_API')
);

DELETE FROM data_repository WHERE type IN ('NOSQL_DOCUMENT', 'KEY_VALUE', 'WEB_API');

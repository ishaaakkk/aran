-- ===========================================================================
--  V40__iceberg_rest_repository.sql — the ICEBERG_REST repository kind (Gap 2)
--
--  Three columns on the existing single-table inheritance, following V39's
--  SINGER_TAP precedent. The data-plane credential columns are NOT added here:
--  an Iceberg repository's S3-style file access reuses the endpoint /
--  access_key / secret_key / region / provider columns V6 created for
--  OBJECT_STORAGE — same concept, same encryption converter, and a second copy
--  of "how do I reach an S3-compatible store" is a duplicate that would drift.
--
--    catalog_uri    the REST catalog's base URI (control plane), screened at
--                   registration like every other outbound target.
--    warehouse      which warehouse the catalog should serve; NULL when the
--                   catalog serves exactly one.
--    catalog_token  bearer token for the catalog, written by the
--                   CredentialEncryptionConverter. TEXT because ciphertext.
-- ===========================================================================

ALTER TABLE data_repository ADD COLUMN catalog_uri VARCHAR(512);
ALTER TABLE data_repository ADD COLUMN warehouse VARCHAR(255);
ALTER TABLE data_repository ADD COLUMN catalog_token TEXT;

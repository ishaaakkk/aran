-- The two SHA-256 hex columns were created as CHAR(64) (V21, V22) while their entities map them
-- as plain Strings with length = 64 — that is varchar(64) to Hibernate. Postgres reports CHAR(64)
-- as bpchar / JDBC Types.CHAR, so `ddl-auto: validate` rejects the mismatch and the whole Spring
-- context fails to start; every @Tag("docker") integration test dies with it.
--
-- Fixed on the SQL side rather than by pinning the entities to CHAR: char(n) buys nothing in
-- Postgres (same storage as varchar) and its blank-padded comparison semantics — 'abc' = 'abc   '
-- is true — are the wrong equality for a credential digest that is looked up on every
-- authenticated request (idx_api_token_hash).
--
-- These were the only two CHAR(n) columns in the migration set; everything else is VARCHAR(n).
--
-- Numbered V26 rather than next to the V21/V22 columns it repairs: this was written as V23 on a
-- branch, and V23–V25 were taken by another branch that merged first. Two branches also fixed
-- this independently; the duplicate was dropped at merge, so this is the only copy.
ALTER TABLE api_token
    ALTER COLUMN token_hash TYPE VARCHAR(64);

ALTER TABLE dataset_schema_fingerprint
    ALTER COLUMN fingerprint_hash TYPE VARCHAR(64);

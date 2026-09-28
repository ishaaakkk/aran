-- ===========================================================================
--  V39__singer_tap_repository.sql — the SINGER_TAP repository kind (T6)
--
--  CUT AGAINST V38 (V38__wrapper_edited, which landed from another branch while
--  this tranche was in flight; this file was renumbered from V38 for exactly that
--  reason). Flyway version numbers are claimed, not allocated: another branch may
--  yet land a V39 too. Whoever merges second RENUMBERS this file — it references
--  no other migration, so nothing has to move with it.
--
--  Two columns on the existing single-table inheritance, exactly as V6 does for
--  the other six kinds; there is no second table because there is no second
--  entity hierarchy.
--
--    tap_id     which tap in the deployment's SingerTapCatalog to run. Only an
--               id: the COMMAND is configuration and is never stored per-row,
--               because a row a user can write must not be able to name a
--               program the backend executes.
--    tap_config the user's settings as a JSON object, written by the
--               CredentialEncryptionConverter. TEXT rather than JSONB: Aran
--               never queries inside it, and ciphertext is not JSON.
-- ===========================================================================

ALTER TABLE data_repository ADD COLUMN tap_id VARCHAR(64);
ALTER TABLE data_repository ADD COLUMN tap_config TEXT;

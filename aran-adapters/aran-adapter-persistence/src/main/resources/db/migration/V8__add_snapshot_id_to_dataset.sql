-- ===========================================================================
--  V8__add_snapshot_id_to_dataset.sql  —  Link Dataset with aran-lake Snapshots
-- ===========================================================================

ALTER TABLE dataset ADD COLUMN snapshot_id VARCHAR(255);

-- Phase 5 (Task 1): a data product now records how it serves rows — MATERIALIZED (a pinned
-- parquet snapshot in the lake, computed once by product:materialize and served until
-- re-materialized) or VIRTUAL (no snapshot; its OQL is expanded through the live GAV pipeline at
-- read time instead — wired in a later phase-5 task, not by this migration alone). DEFAULT
-- 'MATERIALIZED' backfills every existing row correctly: nothing before this migration was ever
-- anything but materialized, so this migration changes no existing product's behavior. Same
-- choice V31/V32 made for column_profile.subject/model_constraint.origin: a plain VARCHAR
-- discriminator, no CHECK constraint, and the default is kept rather than dropped after backfill
-- so a newly created product can rely on the same default too.
ALTER TABLE data_product ADD COLUMN access_mode VARCHAR(16) NOT NULL DEFAULT 'MATERIALIZED';

-- V36: column_profile's identity catches up with the subject model.
--
-- V31 added subject/class_iri: one physical column legitimately carries a SNAPSHOT row, a
-- WRAPPER_OUTPUT row per class, and (Phase 4B) a HARVESTED row — the merge writers
-- (ProfilingOperator's per-generation replace, HarvestPersistenceService's additive stats)
-- deliberately keep those generations side by side. V10's UNIQUE (dataset_id, column_name)
-- predates that model and forbade exactly the rows the code writes: the second generation's
-- save threw, the tail swallowed it to a WARN, and harvested stats / wrapper-output evidence
-- silently never persisted. Watched live as PENDING_VALIDATION V40 (a re-profile never
-- persists after the first time) and V44 (a second table cannot be enrolled in a relational
-- repository); audit finding PROFILING-1.
--
-- NULLS NOT DISTINCT because class_iri is NULL for SNAPSHOT/HARVESTED rows: two rows of the
-- SAME generation for one column must still collide, which a plain unique constraint would
-- allow (NULL <> NULL). Postgres 15+ syntax; every environment pins Postgres 17.
ALTER TABLE column_profile DROP CONSTRAINT uq_column_profile_dataset_name;
ALTER TABLE column_profile
    ADD CONSTRAINT uq_column_profile_identity
    UNIQUE NULLS NOT DISTINCT (dataset_id, subject, class_iri, column_name);

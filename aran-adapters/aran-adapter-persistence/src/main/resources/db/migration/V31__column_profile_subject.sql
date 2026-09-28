-- Phase 3B: the profile subject discriminator. A column_profile row now says what it profiled —
-- SNAPSHOT (today's only real shape: Freyja over the dataset's raw snapshot) or WRAPPER_OUTPUT (a
-- row profiling one source class's resolved wrapper output instead, named by class_iri). DEFAULT
-- 'SNAPSHOT' backfills every existing row correctly: nothing before this migration profiled
-- anything else. class_iri stays NULL for a SNAPSHOT row (dataset-scoped, not class-scoped); the
-- pairing (class_iri set iff subject = 'WRAPPER_OUTPUT') is a domain/adapter invariant, not a
-- CHECK constraint here — same choice V30 made for expansion_status/expansion_reason.
ALTER TABLE column_profile ADD COLUMN subject VARCHAR(24) NOT NULL DEFAULT 'SNAPSHOT';
ALTER TABLE column_profile ADD COLUMN class_iri VARCHAR(512);

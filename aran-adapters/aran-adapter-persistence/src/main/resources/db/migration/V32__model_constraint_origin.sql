-- Phase 3B: the constraint origin discriminator. A model_constraint row now says how it came to
-- exist — MANUAL (a human typed it in the constraints editor) or DISCOVERED (IntegrationModelService
-- .discoverConstraints persisted it as an unenforced candidate after screening a LIMA discovery
-- result). DEFAULT 'MANUAL' backfills every existing row correctly: nothing before this migration
-- discovered anything, every row was hand-authored. Same choice V31 made for column_profile.subject:
-- no CHECK constraint tying origin to anything else, since a DISCOVERED row is enforced through the
-- exact same PATCH as a MANUAL one — origin is a label for the review UI, not a different code path.
ALTER TABLE model_constraint ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL';

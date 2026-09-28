-- ===========================================================================
--  V5__project_metadata.sql  —  Extend project table with frontend attributes
--  Adds columns color, privacy, status, and progress to support premium design.
-- ===========================================================================

ALTER TABLE project 
ADD COLUMN color   VARCHAR(32) NOT NULL DEFAULT '#4945FF',
ADD COLUMN privacy VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
ADD COLUMN status  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
ADD COLUMN progress INTEGER NOT NULL DEFAULT 0;

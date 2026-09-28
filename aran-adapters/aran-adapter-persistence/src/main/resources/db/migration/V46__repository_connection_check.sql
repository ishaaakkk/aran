-- The last explicit connection test of each stored repository (UI audit J2-F19).
--
-- The repositories list used to probe every repository on every visit: a Singer tap's discovery
-- ran and a JDBC connection opened per database for a glance at a table, a first driver download
-- read UNREACHABLE, and — with the probe posting the GET body back — a password-protected source
-- read UNREACHABLE because the '********' mask was dialed as its password (J2-F1). The list now
-- runs nothing on load and shows what the last test a person ran found, and when.
--
-- One row per repository, replaced by each test of the stored configuration. A test of unsaved
-- edits in the edit dialog is not recorded (it describes settings nobody saved), and a save of the
-- repository deletes the row: it described the configuration the save replaced.
CREATE TABLE repository_connection_check (
    repository_id UUID PRIMARY KEY REFERENCES data_repository(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    success BOOLEAN NOT NULL,
    -- The probe's own one-line summary ("Connected to POSTGRESQL at …", "Database connection
    -- refused") and, on failure, the driver's or protocol's diagnostics — what the person who ran
    -- the test saw, kept so the next person does not have to dial the source to read it.
    message TEXT NOT NULL,
    details TEXT,
    tested_at TIMESTAMPTZ NOT NULL,
    -- Who ran it. SET NULL rather than CASCADE: a deleted account does not make the source any
    -- less reachable than it was when they tested it.
    tested_by UUID REFERENCES app_user(id) ON DELETE SET NULL
);

-- When each repository's configuration was last saved, stamped by every save. A check that began
-- before it is not shown: a test running while a save lands ("Test all" with the host being
-- edited) would otherwise record "connected" after the save deleted the old check, against a
-- configuration nobody has dialed. NULL for rows saved before this migration — they keep their
-- checks (they have none yet).
ALTER TABLE data_repository ADD COLUMN config_saved_at TIMESTAMPTZ;

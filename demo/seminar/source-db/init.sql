-- ===========================================================================
--  Demo relational sources for the Aran seminar scenario.
--
--  The file fixtures next to this directory (acm_papers.csv,
--  semantic_papers.json, researchers.json) are all files. Everything Aran
--  claims to be — integrating *heterogeneous* sources — needs at least one
--  source that is not a file, or the first-session demo argues for the product
--  using only half of it.
--
--  Two schemas, deliberately, rather than two containers. They stand for two
--  independent institutional systems and register in Aran as two separate
--  repositories (same host and database, different schema). Two containers
--  would be marginally more realistic and materially heavier for a quickstart
--  whose whole point is one command.
--
--  The names here do NOT line up with the file sources, on purpose:
--
--    file sources          this database
--    ------------          -------------
--    paper_id / doi        pub_ref
--    title                 pub_title
--    year                  pub_year
--    venue ('ISWC')        outlet ('International Semantic Web Conference')
--    author_id             person_ref
--    researcher_id         person_ref
--
--  A demo where the join key is called the same thing everywhere demonstrates
--  nothing: join discovery has no work to do, and a reviewer correctly reads it
--  as staged. The overlaps below are real overlaps — the same publications
--  recorded by different systems under different identifiers — which is what
--  makes the integrated IPaper class worth building.
-- ===========================================================================

-- ---------------------------------------------------------------------------
--  registry — a university CRIS (current research information system).
--  A third view of publications, overlapping both file sources partially.
-- ---------------------------------------------------------------------------
CREATE SCHEMA registry;

CREATE TABLE registry.publication (
    pub_ref     TEXT PRIMARY KEY,
    pub_title   TEXT    NOT NULL,
    pub_year    INTEGER NOT NULL,
    outlet      TEXT    NOT NULL,
    -- A CRIS records the external DOI when it knows one, and often does not.
    -- Where it is present it is a higher-precision join key into the Semantic
    -- Scholar source than title matching; where it is NULL, title and year are
    -- all there is. Both cases appear below on purpose.
    doi_link    TEXT,
    open_access BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE registry.publication_credit (
    pub_ref      TEXT    NOT NULL REFERENCES registry.publication (pub_ref),
    person_ref   TEXT    NOT NULL,
    credit_order INTEGER NOT NULL,
    PRIMARY KEY (pub_ref, person_ref)
);

-- Five records that are the SAME works as entries in the file sources, under
-- this system's own identifiers and its own spelling of the venue.
INSERT INTO registry.publication (pub_ref, pub_title, pub_year, outlet, doi_link, open_access) VALUES
    ('REG-2022-041', 'Federated Query Processing over Heterogeneous RDF Graphs', 2022, 'International Semantic Web Conference', NULL,              TRUE),
    ('REG-2024-118', 'Open Data Integration at Scale',                  2024, 'Very Large Data Bases',                 NULL,              TRUE),
    ('REG-2023-077', 'Schema Matching at Scale: A Comparative Survey',           2023, 'Very Large Data Bases',                 NULL,              FALSE),
    ('REG-2023-090', 'Data Profiling for Automated Integration',                 2023, 'Very Large Data Bases',                 '10.1145/SS007',   TRUE),
    ('REG-2022-055', 'Denial Constraint Discovery in Relational Data',           2022, 'Very Large Data Bases',                 '10.1145/SS014',   FALSE);

-- Seven that exist only here: national venues, teaching and institutional
-- output that neither ACM nor Semantic Scholar indexes. Without these the
-- integrated class would be a strict superset of one source and the union
-- would look decorative.
INSERT INTO registry.publication (pub_ref, pub_title, pub_year, outlet, doi_link, open_access) VALUES
    ('REG-2021-012', 'A Reference Architecture for Semantic Data Lakes',         2021, 'Jornadas de Ingeniería del Software y Bases de Datos', NULL, TRUE),
    ('REG-2023-101', 'Evaluating Join Discovery on Open Government Data',        2023, 'Joint Conference on Digital Libraries',                NULL, TRUE),
    ('REG-2024-133', 'Profile-Based Drift Detection for Integrated Schemas',     2024, 'Extending Database Technology',                        NULL, TRUE),
    ('REG-2020-008', 'Wrapper Induction for Catalogue Websites',                 2020, 'Jornadas de Ingeniería del Software y Bases de Datos', NULL, FALSE),
    ('REG-2022-064', 'Teaching Data Integration with Open Source Tools',         2022, 'Technical Symposium on Computer Science Education',    NULL, TRUE),
    ('REG-2024-150', 'Constraint Repair Strategies for Integrated Views',        2024, 'Extending Database Technology',                        NULL, FALSE),
    ('REG-2021-029', 'A Survey of Entity Resolution in Practice',                2021, 'Conference on Information and Knowledge Management',   NULL, FALSE);

-- Authorship. Weighted towards the UPC-affiliated researchers (R001, R007)
-- because this is a UPC registry — a uniform spread would be the giveaway that
-- the data was generated rather than recorded.
INSERT INTO registry.publication_credit (pub_ref, person_ref, credit_order) VALUES
    ('REG-2022-041', 'R001', 1), ('REG-2022-041', 'R006', 2),
    ('REG-2024-118', 'R001', 1), ('REG-2024-118', 'R007', 2), ('REG-2024-118', 'R011', 3),
    ('REG-2023-077', 'R008', 1), ('REG-2023-077', 'R019', 2),
    ('REG-2023-090', 'R003', 1), ('REG-2023-090', 'R018', 2),
    ('REG-2022-055', 'R012', 1), ('REG-2022-055', 'R001', 2),
    ('REG-2021-012', 'R001', 1), ('REG-2021-012', 'R007', 2),
    ('REG-2023-101', 'R007', 1), ('REG-2023-101', 'R016', 2),
    ('REG-2024-133', 'R001', 1), ('REG-2024-133', 'R012', 2), ('REG-2024-133', 'R017', 3),
    ('REG-2020-008', 'R007', 1),
    ('REG-2022-064', 'R007', 1), ('REG-2022-064', 'R013', 2),
    ('REG-2024-150', 'R012', 1), ('REG-2024-150', 'R001', 2),
    ('REG-2021-029', 'R008', 1), ('REG-2021-029', 'R005', 2), ('REG-2021-029', 'R019', 3);

-- ---------------------------------------------------------------------------
--  funding — a national/European funding agency.
--
--  A concept that appears in NO file source. This is what makes the demo a
--  data-integration demo rather than a de-duplication demo: the interesting
--  query ("which funded projects produced open-access publications, and by
--  whom") cannot be answered from any single source, only from the integrated
--  model.
-- ---------------------------------------------------------------------------
CREATE SCHEMA funding;

CREATE TABLE funding.project (
    project_code  TEXT PRIMARY KEY,
    project_title TEXT           NOT NULL,
    programme     TEXT           NOT NULL,
    start_year    INTEGER        NOT NULL,
    end_year      INTEGER        NOT NULL,
    budget_eur    NUMERIC(12, 2) NOT NULL,
    CONSTRAINT project_years_ordered CHECK (end_year >= start_year)
);

CREATE TABLE funding.project_member (
    project_code TEXT NOT NULL REFERENCES funding.project (project_code),
    person_ref   TEXT NOT NULL,
    role         TEXT NOT NULL,
    PRIMARY KEY (project_code, person_ref)
);

INSERT INTO funding.project (project_code, project_title, programme, start_year, end_year, budget_eur) VALUES
    ('ODIS-ES',  'Open Data Integration for Science',                 'Horizon Europe',      2022, 2025, 1200000.00),
    ('SEMLAKE',  'Semantic Data Lakes for Public Administration',     'Plan Nacional I+D+i', 2021, 2024,  780000.00),
    ('KGREASON', 'Reasoning at Scale over Enterprise Knowledge Graphs','Horizon Europe',     2023, 2026, 1450000.00),
    ('DQREPAIR', 'Automated Data Quality Repair',                     'Plan Nacional I+D+i', 2020, 2023,  410000.00),
    ('EDULAKE',  'Open Curricula for Data Engineering',               'Erasmus+',            2022, 2024,  295000.00),
    ('PROVTRACK','Provenance Tracking for Scientific Pipelines',      'Horizon Europe',      2024, 2027,  990000.00);

INSERT INTO funding.project_member (project_code, person_ref, role) VALUES
    ('ODIS-ES',  'R001', 'Principal Investigator'),
    ('ODIS-ES',  'R007', 'Co-Investigator'),
    ('ODIS-ES',  'R011', 'Researcher'),
    ('ODIS-ES',  'R003', 'Researcher'),
    ('SEMLAKE',  'R007', 'Principal Investigator'),
    ('SEMLAKE',  'R001', 'Co-Investigator'),
    ('SEMLAKE',  'R016', 'Researcher'),
    ('KGREASON', 'R006', 'Principal Investigator'),
    ('KGREASON', 'R017', 'Co-Investigator'),
    ('KGREASON', 'R002', 'Researcher'),
    ('DQREPAIR', 'R012', 'Principal Investigator'),
    ('DQREPAIR', 'R018', 'Researcher'),
    ('EDULAKE',  'R013', 'Principal Investigator'),
    ('EDULAKE',  'R007', 'Co-Investigator'),
    ('PROVTRACK','R005', 'Principal Investigator'),
    ('PROVTRACK','R019', 'Co-Investigator'),
    ('PROVTRACK','R001', 'Researcher');

-- ---------------------------------------------------------------------------
--  A read-only role, so the credentials pasted into Aran's registration form
--  cannot write. Aran only ever introspects and reads from a registered
--  relational source; handing it an owner account in a demo teaches the wrong
--  habit to everyone who copies the demo.
-- ---------------------------------------------------------------------------
CREATE ROLE aran_reader WITH LOGIN PASSWORD 'aran_reader';
GRANT CONNECT ON DATABASE demo_sources TO aran_reader;
GRANT USAGE ON SCHEMA registry, funding TO aran_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA registry, funding TO aran_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA registry, funding GRANT SELECT ON TABLES TO aran_reader;

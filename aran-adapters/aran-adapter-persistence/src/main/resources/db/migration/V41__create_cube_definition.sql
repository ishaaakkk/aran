-- The cube layer (OLAP/BI interop): a dimensional model over the integrated schema — one fact
-- class, its measures, the dimensions reached from it, and an optional generated calendar.
--
-- Deliberately ONE table rather than a shredded star of measure/dimension/level tables. Measures,
-- dimensions and the calendar are only ever read as a whole (CubeService parses them in one go and
-- there is no update path in v1), so shredding them would buy indexable columns nothing queries
-- and cost a migration per shape change. What a listing screen and a "cubes over this class"
-- lookup actually filter on — name, project, fact class — are columns; the rest is opaque JSON the
-- adapter stores and never interprets, so the wire shape has exactly one author.
--
-- schema_version_id is the ACTIVE graph version the definition was validated against. It is what
-- makes "no update path" coherent: a cube's gates passed against the evidence of exactly that
-- version, and re-validating against a later one is creating a new cube, not editing this one.
--
-- product_ids_json is the star's member products in creation order (fact first, then one per
-- dimension in definition order). Position is the only link back to a dimension, which is why the
-- order is part of the stored record rather than an accident of how it was written.
--
-- created_by is TEXT, not UUID: the domain carries the actor as a string and leaves it null for an
-- unattributed, system-run creation — the same choice data_product makes.
CREATE TABLE cube_definition (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    fact_class_iri TEXT NOT NULL,
    definition_json TEXT NOT NULL,
    product_ids_json TEXT NOT NULL,
    schema_version_id UUID NOT NULL,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_cube_definition_name UNIQUE (tenant_id, project_id, name)
);

-- The listing read is always (tenant, project) ordered by creation, so it gets the index the
-- unique constraint above cannot serve on its own once `name` is not in the predicate.
CREATE INDEX idx_cube_definition_tenant_project
    ON cube_definition (tenant_id, project_id, created_at DESC);

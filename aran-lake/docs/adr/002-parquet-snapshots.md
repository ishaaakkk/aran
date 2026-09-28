# ADR 002 — Plain Parquet snapshots, no Iceberg or Delta

**Status:** Accepted
**Date:** 2026-05-23

## Context

The lake stores datasets as immutable artifacts in object storage. We
considered three table formats:

- **(a)** Plain Parquet files with a Postgres catalog.
- **(b)** Apache Iceberg tables (multi-engine, time-travel, schema
  evolution as first-class concepts).
- **(c)** Delta Lake (similar to Iceberg, Databricks-aligned).

## Decision

**Plain Parquet, content-hash-addressed, with snapshot metadata in a
Postgres catalog.** No Iceberg, no Delta, no medallion.

## Rationale

1. **Iceberg and Delta solve problems we don't have.** Both are designed
   for the case where *multiple engines* (Spark, Trino, Snowflake, Flink)
   read and write the same logical table. Aran's lake has one writer —
   the lake service itself. The coordination overhead buys nothing here.

2. **Immutable content-addressed snapshots are simpler than versioned
   tables.** Each upload becomes a new snapshot. Old snapshots stay
   readable forever. "Time travel" is just "query the older snapshot."
   No commit logs, no compaction jobs, no orphan-file cleanup.

3. **Lake-side projections, not centralized warehousing.** Aran
   federates queries to source systems. The lake exists to cache, profile,
   and accelerate — not to be the system of record. A simpler storage
   format matches simpler semantics.

4. **DuckDB reads Parquet natively at full speed.** The whole point of
   adopting a format like Iceberg would be lost if we then queried it
   through DuckDB's `read_parquet` anyway.

5. **Future migration cost is low.** If we ever need Iceberg, the
   snapshot table maps cleanly onto an Iceberg manifest. Going Parquet
   → Iceberg is a few weeks of work; going the other way isn't.

## Consequences

- No native time-travel-by-timestamp. Implemented at the catalog level
  via the `created_at` index on snapshots — slightly less elegant, fine
  for the workload.
- No schema-evolution-with-history baked in. Schemas live on the
  individual parquet snapshots; the catalog records column names and
  types via profiles.
- We are explicitly *not* a lakehouse. This is a positioning choice
  documented elsewhere.

## Alternatives considered

- **Iceberg via PyIceberg.** Rejected for the reasons above; revisit in
  year 2 if multi-engine writes become a real requirement.
- **Delta Lake.** Same.
- **DuckDB native tables stored as `.duckdb` files in object storage.**
  Rejected — single-writer, no parallel access, breaks every other
  language's ability to read directly.

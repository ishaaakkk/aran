---
title: 'Release notes'
sidebar_label: 'Release notes'
sidebar_position: 99
description: 'Notable changes to Aran, newest first, and how this documentation is versioned.'
# Diátaxis mode: reference
---

## v0.1.0 — the upcoming first release

v0.1.0 is not yet tagged. This section describes what it will contain.

Aran integrates data sources that were never built to work together. It
reads each source and derives a schema for it. It profiles the data and
proposes how the schemas relate. You correct and approve those proposals.
Aran compiles the result into one shared vocabulary, and keeps every version
of it. Querying across the sources, publishing a data product, and serving
an AI agent all follow from that vocabulary.

### What this release does

- **Registers data repositories** — five kinds: a relational database, an
  object store, a home for uploaded files, a Singer tap, or an Iceberg REST
  catalog.
  Uploading a file works for every kind. Aran pulls a table in by itself
  from a relational database, reads or copies objects from object storage,
  and runs a configured Singer tap per stream. See [Data
  repositories](./repositories/index.md).
- **Derives a schema for each source**, then profiles every column. See
  [Bootstrap and profile a dataset](./how-to/bootstrap-and-profile-a-dataset.md).
- **Proposes joins** by comparing those profiles, and ranks each proposal.
  See [Run join discovery](./how-to/run-join-discovery.md).
- **Integrates the schemas you approve** into versioned integrated classes.
  You can compare two versions, promote one, and roll back. See [Align and
  integrate schemas](./how-to/align-and-integrate-schemas.md).
- **Answers OQL queries** across every integrated source, with typed columns
  and cost-based plans you can inspect. See [OQL](./oql/index.md) and [Typed
  columns and cost-based plans](./query-engine/typed-columns-and-costing.md).
- **Reads a PostgreSQL table live** instead of from a snapshot, when you
  want current rows. See [Query a database live](./how-to/query-a-database-live.md).
- **Filters rows with denial constraints** you declare per class. See
  [Denial constraints in practice](./query-engine/denial-constraints.md).
- **Publishes data products**, computed once or recomputed on every read.
  See [Materialised and virtual data products](./deep-dives/virtual-data-products.md).
- **Serves three client paths** — a JDBC endpoint with three schemas, a
  Python SDK, and an MCP endpoint for an AI agent. See [Programmatic
  access](./programmatic-access/index.md).
- **Keeps tenants apart** in storage, not by convention, and ships identity
  with it. One compose file starts the whole stack — see
  [Run Aran standalone](./administration/standalone.md).

### Stability

Defects found and fixed by driving the running product — a browser, a real
JDBC driver, a real MCP client, a real database — before this release.
Among them:

- A JDBC client could not read a date or a timestamp at all. Every accessor
  failed. See [Dates and timestamps over
  JDBC](./programmatic-access/temporal-types.md).
- A second table could not be enrolled from one relational repository.
- A table meant to be read live could never be bootstrapped.
- A source that changed shape left its wrapper stale, and the class stopped
  answering.
- Re-profiling a dataset never kept the new profile.
- A denial constraint stopped filtering when its class was merged away, and
  nothing said so.
- Constraint discovery offered candidates that could not filter anything.
- Reloading the model discarded what you had typed in the wrapper editor.

None of these was found by reading code. Each one turned up while somebody
drove a running instance.

### Read these limits before you start

- **Getting data in is narrower than the repository list.** Uploading a file
  works everywhere. Pulling data in works for a relational database, object
  storage, and a configured Singer tap. There is no document-store, cache or
  web-API kind: those were removed because no data could flow from them, and
  a SaaS API is reached through a Singer tap. The file-system kind reads no
  server directory; it holds what you upload. See [Data
  repositories](./repositories/index.md).
- **Live reads exist for PostgreSQL, MySQL, MariaDB, and object storage
  (`s3://`)**; a live table can be bootstrapped from PostgreSQL, MySQL and
  MariaDB. See
  [Query a database live](./how-to/query-a-database-live.md).
- **Constraint discovery adds no constraints yet.** It reports what it
  found and explains why it cannot store it. See [Denial constraints in
  practice](./query-engine/denial-constraints.md).
- **A time-of-day column is not carried end to end.** Dates and timestamps
  are. See [Dates and timestamps over
  JDBC](./programmatic-access/temporal-types.md).

### Start here

[Quickstart](./getting-started/quickstart.md) gets an instance running. The
[full walkthrough](./getting-started/walkthrough.md) takes two sources to
one query.

## How this documentation is versioned

The site tracks `main`; there are no versioned docs yet. Later releases will
be listed here, newest first. Between releases, `git log --oneline main` is
the record of what changed.

## Since then

Landed on `main` after the v0.1.0 feature set above:

- **A performance suite for the query engine**, in two tiers: plan-shape and
  row-count assertions run on every build, and an opt-in benchmark
  (`make perf`) writes a duration report. No wall-clock threshold is ever a
  build gate.
- **Singer taps as a repository kind.** An operator names installed taps in
  configuration; a user picks one, and each stream becomes a dataset through
  the normal ingest path.
- **The standalone topology** — one compose file runs the whole platform on
  a single node. See [Run Aran standalone](./administration/standalone.md).

## Recent work worth knowing about

This is not a changelog. It lists the things most likely to surprise
someone who last looked at Aran a while ago. It also says where to read
about each.

| Change | Where |
| --- | --- |
| The legacy `OqlRewriter` is deleted. Parser → Calcite planner → DuckDB is the only query path | [The query pipeline](./deep-dives/query-pipeline.mdx) |
| Live federated query for PostgreSQL, MySQL and MariaDB, alongside snapshot execution | [Relational databases](./repositories/relational-databases.md) |
| Table import from a relational repository — the dialog offers it for PostgreSQL, other engines over the API | [Relational databases](./repositories/relational-databases.md) |
| A data product can be virtual: no snapshot, recomputed on every read | [Materialised and virtual data products](./deep-dives/virtual-data-products.md) |
| A JDBC client can read dates and timestamps; before, every accessor failed | [Dates and timestamps over JDBC](./programmatic-access/temporal-types.md) |
| `DRIFTED` datasets: Aran reports a source that changed shape, instead of silently applying it | [Bootstrap and profile](./how-to/bootstrap-and-profile-a-dataset.md#handle-drift) |
| The FREYJA sidecar now refuses to start without a shared secret | [Python sidecar security contract](./contributing/python-sidecar-security.md) |
| A browser end-to-end suite drives the real UI in Chromium | [The e2e suite](./contributing/e2e-suite.md) |

---
title: 'Glossary'
sidebar_label: 'Glossary'
sidebar_position: 4
description: 'Alphabetical definitions of the terms and identifiers used across Aran''s code, UI, and documentation.'
# Diátaxis mode: reference
---

Terms as this documentation and the codebase use them. Where a term has a
longer treatment, the definition links to it.

### Alignment

A declaration that a source property means the same thing as a global
property. The recorded form is a mapping.

### Bootstrapping

The operator that reads a dataset snapshot and emits an RDF description of
it — one class per record type, one property per field, plus wrapper SQL. See
[Bootstrapping and the schema graph](../deep-dives/bootstrapping-and-the-schema-graph.mdx).

### Column profile

Per-column statistics that FREYJA computes: distinct values, uniqueness,
value domains. Feeds both join discovery and the query optimiser's cost
model.

### Data repository

A registered connection to a system where data lives. Has one of five
`RepositoryType` values: `RELATIONAL_DB`, `FILE_SYSTEM`, `OBJECT_STORAGE`,
`SINGER_TAP`, or `ICEBERG_REST`.

### Dataset

One concrete body of records drawn from a repository — a table, an uploaded
file, an import result. Moves through `PENDING` → `RUNNING` → `READY`, or
`FAILED`, `CANCELLED`, or `DRIFTED`.

### Denial constraint

A predicate rows must satisfy, such as `year >= 1990`. Aran applies it per
union member in the `u_` views, so it survives the union.

### Drift / `DRIFTED`

A source has changed shape in a way that would break something already
compiled into the active schema, and Aran did **not** apply the change.
Distinct from `FAILED`: nothing went wrong, and nothing is retryable. A human
must decide whether to repair the mappings or accept the new shape.

### FREYJA

The Python profiling and join-discovery sidecar, on port 8086. The
`profiling` and `join-discovery` operators call it. It shares the lake's
virtual environment.

### Federated repository

A relational repository flagged `federated`. Aran reads its data live at
query time, from the source database DuckDB attaches read-only (through its
postgres or mysql extension), rather than from a lake snapshot. PostgreSQL, MySQL, and MariaDB
can be federated.
Object-storage datasets have their own live path: a federated read from
`s3://` over DuckDB's httpfs.

### GAV

Global-as-view — the integration style Aran uses. Aran defines each
integrated class as a view over the sources. It answers a query against that
class by unfolding the view.

### Global property

A property of an integrated class — a field in the shared vocabulary. Column
names in `int_` views are full global property IRIs.

### Graph version

A numbered, statused snapshot of a tenant's schema graph: `DRAFT`, `ACTIVE`,
or `HISTORICAL`. Compiling produces a draft. Promoting makes it active.

### `int_<class>`

The DuckDB view for an integrated class: `UNION ALL` over every source
feeding it. Aran names its columns with full global property IRIs.

### Integrated class

A class in the shared vocabulary, such as `IPaper`. Aran unions two source
classes mapped to the same integrated class.

### Integration model

The user-owned declaration of how sources map onto integrated classes: its
sources, alignments, per-class wrappers, and constraints. Compiling one
writes it into the schema graph.

### Join property

A relationship between two integrated classes, carrying both class IRIs and
the two global property IRIs that are the join keys. You traverse it in OQL
with `-[name]->`.

### Lake / `aran-lake`

The FastAPI service on port 8085 that owns snapshots, imports, and
materialisation. It has its own PostgreSQL database and an S3-compatible
store.

### Mapping catalog

The derived view of the active schema graph that the query planner works
from: integrated classes, their source views, wrapper SQL, column maps, join
properties, and profile statistics. A SPARQL query builds it on demand —
Aran does not store it.

### Materialisation

Writing a full, uncapped query result to a file rather than returning it in a
response. The `materialize-oql` operation.

### `nx:`

Prefix for the NextiaDI vocabulary, `http://www.essi.upc.edu/DTIM/NextiaDI/`.
Aran uses it for all of its own RDF terms.

### Operator

A platform capability with an id, named operations, and a parameter schema.
Spring component scanning registers it. It receives its tenant explicitly on
the request. See the [Operator contract](../how-to/write-a-new-operator.md).

### OQL

The Aran Query Language. It looks like SQL. You write path joins as
`-[property]->`. See the [syntax reference](../oql/syntax-reference.md).

### Project

An organisational grouping inside a tenant. Partitions work, not the schema
graph.

### `${SNAPSHOT}`

The literal placeholder every wrapper's `FROM` clause uses. Aran substitutes
it at execution time with the `src_` view name.

### Singer tap

A repository type (`SINGER_TAP`) that pulls a SaaS API by running a Singer
tap binary the operator installed and allowlisted in deployment
configuration. With no tap configured, the type cannot be registered.

### Snapshot

A dataset's contents materialised in the lake as parquet, CSV, or JSON, so
later stages read a stable local copy.

### Source class / source property

A class or property that bootstrapping derives from one dataset, described
in that source's own vocabulary. Contrast with integrated.

### `src_<dataset>`

The DuckDB view over a dataset's raw snapshot, read with whatever function
the format requires — or, for a federated repository, a table of the source
database DuckDB attaches read-only.

### Tenant

The isolation boundary. Aran resolves it from the caller's `membership` row,
not a token claim. One active schema graph per tenant. See
[Multi-tenancy](../deep-dives/multi-tenancy.md).

### `u_<dataset>_<class>`

A named union member: one source's rows projected onto the integrated
class's column list, with denial constraints applied. Exists so the planner
can see the union and cost each branch separately.

### `w_<dataset>_<class>`

The DuckDB view Aran produces by applying a wrapper's SQL to a `src_` view.

### Wrapper

The SQL that turns a raw snapshot into the flat relation an integrated class
expects. It reads from `${SNAPSHOT}`. Aran validates it to a single `SELECT`.
Two fields are called "wrapper", and only one executes — see
[The two wrapper fields](../deep-dives/wrapper-fields.md).

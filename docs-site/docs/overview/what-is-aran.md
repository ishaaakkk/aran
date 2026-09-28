---
title: 'What is Aran'
sidebar_label: 'What is Aran'
sidebar_position: 1
description: 'What the Aran platform does, the problem it addresses, and what is and is not implemented today.'
# Diátaxis mode: explanation
---

Aran is a data integration platform. It takes several data sources that
were never designed to work together and derives a schema for each one. You
declare how the schemas relate to each other. From that declaration, Aran
builds one queryable vocabulary.

Aran comes from more than a decade of data integration research at UPC
(Universitat Politècnica de Catalunya). The name comes from the Val d'Aran in
the Catalan Pyrenees: *aran* is the old Pyrenean word for *valley*, the place
where many streams meet.

One query can then return rows from a CSV file, a JSON API, and a PostgreSQL
table. The query never names any of the three.

## The problem it addresses

Two organisations rarely describe the same thing the same way. One names a
column `author_id`. Another nests the same identifier inside an `authors`
array. One publishes CSV. Another publishes JSON. Neither is wrong, and
neither will change for you.

The usual answer is an ETL pipeline. You write transformation code for each
source. You load everything into a warehouse. You maintain that code forever.
This works, but the integration logic now lives in a program. Nothing can
inspect that program, suggest a change to it, or tell you when a source has
changed shape underneath it.

Aran takes a different route. It **declares the integration as data**: a
graph of classes and properties, with mappings from each source's own
vocabulary onto a shared one. Aran derives the first draft of this graph
automatically. It inspects the sources and proposes the relationships it
finds. It then compiles the whole graph into executable SQL. Because the
integration is a graph, you can query it and compare two versions of it. You
can keep a history of every version, and detect when it no longer matches the
sources.

## What it does, concretely

1. **Registers repositories.** A connection to a PostgreSQL database, an S3
   bucket, a Singer tap, an Iceberg catalog. See
   [Data repositories](../repositories/index.md) for what each type supports.
2. **Ingests datasets.** Aran snapshots each supported source into its own
   lake. Later stages read the snapshot cheaply, as many times as they need.
3. **Bootstraps a schema.** The bootstrapping operator reads a snapshot. It
   emits an RDF description of the snapshot: one class per record type, one
   property per field. It also emits the SQL needed to read the snapshot
   back.
4. **Profiles the data.** The FREYJA sidecar computes statistics for each
   column: distinct values, uniqueness, value domains. These statistics
   drive join suggestions. They also drive the cost estimates the query
   optimiser uses.
5. **Proposes joins.** Join discovery compares column profiles across
   datasets. It proposes a relationship wherever two value domains overlap.
6. **Integrates schemas.** You map source properties onto global properties.
   You group these global properties into integrated classes. Aran unions
   two sources mapped to the same integrated class. A join property links
   two different classes.
7. **Answers queries.** You write [OQL](../oql/index.md) queries against the
   integrated classes. The query engine plans each query with Apache
   Calcite. It executes the query on DuckDB.

## What is implemented today

This documentation describes what the code does now. It does not describe
the roadmap. Two distinctions matter most:

**Connecting is not ingesting.** You can configure and test the connection
for every repository type. The depth of that test varies by type. Getting
data *in* requires a connector, and every repository type has one:

- `FILE_SYSTEM`: upload a CSV, JSON, or Parquet file.
- `RELATIONAL_DB`: upload a file, or pull a table over the import API.
  The import works for every relational engine except HBase. The
  **Import from table** dialog offers it for PostgreSQL only today. Other
  engines need a direct API call.
- `OBJECT_STORAGE`: upload a file. The engine can also read bucket objects
  over DuckDB's httpfs, but no endpoint or button registers a dataset from a
  bucket yet. See [Object storage](../repositories/object-storage.md).
- `SINGER_TAP`: pull a SaaS API by running a Singer tap the operator
  installed and allowlisted. With no tap configured, this type cannot be
  registered.
- `ICEBERG_REST`: read a table of an Iceberg REST catalog live, or copy it
  into the lake. This type has not yet run against a real external catalog.
  See [Iceberg REST catalogs](../repositories/iceberg-rest-catalogs.md).

There is no document-store, cache or generic web-API type. To integrate
such data today, export it to a file and upload it under a `FILE_SYSTEM`
repository. For a SaaS API, use a Singer tap.
The [capability table](../repositories/index.md) states what each type
supports.

**Snapshots are the normal execution path.** Queries usually run over
snapshots held in the lake. A relational repository flagged `federated` is
read live through DuckDB, which attaches the PostgreSQL, MySQL or MariaDB
database read-only. The tables of an Iceberg REST catalog can be read live
too. The query engine also carries a live `s3://` read for object storage,
but no user path reaches it yet. No other engine or source type has a live
path.

Aran is also multi-tenant. Each tenant's data, schema graph, and
integration models are isolated from every other tenant's. The persistence
layer enforces this isolation. See
[Multi-tenancy](../deep-dives/multi-tenancy.md).

## What Aran is not

- **Not a data warehouse.** Aran does not store your data long-term. The
  lake holds snapshots so the pipeline has something stable to read.
- **Not an ETL scheduler.** Aran has no schedule of recurring
  transformations. A job runs once, for one dataset or one integration run.
- **Not a BI tool.** Aran answers queries and exports the results. It does
  not chart them.

## Where to go next

- [Core concepts](./core-concepts.md): the vocabulary the rest of the
  documentation assumes.
- [Architecture](./architecture.mdx): what runs where, and why the modules are
  split the way they are.
- [Quickstart](../getting-started/quickstart.md): get an instance running.

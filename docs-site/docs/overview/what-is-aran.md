---
title: 'What is Aran'
sidebar_label: 'What is Aran'
sidebar_position: 1
description: 'What Aran does, the problem it addresses, how it approaches it, and what it is not.'
# Diátaxis mode: explanation
---

Aran is a data integration platform for sources that were never designed to
work together. It derives a schema for each source, proposes how the sources
join, and lets you declare how their schemas relate. From that declaration it
builds one queryable vocabulary.

A single query can then return rows from a CSV file, a JSON file and a
PostgreSQL table, and the query names none of the three.

The name comes from the Val d'Aran in the Catalan Pyrenees. *Aran* is the old
Pyrenean word for *valley*, a place where many streams meet. The platform grew
out of more than a decade of data integration research at the Universitat
Politècnica de Catalunya (UPC).

## The problem

Two organisations rarely describe the same thing the same way. One names a
column `author_id`. Another nests the same identifier inside an `authors`
array. One publishes CSV and the other JSON, and neither will change for you.

The usual answer is an ETL pipeline: transformation code for each source,
loaded into a warehouse and maintained indefinitely. That works, but the
integration logic now lives in a program. Nothing can inspect it, suggest a
change to it, or tell you when a source has changed shape underneath it.

## The approach

Aran stores the integration as data. It is a graph of classes and properties,
with mappings from each source's vocabulary onto a shared one. Aran drafts the
first version by inspecting the sources and proposing the relationships it
finds. It then compiles the graph into SQL.

Because the integration is a graph, you can query it and compare two versions.
Aran keeps every promoted version, and it detects when a source no longer
matches.

## What it does

| | Capability | In one line |
| --- | --- | --- |
| 1 | [Connect](../capabilities/connect.md) | Register files, databases, object storage, Iceberg catalogs and Singer taps. [Data repositories](../repositories/index.md) says what each type supports. |
| 2 | [Derive and profile](../capabilities/derive-and-profile.md) | Generate a schema, a SQL wrapper and column statistics from the data. The statistics drive join suggestions and the optimiser's cost estimates. |
| 3 | [Align and version](../capabilities/align-and-version.md) | Accept join proposals, map onto one vocabulary, promote and roll back versions. |
| 4 | [Query](../capabilities/query.md) | Run [OQL](../oql/index.md) across all sources. Calcite plans the query and DuckDB runs it. |
| 5 | [Serve](../capabilities/serve.md) | Save a query as a data product and read it over JDBC, Iceberg REST or MCP. Scripts and notebooks run OQL over REST or the Python SDK. |

Queries normally run over snapshots in Aran's lake. A PostgreSQL, MySQL or
MariaDB repository flagged `federated` is read live instead: DuckDB attaches the
database read-only. The tables of an Iceberg REST catalog are read live too,
unless a dataset has been copied into the lake. No other source type has a live
path a user can reach.

## What it is not

- **Not a data warehouse.** The lake holds snapshots so the pipeline has a
  stable copy to read. It is not long-term storage.
- **Not a scheduler.** A job runs once, for one dataset or one integration run.
  You can set a refresh policy on an imported dataset, and that is the only recurring work.
- **Not a BI tool.** Aran answers queries and exports rows. It does not chart them, and BI tools can connect to it over JDBC.

Aran is also multi-tenant. Each tenant's data, schema graph and integration
models are isolated, and the persistence layer enforces it. See
[Multi-tenancy](../deep-dives/multi-tenancy.md).

## How far it has been tested

Aran is pre-1.0. Some source types and features are verified against real
services and others are preview. The [status and limitations](./status.md) page
lists which is which.

## Next

- [Why Aran](./why-aran.md): when it fits and when it does not.
- [Core concepts](./core-concepts.md): the vocabulary the docs use.
- [Architecture](./architecture.mdx): what runs where, and why the modules are split as they are.
- [Quickstart](../getting-started/quickstart.md): run an instance.

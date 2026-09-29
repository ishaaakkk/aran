---
title: 'Connect'
sidebar_label: 'Connect'
sidebar_position: 1
description: 'Register files, databases, object storage, Iceberg catalogs and Singer taps as data sources, and choose between a snapshot and a live read.'
# Diátaxis mode: explanation
---

Everything in Aran starts from a registered source. This page explains the
source types, the choice between copying a dataset and reading it live, and
what Aran does with your credentials. The step-by-step task is
[Register a data repository](../how-to/register-a-data-repository.md).

A **data repository** is a registered connection. A **dataset** is one table,
file or stream taken from it.

## Source types

| Type | Reach it with | Snapshot | Live read | Refresh |
| --- | --- | --- | --- | --- |
| [File upload](../repositories/file-uploads.md) | Browser or API upload of CSV, JSON or Parquet | Yes | No | No |
| [Relational database](../repositories/relational-databases.md) | JDBC: PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, HBase (Phoenix) | Every engine except HBase | PostgreSQL, MySQL and MariaDB | Yes, for an imported table |
| [Object storage](../repositories/object-storage.md) | S3-compatible endpoint | Not yet: upload files into the repository instead | Not yet | No |
| [Iceberg REST catalog](../repositories/iceberg-rest-catalogs.md) | Iceberg REST protocol | Yes, per table | Yes | Yes, for a copied table |
| [Singer tap](../repositories/singer-taps.md) | A tap your operator has installed and allowlisted | Yes, per stream | No | Yes |

There is no connector for document stores, caches or generic web APIs. Export
that data to a file, or reach a SaaS API through a Singer tap. How far each type
has been tested is on the [status page](../overview/status.md).

## Snapshot or live

A snapshot copies the dataset into Aran's lake as Parquet. Every later stage
reads that stable copy, so profiling and repeated queries put no load on the
source. Refreshing a dataset takes a new snapshot, and a refresh that finds the
source's shape changed does not break the active schema (see
[Align and version](./align-and-version.md#compile-promote-and-roll-back)).

A live read sends the query to the source at execution time. You trade
freshness for load on the source. How you get one depends on the type:

- **Relational database.** Turn on the repository's `federated` flag. Only
  PostgreSQL, MySQL and MariaDB can honour it; DuckDB attaches the database
  read-only.
- **Iceberg REST catalog.** There is no flag. A table is read live unless that
  dataset has been copied into the lake.

[Federated access](./federated-access.md) covers live reads in full.

## Credentials

Aran encrypts stored credentials and returns every secret field masked as
`********`. A failed connection test names the host and never the password.
Sending the mask back on an update keeps the stored value, and the rules for
that are in [Data repositories](../repositories/index.md#credentials).

## Next

- [Register a data repository](../how-to/register-a-data-repository.md)
- [Query a database live](../how-to/query-a-database-live.md)
- [Capability table by repository type](../repositories/index.md)

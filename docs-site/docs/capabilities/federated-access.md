---
title: 'Federated access'
sidebar_label: 'Federated access'
sidebar_position: 2
description: 'What it means for Aran to read a source live, which source types can be read that way, how credentials are protected, and what changes in each pipeline stage.'
# Diátaxis mode: explanation
---

A federated dataset is read where it lives. Aran keeps no copy of it in the lake,
so every query sees the source as it is now. This page is the starting point for
everything live: which sources can do it, how Aran keeps credentials out of sight,
what the pipeline does differently, and what it costs the source. Details that
belong to a single subject live on the page linked from it.

## Snapshot or live is decided per dataset

Aran chooses the access mode for each dataset through `SourceAccess.resolve`. The
answer depends on the source type.

| Source | Rule |
| --- | --- |
| Relational database | Live if the repository has **Federated Execution** ticked and the engine has a live reader. Otherwise a snapshot. |
| Iceberg REST catalog | Live unless the dataset has a lake copy. The copy is the signal. |
| Object storage | Live unless the dataset has a lake copy. The copy is the signal. |
| Anything else | Snapshot. |

Ticking the flag is a request, not a grant. A relational repository on an engine
with no live reader (Oracle, SQL Server, HBase) resolves to a snapshot, and a
query over a dataset that has no snapshot then fails with "No snapshot recorded
for dataset", although you asked for a live source.

The relational flag covers every dataset in the repository. Iceberg and object
storage have no such flag, because one catalog or bucket can hold a table worth
reading live and a table worth copying. A table that has both a lake copy and a
live upstream is called dual-access, and Aran picks between the two for each
query. That decision is covered in
[Federated execution and placement](../query-engine/federated-execution-and-placement.md).

## Which sources can be read live

| Source | Read live | How the live read works | Enrol live today |
| --- | --- | --- | --- |
| [PostgreSQL](../repositories/relational-databases.md#federated-repositories) | Yes | DuckDB attaches the database read-only and reads `"<schema>"."<table>"`, with `public` when no schema is set | Yes |
| [MySQL](../repositories/relational-databases.md#federated-repositories) | Yes | DuckDB attaches the database read-only through its MySQL extension and reads `"<database>"."<table>"` | Yes |
| [MariaDB](../repositories/relational-databases.md#federated-repositories) | Yes | The same as MySQL | Yes |
| [Iceberg REST catalog](../repositories/iceberg-rest-catalogs.md) | Yes | Aran asks the catalog for the table's current metadata location at query time and reads it with `iceberg_scan` | Yes, register a dataset named `namespace.table` |
| [Object storage](../repositories/object-storage.md) | Built, not reachable | `read_parquet` or `read_csv_auto` over `s3://` through httpfs | No |
| [Singer tap](../repositories/singer-taps.md) | No | A tap produces records only when it runs, so there is nothing to scan | Not applicable |
| [File upload](../repositories/file-uploads.md) | No | The file is already in the lake | Not applicable |

Oracle and SQL Server repositories can import a table into the lake but cannot be
read live. HBase can do neither: it has no live reader and no table import. How
thoroughly each path has been tested is on the [status page](../overview/status.md).

## How credentials are protected

A live read has to hand a password to something that talks to the source. The
design goal is that no artefact a person or a later query could inspect ever holds it.

- Aran attaches relational sources read-only. A live read cannot change the source.
- The credential goes into a temporary DuckDB secret on that one connection. The
  attach statement refers to the secret by name and carries no password. An
  Iceberg or object-storage read uses a connection-scoped S3 secret, or an HTTP
  bearer secret for `http(s)` locations.
- No plan, view body, DDL statement or explain output contains a credential.
  Query and profiling errors have `password=` values masked before they are
  returned or logged. A connection error can still name the user.
- The attach alias, `aran_attach_` followed by the repository id without dashes,
  is reserved. An attached database exposes every table the account can see, so
  any screened SQL that names the alias is refused, along with every `postgres_*`
  and `mysql_*` function, because `postgres_query` and `mysql_query` take an
  attached catalogue by name. Give the source account read access to the
  registered tables only, all the same.
- A snapshot query runs with filesystem access locked down after setup. A query
  that contains a live read skips that filesystem lockdown, because the read itself
  needs the network. The wrapper validator is the fence in that case.
- Stored credentials are encrypted and come back from the API as `********`. While
  a masked credential is kept, the connection target is pinned: changing the host,
  endpoint or catalogue URI requires re-entering the credential in the same request.

Live profiling adds one exception to the "only Aran holds the credential" picture.
Aran passes the scan expression and setup statements to FREYJA for the profiling
pass, so FREYJA's host needs network access to the source. Nothing in that
exchange is logged or returned in a result.

## What changes across the pipeline

| Stage | For a federated dataset |
| --- | --- |
| Bootstrapping | Reads the live catalogue (columns and types) and writes a source schema. It reads no rows. The dataset list's bootstrap action refuses a dataset with no snapshot, so use the Alignments view. |
| Profiling | The dataset tab's **Profile** action needs a snapshot and is unavailable. At compile time, FREYJA profiles each opaque wrapper class in a local DuckDB bound to the live source, so only aggregates cross the wire. PostgreSQL also gets statistics harvested from its own catalogue. |
| Costing | Uses whichever evidence exists: live profiles, harvested statistics, or none. Neither counts as proof that a column is a key, so join elimination does not rely on it. |
| Wrapper verification | Saving a wrapper runs a `COUNT(*)` and a row hash against the live source. That is a full scan of the remote table at every save, and again for each re-check during a full rebuild. |
| Preview and constraint discovery | Both need a snapshot. Preview answers `409` with the code `NO_SNAPSHOT` and a message that the dataset is read live, and constraint discovery skips the class. |
| Placement | Only a dual-access dataset has a choice to make. See [Federated execution and placement](../query-engine/federated-execution-and-placement.md). |
| Data products | A virtual product over a federated class serves live rows on every read. A materialised product copies the rows at build time and does not change until it is refreshed. |
| Drift checks | The drift probe asks the live source for the table's current shape, through the same attached-catalogue read that queries use. |

## Costs and trade-offs

A live read buys freshness: a row inserted in the source shows up in the next
query, with no refresh to run and no copy to store. In return, the source carries
the load. Every query that touches the dataset reads it, every compile of an opaque
class runs one profiling pass, and every wrapper save scans the table once. A
snapshot puts none of that on the source after the import.

A query also pays for the network and a fixed setup cost per live read. Selective
filters recover much of it, because DuckDB pushes them into the source where its
extension can. An unfiltered scan of a large table is the case where a snapshot wins.

Use a live read for a table whose rows change often and whose queries are
selective. Use a snapshot for a large table that many queries scan, or for a source
that cannot take the extra load. A dual-access table with an `Every` refresh policy
gets both, chosen per query.

## Where to go

- [Query a database live](../how-to/query-a-database-live.md): the steps for PostgreSQL, MySQL and MariaDB.
- [Federated execution and placement](../query-engine/federated-execution-and-placement.md): the placement decision, cost weights and live profiling.
- [Relational databases](../repositories/relational-databases.md#federated-repositories): engines, schema handling and catalogue harvest.
- [Iceberg REST catalogs](../repositories/iceberg-rest-catalogs.md): the two credentials and query-time metadata resolution.
- [Object storage](../repositories/object-storage.md): the live read that exists and the path that does not yet.
- [Driver resolution](../deep-dives/driver-resolution.md): how the vendor JDBC drivers used for bootstrapping arrive.
- [Configuration reference](../administration/configuration-reference.md): the placement settings.
- [Connect](./connect.md): the capability this page belongs to.

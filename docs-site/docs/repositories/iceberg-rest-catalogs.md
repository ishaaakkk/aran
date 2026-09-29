---
title: 'Iceberg REST catalogs'
sidebar_label: 'Iceberg REST catalogs'
sidebar_position: 7
description: 'Connecting an Apache Iceberg REST catalog, browsing its tables, and reading them live or as lake copies.'
# Diátaxis mode: reference
---

An Iceberg REST catalog repository records how to reach a lakehouse catalog
(Polaris, Lakekeeper or any other server that speaks the Iceberg REST protocol)
and the object store its tables live in. Aran can browse the catalog's tables,
import a table into the lake as a snapshot, or read a table live at query time.

This type is a preview. The live-read and copy paths have run against Iceberg's
reference REST server (`apache/iceberg-rest-fixture`) with a local `file://`
warehouse: tables enrol, bootstrap and answer queries, and the next query reads
a commit made afterwards. No production catalog (Polaris, Lakekeeper, Glue) and
no `s3://` object store has been tried, so treat the import and live-query rows
below as proven against the reference server only. See the
[status page](../overview/status.md) and the [known issues](#known-issues).

## Two credentials

Reaching an Iceberg table takes two separate systems, and the form keeps their
credentials apart:

- The catalog (control plane): a base URI, an optional warehouse name and an
  optional bearer token. Aran's own backend calls it.
- The object store (data plane): provider, endpoint, access key, secret key and
  region, the same fields an object-storage repository uses. The query engine's
  file reads authenticate with these.

Each credential is stored encrypted, comes back masked as `********`, and is
guarded independently: a masked token cannot be pointed at a new catalog URI,
and a masked storage secret cannot be pointed at a new endpoint. Re-enter a
credential to change its target.

## Prerequisites

- The catalog URI must be reachable from the Aran backend.
- The files the catalog's metadata points at must be readable from the Aran
  backend. Three kinds of location are read: `s3://` on an S3-compatible store,
  with the object-store credentials on this form; `http(s)://`, with the
  catalog bearer token sent on each file read (this is how another Aran's
  [product catalog](../programmatic-access/iceberg.md) serves its files); and a
  `file://` warehouse on a path the backend itself can read. Integration tests
  have run two of these: `file://` behind the reference REST server, and
  `http://` (with the bearer token) behind Aran's own product catalog on the
  same host. The `s3://` kind has not been run. Azure and GCS native file
  locations are not supported yet.

## Setup

1. **Repositories → Register repository**, pick a project, confirm.
2. Fill in:
   - **Name** and **Description**
   - **Type:** `ICEBERG_REST`
   - **Catalog URI**: up to, but not including, `/v1` (for example
     `https://polaris.example/api/catalog`)
   - **Warehouse**: only if the catalog serves more than one
   - **Catalog bearer token**: only if the catalog requires one
   - The data-plane storage fields: **Provider**, **Endpoint**, **Access
     key**, **Secret key**, **Region**
3. Submit, then run the connection test.

### What the connection test proves

Aran performs the protocol's own hello, `GET /v1/config`, with your token. A
green result proves the catalog is reachable, TLS works and the token is
accepted. It proves nothing about the data plane, on purpose: you can register
a catalog while storage credentials are still being provisioned, and the first
table read reports a wrong credential.

## Supported capabilities

| Capability | Status |
| --- | --- |
| Connection test | Yes, a real protocol hello (`GET /v1/config`, token included). |
| Browse contents | Yes. The scan lists every `namespace.table`, nested namespaces included. |
| Register a dataset from a table (import) | Yes. Copies the table's current snapshot into the lake as parquet, then bootstraps it like any upload. Proven against the reference server, and against another Aran's product catalog in a test (see [known issues](#known-issues)). |
| Live query over a table in place | Yes, see below. Proven against the reference server only. |
| Refresh an imported table | Yes. Re-runs the same copy; manual by default. |
| Preview before import | No. |
| File upload into this repository | Yes, like any repository. |

## Live reads resolve at query time

An Iceberg commit replaces the table's metadata file, so Aran never stores a
metadata location. At every query it asks the catalog where the table is now
and reads that, which means a federated Iceberg dataset always answers from the
table's latest committed snapshot.

To enrol a table for live reads, register a dataset whose name is the
`namespace.table` identifier (no file), then bootstrap it. The schema comes from
the catalog, and no data is moved at enrolment. To take a durable copy instead,
use the import picker; the copy then behaves like any lake snapshot, profiling
included.

One catalog can serve both: import the tables you want isolated and profiled,
and enrol the ones you want always fresh. The choice is per dataset, and the
query plan view labels each source `FEDERATED` or `MATERIALIZED` accordingly.

### Planner behaviour for live tables

At compile time Aran profiles a live Iceberg dataset in place. The profiling
queries run over the same live scan ordinary queries use, so the source does
the scanning and only aggregate statistics travel back. No copy of the table is
staged to the lake, whatever its size, and the numbers the planner sees are
exact for the whole table as of that compile.

A live table's statistics never claim a unique key. Distinctness measured at
profile time can be stale by query time, because the table keeps committing, and
Aran does not eliminate joins on stale evidence. An imported copy is what earns
key-capable profiling. Each compile of a live class costs one profiling pass at
the source. This flow is implemented and tested but has not run in the field.

## Naming limits

A table is addressed as `namespace.table`, with nested namespaces joined by dots
(`db.nested.table`). A namespace or table whose own name contains a literal dot
cannot be addressed.

## Known issues

- Aligning two tables by a join alone creates each side's integrated class for
  you, named from the table identifier with `_` in place of `.`, in the
  alphabet a query writes bare: `db.venue` becomes `Int_db_venue`, and
  `db.nested.table` becomes `Int_db_nested_table`. Two tables whose identifiers
  differ only by `.` versus `_` would get the same name, and Aran refuses the
  second alignment rather than merge two tables into one class. To choose the
  name yourself, align the tables' classes with a label (a class alignment).
- Importing a table served by another Aran is proven in a test, not across
  deployments. Another Aran's [product catalog](../programmatic-access/iceberg.md)
  serves its files over authenticated `https://`, and the import sends the
  catalog's bearer token with every file read, as live reads do. That has been
  watched against Aran's own catalog in one process, not between two
  deployments.

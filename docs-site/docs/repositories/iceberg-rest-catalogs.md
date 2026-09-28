---
title: 'Iceberg REST catalogs'
sidebar_label: 'Iceberg REST catalogs'
sidebar_position: 7
description: 'Connecting an Apache Iceberg REST catalog, browsing its tables, and reading them live or as lake copies.'
# Diátaxis mode: reference
---

An Iceberg REST catalog repository records how to reach a lakehouse catalog
(Polaris, Lakekeeper, and other servers that speak the Iceberg REST protocol)
and how to reach the object store its tables live in. Aran can browse the
catalog's tables, import a table into the lake as a snapshot, or read a table
live at query time.

**Read the caveat first.** The live-read and copy paths have run against
Iceberg's own reference REST server (`apache/iceberg-rest-fixture`) with a
local `file://` warehouse: tables enrol, bootstrap and answer queries, and a
commit made afterwards is what the next query reads. No production catalog
(Polaris, Lakekeeper, Glue) and no `s3://` object store has been exercised
yet, and the known issues are listed [at the end of this
page](#known-issues). Treat the import and live-query rows below as "proven
against the reference server only".

## Two credentials, not one

Reaching an Iceberg table takes two separate systems, and the form keeps them
separate:

- **The catalog** (control plane): a base URI, an optional warehouse name, and
  an optional bearer token. This is what Aran's own backend calls.
- **The object store** (data plane): provider, endpoint, access key, secret
  key, region — the same fields an object-storage repository uses. This is
  what the query engine's file reads authenticate with.

Each credential is stored encrypted, comes back masked as `********`, and is
guarded independently: a masked token cannot be pointed at a new catalog URI,
and a masked storage secret cannot be pointed at a new endpoint. Re-enter a
credential to change its target.

## Prerequisites

- The catalog URI must be reachable from the Aran backend.
- The files the catalog's metadata points at must be readable from the Aran
  backend. Three kinds of location are read: `s3://` on an S3-compatible
  store, with the object-store credentials on this form; `http(s)://`, with
  the catalog bearer token sent on each file read (how another Aran's
  [product catalog](../programmatic-access/iceberg.md) serves its files); and
  a `file://` warehouse on a path the backend itself can read. The `file://`
  and `http://` kinds have been watched working, in integration tests; the
  `s3://` kind has not. Azure- and GCS-native file locations are not
  supported yet.

## Setup

1. **Repositories → Register repository**, pick a project, confirm.
2. Fill in:
   - **Name** and **Description**
   - **Type:** `ICEBERG_REST`
   - **Catalog URI** — up to, but not including, `/v1` (for example
     `https://polaris.example/api/catalog`)
   - **Warehouse** — only if the catalog serves more than one
   - **Catalog bearer token** — only if the catalog requires one
   - The data-plane storage fields: **Provider**, **Endpoint**, **Access
     key**, **Secret key**, **Region**
3. Submit, then run the connection test.

### What the connection test proves

More than most types: Aran performs the protocol's own hello,
`GET /v1/config`, with your token. A green result proves the catalog is
reachable, TLS works, and the token is accepted. It deliberately proves
nothing about the data plane — you can register a catalog while storage
credentials are still being provisioned, and the first table read will say
plainly if they are wrong.

## Supported capabilities

| Capability | Status |
| --- | --- |
| Connection test | **Yes — real protocol hello** (`GET /v1/config`, token included). |
| Browse contents | **Yes.** The scan lists every `namespace.table`, nested namespaces included. |
| Register a dataset from a table (import) | **Yes** — copies the table's current snapshot into the lake as parquet, then bootstraps it like any upload. Proven against the reference server only (V53), and against another Aran's product catalog in a test (see [known issues](#known-issues)). |
| Live query over a table in place | **Yes** — see below. Proven against the reference server only (V53). |
| Refresh an imported table | Yes — re-runs the same copy; manual by default. |
| Preview before import | No. |
| File upload into this repository | Yes, like any repository. |

## Live reads resolve at query time

An Iceberg commit replaces the table's metadata file. Aran therefore never
stores a metadata location: at every query, it asks the catalog where the
table is *now* and reads that. A federated Iceberg dataset always answers
from the table's latest committed snapshot.

To enroll a table for live reads, register a dataset whose **name is the
`namespace.table` identifier** (no file), then bootstrap it. The schema comes
from the catalog itself — no data is moved at enrolment. To take a durable
copy instead, use the import picker; the copy behaves like any lake snapshot
afterwards, profiling and all.

One catalog can serve both: import the tables you want isolated and
profiled, enroll the ones you want always-fresh. Whether a dataset is served
live or from its copy is decided per dataset, and the query plan view labels
each source `FEDERATED` or `MATERIALIZED` accordingly.

### A planner note for live tables

A live Iceberg dataset is profiled at compile time **in place**: the
profiling queries execute over the same live scan ordinary queries use, so
the source does the scanning and only aggregate statistics travel back —
no copy of the table is ever staged to the lake, whatever its size, and
the numbers the planner sees are exact for the whole table as of that
compile. The one thing a live table's statistics never claim is a
**unique key**: distinctness measured at profile time can be stale by
query time (the table keeps committing), and Aran refuses to eliminate
joins on stale evidence — an imported copy is what earns key-capable
profiling. Each compile of a live class costs one profiling pass at the
source. This flow is implemented and tested but, like the rest of the
live path, not yet field-proven (V54).

## Naming limits

A table is addressed as `namespace.table`, with nested namespaces joined by
dots (`db.nested.table`). A namespace or table whose own name contains a
literal dot cannot be addressed.

## Known issues

- **A join-only class is named after the identifier, with `_` for `.`.**
  Aligning two tables by a join alone creates each side's integrated class
  for you, named from the table identifier in the alphabet a query writes
  bare: `db.venue` becomes `Int_db_venue`, and `db.nested.table` becomes
  `Int_db_nested_table`. Two tables whose identifiers differ only by `.`
  versus `_` would mint the same name; Aran refuses the second alignment
  rather than merge two tables into one class. To choose the name yourself,
  align the tables' classes with a label (a class alignment).
- **Importing a table served by another Aran is proven in a test, not
  across deployments.** Another Aran's
  [product catalog](../programmatic-access/iceberg.md) serves its files over
  authenticated `https://`. The import sends the catalog's bearer token with
  every file read, as live reads do. That has been watched against Aran's own
  catalog in one process, not between two deployments.

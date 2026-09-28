---
title: 'Register a data repository'
sidebar_label: 'Register a data repository'
sidebar_position: 1
description: 'Add a repository connection, test it, and make its contents available as datasets.'
# Diátaxis mode: how-to
---

## Before you start

Decide which type you need. Check what that type can actually do — the
[capability table](../repositories/index.md) is the short version. If you
only want to get a file into Aran, any type accepts an upload; a file-system
repository is the usual choice.

## Register it

1. **Repositories → Register repository**.
2. Pick the project.
3. Confirm.
4. Fill in name, description, and **Type**. The remaining fields change with
   the type — each [repository page](../repositories/index.md) lists them.
5. Submit.

:::tip The New button silently does nothing while the page is loading
If the project list has not arrived yet, the button does nothing — no
dialog, no error. Wait a second, then click again.
:::

## Test the connection

Run the connection test from the repository's row or detail panel.

**Read the result, not just the colour.** The depth of the test varies a lot
by type:

| Type | A pass means |
| --- | --- |
| Relational | Credentials accepted, database exists, server version read |
| File system | Nothing — the kind holds uploaded files, so there is nothing to reach |
| Singer tap | The tap ran and offered its streams |
| Object storage | The endpoint returned *any* HTTP status, 403 included |

For object storage, a pass does not validate credentials.

### When it fails

| Message | Do this |
| --- | --- |
| *Engine 'X' is not registered* | Use one of `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `SQLSERVER`, `HBASE`. |
| *Database connection refused* | Credentials or database name. The `SQLState` is in the detail. |
| *Transport error — could not reach host* | The host is unreachable **from the backend**. `localhost` means the backend's own machine. |
| *… resolves to a private or loopback address, and this deployment restricts outbound connections to public hosts* | The deployment sets `aran.security.block-private-networks=true`. Use a public host, or ask the operator. |
| *No endpoint configured* | MinIO and other self-hosted providers need an explicit endpoint. |
| *Tap settings are not accepted* | The tap id or a setting key is not in the deployment's `aran.singer.taps` catalogue. The detail names the accepted keys. |
| *The tap did not complete discovery* | The tap ran and failed. The detail carries the tap's own diagnostics, e.g. *exited with status 1*. |

A relational test also downloads the JDBC driver on first use. So an engine
other than PostgreSQL needs the backend to reach Maven Central once. See
[Driver resolution](../deep-dives/driver-resolution.md).

## Edit it later

Editing a repository while leaving a credential field masked (`********`)
keeps the stored value — and pins the connection target. Changing the host,
port, database, endpoint, or catalog URI in the same update fails with:

> Re-enter the credential to change the connection target — a kept (masked)
> credential may not be redirected to a new host or endpoint

Renaming and editing the description are always fine. To repoint the
repository, re-enter the credential in the same request.

## Get data in

Registering proves reachability. Three paths put data into the pipeline.

### Upload a file

Works for every repository type.

1. **Datasets → Register dataset**.
2. Pick the project and this repository.
3. Name the dataset.
4. Choose the file.
5. Submit.

Aran understands CSV, JSON, and parquet.

### Import a table

For every relational engine except HBase. The dataset dialog offers **Import
from table** for every engine whose extract this release builds — the
`bulkExtractReady` flag `GET /api/relational-engines` publishes, the same one
the server checks. The API call is the same for every engine:

```
POST /api/projects/{projectId}/repositories/{repositoryId}/datasets/import
```

1. In the dataset wizard, choose the import mode.
2. Select a table from the list.

Aran snapshots the table to the lake as parquet and bootstraps it. Only an
HBase import is rejected, with `UNSUPPORTED_IMPORT_SOURCE` — upload a file
for its data.

### Import a stream

Only for **Singer tap** repositories. Pick a stream from the tap's discovery
listing in the dataset wizard, or call:

```
POST /api/projects/{projectId}/repositories/{repositoryId}/datasets/import-stream
{"stream": "exchange_rate"}
```

Aran runs the tap, snapshots the stream's records to the lake as parquet,
and bootstraps them. See [Singer taps](../repositories/singer-taps.md).

## Make a relational repository federated

Set **federated** on the repository, and its queries read the live database
instead of a snapshot. This is available for PostgreSQL, MySQL, and MariaDB
only.

Know this before you turn it on:

- Every query hits the source database. Its load becomes your query load.
- The credentials become query-path credentials, not just test credentials.
- There is no snapshot. Aran detects drift by probing the live table
  (`drift-probe`), not by re-reading a snapshot.

## Related

- [Repository reference](../repositories/index.md) — per-type fields and limits.
- [Bootstrap and profile a dataset](./bootstrap-and-profile-a-dataset.md) — the next step.

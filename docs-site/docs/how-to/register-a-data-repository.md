---
title: 'Register a data repository'
sidebar_label: 'Register a data repository'
sidebar_position: 1
description: 'Add a repository connection, test it, and make its contents available as datasets.'
# Diátaxis mode: how-to
---

A repository is a connection to a place data lives: a database, an object
store, an Iceberg catalog, a Singer tap, or a folder of uploaded files. Use
this page to register one, test it, and bring its data in as datasets.

## Before you start

Decide which type you need. Check what that type can do in the
[capability table](../repositories/index.md). If you
only want to get a file into Aran, any type accepts an upload; a file-system
repository is the usual choice.

## Register it

1. **Repositories → Register repository**.
2. Pick the project.
3. Confirm.
4. Fill in name, description, and **Type**. The remaining fields change with
   the type, and each [repository page](../repositories/index.md) lists them.
5. Submit.

:::tip Register repository is disabled while the page loads
The button stays disabled until your projects have loaded, and the reason
shows beside it (*Loading your projects…*). With no project yet, clicking it
offers to create one.
:::

## Test the connection

Run the connection test from the repository's row or detail panel.

How much a passing test proves depends on the type:

| Type | A pass means |
| --- | --- |
| Relational | Credentials accepted, database exists, server version read |
| File system | Nothing: the kind holds uploaded files, so there is nothing to reach |
| Singer tap | The tap ran and offered its streams |
| Iceberg REST catalog | The catalog answered `GET /v1/config`: reachable, and the token accepted. The object store its tables live in is not contacted |
| Object storage | The endpoint returned *any* HTTP status, 403 included |

For object storage, a pass does not validate credentials.

### When it fails

| Message | Do this |
| --- | --- |
| *Engine 'X' is not registered in the driver registry* | Use one of `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `SQLSERVER`, `HBASE`. |
| *Database connection refused* | Credentials or database name. The `SQLState` is in the detail. |
| *Transport error — could not reach host* | The host is unreachable **from the backend**. `localhost` means the backend's own machine. |
| *… resolves to a private or loopback address, and this deployment restricts outbound connections to public hosts* | The deployment sets `aran.security.block-private-networks=true`. Use a public host, or ask the operator. |
| *No endpoint configured* | MinIO and other self-hosted providers need an explicit endpoint. |
| *Tap settings are not accepted* | The tap id or a setting key is not in the deployment's `aran.singer.taps` catalogue. The detail names the accepted keys. |
| *The tap did not complete discovery* | The tap ran and failed. The detail carries the tap's own diagnostics, e.g. *exited with status 1*. |
| *Could not reach the Iceberg catalog* | The catalog URI is unreachable from the backend, or it refused the token. The detail says which. |

A relational test also downloads the JDBC driver on first use, so an engine
other than PostgreSQL needs the backend to reach Maven Central once. See
[Driver resolution](../deep-dives/driver-resolution.md).

## Edit it later

Editing a repository while leaving a credential field masked (`********`)
keeps the stored value and pins the connection target. Changing the host,
port, database, endpoint, or catalog URI in the same update fails with:

> Re-enter the credential to change the connection target — a kept (masked)
> credential may not be redirected to a new host or endpoint

Renaming and editing the description always work. To repoint the
repository, re-enter the credential in the same request.

## Get data in

Registering proves reachability. Four paths put data into the pipeline:
upload a file, or import a table, a stream or an Iceberg table.

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
from table** for every engine whose extract this release builds. That is the
`bulkExtractReady` flag that `GET /api/relational-engines` publishes, the same one
the server checks. The API call is the same for every engine:

```
POST /api/projects/{projectId}/repositories/{repositoryId}/datasets/import
```

With body `{"tableName": "<table>"}`.

1. In the dataset wizard, choose **Import from table**.
2. Select a table from the list.

Aran snapshots the table to the lake as parquet and bootstraps it. Only an
HBase import is rejected, with `UNSUPPORTED_IMPORT_SOURCE`; upload a file
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

### Import an Iceberg table

Only for **Iceberg REST catalog** repositories. In the dataset wizard, choose
**Import from iceberg table** and pick a table the catalog offers, or call:

```
POST /api/projects/{projectId}/repositories/{repositoryId}/datasets/import-iceberg
{"tableIdentifier": "<namespace>.<table>"}
```

Aran copies the table's current contents into the lake as parquet and
bootstraps it. See [Iceberg REST catalogs](../repositories/iceberg-rest-catalogs.md).

## Make a relational repository federated

Set **federated** on the repository, and its queries read the live database
instead of a snapshot. This is available for PostgreSQL, MySQL, and MariaDB
only. Before you turn it on, note that:

- Every query hits the source database. Its load becomes your query load.
- The credentials become query-path credentials as well as test credentials.
- There is no snapshot. Aran detects drift by probing the live table
  (`drift-probe`), not by re-reading a snapshot.

[Query a database live](./query-a-database-live.md) walks through the steps.

## Related

- [Repository reference](../repositories/index.md): per-type fields and limits.
- [Bootstrap and profile a dataset](./bootstrap-and-profile-a-dataset.md): the next step.

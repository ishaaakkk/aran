---
title: 'Data repositories'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'One reference page per repository type, each stating which pipeline capabilities that type currently supports.'
# Diátaxis mode: reference
---

A **data repository** is a registered connection to a place where data lives.
Aran recognises five types, and they do not all do the same things.

## Capabilities by type

Registering a repository proves Aran can reach it. It does not mean data can
flow from it into the pipeline, because those are separate capabilities.

| Type | Connection test | Browse contents | Import contents | Live federated query | File upload |
| --- | --- | --- | --- | --- | --- |
| [Relational databases](./relational-databases.md) | Yes (real JDBC connect) | Yes (table list) | Every engine except HBase | PostgreSQL, MySQL, MariaDB | Yes |
| [Object storage](./object-storage.md) | Yes (HTTP probe) | No | Not yet | Not yet | Yes |
| [File uploads](./file-uploads.md) | Nothing to test (upload-only) | No | No | No | Yes |
| [Singer taps](./singer-taps.md) | Yes (a real tap run) | Yes (stream list) | Yes, per stream | No | Yes |
| [Iceberg REST catalogs](./iceberg-rest-catalogs.md) | Yes (real protocol hello) | Yes (table list) | Yes, per table | Yes | Yes |

Notes on the table:

- **Object storage.** No endpoint or button reaches the copy path yet, and the
  live reader exists but nothing bootstraps it.
- **Singer taps.** A tap has no live query by nature: it produces records only
  when it runs.
- **Iceberg REST catalogs.** Import and live query have run against Iceberg's
  own reference REST server over a local `file://` warehouse, but not yet
  against a production catalog (Polaris, Lakekeeper, Glue) or an `s3://`
  object store. See the caveat on the [Iceberg page](./iceberg-rest-catalogs.md).

How far each type has been tested is on the
[status page](../overview/status.md).

There is no document-store, cache or generic web-API type. Three such types
once existed: they could be registered and connection-tested, but no data could
flow from them, so they were removed before the first release. To bring data
from one of those systems into Aran, export it to a file and upload it, or
reach a SaaS API through a [Singer tap](./singer-taps.md).

`GET /api/repository-types` returns `{type}` for every type this build knows.
Every one of them can take data in, so there is no per-type capability flag.
`GET /api/singer-taps` lists the taps this deployment has configured (see
[Singer taps](./singer-taps.md)).

## What the columns mean

**Connection test.** Aran tries to reach the system. An unauthenticated HTTP
request (object storage) and an authenticated database connection (relational)
both count as a connection test but prove different things, so each page states
what its type's test proves.

**Browse contents.** Aran can list what is inside: tables for a database or a
catalog, streams for a tap. The list fills the picker when you register a
dataset from an existing source. A type that cannot browse returns an empty
list, not an error.

**Import contents.** Aran pulls the data in and snapshots it into the lake, so
you do not export a file by hand. An imported table or tap stream keeps its
upstream and can be refreshed later (`POST …/datasets/{id}/refresh`; policies
at `GET …/datasets/refresh-policies`). An uploaded file has no upstream and
cannot be refreshed.

**Live federated query.** The query reads the source directly at execution
time instead of a snapshot. For PostgreSQL, MySQL and MariaDB, DuckDB attaches
the database read-only, and you turn this on per repository with the
`federated` flag. An Iceberg table has no flag: it is read live unless that
dataset has been copied into the lake. See
[Query a database live](../how-to/query-a-database-live.md).

## Credentials

Credential fields are encrypted at rest, and the per-type pages list which
fields. A failed connection test names the host but never the password.

Two behaviours apply to every stored credential:

- API responses mask the secret as the literal `********`. The stored value
  never travels back to a client.
- Sending `********` back on an update means "keep the stored value", and while
  a credential is kept, the connection target is pinned. Changing the host,
  port, database, endpoint or catalog URI in the same update is refused with:

  > Re-enter the credential to change the connection target — a kept (masked)
  > credential may not be redirected to a new host or endpoint

  To repoint a repository, re-enter the credential in the same request.

## Related

- [Register a data repository](../how-to/register-a-data-repository.md): the task, step by step.
- [Driver resolution](../deep-dives/driver-resolution.md): how JDBC drivers arrive at runtime.

---
title: 'Relational databases'
sidebar_label: 'Relational databases'
sidebar_position: 1
description: 'Connecting PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, and HBase, and what Aran can do with each.'
# Diátaxis mode: reference
---

Relational repositories connect over JDBC to PostgreSQL, MySQL, MariaDB,
Oracle, SQL Server or HBase. This page lists what Aran can do with each engine
and every field you fill in when you register one.

This is the most capable repository type: every engine except HBase can be
imported, and PostgreSQL, MySQL and MariaDB can also be queried live.
[Singer taps](./singer-taps.md) import too, and
[object storage](./object-storage.md) has a live `s3://` read path in the
engine; those pages say how much of each is reachable. Of the relational
engines, PostgreSQL, MySQL 8.4 and MariaDB 11 are the ones that have been run
against a real server (see the [status page](../overview/status.md)).

## Prerequisites

- The database must be reachable from the Aran backend, not from your browser.
  A host of `localhost` means the machine running the backend.
- Aran always refuses link-local, wildcard and multicast targets. When the
  deployment sets `aran.security.block-private-networks=true`
  (`ARAN_BLOCK_PRIVATE_NETWORKS`, default `false`), loopback and RFC 1918
  targets are refused too, with *"resolves to a private or loopback address,
  and this deployment restricts outbound connections to public hosts"*. On such
  a deployment, `localhost` and `10.x` hosts do not register.
- A user with permission to connect and to read the tables you intend to use.
- The engine must be in the driver registry (see the table below). Aran
  downloads the JDBC driver on first use, so the backend needs outbound access
  to Maven Central once per engine, or a pre-populated driver cache. See
  [Driver resolution](../deep-dives/driver-resolution.md).

## Setup

1. **Repositories → Register repository**, pick a project, confirm.
2. Fill in:
   - **Name** and **Description**
   - **Type:** `RELATIONAL_DB` (the default)
   - **Engine**, **Host**, **Port**, **Database**, **Schema**
   - **Username**, **Password**
3. Submit, then run the connection test.

## Supported capabilities

| Capability | Status |
| --- | --- |
| Connection test | Yes. A real JDBC connection is opened and the server's product name and version are read back. |
| Browse contents | Yes. The table list comes from JDBC metadata. |
| Import contents | Every engine except HBase, from the dialog's **Import from table** or over the API. |
| Live federated query | PostgreSQL, MySQL, MariaDB. |
| Catalog harvest | PostgreSQL only. Row counts, column statistics and declared foreign keys. |
| File upload into this repository | Yes. |

### What the connection test proves

Aran resolves and loads the driver, builds the JDBC URL from the registry's
template, connects with your credentials and reads `DatabaseMetaData`. A pass
means the host is reachable, the credentials are accepted and the named
database exists. Failures are reported by category: a SQL-level error
(authentication, unknown database, SSL mismatch) comes back with its
`SQLState`, and a transport error (unreachable host, timeout) is reported
separately.

### Importing a table

Registering a dataset from an existing table snapshots that table into the lake
as parquet, then runs the normal bootstrap pipeline. Each engine pulls rows
differently:

| Engine | Import mechanism |
| --- | --- |
| PostgreSQL | The lake reads the table natively. Types come off a prepared statement before any row moves. |
| MySQL, MariaDB, Oracle, SQL Server | A generic JDBC extract. |
| HBase | None. Aran rejects the import with `UNSUPPORTED_IMPORT_SOURCE`. |

The dataset dialog offers **Import from table** for every engine whose import
mechanism is built (all but HBase). It asks `GET /api/relational-engines` which
those are. Over the API, the same import is
`POST …/repositories/{id}/datasets/import` (see
[Import a table](../how-to/register-a-data-repository.md#import-a-table)).

Aran never interpolates the table name into SQL from your input. The name must
be an exact member of the list the repository exposes, checked against a fresh
scan at import time.

An imported table keeps its upstream, and `POST …/datasets/{id}/refresh` re-runs
the same extract. The default policy is manual, so nothing re-imports on a
schedule unless you set one (`PUT …/datasets/{id}/refresh-policy`). An uploaded
file has no upstream and cannot be refreshed.

### Federated repositories

Set **federated** on the repository and its queries read the live database
instead of a snapshot. At query time the engine resolves each dataset to a live
read built from the repository's connection details, through one of DuckDB's
database extensions.

| Engine | Live read |
| --- | --- |
| PostgreSQL | The database, attached read-only (the postgres extension) |
| MySQL | The database, attached read-only (the MySQL extension) |
| MariaDB | The database, attached read-only (the MySQL extension) |
| Oracle, SQL Server, HBase | None (cannot be federated) |

For all three engines the query's DuckDB session stores the credentials in a
temporary secret and attaches the database read-only under a reserved name,
`aran_attach_<repository id>`. A PostgreSQL query reads
`aran_attach_<id>."<schema>"."<table>"`, where a blank **Schema** means
`public`. A MySQL or MariaDB query reads
`aran_attach_<id>."<database>"."<table>"`, because DuckDB's MySQL extension has
no scan function and an attached database is its only live read.

The password never appears in a plan, in the SQL the explain view shows, or in
an error message, although a connection error can name the user. A statement
you write (a wrapper, a constraint, a `sources` or `products` JDBC query)
cannot name the attached database. Aran refuses any identifier starting with
`aran_attach_`, and the `postgres_*` and `mysql_*` functions, because through
them a statement could read every table the repository's account can see, not
only the ones you registered. Even so, give that account read access to the
registered tables only.

MySQL and MariaDB take no schema: the database is the schema, so Aran ignores
the **Schema** field.

A federated dataset has no snapshot, so bootstrapping reads the live catalog
instead of bytes. For MySQL and MariaDB that connection uses the vendor's
driver, which Aran downloads from Maven Central on first use and checks against
a pinned SHA-256 (see [driver resolution](../deep-dives/driver-resolution.md)).
The steps are in [Query a database live](../how-to/query-a-database-live.md).

The **federated** flag applies to every dataset in the repository. A table you
imported into the lake and then marked federated is bootstrapped from its copy
and then queried live. Set the flag before you register datasets under the
repository, or expect that mismatch.

### Query placement: live or from the copy, chosen per query

A table with both paths, an imported lake copy and a federated upstream, is
planned per query. Aran compares the cost of reading the source live (shipping
only the rows the query's own filters keep) against scanning the local copy, and
the plan view's `access` chip shows which side won. A selective lookup typically
runs live, while a full scan reads the copy instead of hauling the whole table
over the wire.

Freshness outranks cost. The copy may serve only if you have declared an
interval refresh policy on the dataset and the refresher is keeping up, so
declaring the policy is how you opt a copy into placement. A one-off import with
no policy, a manual-only policy, or a copy whose refresh has silently stopped is
never substituted for the live source, and those queries keep reading live.
When placement does choose the copy, it serves the dataset's current refreshed
copy, and the query result carries a `placement` entry naming both costs and the
reason.

This decision path is implemented and tested offline. No live database has been
watched flipping between the two modes, and the cost weights are not calibrated.

A federated dataset has no snapshot, so Aran checks drift differently. The
`drift-probe` operation asks the live database for the table's current shape and
compares it against the recorded baseline. The query path and the probe share
the scan expression, so the probe cannot inspect a different database from the
one queries read.

### Catalog harvest

Registering a dataset in a relational repository, importing a table and
refreshing one each trigger a best-effort read of the database's own catalog.
Aran collects approximate row counts, approximate column statistics and declared
foreign keys. This is implemented for PostgreSQL only, and it never fails a
registration, a refresh or a query. See [Referential evidence and join
removal](../query-engine/referential-evidence.md).

## Configuration reference

| Field | Required | Notes |
| --- | --- | --- |
| `engine` | Yes | Must be a registry key: `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `SQLSERVER`, `HBASE`. Rejected if unknown. |
| `host` | Yes | Must match `^[A-Za-z0-9._:\[\]-]+$`, otherwise *"host contains characters not allowed in a connection target"*. Resolved from the backend. |
| `port` | Yes | Substituted into the engine's URL template. |
| `database` | Yes | Must match `^[A-Za-z0-9._-]+$`. |
| `schema` | No | Used by PostgreSQL's federated scan. Ignored for MySQL and MariaDB. |
| `username` | No | Sent as the JDBC `user` property when set. |
| `password` | No | Sent as the JDBC `password` property when set. Encrypted at rest. |
| `federated` | No | Defaults to false. Only meaningful for the three engines above. |

### Registered engines and their pinned drivers

| Engine | Driver class | Version | URL template |
| --- | --- | --- | --- |
| `POSTGRESQL` | `org.postgresql.Driver` | 42.7.5 | `jdbc:postgresql://{host}:{port}/{database}` |
| `MYSQL` | `com.mysql.cj.jdbc.Driver` | 9.2.0 | `jdbc:mysql://{host}:{port}/{database}` |
| `MARIADB` | `org.mariadb.jdbc.Driver` | 3.5.2 | `jdbc:mariadb://{host}:{port}/{database}` |
| `ORACLE` | `oracle.jdbc.OracleDriver` | 23.6.0.24.10 | `jdbc:oracle:thin:@{host}:{port}:{database}` |
| `SQLSERVER` | `com.microsoft.sqlserver.jdbc.SQLServerDriver` | 12.8.1.jre11 | `jdbc:sqlserver://{host}:{port};databaseName={database};encrypt=false` |
| `HBASE` | `org.apache.phoenix.queryserver.client.Driver` (Phoenix thin) | 6.0.0 | `jdbc:phoenix:thin:url=http://{host}:{port}` |

PostgreSQL's driver is already on the application classpath, so Aran never
downloads it. It fetches the others on first use and verifies each against a
pinned SHA-256.

The HBase entry connects through the Phoenix Query Server. The **Port** field
lands in the `http://{host}:{port}` part of the URL, the engine's default port is
2181, and the template ignores the **Database** field.

## Known limitations

- HBase cannot be imported. Connect, test and list its tables, and upload a file
  for the data.
- Oracle, SQL Server and HBase cannot be federated, because no DuckDB scanner
  exists for them.
- The catalog harvest reads PostgreSQL only. Every other engine contributes no
  row counts, no column statistics and no declared foreign keys.
- A federated dataset cannot be previewed or mined for constraints, because
  both read a snapshot and a federated dataset has none. It is profiled in
  place: while its wrapper is Opaque, each compile runs FREYJA's profiling
  queries over the live read. See [Query a database
  live](../how-to/query-a-database-live.md#what-still-works-and-what-does-not).
- The Oracle URL template uses SID syntax (`@host:port:database`), not the
  service-name form (`@//host:port/service`). A database that accepts only a
  service name does not connect.
- Adding an engine means adding a registry entry, including the driver's
  SHA-256. There is no configuration-only way to introduce one.
- Credentials are per repository, not per user. Everyone who can see the project
  queries as the same database user.

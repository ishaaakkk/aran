---
title: 'Iceberg catalog'
sidebar_label: 'Iceberg catalog'
sidebar_position: 5
description: 'Reading materialised data products from Iceberg-speaking engines — DuckDB, or iceberg-core''s REST client — through Aran''s read-only REST catalog.'
# Diátaxis mode: reference
---

Aran serves its **materialised data products** as Apache Iceberg tables
through a read-only Iceberg REST catalog. The catalog URI, the base a client
appends `/v1/…` to, is:

```
https://<your-aran-host>/api/iceberg
```

An engine that speaks the Iceberg REST protocol, and can send a bearer
token on its **file** reads as well as its catalog calls, can list and read
products without an Aran driver. The catalog has one namespace, `products`,
holding one table per materialised product, under the same table names the
[JDBC `products` schema](./jdbc.md) uses. A product's **Connect** block on
the **Data products** screen shows its table name and this deployment's
catalog URI, or why the product is not served.

Two clients read this catalog end to end over HTTP against a running Aran in
its integration tests: **DuckDB 1.5.5** (both recipes below) and
**iceberg-core 1.7.1's `RESTCatalog`** (Java). Nobody has yet watched a real
deployment over HTTPS behind a proxy, reads at scale, or any other engine,
Spark and Trino included. See the [status page](../overview/status.md) for
what else is untested.

## What is served

| Product | Served? |
| --- | --- |
| Materialised, refreshed at least once, schema recorded | **Yes**, as an Iceberg v2 table pinned to its latest snapshot. |
| Virtual | **No.** A virtual product has no files: its rows exist only when Aran expands its query at read time, and static Iceberg metadata would freeze them. Read virtual products over [JDBC](./jdbc.md) or [MCP](./mcp.md). |
| Draft, failed, or never materialised | No. There is no data to point at. |

A product refresh moves the table to the new snapshot. Clients holding old
metadata get a clean 404 and recover by re-loading the table through the
catalog.

Each table's metadata carries a `schema.name-mapping.default` property. The
parquet Aran writes has no Iceberg field ids, so a reader matches columns by
name through that mapping.

## Routes

| Method | Path | Answers |
| --- | --- | --- |
| `GET` | `/api/iceberg/v1/config` | The protocol's configuration, with no overrides |
| `GET` | `/api/iceberg/v1/namespaces` | The one namespace, `products` |
| `GET` | `/api/iceberg/v1/namespaces/products` | That namespace |
| `GET` | `/api/iceberg/v1/namespaces/products/tables` | One entry per served product |
| `GET`, `HEAD` | `/api/iceberg/v1/namespaces/products/tables/<name>` | The table, including its `metadata-location` |
| `GET` | `/api/iceberg/files/<product-id>/<snapshot-id>/metadata/<file>` | The metadata file and manifests |
| `GET` | `/api/iceberg/files/<product-id>/<snapshot-id>/data/data.parquet` | The product's rows |

Any other namespace or table answers 404. A file route answers 404 unless the
product belongs to the caller's tenant and the snapshot in the path is the
product's current one. Reads of the data file count against the tenant's
egress ceiling, as JDBC statements and exports do. See [Licensing
modes](../deep-dives/licensing-modes.md).

## Every byte comes from Aran

Iceberg engines normally read data files straight from object storage. Aran's
catalog does not. Every location in the served metadata (the metadata file,
the manifests and the parquet) points back at Aran's own authenticated
`/api/iceberg/files/…` endpoint. No storage URL or credential reaches a
client, and revoking the API token revokes access to the data. The cost is
that reads flow through Aran, so you get protocol compatibility rather than
direct-to-storage throughput.

This also limits which engines can use the catalog. An engine must fetch
`https://` file locations with the token attached, which means an HTTP file
reader with a bearer header. Not every Iceberg engine has one.

## Authentication

The same [API token](./index.md) as every other surface, sent as a bearer
token on **both** the catalog calls and the file reads. `READ` scope is
enough. The catalog only implements GET and HEAD, so there is nothing to send
a write to.

## DuckDB

Load the extensions and give httpfs the token for the file reads:

```sql
INSTALL httpfs;  LOAD httpfs;
INSTALL iceberg; LOAD iceberg;
CREATE SECRET aran_files (TYPE HTTP, BEARER_TOKEN 'aran_pat_...');
```

Then attach the catalog and query products by name:

```sql
ATTACH 'aran' AS aran (
    TYPE ICEBERG,
    ENDPOINT 'https://aran.example/api/iceberg',
    TOKEN 'aran_pat_...'
);
SELECT * FROM aran.products.papers_per_year;
```

You need both the secret and the `ATTACH`. `TOKEN` authenticates only the
catalog calls, and the manifests and parquet are plain httpfs reads that
answer 401 without the HTTP secret. `ENDPOINT` stops before `/v1` because
DuckDB appends it: `…/api/iceberg/v1` asks for `/v1/v1/config` and fails with
404.

Without the `ATTACH`, a single table reads with `iceberg_scan` on the
`metadata-location` the catalog returns for it
(`GET /api/iceberg/v1/namespaces/products/tables/<name>`), with the same
HTTP secret:

```sql
SELECT * FROM iceberg_scan(
    'https://aran.example/api/iceberg/files/<product-id>/<snapshot-id>/metadata/v1.metadata.json');
```

Verified with DuckDB 1.5.5; the `ATTACH (TYPE ICEBERG)` options have shifted
across DuckDB's 1.x releases, so check them against your version.

## Java: iceberg-core's `RESTCatalog`

```java
RESTCatalog catalog = new RESTCatalog();
catalog.initialize("aran", Map.of(
        "uri", "https://aran.example/api/iceberg",
        "token", "aran_pat_...",
        "io-impl", "com.example.BearerHttpFileIO"));
Table table = catalog.loadTable(TableIdentifier.of("products", "papers_per_year"));
```

The `token` covers the catalog calls. iceberg-core ships no `FileIO` for
`http(s)://` locations, so reading the manifests and the parquet needs one
that performs an HTTP GET with the same bearer header, named in `io-impl`.
Aran does not ship one. The one its integration test uses is short.

## PyIceberg: not supported

PyIceberg (0.12) ships no FileIO for `https://`, so opening such a location
fails with *Unrecognized filesystem type in URI: https*. Every file location
Aran serves is under its own `https://` origin, so PyIceberg cannot read a
product without a custom `py-io-impl` that sends the bearer token on each GET.
Aran does not ship one, and no PyIceberg client has been run against this
catalog. From Python, use the [Python SDK](./python-sdk.md) or DuckDB's
Python package with the recipe above.

## Choosing a surface

| You want | Use |
| --- | --- |
| A product in DuckDB, or in a Java program built on iceberg-core | **This catalog** |
| Live OQL over the integrated schema, or virtual products | [JDBC](./jdbc.md) or the [Python SDK](./python-sdk.md) |
| An AI agent reading curated products | [MCP](./mcp.md) |

## Behind a proxy

The file locations in the served metadata are minted from the request's
origin. If Aran sits behind a proxy that rewrites `Host`, set the external
origin explicitly or clients will be handed URLs they cannot reach:

```yaml
aran:
  iceberg:
    public-base-url: https://aran.example
```

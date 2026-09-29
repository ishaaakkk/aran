---
title: 'Serve'
sidebar_label: 'Serve'
sidebar_position: 5
description: 'What a data product is, when to materialise one or keep it virtual, and how JDBC, REST export, Iceberg REST, MCP and the Python SDK read what Aran serves.'
# Diátaxis mode: explanation
---

A **data product** is a saved OQL query with a name. This page explains why you
would publish one, how the two access modes differ, and what each client
interface reads. The steps are in
[Create a data product](../how-to/create-a-data-product.md).

## Why publish a product

Querying the integrated schema needs OQL. A product gives one query's result a
fixed name and the shape of a table, which changes who can read it:

- A SQL client reads it with plain SQL, as a table in the JDBC `products`
  schema.
- An Iceberg engine reads a materialised product as an Iceberg table.
- An AI agent reads it over MCP. The MCP sidecar lists products and reads their
  rows, and has no tool that runs OQL, so the products you create are what an
  agent sees.
- A materialised product is answered from one stored file, so its readers put
  no load on the sources.

A product curates what a reader is offered. It does not restrict what a token
can read: see [Tokens and security](#tokens-and-security).

## Materialised or virtual

You choose the **access mode** when you create the product, and you cannot
change it later. To switch, create a second product from the same query.

| | Materialised (the default) | Virtual |
| --- | --- | --- |
| Aran computes the rows | When you refresh | On every read |
| A reader gets | The rows as of the last refresh | The rows the query yields now |
| A read costs | One scan of a stored file | One full run of the query |
| An enforced denial constraint applies | At the next refresh | At the next read |
| Served over Iceberg | Yes | No, because there are no files |
| States | `DRAFT` until the first refresh, `MATERIALIZING` during one, then `READY`, `STALE` or `FAILED` | `READY` from creation |

Choose materialised when the query is expensive, the data changes slowly, many
clients read the same product, or an Iceberg engine needs it. Choose virtual
when a stale answer costs you more than rerunning the query. A virtual product
read from a live source reads that source on every call. See
[Materialised and virtual data products](../deep-dives/virtual-data-products.md#what-a-virtual-read-costs).

### Refresh and `STALE`

A materialised product is refreshed on request: **⟳ refresh** in **Products**,
or `POST …/products/{id}/materialize`. No read recomputes it. The first refresh
builds its snapshot.

Aran records the schema version that was active when you created the product.
That record is not the version a query runs against: every refresh runs the
OQL against the schema that is active at that moment. The record has one job.
When a different version is promoted or rolled back to, Aran marks every
`READY` materialised product created under another version `STALE`. A `STALE`
product keeps serving its snapshot, and a refresh returns it to `READY` until
the next promotion. `STALE` follows schema changes only: a new snapshot of a
source does not mark a product `STALE`, so refresh the product when its sources
change. See [Refresh it later](../how-to/create-a-data-product.md#refresh-it-later).

A virtual product is never `STALE`, because it stores no rows. If a schema
change breaks its query, its next read fails with an error that names the
missing property, instead of serving an old answer. See
[When the schema changes](../deep-dives/virtual-data-products.md#when-the-schema-changes).

## Ways to read

JDBC, the Iceberg catalog and MCP read products. REST export and the Python
SDK run OQL over the integrated schema instead, for a reader who wants the
whole vocabulary rather than a published result; JDBC can do both. Each
interface has its own setup guide under
[Connect your tools](../programmatic-access/index.md). The examples use
`localhost:8081`, the application's default port.

| Interface | Reads | Use it from |
| --- | --- | --- |
| [JDBC](../programmatic-access/jdbc.md) | Products as tables in the `products` schema; OQL in `graph`; per-source SQL in `sources` | Any SQL client or BI tool |
| [Iceberg REST catalog](../programmatic-access/iceberg.md) | Materialised products, read-only | DuckDB, iceberg-core |
| [MCP](../programmatic-access/mcp.md) | `READY` and `STALE` products | AI agents such as Claude Desktop, Claude Code and Cursor |
| [REST export](../how-to/export-query-results.md) | An OQL result as a CSV or Parquet file | Scripts and pipelines |
| [Python SDK](../programmatic-access/python-sdk.md) | An OQL result as a DataFrame | Notebooks and scheduled jobs |

**JDBC.** Aran speaks the Avatica remote protocol. The token goes in the
password:

```
jdbc:avatica:remote:url=http://localhost:8081/api/jdbc;serialization=json;authentication=BASIC;avatica_user=aran;avatica_password=<your-token>
```

**Iceberg REST catalog.** Point the engine at the catalog URI, and send the
token as a bearer token on catalog calls and file reads alike:

```
https://<your-aran-host>/api/iceberg
```

**MCP.** Add an `aran` entry under `mcpServers` in the client's configuration,
running `aran-mcp/.venv/bin/aran-mcp` from a source checkout with `ARAN_URL`
and `ARAN_TOKEN` in its environment. The [MCP guide](../programmatic-access/mcp.md#configure-your-mcp-client)
has the full block.

**REST export.**

```bash
curl -X POST http://localhost:8081/api/query/export \
  -H "Authorization: Bearer $ARAN_TOKEN" -H "Content-Type: application/json" \
  -d '{"oql": "SELECT IPaper.title, IPaper.year FROM IPaper", "format": "CSV"}' \
  -o papers.csv
```

**Python SDK.**

```python
import aran_client as aran
aran.connect("http://localhost:8081", token="aran_pat_…")
df = aran.query("SELECT IPaper.title, IPaper.year FROM IPaper")
```

## Tokens and security

Every interface authenticates with the same API token, which you create under
**API Tokens** in your avatar menu. A `READ` token can query, export, browse
and read products. Creating or refreshing a product, and every other change,
needs a `FULL` token. No token of either scope reaches the administrative
routes. The exact route split is in
[Token scopes](../programmatic-access/index.md#token-scopes-the-split-is-by-route-not-by-meaning).

A token reads everything its owner can read in the organisation, over every
interface. Creating fewer products, or setting `ARAN_PROJECT` for the MCP
sidecar, narrows what a client lists, not what the token can reach.

Clients never receive a lake URL or a storage credential. Aran serves every
byte itself: a JDBC read runs inside the Aran process and returns rows, an
export streams the file from Aran, and every file location in the Iceberg
catalog points at Aran's own `/api/iceberg/files/…` route. See
[Every byte comes from Aran](../programmatic-access/iceberg.md#every-byte-comes-from-aran).

## Next

- [Create a data product](../how-to/create-a-data-product.md)
- [Materialised and virtual data products](../deep-dives/virtual-data-products.md)
- [Connect your tools](../programmatic-access/index.md): setup for each interface and the token model

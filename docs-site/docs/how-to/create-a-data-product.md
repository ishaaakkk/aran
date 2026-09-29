---
title: 'Create and materialise a data product'
sidebar_label: 'Create a data product'
sidebar_position: 9
description: 'Turn an OQL query into a named, pre-computed result that JDBC, the Python SDK, and MCP clients can all read.'
# Diátaxis mode: how-to
---

A data product is a named query result. Aran computes it once and serves it
many times, or recomputes it on every read. You choose which when you create
it. Use this page to save an OQL query as a product, build its first snapshot,
and keep it current.

A product is the surface [MCP](../programmatic-access/mcp.md) exposes to an
AI agent. JDBC's `products` schema serves it too, as a real table queried with
plain SQL, and the read-only [Iceberg catalog](../programmatic-access/iceberg.md)
serves a materialised one to external engines.

## Before you start

- The tenant needs an **ACTIVE** schema version. A draft is not enough:
  [promote](./align-and-integrate-schemas.md#compile-and-promote) it first.
- The product belongs to a project. Its name must be unique in that project.
- Over the API, creating and refreshing a product needs a **`FULL`** token.

## Save a query as a product

1. Open the **Workbench**.
2. Write and run the OQL query that defines the product. Confirm the
   result is what you want, because this OQL becomes the product's definition.
3. Click **◈ save as data product** under the result.
4. Pick the **PROJECT**, type a **NAME**, and add a **DESCRIPTION** if you
   want one.
5. Choose an **ACCESS MODE**: **Materialized** or **Virtual**.
6. Click **save**.

The dialog shows the query it saves. A `LIMIT` in that query stays part of the
definition, so the product holds at most that many rows; the dialog warns you
when there is one. Remove it to keep every row. The workbench's own display
cap does not apply: materialising writes the full result.

Over the API:

```http
POST /api/projects/{projectId}/products
{
  "name": "papers_by_author",
  "description": "Papers joined to their authors",
  "oql": "SELECT …",
  "accessMode": "MATERIALIZED"
}
```

`accessMode` is `MATERIALIZED` or `VIRTUAL`, and a request without it
creates a `MATERIALIZED` product. Aran plans the OQL to check it and does not
run it. Creation fails in these cases:

| Response | Message | Cause |
| --- | --- | --- |
| `400` | *Invalid OQL: `<message>`* | The OQL does not parse or plan against the active schema. |
| `400` | *The current schema version is `<status>`; a data product pins an ACTIVE version — promote it first.* | The current version is a draft. |
| `400` | *No schema version to pin — promote an integrated schema first.* | The tenant has no schema version at all. |
| `409` | *A data product named '`<name>`' already exists in this project. …* | The name is taken. If an earlier save timed out, the product may be that one: check the list first. |

## Choose the access mode

You make this choice once. No action converts one mode into the other
afterwards.

| | **Materialized** | **Virtual** |
| --- | --- | --- |
| Aran computes the rows | Once, when you refresh | On every read |
| A reader gets | The rows as of the last refresh | The rows the query yields now, from sources as fresh as each one is |
| A read costs | One scan of a stored file | One full run of the query |
| Refresh | You run it | There is none |
| Status after saving | `DRAFT` until the first refresh | `READY` straight away |
| Served over Iceberg | Yes, once built | No: there are no files to read |

**Materialized** is the default, and the rest of this guide describes it.
The steps below do not apply to a **Virtual** product, because it needs no
snapshot and offers no refresh. For that mode, read [Materialised and virtual
data products](../deep-dives/virtual-data-products.md).

Saving records the schema version that was active when you saved it (the
**defined on** column in **Products**). Aran uses that record for one thing:
to mark the product `STALE` once a different version is promoted. It is not
the version a query runs against. Both modes compute against whatever schema
is active when they compute.

## Build the first snapshot

Saving a materialised product does not compute it. It starts `DRAFT`, with
no rows and nothing for a client to read.

1. Open **Products**.
2. Find your product in the list.
3. Click **⟳ refresh**.

The same button builds the first snapshot and every later one. Until the first
one exists, the product's detail panel says *"Not materialized yet — refresh
to create the first snapshot."*

Over the API, the same step is a job:

```http
POST /api/projects/{projectId}/products/{productId}/materialize
```

The response is `202` with a `jobId`. A tenant already over its storage limit
is refused at this call with `402`, before any job starts. Calling it on a
virtual product fails with `400`: *A VIRTUAL product has no materialize step —
it is already servable.*

Aran runs the product's OQL and writes the result to a parquet snapshot in
the lake. The product goes through `MATERIALIZING` and becomes `READY`, with
a row count and a `materializedAt` timestamp (**data as of** in the list). For
a **materialised** product, JDBC's `products` schema and MCP's `read_product`
tool both read this snapshot, not a fresh run of the query. A virtual product
has no snapshot, and both surfaces recompute it on every read.

This holds for federated sources too. If the product's OQL reads
a repository flagged `federated`, the refresh reads that source through the
same planner as any query: live, unless the dataset also has a lake copy
with an interval refresh that is fresh enough for the planner to serve. In
that case the refresh may read the copy instead. Aran then stores the result,
like any other refresh, and later reads come from that stored copy. Choose
**Virtual** for a product that recomputes on every read.

## Read the product

Click the product in **Products** to open its detail panel. It shows the OQL,
the snapshot, and a **connect** section with the table name JDBC serves it
under. That name is the product name with every character other than a
letter, digit or `_` written as `_`, prefixed when it would start with a
digit, and suffixed when a product in another project already claims it. From there you can:

- **▶ run live preview** to see the first rows.
- **⤓ all rows · parquet** to download the whole snapshot through Aran.
- **⌁ agents** to connect an MCP client.

From a JDBC client, the product is a table in the `products` schema, and
`products._products` lists every product with its status, access mode and
`last_refreshed` time:

```sql
SELECT * FROM products.papers_by_author;
SELECT product, status, access, last_refreshed FROM products._products;
```

See [JDBC](../programmatic-access/jdbc.md), the
[Python SDK](../programmatic-access/python-sdk.md) and
[MCP](../programmatic-access/mcp.md) for each client.

## Refresh it later

A schema change can leave a `READY` product `STALE`, meaning the version
recorded at creation is no longer the active one. A client still reads the old
snapshot and can see that it is `STALE`.

Click **⟳ refresh** again to rebuild it. The rebuild runs the product's OQL
against the schema that is **active now**, not against the recorded version.
A refresh therefore does not restore the product's old meaning. It re-answers
the same question under today's schema.

Refreshing does not change the recorded version either. The product returns
to `READY`, and the next promotion marks it `STALE` again. Aran does record
the version each successful build ran under (`builtAgainstVersionId` in the
API), which is the version the rows actually mean.

If the OQL no longer plans against the active schema, the refresh fails. The
product reads `FAILED` and shows *Last materialization failed:* with the
error under its row. Its previous snapshot goes on serving over JDBC. MCP lists
only `READY` and `STALE` products, so an agent does not see the product again
until a refresh succeeds.

To change the query itself, create a new product. There is no edit.

## Delete a product

Open the product and delete it. The confirmation names what stops working.
Over the API, `DELETE /api/projects/{projectId}/products/{productId}`. A
product that belongs to a cube is refused with `400`: delete the cube instead,
which removes its whole star.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| **save** stays disabled | No project is selected, or the name is blank. |
| The dialog says there is no ACTIVE schema version | Only a draft exists. Promote it. |
| The product has fewer rows than expected | The saved OQL carries a `LIMIT`. Create a new product without it. |
| JDBC does not list the product as a table | It has no snapshot yet (`DRAFT`). Click **⟳ refresh**. It still appears in `products._products` with its status. |
| An agent cannot find the product | It is `DRAFT`, `MATERIALIZING` or `FAILED`. MCP lists only `READY` and `STALE` products. |
| **⟳ refresh** is disabled | A materialisation of this product is already running. Its job card shows the progress. |

## Related

- [Materialised and virtual data products](../deep-dives/virtual-data-products.md):
  the two access modes in detail.
- [Connect your tools](../programmatic-access/index.md): the ways
  to read a product from code.
- [MCP](../programmatic-access/mcp.md): which products an AI agent can read.

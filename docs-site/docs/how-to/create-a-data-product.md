---
title: 'Create and materialise a data product'
sidebar_label: 'Create a data product'
sidebar_position: 9
description: 'Turn an OQL query into a named, pre-computed result that JDBC, the Python SDK, and MCP clients can all read.'
# Diátaxis mode: how-to
---

A data product is a named query result. Aran computes it once and serves it
many times, or recomputes it on every read. You choose which when you create
it.

A product is the surface [MCP](../programmatic-access/mcp.md) exposes to an
AI agent. JDBC's `products` schema serves it too — as a real table, with
plain SQL.

## Save a query as a product

1. Open the **Workbench**.
2. Write and run the OQL query that defines the product. Confirm the
   result is what you want — this OQL becomes the product's definition.
3. Click **◈ save as data product**.
4. Name the product. Add a description if you want one.
5. Choose an **ACCESS MODE**: **Materialized** or **Virtual**.

Creation needs an **ACTIVE** schema version. With only a draft, it fails
with *The current schema version is `<status>`; a data product pins an
ACTIVE version — promote it first.* OQL that does not plan fails with
*Invalid OQL: `<message>`*.

## Choose the access mode

You make this choice once. No action converts one mode into the other
afterwards.

| | **Materialized** | **Virtual** |
| --- | --- | --- |
| Aran computes the rows | Once, when you refresh | On every read |
| A reader gets | The rows as of the last refresh | The rows the query yields now, from sources as fresh as each one is |
| A read costs | One scan of a stored file | One full run of the query |
| Refresh | You run it | There is none |

**Materialized** is the default, and the rest of this guide describes it.
For **Virtual**, read [Materialised and virtual data
products](../deep-dives/virtual-data-products.md) — the steps below do not
apply to one, because a virtual product needs no snapshot and offers no
refresh.

Saving records the schema version that was active when you saved it. Aran
uses that record for one thing: to mark the product `STALE` once a different
version is promoted. It is not the version a query runs against. Both modes
compute against whatever schema is active when they compute.

## Build the first snapshot

Saving a materialised product does not compute it. It starts `DRAFT`, with
no rows and nothing for a client to read.

1. Open **Products**.
2. Find your product in the list.
3. Click **⟳ refresh**.

The same button builds the first snapshot and every later one — the panel
tells you so directly: *"Not materialised yet — refresh to create the
first snapshot."*

Aran runs the product's OQL and writes the result to a parquet snapshot in
the lake. The product becomes `READY`, with a row count and a
`materializedAt` timestamp. For a **materialised** product, JDBC's
`products` schema and MCP's `read_product` tool both read this snapshot —
not a fresh run of the query. A virtual product has no snapshot, and both
surfaces recompute it on every read.

**A federated source is not an exception here.** If the product's OQL reads
a repository flagged `federated`, the refresh reads that source through the
same planner as any query: live, unless the dataset also has a lake copy
with an interval refresh that is fresh enough for the planner to serve — then
the refresh may read the copy instead. Aran then stores the result, like any
other refresh, and later reads come from that stored copy. Choose
**Virtual** for a product that recomputes on every read.

## Refresh it later

A schema change can leave a `READY` product `STALE` — the version recorded
at creation is no longer the active one. `STALE` is reported, not hidden: a
client still reads the old snapshot, and knows it is old.

Click **⟳ refresh** again to rebuild it. The rebuild runs the product's OQL
against the schema that is **active now**, not against the recorded version.
So a refresh does not restore the product's old meaning — it re-answers the
same question under today's schema.

Refreshing does not change the recorded version either. The product returns
to `READY`, and the next promotion marks it `STALE` again.

If the OQL no longer plans against the active schema, the refresh fails. The
product reads `FAILED` and carries the error, and its previous snapshot goes
on serving.

## Related

- [Materialised and virtual data products](../deep-dives/virtual-data-products.md)
  — the two access modes, in full.
- [Programmatic access](../programmatic-access/index.md) — the three ways
  to read a product from code.
- [MCP](../programmatic-access/mcp.md) — why an AI agent can only read a
  `READY` product.

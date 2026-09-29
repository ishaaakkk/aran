---
title: 'Materialised and virtual data products'
sidebar_label: 'Virtual data products'
description: 'A data product is served from a stored snapshot or recomputed on every read. What you choose at creation, what each mode guarantees, and what each one costs.'
# Diátaxis mode: explanation
---

A data product is a named query result. Aran serves it to a JDBC client, the
Python SDK and an AI agent over MCP, in one of two ways. That choice is the
product's **access mode**, and you make it once, when you create the product.

| | Materialised | Virtual |
| --- | --- | --- |
| Aran computes the rows | Once, when you refresh | On every read |
| A reader gets | The rows as of the last refresh | The rows the query yields now, from sources as fresh as each one is: a live source as it is now, an upload as of its last upload or refresh |
| A read costs | One scan of a stored file | One full run of the product's query |
| The product list shows | **Snapshot** | **Virtual** |

Materialised is the default and the older mode. Elsewhere on this site, "data
product" means a materialised one unless the page says otherwise.

## Choose the mode at creation

The **save as data product** dialog asks for it under **ACCESS MODE**, with
two options:

- **Materialized** — *computed once to a snapshot; as fresh as its last
  refresh*
- **Virtual** — *recomputed on every read from the sources the active
  schema points at — no stored snapshot, heavier per query, not served over
  Iceberg*

An agent's read of a virtual product takes the agent's own row limit, as a
materialised product's does (see [With an AI agent](#with-an-ai-agent)).

You cannot change the mode later, because no action or endpoint converts one
into the other. To change your mind, create a second product from the same
query. Calling `materialize` on a virtual product is a 400:

> A VIRTUAL product has no materialize step — it is already servable.

Both modes need an **ACTIVE** schema version. A draft is not enough, and
creation fails with:

> The current schema version is `<status>`; a data product pins an ACTIVE
> version — promote it first.

With no schema version at all, the message is *No schema version to pin —
promote an integrated schema first.*

Both record the version that was active at creation, and the product list
shows it under **defined on**. That record does not decide what a query reads;
see [When the schema changes](#when-the-schema-changes).

## A virtual product is ready at once

Creating a virtual product runs no query and writes nothing to the lake. Aran
plans the OQL to learn its columns and stops there. OQL that does not plan
fails the creation with `Invalid OQL: <message>`.

A virtual product therefore reaches `READY` on the click that creates it. Its
row in the product list shows:

- `READY` immediately. It is never `DRAFT`.
- **Virtual** in the **access** column.
- `—` under **rows**, because Aran stores no row count for a virtual product.
- **recomputed on read** under **data as of**, where a materialised product
  shows its last refresh time.
- No **⟳ refresh** button, because there is nothing to refresh.

A virtual product is never `STALE` either. `STALE` means "the stored rows are
older than the schema", and there are no stored rows.

## What a virtual read guarantees

A read runs the product's own OQL through the same pipeline a workbench query
takes. Two things follow, and they are the reason the mode exists.

The rows are current. No refresh step stands between a change in a source and
what the product returns.

An enforced denial constraint applies on the very next read. Enforce one on a
class the product reads, and the next statement against the product already
excludes the rows that fail it. Un-enforce it and they come back, with nothing
recomputed or promoted in between. A materialised product cannot do this: its
rows were fixed at its last refresh, so only a refresh applies the constraint.
See [Denial constraints in practice](../query-engine/denial-constraints.md).

A virtual read also follows how each class is served. A class read live from a
relational database is read live here too. See [Query a database
live](../how-to/query-a-database-live.md).

## What a virtual read costs

Every read runs the whole query. A materialised product answers from one file,
while a virtual product joins, filters and aggregates its sources again for
each caller. Aran does not weigh this trade-off for you.

Prefer materialised when the query is expensive, when the data changes slowly
or when many clients read the same product. Prefer virtual when staleness
costs you more than the query does.

## Over JDBC

Both modes appear in the `products` schema as ordinary tables. They differ in
what Aran does when your statement arrives.

| | Materialised | Virtual |
| --- | --- | --- |
| Aran runs | Your SQL over the product's stored file | The product's OQL, then your SQL over its result |
| `last_refreshed` in `products._products` | The refresh time | `NULL` |
| `access` in `products._products` | `MATERIALIZED` | `VIRTUAL` |
| The table's remarks carry | `access MATERIALIZED · last refreshed <time>` | `access VIRTUAL · always fresh` |

A `WHERE` clause, an aggregate and a join between two virtual products all
behave as they would over a stored table.

Column types are real in this schema for both modes. Aran records a
materialised product's columns when it writes the snapshot, and a virtual
product's columns when it plans the OQL at creation. See [Connect a JDBC
client](../programmatic-access/jdbc.md).

## With an AI agent

An agent reads a virtual product like any other: the sidecar lists it and
returns its rows. An enforced constraint reaches the agent's next read just as
it reaches a JDBC client's.

For a virtual product, the **⌁ agents** panel says:

> This product is virtual: it is recomputed on every call from the sources
> the active schema points at — live sources live, uploaded ones as of their
> last upload or refresh — so it is ready for agents now, with nothing to
> refresh. Its reads take the agent’s row limit like any other product’s. If
> a schema change breaks its query, agent reads fail with an error rather
> than returning stale rows.

The row limit works the same way in both modes. `read_product` clamps the
agent's limit to between 1 and 500 and passes it to Aran, which runs a virtual
product's query with that limit as its page size, inside the engine. An agent
that asks for 20 rows receives at most 20, and one that asks for 10,000
receives at most 500. See [Connect an MCP
client](../programmatic-access/mcp.md).

## When the schema changes

The two modes differ most here.

A materialised product keeps serving the rows of its last refresh, and a
schema change does not touch them. Aran marks the product `STALE` to say the
schema has moved on, and the product keeps answering from the same snapshot
until you refresh it.

A virtual product reads the schema that is active now. It stores no rows, so a
schema change reaches its next read. The product's detail panel says so in
place of the pinning note.

### What the pin does, and what it does not

Aran records the schema version that was active when the product was created.
Its one job is to give Aran something to compare against when a new version is
promoted, so it can decide whether to mark the product `STALE`. It is not the
version a query runs against, and nothing reads it at execution time. Two
consequences follow:

- A refresh recomputes against the schema that is active then, not the
  recorded one. Refreshing a `STALE` product does not reproduce its old
  meaning: you get the same OQL answered by today's schema.
- A refresh does not move the record. The product returns to `READY`, and the
  next promotion marks it `STALE` again.

### A change that breaks the query

A class the product reads may lose a property or be merged away. The OQL then
no longer plans, and the two modes report it at different moments.

A materialised product fails its next refresh. It reads `FAILED` and carries
the error. Its previous snapshot is untouched, and clients keep reading it
until somebody acts.

A virtual product fails the read itself. Aran answers with a message that
names the product, the property and the properties that do exist:

```
Virtual data product 'venues' cannot be queried: its stored OQL no longer
expands against the active schema (… Class 'Int_venue' (alias 't0') has no
property 'city'. Available: capacity, city_name, country, heldAt, name).
Recreate the product against the current schema.
```

The failure is deliberate, because there is no older answer to serve. It is
also isolated: Aran skips a broken product with a WARN when the connection
binds its views, so every other product on the same connection keeps serving,
and a statement that joins a working product to a broken one names only the
broken one.

## Known limitations

- A broken virtual product looks healthy until you read it.
  `products._products` still reports it `READY`, and nothing in the registry
  says its query has stopped planning.
- The recorded column list can drift. Aran records a virtual product's columns
  when you create it and does not re-derive them when the schema changes, so
  `getColumns` can name a column that `SELECT *` no longer returns and omit one
  that it does. A tool that builds its projection from `getColumns` then asks
  for a column that is not there. Recreate the product against the current
  schema.
- A preview queries the sources. Each preview of a virtual product runs its
  query live, so the detail panel runs one only when you press **run live
  preview**. It shows the first 100 rows.
- A product's query cannot name another product, in either mode. A product
  reads integrated classes only, so products cannot be layered or form a
  cycle. A product whose name starts with one of the engine's view prefixes
  (`int_`, `u_`, `src_`, `w_`) gets the table name `p_<name>`, because a
  virtual product's body reads those views on the same connection.

## Related

- [Create and materialise a data product](../how-to/create-a-data-product.md): the steps for both modes.
- [Denial constraints in practice](../query-engine/denial-constraints.md): what an enforced constraint does to a virtual read.
- [Connect a JDBC client](../programmatic-access/jdbc.md): the `products` schema, and the other two.
- [Connect an MCP client](../programmatic-access/mcp.md): what an agent sees.

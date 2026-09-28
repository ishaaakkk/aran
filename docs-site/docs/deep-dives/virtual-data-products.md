---
title: 'Materialised and virtual data products'
sidebar_label: 'Virtual data products'
description: 'A data product is served from a stored snapshot or recomputed on every read. What you choose at creation, what each mode guarantees, and what each one costs.'
# Diátaxis mode: explanation
---

A data product is a named query result. Aran serves it to a JDBC client, to
the Python SDK, and to an AI agent over MCP.

Aran serves one in two ways. The choice is the product's **access mode**.
You make it once, when you create the product.

| | Materialised | Virtual |
| --- | --- | --- |
| Aran computes the rows | Once, when you refresh | On every read |
| A reader gets | The rows as of the last refresh | The rows the query yields now, from sources as fresh as each one is — a live source as it is now, an upload as of its last upload or refresh |
| A read costs | One scan of a stored file | One full run of the product's query |
| The product list shows | **Snapshot** | **Virtual** |

Materialised is the default, and it is the older of the two. Everything
written about data products elsewhere on this site describes a materialised
one unless it says otherwise.

## Choose the mode at creation

The **save as data product** dialog asks for it under **ACCESS MODE**. It
offers two options, in these words:

- **Materialized** — *computed once to a snapshot; as fresh as its last
  refresh*
- **Virtual** — *recomputed on every read from the sources the active
  schema points at — no stored snapshot, heavier per query, not served over
  Iceberg, agent reads capped at 1,000 rows*

The mode cannot be changed later. There is no action, and no endpoint, that
converts one into the other. To change your mind, create a second product
from the same query. Calling `materialize` on a virtual product is a 400:

> A VIRTUAL product has no materialize step — it is already servable.

Both modes need an **ACTIVE** schema version. A draft is not enough —
creation fails with:

> The current schema version is `<status>`; a data product pins an ACTIVE
> version — promote it first.

Both record the version that was active at creation, and the product list
shows it under **defined on**. That record does not decide what a query reads —
see [When the schema changes](#when-the-schema-changes).

## A virtual product is ready at once

Creating a virtual product runs no query and writes nothing to the lake.
Aran plans the OQL to learn its columns, and stops there. OQL that does not
plan fails the creation with `Invalid OQL: <message>`.

So a virtual product reaches `READY` on the same click that creates it. Its
row in the product list reads:

- `READY`, immediately. It is never `DRAFT`.
- **Virtual**, in the **access** column.
- `—` under **rows**. Aran stores no row count for a virtual product.
- **recomputed on read** under **data as of**, where a materialised product
  shows its last refresh time.
- No **⟳ refresh** button. A virtual product has nothing to refresh, so
  Aran offers no control that says it does.

A virtual product is also never `STALE`. `STALE` means "the stored rows are
older than the schema", and there are no stored rows.

## What a virtual read guarantees

A read runs the product's own OQL through the same pipeline a workbench
query takes. Two things follow, and they are the reason the mode exists.

**The rows are current.** No refresh step stands between a change in a
source and what the product returns.

**An enforced denial constraint applies on the very next read.** Enforce one
on a class the product reads. The next statement against the product already
excludes the rows that fail it. Un-enforce it, and they come back. Nothing
has to be recomputed or promoted in between.

A materialised product cannot do this. Its rows were fixed at its last
refresh, so only a refresh applies the constraint. See [Denial constraints
in practice](../query-engine/denial-constraints.md).

A virtual read also honours what a class is served from. A class read live
from a relational database is read live here too. See [Query a database
live](../how-to/query-a-database-live.md).

## What a virtual read costs

Every read runs the whole query. A materialised product answers from one
file; a virtual product joins, filters and aggregates its sources again for
each caller. Aran does not price this choice for you.

Prefer materialised when the query is expensive, when the data changes
slowly, or when many clients read the same product. Prefer virtual when
staleness costs you more than the query does.

## Over JDBC

Both modes appear in the `products` schema, as ordinary tables. The
difference is what Aran does when your statement arrives.

| | Materialised | Virtual |
| --- | --- | --- |
| Aran runs | Your SQL over the product's stored file | The product's OQL, then your SQL over its result |
| `last_refreshed` in `products._products` | The refresh time | `NULL` |
| `access` in `products._products` | `MATERIALIZED` | `VIRTUAL` |
| The table's remarks carry | `access MATERIALIZED · last refreshed <time>` | `access VIRTUAL · always fresh` |

Your SQL is still your SQL. A `WHERE` clause, an aggregate, and a join
between two virtual products all behave as they would over a stored table.

Column types are real in this schema for both modes. Aran records a
materialised product's columns when it writes the snapshot, and a virtual
product's columns when it plans the OQL at creation. See [Connect a JDBC
client](../programmatic-access/jdbc.md).

## With an AI agent

An agent reads a virtual product like any other. The sidecar lists it and
returns its rows. An enforced constraint reaches the agent's next read too,
exactly as it reaches a JDBC client's.

The **⌁ agents** panel says so for a virtual product, in these words:

> This product is virtual: it is recomputed on every call from the sources
> the active schema points at — live sources live, uploaded ones as of their
> last upload or refresh — so it is ready for agents now, with nothing to
> refresh. Its reads ignore the agent's row limit and can return up to 1,000
> rows. If a schema change breaks its query, agent reads fail with an error
> rather than returning stale rows.

`read_product`'s row limit applies to a virtual product too. The MCP tool
clamps the caller's limit to 500, and the read runs the product's query
with that limit as its page size, inside the engine. An agent that asks for
20 rows receives at most 20. See [Connect an MCP
client](../programmatic-access/mcp.md).

## When the schema changes

This is where the two modes are furthest apart.

**A materialised product keeps serving the rows of its last refresh.** A
schema change does not touch them. Aran marks the product `STALE` to say the
schema has moved on, and the product goes on answering from the same
snapshot until you refresh it.

**A virtual product reads the schema that is active now.** It stores no rows
to keep serving. A schema change therefore reaches the product's next read.
The product's detail panel says so, in place of the pinning note.

### What the pin does, and what it does not

Aran records the schema version that was active when the product was
created. That record has exactly one job: it is what Aran compares against
when a new version is promoted, to decide whether to mark the product
`STALE`.

It is **not** the version a query runs against. Nothing reads it at
execution time. Two consequences:

- **A refresh recomputes against the schema that is active then**, not
  against the recorded one. So refreshing a `STALE` product does not
  reproduce its old meaning. It gives you the same OQL, answered by today's
  schema.
- **A refresh does not move the record.** The product returns to `READY`,
  and the next promotion marks it `STALE` again.

### A change that breaks the query

A class the product reads may lose a property, or be merged away. The OQL
then no longer plans, and the two modes report it at different moments.

**A materialised product fails its next refresh.** It reads `FAILED` and
carries the error. Its previous snapshot is untouched, and clients go on
reading it until somebody acts.

**A virtual product fails the read itself.** Aran answers with a message
that names the product, the property, and the properties that do exist:

```
Virtual data product 'venues' cannot be queried: its stored OQL no longer
expands against the active schema (… Class 'Int_venue' (alias 't0') has no
property 'city'. Available: capacity, city_name, country, heldAt, name).
Recreate the product against the current schema.
```

The failure is deliberate — there is no honest older answer to serve — and
it is isolated: a broken product is skipped with a WARN when the connection
binds its views, so every other product on the same connection keeps
serving, and a statement that joins a working product to a broken one names
only the broken one.

## Known limitations

- **A broken virtual product looks healthy until you read it.**
  `products._products` still reports it `READY`. Nothing in the registry
  says its query has stopped planning.
- **The recorded column list can drift.** Aran records a virtual product's
  columns when you create it. It does not re-derive them when the schema
  changes. So `getColumns` can name a column that `SELECT *` no longer
  returns, and omit one that it does. A tool that builds its projection from
  `getColumns` then asks for a column that is not there. Recreate the
  product against the current schema.
- **A preview queries the sources.** Each preview of a virtual product
  runs its query live, so the product's detail panel runs one only when you
  press **run live preview**. It shows the first 100 rows.
- **A product's query cannot name another product.** This holds for both
  modes. A product reads integrated classes only, so products cannot be
  layered — and cannot form a cycle. A product's name may not collide with
  the engine's view prefixes either, because a virtual product's body reads
  those views on the same connection.

## Related

- [Create and materialise a data product](../how-to/create-a-data-product.md) — the steps for both modes.
- [Denial constraints in practice](../query-engine/denial-constraints.md) — what an enforced constraint does to a virtual read.
- [Connect a JDBC client](../programmatic-access/jdbc.md) — the `products` schema, and the other two.
- [Connect an MCP client](../programmatic-access/mcp.md) — what an agent sees.

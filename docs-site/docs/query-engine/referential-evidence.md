---
title: 'Referential evidence and join removal'
sidebar_label: 'Referential evidence'
sidebar_position: 3
description: 'What Aran harvests from a PostgreSQL catalog, how much it trusts each kind of relationship, and the one case where it removes a join from a query.'
# Diátaxis mode: explanation
---

Two classes can be related in Aran for two different reasons: you accepted a
join alignment, or the source database declares a foreign key. Both let you
traverse the relationship in OQL. Only the second is proof. This page
describes what Aran collects, how far it trusts each kind, and the one
rewrite that trust pays for.

## What Aran harvests

Aran reads a relational repository's own catalog at three moments. You
register a dataset under it. You import a table from it. You refresh a
dataset in it. Each time, Aran collects three things:

| What | From | Used for |
| --- | --- | --- |
| Approximate row counts per table | The database's own statistics | Sizing a plan when FREYJA has no profile of the table |
| Approximate distinct-value counts per column | The same | Estimating filters and joins |
| Declared foreign keys | The information schema | The declared level below |

The harvest is best effort in the strict sense. It never fails a
registration, a refresh, or a query. An unreachable database costs one
warning in the server log and nothing else — `catalog harvest could not
connect to … — …`. So do wrong credentials. Aran sets a ten-second
**connection** timeout, so a blackholed host cannot hold a request open.
The timeout bounds only the connection: a database that connects and then
answers slowly is not bounded.

Aran keeps yesterday's good foreign keys when today's harvest fails. It
replaces stored foreign keys only when the foreign-key read itself
succeeded. A foreign key whose two ends cannot both be placed in the model
is dropped whole, never partially — the log says `Dropping a harvested
foreign key: … — can't place it`. A half-resolved edge would be a wrong
edge, and the rewrite below trusts declared edges without re-checking.

**PostgreSQL only.** No other engine supplies the catalog queries this
harvest needs. A MySQL, MariaDB, Oracle, SQL Server, or HBase repository
registers and queries normally. It simply contributes nothing here.

## The three levels of trust

Aran labels every relationship it knows with one of three levels.

**A hypothesis** is a join you accepted in the Alignments view. Accepting a
join declares that two columns line up. It does not prove that every value on
one side has a match on the other. Aran uses a hypothesis to estimate a
join's size, and for nothing else. **A hypothesis never removes anything from
a query.**

**A declared relationship** is a foreign key the source database itself
declares. Aran trusts it the moment the database states it. This level is
what pays for the rewrite below.

**A verified relationship** would be one Aran has checked against the actual
rows. Aran defines the level, and nothing produces one yet.

A harvested distinct-value count never counts as proof that a column is
unique. Only FREYJA's exact measurement over a real snapshot does, and only
for a single-column key. This matters, because the rewrite below needs that
proof.

## Removing a redundant join

Consider a query that traverses a relationship and then reads nothing from
the far side:

```
SELECT Int_order.total
FROM Int_order -[placedBy]-> Int_customer
```

The join contributes no column. It can still change the answer. It drops
every order whose customer reference is NULL. It repeats every order whose
customer matches more than one row. So removing it is safe only under
conditions:

1. The far side contributes no column above the join.
2. The relationship is **declared**, never a hypothesis.
3. The far side's referenced key is measured as exactly unique by FREYJA.
4. The join is an inner join on equality, with no extra condition.
5. Each side of the join is a bare scan of a single-source class.

When all five hold, Aran replaces the join with a filter — `fk IS NOT NULL`
on the referencing side. That filter drops exactly the rows the join would
have dropped. Condition 2 guarantees that every surviving row has a match.
Condition 3 guarantees that it has only one. The result is row for row
identical, and the query reads one table instead of two. Either orientation
of the join is tried: the planner also explores the commuted join, so which
side you wrote first does not matter.

Condition 5 is enforced by three independent mechanisms, and each blocks
the rewrite on its own:

- A declared edge is exposed to the planner only when the **referenced**
  class has a single source.
- A declared edge is exposed only when the **referencing** class has a
  single source too — a structural gate, deliberately not left to plan
  shape.
- The rule itself matches only a join of two bare table scans. A
  multi-source class plans as a union, so it never matches — and neither
  does a class whose wrapper is planned through as
  [Optimizable](./wrapper-optimisation.md), because expansion puts a
  projection over the `src_` scan rather than a bare scan. Planning through
  a wrapper and removing a join over it are mutually exclusive today.

You can watch this happen. Open the **explain** tab in the Workbench. A
removed join shows as a filter over one scan. The same query without the
rewrite shows a join over two scans.

## Known limitations

- **The harvest reads PostgreSQL and nothing else.** Every other engine
  contributes no row counts, no distinct-value counts, and no declared
  foreign keys.
- **A declared foreign key is trusted, not checked.** Aran does not read the
  rows to confirm the database enforces what it declares. A database whose
  data violates its own declared key would produce a wrong result from the
  rewrite. Checking is what the verified level is for, and nothing produces
  one yet.
- **The rewrite handles one shape.** Two classes, one join, one source each.
  A chain of joins, a class fed by several sources, or a class read through
  an Optimizable wrapper is left as written.
- **A live federated dataset cannot reach the rewrite today.** Its
  statistics are FREYJA's profiles taken over the live read at compile time,
  or harvested approximations. Neither is ever key evidence, so neither can
  establish the unique key condition 3 requires. A live table keeps changing
  after it is profiled.
- **Row counts and distinct-value counts from a catalog are estimates.** They
  are the database's own approximations. They are also only as fresh as that
  database's last statistics run.

## Related

- [Run join discovery](../how-to/run-join-discovery.md) — proposing a join from the data.
- [Align and integrate schemas](../how-to/align-and-integrate-schemas.md) — accepting one.
- [Typed columns and cost-based plans](./typed-columns-and-costing.md) — the rest of the cost model.
- [Query a database live](../how-to/query-a-database-live.md) — the access mode this harvest was written for.

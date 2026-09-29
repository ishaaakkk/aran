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

Aran reads a relational repository's own catalog when you register a dataset
under it, import a table from it, or refresh a dataset in it. Each time it
collects three things:

| What | From | Used for |
| --- | --- | --- |
| Approximate row counts per table | The database's own statistics | Sizing a plan when FREYJA has no profile of the table |
| Approximate distinct-value counts per column | The same | Estimating filters and joins |
| Declared foreign keys | The system catalog (`pg_constraint`) | The declared level below |

The harvest is best effort. It never fails a registration, a refresh or a
query. An unreachable database, or wrong credentials, costs one warning in the
server log and nothing else: `catalog harvest could not connect to … — …`. Aran
sets a ten-second **connection** timeout so a blackholed host cannot hold a
request open. The timeout bounds only the connection, not a database that
connects and then answers slowly.

Aran keeps yesterday's good foreign keys when today's harvest fails, and
replaces stored foreign keys only when the foreign-key read itself succeeded. A
foreign key whose two ends cannot both be placed in the model is dropped whole,
never partially, and the log says `Dropping a harvested foreign key: … — can't
place it`. A half-resolved edge would be a wrong edge, and the rewrite below
trusts declared edges without re-checking.

The harvest works for **PostgreSQL only**, because no other engine supplies the
catalog queries it needs. A MySQL, MariaDB, Oracle, SQL Server or HBase
repository registers and queries normally but contributes nothing here.

## The three levels of trust

Aran labels every relationship it knows with one of three levels.

**A hypothesis** is a join you accepted in the Alignments view. Accepting a
join declares that two columns line up, but does not prove that every value on
one side has a match on the other. Aran uses a hypothesis only to estimate a
join's size. **A hypothesis never removes anything from a query.**

**A declared relationship** is a foreign key the source database itself
declares. Aran trusts it as soon as the database states it, and this level is
what enables the rewrite below.

**A verified relationship** would be one Aran has checked against the actual
rows. The level is defined, but nothing produces one yet.

A harvested distinct-value count never counts as proof that a column is unique.
Only FREYJA's exact measurement over a real snapshot does, and only for a
single-column key. The rewrite below needs that proof.

## Removing a redundant join

Consider a query that traverses a relationship and then reads nothing from
the far side:

```
SELECT Int_order.total
FROM Int_order -[placedBy]-> Int_customer
```

The join contributes no column, but it can still change the answer: it drops
every order whose customer reference is NULL, and it repeats every order whose
customer matches more than one row. Removing it is therefore safe only under
these conditions:

1. The far side contributes no column above the join.
2. The relationship is **declared** (or verified) on exactly the join's key
   columns, never a hypothesis.
3. The far side's referenced key is measured as exactly unique by FREYJA.
4. The join is an inner join on equality, with no extra condition.
5. Each side of the join is a bare scan of a single-source class.

When all five hold, Aran replaces the join with a filter, `fk IS NOT NULL` on
the referencing side, which drops exactly the rows the join would have dropped.
Condition 2 guarantees that every surviving row has a match, and condition 3
guarantees that it has only one. The result is identical row for row, and the
query reads one table instead of two. The planner also explores the commuted
join, so it does not matter which side you wrote first.

Condition 5 is enforced by three independent mechanisms, and each blocks
the rewrite on its own:

- A declared edge is exposed to the planner only when the **referenced**
  class has a single source.
- A declared edge is exposed only when the **referencing** class has a
  single source too, a structural gate that is deliberately not left to plan
  shape.
- The rule itself matches only a join of two bare table scans. A
  multi-source class plans as a union and never matches, and neither
  does a class whose wrapper is planned through as
  [Optimizable](./wrapper-optimisation.md), because expansion puts a
  projection over the `src_` scan rather than a bare scan. Planning through
  a wrapper and removing a join over it are mutually exclusive today.

To see it, open the **explain** tab in the Workbench. A removed join shows as a
filter over one scan, while the same query without the rewrite shows a join
over two scans.

## Known limitations

- The harvest reads PostgreSQL only. Every other engine contributes no row
  counts, no distinct-value counts and no declared foreign keys.
- A declared foreign key is trusted, not checked. Aran does not read the rows
  to confirm the database enforces what it declares, so a database whose data
  violates its own declared key would produce a wrong result from the rewrite.
  The verified level exists for this check, and nothing produces one yet.
- The rewrite handles one shape: two classes, one join, one source each. A
  chain of joins, a class fed by several sources, or a class read through an
  Optimizable wrapper is left as written.
- A live federated dataset cannot reach the rewrite today. Its statistics are
  FREYJA's profiles taken over the live read at compile time, or harvested
  approximations. Neither is ever key evidence, so neither can establish the
  unique key that condition 3 requires, and a live table keeps changing after
  it is profiled.
- Row counts and distinct-value counts from a catalog are the database's own
  approximations, and are only as fresh as its last statistics run.

For which source types are tested against a live system, see the
[status page](../overview/status.md).

## Related

- [Run join discovery](../how-to/run-join-discovery.md): proposing a join from the data.
- [Align and integrate schemas](../how-to/align-and-integrate-schemas.md): accepting one.
- [Typed columns and cost-based plans](./typed-columns-and-costing.md): the rest of the cost model.
- [Query a database live](../how-to/query-a-database-live.md): the access mode this harvest was written for.

---
title: 'OQL — the Aran Query Language'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'What OQL is, how it differs from SQL over the integrated views, and how the three OQL sections fit together.'
# Diátaxis mode: explanation
---

OQL is the language you use to query integrated classes. It looks like a
restricted form of SQL, with one difference: a join traverses a **named
relationship**, not a condition on columns.

```
SELECT IPaper.title, IResearcher.name
FROM IPaper -[hasAuthor]-> IResearcher
WHERE IResearcher.affiliation = 'UPC'
```

## Why not just SQL

Integrated classes are views over sources. The join keys between them are
already declared in the schema graph, as join properties. Writing that join by
hand in SQL means you must know which columns to match. Those columns are
named with full property IRIs. They are hard to remember, and they can change.

`-[hasAuthor]->` names the relationship instead. The planner looks up the join
property. It takes the two class IRIs and the two key columns from the join
property, and builds the join. You do not restate what the model already
knows. The query keeps working even if the key columns change.

The second difference is scope. OQL has no table aliases and no variables —
`AS` renames an output column, nothing else. You refer to classes and
properties by their local names. This keeps queries short. It also keeps the
language small enough to check against the model.

## What it does not have

OQL is deliberately narrow. It has no subqueries, no `LIKE`, no `IN`, no
`BETWEEN`, no `CASE`, no `DISTINCT` inside an aggregate, no expressions in a
projection, and no parenthesised boolean grouping. When you need one of these,
use [SQL mode](./sql-fallback.md) — plain DuckDB SQL over the source tables
or the `int_` views.

This is not a workaround. It is the intended escape hatch. OQL covers the
queries the model can answer on its own terms. DuckDB covers the rest.

## The three parts of this section

| Page | Read it when |
| --- | --- |
| [Tutorial](./tutorial.md) | You have never written an OQL query. Builds up from a projection to a filtered join. |
| [Syntax reference](./syntax-reference.md) | You want to know exactly what the parser accepts. Written from the grammar, not from examples. |
| [SQL mode](./sql-fallback.md) | OQL cannot express your query, or you need the raw views. |
| [Cookbook](./cookbook.md) | You have a shape of question in mind and want a worked pattern. |
| [Cheat sheet](./cheat-sheet.md) | You know the language and want one page to glance at. |

## How a query runs

Briefly: the parser turns the query string into an AST. A lowering stage
rewrites the AST into alias form. The planner builds a Calcite `RelNode` tree
over a schema, using the mapping catalog. A cost-based planner then optimises
the plan, using profile statistics. The engine converts the plan to DuckDB SQL
and runs it against the `int_` views. Results are capped at 1000 rows, paged
with the `offset` operator parameter.

The full account, including the other operations the same `query-engine`
operator serves (`materialize-oql`, `verify-wrapper`, and the rest), is in
[The query pipeline](../deep-dives/query-pipeline.mdx).

## Where queries are written

You write queries in the **Workbench**, at the `/oql` route. The catalog panel
beside the editor lists the integrated classes that actually exist, with their
row counts. Read class names from there, not by guessing. An integrated class
takes its name from its **alignment label**. A class reached only through a
join is auto-named `Int_<sourceClass>`.

The same editor also runs plain SQL: switch the language toggle from OQL to
SQL. The catalog panel then lists the source tables a SQL statement can name.
See [SQL mode](./sql-fallback.md).

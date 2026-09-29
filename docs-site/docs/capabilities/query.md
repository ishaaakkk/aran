---
title: 'Query'
sidebar_label: 'Query'
sidebar_position: 4
description: 'OQL, the query language over the integrated schema, and how the engine plans and executes a query with Calcite and DuckDB.'
# Diátaxis mode: explanation
---

You query the integrated vocabulary, not the sources. One query can return rows
from a CSV file, a JSON file and a PostgreSQL table without naming any of them.

```
SELECT IPaper.title, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE Int_researchers.affiliation = 'UPC'
```

## OQL

OQL is a small SQL-like language. A join names a relationship, such as
`-[hasAuthor]->`, and Aran looks up the key columns from the integrated schema.
When OQL cannot express a query, [SQL mode](../oql/sql-fallback.md) runs plain
DuckDB SQL over the per-source tables or the integrated views.

## How a query runs

1. The parser turns the OQL text into a syntax tree.
2. The planner builds a Calcite relational plan over the integrated classes.
3. A cost-based optimiser reorders and simplifies the plan, using the column
   profiles for row counts and join selectivity. A planning budget bounds this
   step: past it, Aran runs the best plan found so far and says so.
4. Aran converts the plan to SQL and runs it on DuckDB, over lake snapshots or,
   for a source read live, over that source.

## What the optimiser can do

| Rewrite | When it applies |
| --- | --- |
| Push a filter through a wrapper | The wrapper is optimisable. See [Wrapper optimisation](../query-engine/wrapper-optimisation.md). |
| Remove a join | A single join between two single-source classes, where the query uses no column of the joined class, a foreign key declared by the source links the two, and a snapshot profile proves the referenced key unique. Aran replaces the join with a filter that keeps rows whose foreign key is not NULL. See [Referential evidence](../query-engine/referential-evidence.md). |
| Simplify a filter with a constraint | An enforced denial constraint already implies the filter, or contradicts it. A contradiction makes the plan provably empty, and Aran answers without reading any data. See [Denial constraints in practice](../query-engine/denial-constraints.md). |

## Next

- [OQL tutorial](../oql/tutorial.md) and [syntax reference](../oql/syntax-reference.md)
- [Query optimisation](../query-engine/index.md), including [federated execution and placement](../query-engine/federated-execution-and-placement.md)
- [Query pipeline in depth](../deep-dives/query-pipeline.mdx)
- [Export query results](../how-to/export-query-results.md)

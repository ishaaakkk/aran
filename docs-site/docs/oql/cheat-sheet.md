---
title: 'OQL cheat sheet'
sidebar_label: 'Cheat sheet'
sidebar_position: 5
description: 'The whole language on one scrollable page.'
# Diátaxis mode: reference
---

The whole language, one page. Detail in the
[syntax reference](./syntax-reference.md).

## Shape

```
SELECT [DISTINCT] projections
[FROM   Class (-[rel]-> Class)*]
[WHERE  filters]
[GROUP BY Class.prop, …]
[HAVING predicates]
[ORDER BY key [ASC|DESC], …]
[LIMIT  n]
```

Clause order is fixed. `FROM` is optional for a single class.

## SELECT

```
SELECT *                              -- every column of every class in FROM (requires FROM)
SELECT IPaper.*                       -- every column of one class
SELECT IPaper.title                   -- one property
SELECT IPaper.title AS t              -- renamed
SELECT DISTINCT IPaper.year           -- de-duplicate the whole result
SELECT COUNT(*)                       -- row count
SELECT COUNT(*) AS n
SELECT AVG(IResearcher.hIndex)        -- COUNT | SUM | AVG | MIN | MAX
```

`SUM(*)`, `AVG(*)`, `MIN(*)`, `MAX(*)` → error. Only `COUNT` takes `*`.

`SELECT *` without `FROM` → error (no class to infer). Across a join, its
output columns come back prefixed: `t0_title`, `t1_name`.

## FROM

```
FROM IPaper                                              -- explicit single class
FROM IPaper -[hasAuthor]-> IResearcher                   -- one hop
FROM IPaper -[hasAuthor]-> IResearcher -[worksAt]-> IOrg -- multi-hop
```

Rejected: `A -[p]-> A` (self-join), and any path visiting a class twice (cyclic).

Every class used in `SELECT`, `WHERE`, `GROUP BY`, `HAVING` or `ORDER BY` must be in the path.

## WHERE

```
WHERE IPaper.year > 2020
WHERE IPaper.year > 2020 AND IResearcher.country = 'Spain'
WHERE IPaper.year > 2020 OR IResearcher.hIndex >= 30
WHERE IPaper.venue IS NULL
WHERE IPaper.venue IS NOT NULL
```

- Operators: `=` `<>` `!=` `<` `<=` `>` `>=`
- `AND` binds tighter than `OR`
- **No parentheses.** `A AND B OR C` is `(A AND B) OR C`
- Left side is always `Class.prop`. Right side is always a literal

## GROUP BY / HAVING

```
GROUP BY IResearcher.affiliation
GROUP BY IResearcher.country, IResearcher.affiliation

HAVING COUNT(*) > 2
HAVING AVG(IResearcher.hIndex) >= 10
HAVING IResearcher.country = 'Spain'      -- on a group key
HAVING COUNT(*) > 2 AND MIN(IPaper.year) < 2020
```

`HAVING` takes `AND` only, not `OR`. An aggregate in `HAVING` must also be in `SELECT`, and a property in `HAVING` must be a `GROUP BY` key.

## ORDER BY / LIMIT

```
ORDER BY IPaper.year DESC
ORDER BY papers DESC             -- bare alias: any SELECT output, including an aggregate
ORDER BY IPaper.year, IPaper.title ASC
LIMIT 100
```

No `OFFSET`. Paging is the `offset` parameter on `run-oql`/`run`, at most
1000 rows per page. An `ORDER BY` key must be a column of the result.

## Literals

```
'text'          'O''Neill'          -- single quotes, doubled to escape
42              3.14                -- numbers
TRUE  FALSE                         -- case-insensitive; NULL only in IS [NOT] NULL
year > -1                           -- negatives only right after an operator
```

## Lexical

| | |
| --- | --- |
| Keywords | Case-insensitive, and keywords only where the grammar expects one (`Order.count` is a class and a property) |
| Identifiers | `[A-Za-z_][A-Za-z0-9_]*`, or double-quoted: `"first-name"`, `"price.usd"` (`""` escapes a quote) |
| Semicolons | Ignored anywhere |
| Whitespace | Insignificant, newlines included |

Keywords: `select from where and or as limit group order by having asc desc
distinct is not null true false count sum avg min max`. `x = NULL` is refused —
write `x IS NULL`.

## Not in OQL → use [SQL mode](./sql-fallback.md)

`COUNT(DISTINCT x)` · `CASE` · `LIKE` · `IN` · `BETWEEN` · subqueries · CTEs ·
arithmetic or functions in a projection · parenthesised boolean logic · `OR` in
`HAVING` · self-joins · cyclic paths · property-to-property comparison ·
`UNION`/`INTERSECT`/`EXCEPT` · `OFFSET`

## Gotchas

| | |
| --- | --- |
| Class names | Come from the **alignment label**. Join-only classes are named `Int_<sourceClass>`. Read them from the workbench catalog. |
| Row counts | A joined, unioned view has one row per pair — `COUNT(*)` counts pairs, not entities |
| Null columns | Normal in a union: a column only one source has is null for the others |
| Result cap | 1000 rows per page. `POST /api/query/export` returns the full result (it runs `materialize-oql`) |
| SQL columns | In raw SQL, `int_` columns are **full property IRIs** and need double quotes |

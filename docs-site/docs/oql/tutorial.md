---
title: 'Your first OQL query'
sidebar_label: 'Tutorial'
sidebar_position: 1
description: 'Write a projection, then a path join, then a filtered join, against the walkthrough''s integrated schema.'
# Diátaxis mode: tutorial
---

By the end of this page, you will have written six queries. Each one adds one
idea to the last. You will also know how to read a join out of the schema,
instead of guessing at it.

## Before you start

You need an integrated schema with something in it. The
[full walkthrough](../getting-started/walkthrough.md) builds exactly the one used
here: two paper sources unioned into `IPaper`, researchers in `Int_researchers`
(named after the source class, because only the join created it), and a
`hasAuthor` join between them.

Open the **Workbench** at the `/oql` route.

:::tip Read the class names off the catalog
The panel beside the editor lists the integrated classes that actually exist.
Class names come from **alignment labels**. A class reached only through a
join is auto-named `Int_<sourceClass>`. If yours are not `IPaper` and
`Int_researchers`, substitute what the catalog shows.
:::

## 1. Project some columns

Start with one class and two of its properties.

```
SELECT IPaper.title, IPaper.year
LIMIT 10
```

Run it. You get titles and years.

Notice what is **not** there: no `FROM`. With a single class, OQL infers it
from the first projection that names a class. A projection that names no
class cannot do that — `SELECT COUNT(*)` alone fails, while
`SELECT COUNT(*) FROM IPaper` works. Notice also that there is no table
called `IPaper` anywhere. `IPaper` is a view over two different files. You
did not need to know that.

`LIMIT` is worth keeping on while exploring. Results are capped at 1000 rows
regardless, but a small limit returns faster.

## 2. See everything

```
SELECT IPaper.*
```

No `LIMIT` this time. Without an `ORDER BY` the order of rows is not
defined, so the first thirty could all come from one source and hide the
very thing this step is about. The walkthrough's data is 84 rows.

Now the union is visible. Rows came from both the ACM CSV — 40 of them — and
the Semantic Scholar JSON — 44. Two columns are patchy: `venue` is null for every Semantic
Scholar row, and `citation_count` is null for every ACM row. Only one source
contributes each column.

That is the union working. A column that only one source has is null for rows
from the other source. The union keeps the row. It does not drop it.

## 3. Traverse a relationship

Papers know their authors' ids. The names live in another class. In SQL, you
would now need to know which columns match. In OQL, you name the
relationship:

```
SELECT IPaper.title, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
LIMIT 20
```

`-[hasAuthor]->` is a **join property** declared in the schema. It already
carries both classes and both key columns. So the planner builds the join
from it.

This is the first query that needs `FROM`. As soon as more than one class is
involved, you must spell out the path.

A paper with three authors comes back as three rows. That is a property of
the data, not a bug. You get one row per paper–author pair.

## 4. Filter

Add a `WHERE`. The filter can name either side of the join:

```
SELECT IPaper.title, IPaper.year, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE Int_researchers.affiliation = 'UPC'
```

Strings are single-quoted. Comparison operators are `=`, `<>` (or `!=`), `<`,
`<=`, `>`, `>=`.

Filter the other side instead:

```
SELECT IPaper.title, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE IPaper.year > 2023
```

Combine both filters. Remember that `AND` binds tighter than `OR`. Remember
also that **there are no parentheses**:

```
SELECT IPaper.title, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE IPaper.year > 2023 AND Int_researchers.country = 'Spain'
```

## 5. Count things

A property beside an aggregate must be in the `GROUP BY`:

```
SELECT Int_researchers.affiliation, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.affiliation
```

An aggregate on its own, with no `GROUP BY`, returns one grand-total row. See
the [syntax reference](./syntax-reference.md).

`COUNT`, `SUM`, `AVG`, `MIN`, and `MAX` are available. `COUNT(*)` counts rows.
The others take one property, for example `AVG(Int_researchers.h_index)`.
`MIN` and `MAX` also work over a text column — `MIN(IPaper.title)` returns
the alphabetically first title in each group.

Sort by the count. Because the aggregate's output column belongs to no class, you
order by its **bare alias**:

```
SELECT Int_researchers.affiliation, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.affiliation
ORDER BY paper_count DESC
```

## 6. Filter the groups

`WHERE` filters rows before grouping. `HAVING` filters the groups after
grouping:

```
SELECT Int_researchers.affiliation, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.affiliation
HAVING COUNT(*) > 2
```

Institutions whose researchers appear on more than two paper–author rows,
counted across both source databases.

:::warning `HAVING` takes `AND` only
`WHERE` accepts `OR` between `AND` groups. `HAVING` does not. `HAVING` joins
its predicates with `AND` only.
:::

## What you have learned

| Idea | Syntax |
| --- | --- |
| One class needs no `FROM` | `SELECT IPaper.title` |
| Everything from a class | `SELECT IPaper.*` |
| Join by relationship name | `FROM A -[rel]-> B` |
| Filter, on either side | `WHERE B.prop = 'x'` |
| Aggregate | `COUNT(*)`, `AVG(A.prop)`, with `GROUP BY` |
| Sort by an aggregate | `ORDER BY <alias> DESC` |
| Filter groups | `HAVING COUNT(*) > 2` |

## When OQL is not enough

You will hit a limit fairly quickly. `COUNT(DISTINCT …)`, `CASE`, `LIKE`,
subqueries, and parenthesised boolean logic are all outside the language. That
is by design. The way out is
[SQL mode](./sql-fallback.md). It queries the underlying views
directly.

## Next

- [Cookbook](./cookbook.md) — worked patterns for real questions.
- [Syntax reference](./syntax-reference.md) — everything the parser accepts.
- [Cheat sheet](./cheat-sheet.md) — one page to keep open.

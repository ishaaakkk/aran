---
title: 'OQL cookbook'
sidebar_label: 'Cookbook'
sidebar_position: 4
description: 'Worked query patterns in both OQL and SQL mode, each with a note on when to reach for it.'
# Diátaxis mode: how-to
---

Patterns for questions people actually ask. Each one comes with a note on why
it is shaped that way. The examples use the
[walkthrough's](../getting-started/walkthrough.md) schema: `IPaper` (a union of
two paper databases), `Int_researchers`, joined by `hasAuthor`.

In the SQL recipes, `<name>` stands for the full global property IRI of that
property. See [SQL mode](./sql-fallback.md#the-column-naming-rule) to
find it.

---

## Inspect what you have

### See the union

```
SELECT IPaper.*
```

**Why this pattern.** Before anything else, confirm the class really is a
union. A column that only one source contributes is null for rows from the
other source. If nothing is null, only one source feeds the class. Leave the
`LIMIT` off: without an `ORDER BY`, the first rows can all come from one
source, and a limit then hides exactly the union you came to see. On the
walkthrough's data this is 84 rows — ACM's 40 and Semantic Scholar's 44.

### Find the real column names

```
SELECT IPaper.*
LIMIT 1
```

**Why this pattern.** The headers are the local names in OQL. Run the same
query through the `run` operation instead, and the headers come back as full
IRIs. You need those full IRIs for any SQL recipe below.

---

## Counting

### Count per group

```
SELECT Int_researchers.affiliation, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.affiliation
ORDER BY paper_count DESC
LIMIT 20
```

**Why this pattern.** The canonical aggregate. Order by the **bare alias**. An
aggregate's output column belongs to no class, so `ORDER BY
Int_researchers.paper_count` is rejected.

### Count distinct — SQL only

```sql
SELECT p."<year>"                  AS publication_year,
       COUNT(DISTINCT p."<pid>")   AS papers
FROM   int_IPaper AS p
GROUP  BY p."<year>"
ORDER  BY publication_year
```

**Why this pattern.** The integrated view holds one row per paper–author pair.
So a plain `COUNT(*)` counts a three-author paper three times. `COUNT(DISTINCT
…)` fixes this, and OQL cannot express it: statement-level `DISTINCT` exists,
but distinct *inside* an aggregate does not.

### Keep only the significant groups

```
SELECT Int_researchers.affiliation, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.affiliation
HAVING COUNT(*) > 2
```

**Why this pattern.** `WHERE` filters rows before grouping. `HAVING` filters
groups after grouping. Note that `HAVING` accepts `AND` between predicates,
but never `OR`.

---

## Averages and ranges

### Average with a count beside it

```
SELECT Int_researchers.country,
       AVG(Int_researchers.h_index) AS avg_h_index,
       COUNT(*) AS researcher_count
FROM Int_researchers
GROUP BY Int_researchers.country
LIMIT 20
```

**Why this pattern.** An average without its group size is easy to misread. A
country with one researcher looks the same as a country with forty. Project
both.

### Rounded average — SQL only

```sql
SELECT r."<country>"                    AS country,
       ROUND(AVG(r."<h_index>"), 1)     AS avg_h_index,
       COUNT(*)                         AS researcher_count
FROM   int_Int_researchers AS r
GROUP  BY r."<country>"
ORDER  BY avg_h_index DESC
```

**Why this pattern.** OQL projections are properties and aggregates, nothing
else. There are no function calls, and no arithmetic. Any formatting needs
SQL.

---

## Filtering

### Filter across the join

```
SELECT IPaper.title, IPaper.year, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE Int_researchers.affiliation = 'UPC'
```

**Why this pattern.** The filter can name either side. The planner decides
where to apply it. You do not push it down by hand.

### Combine conditions

```
SELECT IPaper.title, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE IPaper.year > 2023 AND Int_researchers.h_index >= 15
```

**Why this pattern.** `AND` binds tighter than `OR`, and **there are no
parentheses**. `A AND B OR C` means `(A AND B) OR C`. If you need any other
grouping, write a SQL query instead.

### Rows a source did not contribute

```
SELECT IPaper.title, IPaper.year
FROM IPaper
WHERE IPaper.citation_count IS NOT NULL
```

**Why this pattern.** In a union, a column only one source has is null
elsewhere. So a null test works as a source filter. It is a proxy, not a real
provenance query: a genuinely missing value in the contributing source looks
the same.

### Pattern matching — SQL only

```sql
SELECT p."<title>" AS title
FROM   int_IPaper AS p
WHERE  p."<title>" LIKE '%Ontology%'
```

**Why this pattern.** OQL has no `LIKE`, `IN`, or `BETWEEN`.

---

## Cross-source questions

### Which institutions appear in *both* databases — SQL only

```sql
SELECT r."<affiliation>" AS institution,
       COUNT(DISTINCT CASE WHEN p."<pid>" LIKE 'ACM%' THEN p."<pid>" END) AS acm_papers,
       COUNT(DISTINCT CASE WHEN p."<pid>" LIKE '10.%' THEN p."<pid>" END) AS semantic_papers
FROM   int_IPaper          AS p
JOIN   int_Int_researchers AS r
       ON p."<authorId>" = r."<authorId>"
GROUP  BY r."<affiliation>"
HAVING acm_papers > 0 AND semantic_papers > 0
ORDER  BY acm_papers + semantic_papers DESC
```

**Why this pattern.** This is the flagship demonstration of integration. It
needs three things OQL lacks: `CASE`, `COUNT(DISTINCT …)`, and arithmetic in
`ORDER BY`. Note that the join is written by hand here. In SQL, you match the
key columns yourself. That is exactly the work `-[hasAuthor]->` saves you.

Identifying a source by an id prefix is a heuristic. It works because these
two sources use distinguishable id formats. Where they do not, add an
explicit source column in each wrapper instead.

### Most prolific, counted honestly — SQL only

```sql
SELECT r."<full_name>"               AS researcher,
       r."<affiliation>"             AS institution,
       COUNT(DISTINCT p."<pid>")     AS paper_count
FROM   int_IPaper          AS p
JOIN   int_Int_researchers AS r
       ON p."<authorId>" = r."<authorId>"
GROUP  BY r."<full_name>", r."<affiliation>"
ORDER  BY paper_count DESC
LIMIT  10
```

**Why this pattern.** The OQL version is one line shorter, but it counts
pairs, not papers. Whenever you count something in a joined, unioned view,
ask yourself whether you mean rows or distinct entities.

### Join a virtual product to a materialised one — SQL over JDBC

```sql
SELECT f.institution, f.papers, s.avg_h_index
FROM   products.fresh_counts   AS f   -- virtual: recomputed on this read
JOIN   products.yearly_summary AS s   -- materialised: as of its last refresh
       ON f.institution = s.institution
```

**Why this pattern.** Only SQL against the `products` schema can do this. A
virtual product's rows are computed live for this statement — an enforced
denial constraint on an underlying class applies on this very read — while
the materialised side answers from its stored snapshot. Column names here
are the product's own output names, not IRIs. Run it over
[JDBC](../programmatic-access/jdbc.md), whose `products` schema serves both
modes as ordinary tables.

---

## Multi-hop

### Two hops

```
SELECT IPaper.title, IInstitution.name
FROM IPaper -[hasAuthor]-> Int_researchers -[worksAt]-> IInstitution
LIMIT 20
```

**Why this pattern.** Each `-[…]->` is one join, chained left to right. Each
join needs a join property in the schema. Tests cover paths to three hops.

**A class may not appear twice in a path.** `A -[x]-> B -[y]-> A` is rejected
as a cyclic join. `A -[x]-> A` is rejected as a self-join. Both need SQL,
where you control the aliases.

---

## Getting results out

### More than 1000 rows

Both `run-oql` and `run` cap results at 1000 rows. For a full result, use
`materialize-oql` instead. It writes the whole result to a file:

```http
POST /api/operators/query-engine/invoke
{
  "operation": "materialize-oql",
  "parameters": { "oql": "SELECT IPaper.* FROM IPaper", "format": "PARQUET" }
}
```

**Why this pattern.** The cap exists so a broad `SELECT` cannot produce an
unbounded response payload. Materialisation moves the result out of the response
entirely. It takes **OQL, not SQL**. `format` is `PARQUET` by default;
`"format": "CSV"` writes CSV instead.

### Check the plan before running

```http
POST /api/operators/query-engine/invoke
{ "operation": "rewrite-oql", "parameters": { "oql": "…" } }
```

**Why this pattern.** `rewrite-oql` returns the generated SQL without
executing it. Use it to see which views a query touches. It is also a good
starting point when you rewrite a query into SQL by hand. Begin from what the
planner produced, not from a blank editor.

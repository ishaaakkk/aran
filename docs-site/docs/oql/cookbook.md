---
title: 'OQL cookbook'
sidebar_label: 'Cookbook'
sidebar_position: 4
description: 'Worked query patterns in both OQL and SQL mode, each with a note on when to reach for it.'
# Diátaxis mode: how-to
---

Each pattern answers a common question, in OQL where the language can express
it and in SQL mode where it cannot, and notes why it is shaped that way. The
examples use the [walkthrough's](../getting-started/walkthrough.md) schema:
`IPaper` (a union of two paper sources, the ACM CSV and the Semantic Scholar
JSON), `Int_researchers`, joined by `hasAuthor`.

In the SQL recipes, `<name>` stands for the full global property IRI of that
property. See [SQL mode](./sql-fallback.md#the-column-naming-rule) to
find it.

---

## Inspect what you have

### See the union

```
SELECT IPaper.*
```

**Why this pattern.** Confirm the class really is a union. A column that only
one source contributes is null for rows from the other source, so if nothing is
null, only one source feeds the class. Leave the `LIMIT` off: without an
`ORDER BY`, the first rows can all come from one source, and a limit then hides
the union you came to see. On the walkthrough's data this is 84 rows, ACM's 40
and Semantic Scholar's 44.

### Find the real column names

```
SELECT IPaper.*
LIMIT 1
```

**Why this pattern.** The workbench shows each column under its local name,
but for a `Class.*` query over one class the result's `headers` field carries
the full property IRIs (`headerNames` carries the local names). In SQL mode,
`SELECT * FROM int_IPaper LIMIT 1` shows the full IRIs as the headers. You need
those full IRIs for any SQL recipe below.

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

**Why this pattern.** The standard aggregate. Order by the **bare alias**: an
aggregate's output column belongs to no class, so `ORDER BY
Int_researchers.paper_count` is rejected.

### Count distinct (SQL only)

```sql
SELECT p."<year>"                  AS publication_year,
       COUNT(DISTINCT p."<pid>")   AS papers
FROM   int_IPaper AS p
GROUP  BY p."<year>"
ORDER  BY publication_year
```

**Why this pattern.** The integrated view holds one row per paper–author pair,
so a plain `COUNT(*)` counts a three-author paper three times. `COUNT(DISTINCT
…)` fixes this, but OQL cannot express it: statement-level `DISTINCT` exists,
while distinct *inside* an aggregate does not.

### Keep only the significant groups

```
SELECT Int_researchers.affiliation, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.affiliation
HAVING COUNT(*) > 2
```

**Why this pattern.** `WHERE` filters rows before grouping and `HAVING` filters
groups after it. `HAVING` accepts `AND` between predicates, never `OR`.

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

### Rounded average (SQL only)

```sql
SELECT r."<country>"                    AS country,
       ROUND(AVG(r."<h_index>"), 1)     AS avg_h_index,
       COUNT(*)                         AS researcher_count
FROM   int_Int_researchers AS r
GROUP  BY r."<country>"
ORDER  BY avg_h_index DESC
```

**Why this pattern.** OQL projections are properties and aggregates only, with
no function calls and no arithmetic. Any formatting needs SQL.

---

## Filtering

### Filter across the join

```
SELECT IPaper.title, IPaper.year, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE Int_researchers.affiliation = 'UPC'
```

**Why this pattern.** The filter can name either side. The planner decides
where to apply it, so you do not push it down by hand.

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
elsewhere, so a null test works as a source filter. It is a proxy, not a real
provenance query: a genuinely missing value in the contributing source looks
the same.

### Pattern matching (SQL only)

```sql
SELECT p."<title>" AS title
FROM   int_IPaper AS p
WHERE  p."<title>" LIKE '%Ontology%'
```

**Why this pattern.** OQL has no `LIKE`, `IN`, or `BETWEEN`.

---

## Cross-source questions

### Which institutions appear in *both* paper sources (SQL only)

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

**Why this pattern.** This query needs three things OQL lacks: `CASE`,
`COUNT(DISTINCT …)` and arithmetic in `ORDER BY`. The join is also written by
hand: in SQL you match the key columns yourself, which is the work
`-[hasAuthor]->` does for you.

Identifying a source by an id prefix is a heuristic. It works here because the
two sources use distinguishable id formats: ACM's `paper_id` values start with
`ACM` (`ACM001`), and Semantic Scholar's `doi` values start with `10.`
(`10.1145/SS001`), and the walkthrough aligns both under `pid`. Where the
formats overlap, add an explicit source column in each wrapper instead.

### Most prolific, counting distinct papers (SQL only)

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

**Why this pattern.** The OQL version is one line shorter but counts
pairs, not papers. Whenever you count in a joined, unioned view, decide whether
you mean rows or distinct entities.

### Join a virtual product to a materialised one (SQL over JDBC)

```sql
SELECT f.institution, f.papers, s.avg_h_index
FROM   products.fresh_counts   AS f   -- virtual: recomputed on this read
JOIN   products.yearly_summary AS s   -- materialised: as of its last refresh
       ON f.institution = s.institution
```

**Why this pattern.** Only SQL against the `products` schema can do this. A
virtual product's rows are computed live for this statement, so an enforced
denial constraint on an underlying class applies on this very read, while
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

**Why this pattern.** Each `-[…]->` is one join, chained left to right, and each
join needs a join property in the schema. Tests cover paths to three hops. The
walkthrough's schema has no `worksAt` join or `IInstitution` class, so this
example assumes a model that adds them.

**A class may not appear twice in a path.** `A -[x]-> B -[y]-> A` is rejected
as a cyclic join. `A -[x]-> A` is rejected as a self-join. Both need SQL,
where you control the aliases.

---

## Getting results out

### More than 1000 rows

Both `run-oql` and `run` return at most 1000 rows per page. For the full
result, export it. `POST /api/query/export` runs the query uncapped and streams
the file back:

```http
POST /api/query/export
Content-Type: application/json
Authorization: Bearer <token>

{ "oql": "SELECT IPaper.* FROM IPaper", "format": "PARQUET" }
```

**Why this pattern.** The cap stops a broad `SELECT` from producing an
unbounded response payload, and export moves the result out of the JSON
response into a file. It takes **OQL, not SQL**. `format` is `CSV` or
`PARQUET` (`CSV` when blank), and the `X-Aran-Row-Count` header holds the
true row count. The export runs the query-engine operator's `materialize-oql`
operation, which writes the whole result to a file on the server; invoking
that operation directly returns only the server-side path, so use the export
endpoint from a client. See [Export query results](../how-to/export-query-results.md).

### Check the plan before running

```http
POST /api/operators/query-engine/invoke
{ "operation": "rewrite-oql", "parameters": { "oql": "…" } }
```

**Why this pattern.** `rewrite-oql` returns the generated SQL without
executing it. Use it to see which views a query touches, or as the
starting point when you rewrite a query into SQL by hand.

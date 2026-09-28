---
title: 'SQL mode'
sidebar_label: 'SQL mode'
sidebar_position: 3
description: 'Querying the source tables and integrated views directly with DuckDB SQL, in the workbench or over the API.'
# Diátaxis mode: reference
---

OQL is deliberately narrow. When a query needs something the language does not
have, you can run raw DuckDB SQL instead — over the per-source tables (the
same tables the JDBC `sources` schema lists) or over the `int_` views.

Three ways to run it:

1. **The workbench.** Switch the editor's language toggle from OQL to SQL.
   The catalog panel then lists the source tables and their column names —
   the names your statement types.
2. **JDBC.** Use the `sources` schema — see
   [Aran over JDBC](../programmatic-access/jdbc.md).
3. **The API.** Invoke the query-engine operator directly, as below.

Prefer OQL for anything the integrated graph can answer: the point of Aran is
that it writes those joins for you. SQL mode is for checking what one source
contributed, debugging a wrapper, and the expressions OQL does not have.

## When to reach for it

| Need | Why OQL cannot |
| --- | --- |
| `COUNT(DISTINCT x)` | Statement-level `DISTINCT` exists; distinct inside an aggregate does not |
| `CASE WHEN …` | No conditional expressions |
| `LIKE`, `IN`, `BETWEEN` | Not in the grammar |
| Subqueries or CTEs | Not in the grammar |
| `ROUND(x, 1)`, arithmetic | No expressions in projections |
| Parenthesised boolean logic | `WHERE` is OR-of-ANDs only |
| `OR` in a `HAVING` | `HAVING` is AND-only |
| A self-join | Rejected at lowering |

## How to run it

```http
POST /api/operators/query-engine/invoke
Content-Type: application/json
Authorization: Bearer <token>

{
  "operation": "run",
  "parameters": {
    "sql": "SELECT COUNT(*) FROM int_IPaper",
    "offset": "0"
  }
}
```

The result comes back in the operator response's `data.result` field. It is a
JSON string holding headers and rows.

Related operations on the same operator:

| Operation | Use |
| --- | --- |
| `explain` | The `CREATE VIEW` DDL the engine would materialise — the fastest way to see the real view names and column names |
| `catalog` | The mapping catalog: classes, sources, column maps |
| `rewrite-oql` | The SQL that a given OQL query compiles to — a good starting point to edit by hand |

## The views you are querying

Six layers exist. You normally query a source table or `int_`.

| View | One per | Contents |
| --- | --- | --- |
| `src_<dataset>` | dataset | The raw snapshot |
| `w_<dataset>_<class>` | dataset × source class | The wrapper applied to it |
| `<source name>` (e.g. `acm`) | dataset × source class | The wrapper's columns under their own names — the table SQL mode and the JDBC `sources` schema show |
| `u_<dataset>_<class>` | dataset × source class | Projected onto the class's columns, denial constraints applied |
| `int_<class>` | integrated class | `UNION ALL` over the members |
| `q_<class>` | integrated class | The rows enforced denial constraints excluded — exists only while the class has an enforced constraint |

The source table is backed on `w_`, before constraints apply: it shows rows
that denial constraints quarantine from `int_`. That is what a per-source
debugging surface is for. `q_<class>` holds exactly the excluded rows —
except rows whose compared value is NULL, which appear in neither `int_`
nor `q_`.

Aran derives the other names from IRIs. It strips the namespace and replaces
every character outside `[A-Za-z0-9_]` with `_`. A class IRI ending in
`IPaper` becomes `int_IPaper`.

## The column-naming rule

Source tables use plain column names: `SELECT title FROM acm` works as
written. The rule below applies only when you query `int_` views directly.

**Columns in `int_` views use the full global property IRI as their name**, not
the local name. This is the single thing that trips people up.

```sql
-- Not this
SELECT title FROM int_IPaper

-- This
SELECT "http://www.essi.upc.edu/DTIM/NextiaDI/integration/title" FROM int_IPaper
```

Use double quotes: an IRI is not a bare identifier.

Two ways to find the real names, in order of convenience:

1. `SELECT * FROM int_IPaper LIMIT 1` and read the headers.
2. Call `explain` and read the generated DDL, or the Mappings surface in the UI.

The planner resolves the local names you write in OQL to these IRIs. In raw
SQL, nothing does that for you.

## What raw SQL is allowed to be

Every statement passes a read-only guard before it runs. This is not a
formality. It is a security boundary with its own tests.

| Rule | Rejection |
| --- | --- |
| Not blank | *SQL is empty.* |
| One statement — a single trailing `;` is tolerated, an internal one is not | *Only a single statement is allowed.* |
| Starts with `SELECT` or `WITH` | *Only read-only SELECT/WITH queries are allowed.* |
| No forbidden construct | *Forbidden construct — `<reason>`. Queries may only read the integrated views.* |

The forbidden-construct screen is the important one. The reason it exists is
worth stating. The queries run on an ephemeral, in-memory DuckDB connection.
So a *write* could not persist anyway. Writes were never the threat. **Reads
are the threat.** They come back in the response body:

```sql
SELECT * FROM read_csv_auto('/etc/passwd')      -- reads a server file
SELECT * FROM postgres_scan('…aran db…', …)     -- reads every tenant's rows
SELECT * FROM '/etc/passwd.csv'                 -- no function call at all
```

All three are perfectly read-only, and all three exfiltrate data the caller
must not see. The third is the one a function blocklist alone would miss:
DuckDB's replacement scan turns a string literal in `FROM` into a file read
with no function call anywhere. The screen is therefore structural as well
as name-based. This endpoint applies [the same screen the wrapper validator
uses](../how-to/write-wrapper-sql.md#what-is-allowed), which rejects:

- the function-name families `READ_*`, `*_SCAN`, `SCAN_*`, and `DUCKDB_*`;
- a list of named functions — `http_get`, `glob`, `attach`, `install`,
  `load`, `pragma`, `query`, `getenv`, and the rest of the list on the
  wrapper page;
- a string literal in table-reference position (the replacement-scan case
  above);
- any function call in table-reference position, except `UNNEST`,
  `GENERATE_SERIES`, and `RANGE`.

Everything else DuckDB supports is available: CTEs, window functions, `CASE`,
`DISTINCT` inside aggregates, `LIKE`, joins you write yourself. That promise
holds **here and not for wrapper SQL**: SQL mode runs your text through
DuckDB after the token screen, while a wrapper is parsed by Calcite, which
rejects DuckDB-only syntax. See
[Write wrapper SQL](../how-to/write-wrapper-sql.md#what-is-allowed).

## Worked example

The OQL version fails, because it needs `COUNT(DISTINCT …)`:

```
SELECT IPaper.year, COUNT(*) AS papers
FROM IPaper
GROUP BY IPaper.year
```

This counts one row per paper–author pair. So a three-author paper counts
three times. The correct count needs distinctness. That means SQL:

```sql
SELECT p."<year-iri>"                    AS publication_year,
       COUNT(DISTINCT p."<pid-iri>")     AS papers
FROM   int_IPaper AS p
GROUP  BY p."<year-iri>"
ORDER  BY publication_year
```

Replace `<year-iri>` and `<pid-iri>` with the real IRIs from `SELECT *`.

More patterns in the [cookbook](./cookbook.md).

## Limits and caveats

- **Results are capped at 1000 rows**, the same as OQL. Use `materialize-oql`
  for a full, uncapped result written to a file. Note that it takes OQL, not
  SQL.
- **Paging is the `offset` parameter**, not a SQL `OFFSET`.
- **Denial constraints are already applied** in the `u_` layer, so `int_` is
  filtered. Query `q_<class>` to see the rows a constraint excluded, or `w_`
  for the pre-constraint rows of one source.
- **No active schema, no views.** The operator returns `NO_SCHEMA` until you
  promote an integrated schema version.

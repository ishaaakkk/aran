---
title: 'OQL syntax reference'
sidebar_label: 'Syntax reference'
sidebar_position: 2
description: 'The full accepted grammar: SELECT, FROM with path joins, WHERE, GROUP BY, HAVING, ORDER BY, LIMIT, and the supported aggregates.'
# Diátaxis mode: reference
---

This page lists everything the OQL parser and lowering stage accept. It is
written from their code, not from example queries, so anything not listed here
is not accepted. Use it when a query fails to parse or when you need an exact
rule. For a first query, start with the [tutorial](./tutorial.md).

## Grammar

```
query      := SELECT [DISTINCT] projList
              [FROM pathExpr]
              [WHERE conj]
              [GROUP BY groupList]
              [HAVING havingConj]
              [ORDER BY orderList]
              [LIMIT integer]

projList   := projItem (',' projItem)*
projItem   := '*'
            | ClassName '.' '*'
            | ClassName '.' ident [AS alias]
            | COUNT '(' '*' ')' [AS alias]
            | aggFn '(' ClassName '.' ident ')' [AS alias]
aggFn      := COUNT | SUM | AVG | MIN | MAX

pathExpr   := ClassName ('-[' ident ']->' ClassName)*

conj       := andGroup (OR andGroup)*
andGroup   := filter (AND filter)*
filter     := ClassName '.' ident op literal
            | ClassName '.' ident IS [NOT] NULL
op         := '=' | '<>' | '!=' | '<' | '<=' | '>' | '>='

groupList  := ClassName '.' ident (',' ClassName '.' ident)*

havingConj := havingPred (AND havingPred)*
havingPred := COUNT '(' '*' ')' op literal
            | aggFn '(' ClassName '.' ident ')' op literal
            | ClassName '.' ident op literal

orderList  := orderKey (',' orderKey)*
orderKey   := (ClassName '.' ident | alias) [ASC | DESC]

literal    := string | number | TRUE | FALSE
ident      := word | '"' chars '"'        -- also ClassName and alias
```

## Clauses

### SELECT

Required. `DISTINCT` may follow it, applying to the whole result.

| Form | Meaning |
| --- | --- |
| `*` | Every column of every class in the `FROM` path |
| `Class.*` | Every column of that class |
| `Class.prop` | One property |
| `Class.prop AS alias` | One property, renamed |
| `COUNT(*)` | Row count |
| `COUNT(Class.prop)`, `SUM(…)`, `AVG(…)`, `MIN(…)`, `MAX(…)` | Aggregate over a property |

The parser rejects `SUM(*)`, `AVG(*)`, `MIN(*)` and `MAX(*)` with an
explicit error. Only `COUNT` takes `*`.

An aggregate without `GROUP BY` is a grand total: `SELECT AVG(IPaper.year)
FROM IPaper` answers one row over the whole result. Once any column is
aggregated, every other `SELECT` column must be a `GROUP BY` key. A column
that is neither aggregated nor grouped is refused, with or without
`GROUP BY`, rather than dropped from the result.

An alias, like a class or property name, may be spelled like a keyword:
`AS count` parses. See [Lexical rules](#lexical-rules).

`SELECT *` requires a `FROM`. Without one there is no class reference to
infer the class from, and the query fails with *SELECT list has no class
reference — cannot infer primary class*.

Across a join, `SELECT *` prefixes each output column with the class's
position in the path: `t0_title`, `t1_name`. Over a single class, `SELECT *`
and `Class.*` name each column by its property's full IRI
(`http://…/integration/title`). The workbench shows the plain local name
(`title`) as the header, but the full IRI is what the result's `headers`
carry and what a data product built from the query records as its column
names. Project properties by name (`Class.prop`, or `Class.prop AS alias`) for
short column names everywhere.

### FROM

Optional. Without it, OQL infers the class from the projections, which keeps a
single-class query short:

```
SELECT IPaper.title
LIMIT 10
```

With it, each `-[property]->` step is one join:

```
FROM IPaper -[hasAuthor]-> IResearcher -[worksAt]-> IInstitution
```

The name in brackets is a **join property** from the schema graph. The planner
takes both classes and both key columns from it. You never write the join
condition.

Multi-hop paths are supported. Tests cover them to three hops.

**The lowering stage rejects two shapes:**

| Rejected | Error |
| --- | --- |
| `A -[p]-> A` | *self-join is not supported* |
| A path that visits the same class twice | *cyclic joins are not supported* |

Both fail because a class is its own alias key, and a repeat would silently
overwrite the earlier hop.

Every class referenced in `SELECT`, `WHERE`, `GROUP BY`, `HAVING`, or
`ORDER BY` must appear in the `FROM` path. If it does not, the error names
the classes that are in scope.

### WHERE

Optional. A conjunction of comparisons, with `OR` between `AND` groups. **`AND`
binds tighter than `OR`**, as in SQL.

```
WHERE IPaper.year > 2020 AND IResearcher.country = 'Spain'
   OR IResearcher.hIndex >= 30
```

reads as `(year > 2020 AND country = 'Spain') OR (hIndex >= 30)`.

There is **no parenthesised grouping**. For any boolean structure other than
"OR of ANDs", use [SQL mode](./sql-fallback.md) instead.

Comparison operators: `=`, `<>`, `!=`, `<`, `<=`, `>`, `>=`. Both `<>` and `!=`
mean not-equal.

Null tests: `Class.prop IS NULL` and `Class.prop IS NOT NULL`. A comparison
with `NULL` (`= NULL`, `<> NULL`, `> NULL`) is never true, so it is refused
at parse time with a pointer to `IS [NOT] NULL` rather than returning an empty
result.

The left side is always `Class.prop`. A comparison between two properties, or a
literal on the left, does not parse.

### GROUP BY

Optional. A list of `Class.prop` keys. Aliases, positions and expressions are
not accepted.

### HAVING

Optional. `HAVING` joins its predicates with `AND` only. **`HAVING` does not
accept `OR`**, even though `WHERE` does.

The predicate forms are:

```
HAVING COUNT(*) > 2                  -- on an aggregate
HAVING AVG(IResearcher.hIndex) >= 10 -- on an aggregate over a property
HAVING IResearcher.country = 'Spain' -- on a group-by key
```

An aggregate in `HAVING` must also appear in the `SELECT` list, and a property
in `HAVING` must be a `GROUP BY` key. The planner refuses anything else, with
the errors listed under [Errors](#errors). The group-key form filters the same
rows as a `WHERE` on that key would.

`HAVING` has no null test: `IS NULL` does not parse there, and a comparison
with `NULL` is refused as in `WHERE`.

### ORDER BY

Optional. Each key is either `Class.prop` or a **bare alias**, with optional `ASC`
(the default) or `DESC`. Every key must name a column of the result, such as
a projected property or a `GROUP BY` key.

A bare alias names any `SELECT` output: an `AS` alias, or the local name of a
projected property. It is how you sort by an aggregate, because an aggregate's
output column belongs to no class:

```
SELECT IResearcher.affiliation, COUNT(*) AS papers
FROM IPaper -[hasAuthor]-> IResearcher
GROUP BY IResearcher.affiliation
ORDER BY papers DESC
```

### LIMIT

Optional. A single integer literal. There is **no `OFFSET` keyword**. Paging
is the `offset` parameter on the `run-oql` and `run` operations (default
`"0"`). A page holds at most 1000 rows. `run` always uses that page size, and
`run-oql` takes a `limit` parameter from 1 to 1000 (default 1000) for a smaller
page. The workbench's pager uses the `offset` parameter.

## Lexical rules

| Element | Rule |
| --- | --- |
| Keywords | Case-insensitive. `select`, `SELECT`, and `Select` are the same. A word is a keyword only where the grammar expects one, so a class called `Order` or a property called `count` is written bare. |
| Identifiers | A letter or `_`, then letters, digits, or `_`. Matched against catalog names, case-sensitively. |
| Quoted identifiers | Double quotes, for a local name outside that alphabet: `IPaper."first-name"`, `IPaper."price.usd"`, `IPaper."2024_total"`. Embed a quote by doubling it. A quoted word is never a keyword. |
| Strings | Single quotes only. Embed a quote by doubling it: `'O''Neill'`. Unterminated strings are a parse error. SQL forms like `E'…'`, `U&'…'`, and `$$…$$` are not OQL. |
| Numbers | Digits with an optional `.`. |
| Negative numbers | Only directly after a comparison operator: `year > -1` works. A leading minus elsewhere lexes as the start of a path arrow. |
| Booleans and null | `TRUE`, `FALSE`, case-insensitive. `NULL` appears only in `IS [NOT] NULL`. |
| Semicolons | The parser skips them wherever they appear. A trailing `;` is harmless. |
| Whitespace | Insignificant, including newlines. |

Keywords: `select`, `from`, `where`, `and`, `or`, `as`, `limit`, `group`,
`order`, `by`, `having`, `asc`, `desc`, `distinct`, `is`, `not`, `null`, `true`,
`false`, `count`, `sum`, `avg`, `min`, `max`. None is reserved as a name. Two
positions decide by looking ahead: `COUNT`, `SUM`, `AVG`, `MIN` and `MAX` are
aggregates only when `(` follows (`count.x` is a class called `count`), and
`DISTINCT` is the keyword unless `.` follows.

## Plan shapes

The planner builds these shapes, applying the stages in the order shown:

| Query kind | Shape |
| --- | --- |
| Non-aggregate | Scan → [Join] → [Filter] → Project → [Distinct] → [Sort] → [Limit] |
| Aggregate | Scan → [Join] → [Filter] → Aggregate → [HavingFilter] → [Sort] → [Limit] |

The cost-based optimiser can reorder this tree afterwards, for example by
pushing a filter below a join.

## Not in the language

Reach for [SQL mode](./sql-fallback.md) for any of these:

- Subqueries, in any position
- `LIKE`, `IN`, `BETWEEN`, `EXISTS`
- `CASE` expressions
- `COUNT(DISTINCT x)`: statement-level `DISTINCT` exists; distinct *inside* an aggregate does not
- Arithmetic or function calls in a projection (`ROUND(...)`, `a + b`)
- Parenthesised boolean grouping
- `OR` inside `HAVING`
- Self-joins and cyclic paths
- Comparing two properties to each other
- `UNION`, `INTERSECT`, `EXCEPT` between queries (the union *within* an
  integrated class is automatic and needs no syntax)
- `OFFSET`

## Errors

Parse and lowering errors:

| Message | Cause |
| --- | --- |
| *Expected 'SELECT' at position 0* | The query does not start with `SELECT`. |
| *Unexpected character 'x' at position n* | A character the lexer does not recognise. |
| *Unterminated string at position n* | A `'` with no closing quote. |
| *Unterminated quoted identifier at position n* / *Empty quoted identifier at position n* | A `"` with no closing quote, or `""` with nothing inside. |
| *Expected a class name at position n, got …* | A position that needs a name holds something else, such as a number, an operator or the end of the query. The same form names a property, join property or alias. |
| *'= NULL' at position n is never true …* | A comparison with `NULL`. The message says which of `IS NULL` / `IS NOT NULL` to write. |
| *Unknown operator '=>' at position n* | An operator the lexer tokenised but the grammar does not have. |
| *Only COUNT(\*) is allowed* | `SUM(*)` or similar. |
| *Expected literal at position n* | The right side of a comparison is not a literal. A double-quoted value is an identifier, so the message suggests single quotes. |
| *LIMIT must be an integer* | A non-integer after `LIMIT`. |
| *Expected BY after GROUP* / *Expected BY after ORDER* | `GROUP` or `ORDER` without `BY`. |
| *Expected NULL after IS* | `IS` or `IS NOT` followed by anything but `NULL`. |
| *self-join is not supported* | A path step whose two ends are the same class. |
| *cyclic joins are not supported* | A path visiting a class twice. |
| *class 'X' referenced in SELECT is not in FROM* | With the in-scope classes listed. The same form exists for `WHERE`, `GROUP BY` and `HAVING`; `ORDER BY` says *class 'X' in ORDER BY is not in FROM*. |
| *SELECT list has no class reference — cannot infer primary class* | No `FROM`, and no projection names a class, for example a bare `SELECT *` or `SELECT COUNT(*)`. |

Planner errors mean the query parsed but does not resolve against the model:

| Message | Cause |
| --- | --- |
| *Unknown class 'X'. Available: a, b, c* | The class name is not in the catalog. Read names off the workbench catalog panel. |
| *Class 'C' (alias 't0') has no property 'p'. Available: …* | The property name is not on that class. |
| *Class 'C' has two properties sharing local name 'p'.* | Two global properties collapse to one local name; the local name is ambiguous. |
| *Ambiguous class name 'X': …* / *Ambiguous join-property name 'p': …* | Two catalog entries share the local name, and the query names it. A query that never mentions the name is unaffected. |
| *Unknown join property 'p'. Available: …* | The name in `-[p]->` is not a join property. |
| *Join property 'p' connects A → B but 'C' is on neither side.* | The path step uses a join property between two other classes. |
| *Integrated class 'C' has no sources to scan.* | The class exists but no source feeds it. |
| *SELECT projected no columns.* | The projection resolved to nothing. |
| *SELECT C.p is neither aggregated nor in the GROUP BY list.* | A plain column (or `C.*`) beside an aggregate, and not a `GROUP BY` key. Aggregate it or group by it. |
| *HAVING needs an aggregate or GROUP BY — without one there are no groups to filter.* | A `HAVING` on a query with no aggregate and no `GROUP BY`. Filter rows with `WHERE`, or aggregate. |
| *HAVING references aggregate '…' that is not in the SELECT list.* | An aggregate in `HAVING` that the `SELECT` list does not project. Add it to `SELECT`. |
| *HAVING property 'p' is not in the GROUP BY list.* | A property in `HAVING` that is not a `GROUP BY` key. |
| *ORDER BY field 'x' not found in SELECT output — ensure it appears in SELECT* | An `ORDER BY` key that is not projected. |
| *ORDER BY 'n' does not name a SELECT output; available: [...]* | A bare alias in `ORDER BY` that matches no output column. |
| *Column 'C.p': literal '…' is not a valid BIGINT value.* | A literal that does not convert to the column's type, such as a quoted one or a number against a date or timestamp. The same form exists for DATE, TIMESTAMP, and BOOLEAN. See [Typed columns](../query-engine/typed-columns-and-costing.md#literals-convert-too). |

Positions are character offsets into the query string.

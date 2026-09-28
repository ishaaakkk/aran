---
title: 'Configure denial constraints'
sidebar_label: 'Configure denial constraints'
sidebar_position: 6
description: 'Declare the predicates a dataset''s rows must satisfy and see what the constraint-discovery operator infers.'
# Diátaxis mode: how-to
---

A denial constraint is a predicate that rows must satisfy. Aran excludes
rows that fail it from the integrated view.

## Write one by hand

1. Open the dataset.
2. Go to the **Constraints** tab.
3. Add the predicate. Refer to the class's own columns by their **local
   names**.
4. Save.

```
year >= 1990
year <= 2030
author_id IS NOT NULL
h_index >= 0
```

Each constraint is a boolean SQL expression over that class's columns. Aran
combines them with `AND`.

## Validate before saving

```http
POST /api/operators/query-engine/invoke
{
  "operation": "validate-constraint",
  "parameters": {
    "expression": "year >= 1990",
    "classIri": "<integrated class IRI>"
  }
}
```

This operation does not read your data. It builds a throwaway in-memory
view whose columns are NULL literals under the class's local names, and
`EXPLAIN`s the predicate over it. That catches malformed syntax and unknown
column names. It catches nothing about types: `year > 'abc'` on a numeric
column validates cleanly and then filters at query time.

If the class is not in the active catalog, the operation **succeeds** with
`validated: "false"` in the result — only the injection screen has run.
Check that field, not just the HTTP status.

## What an expression may contain

Aran screens constraints before it accepts them. This screen is a security
boundary, not a style check:

| Rejected | Message |
| --- | --- |
| An empty expression | *Constraint expression is empty.* |
| `;`, `--`, or `/*` | *Constraint may not contain ';', '--' or '/\*'.* |
| Any forbidden construct | *Forbidden construct — `<reason>`. A constraint may only reference the class's own columns.* |

The forbidden-construct screen is [the same one wrapper SQL
gets](./write-wrapper-sql.md#what-is-allowed): the function families
`READ_*`, `*_SCAN`, `SCAN_*`, `DUCKDB_*`, the named functions listed there,
a string literal used as a table reference, and a function call used as a
table reference.

The screen matters more than it looks. A constraint becomes part of a view
body. A well-formed predicate can read things it must not:

```sql
name <> (SELECT col FROM read_csv_auto('/etc/passwd') LIMIT 1)
```

That SQL is valid. It EXPLAINs cleanly. With a `postgres_scan` pointed at
Aran's own database, it leaks other tenants' rows one predicate at a time.
Only the function screen rejects it — the punctuation check alone is not
enough.

So a constraint may reference **its own class's columns and nothing else**.

## Ask Aran to look for constraints

Aran can search a class's rows for a rule that already holds.

1. Open the dataset's **Constraints** tab.
2. Pick the class.
3. Click **Discover constraints**.

Aran runs the search as a job over the rows the class's **wrapper**
produces, samples at most 10 000 rows, then reports what it found and keeps
that sentence on screen.

:::warning The search adds no constraints today
It states its findings over a **pair** of rows, and a constraint here is a
rule about **one** row. So Aran reports the finding and stores nothing. Read
[Denial constraints in
practice](../query-engine/denial-constraints.md#why-the-result-can-honestly-be-zero)
before you run it, so the result does not read as a failure.
:::

Over the API, the operator returns its raw findings to you and stores
nothing. To reproduce what the tab does, pass the class's wrapper SQL:

```http
POST /api/operators/constraint-discovery/submit
{
  "operation": "discover",
  "parameters": {
    "datasetId": "<uuid>",
    "classIri": "<source class IRI>",
    "wrapperSql": "SELECT paper_id, title, year FROM ${SNAPSHOT}",
    "aprox": "0.0001",
    "limit": "10000"
  }
}
```

| Parameter | Required | Default | Meaning |
| --- | --- | --- | --- |
| `datasetId` | Yes | | |
| `wrapperSql` | No | none | Discover over the wrapper's resolved output. **Without it, the search reads the raw snapshot** — not what a query sees. |
| `classIri` | No | none | Echoed on the result, so you can attribute a job to a class. |
| `aprox` | No | `0.0001` | Approximation threshold — how many violations a constraint may still have and be reported |
| `limit` | No | **none — all rows** | Row cap for sampling. The UI always passes 10 000; the operator itself is uncapped. |

`wrapperSql` passes the same screen as any wrapper; a screened construct
fails the call with `INVALID_WRAPPER_SQL` and *Forbidden construct in
wrapper SQL: `<reason>`*. The operator's other error codes are
`MISSING_PARAMETER`, `INVALID_PARAMETER`, `DATASET_NOT_FOUND`, and
`NO_SNAPSHOT`.

This operation is long-running, so use `/submit`. Discovery materialises
every sampled row in memory and its sampler is quadratic in the row count,
so **always set a `limit`** on direct API calls — the UI's 10 000 is a
sensible value. A class with fewer than two rows returns a clean zero: the
search needs a distinct pair of rows to state anything.

`aprox` is the dial that matters. At `0`, Aran reports only constraints with
zero violations in the sample. Loosening it surfaces near-constraints. This
is often the more interesting result: a rule that holds for 99.99% of rows
is either a real rule with dirty data behind it, or not a rule at all. Both
are worth knowing.

What comes back is a statement about a **pair** of rows, in the search
engine's own notation. Aran never enforces one. Read it as evidence, then
write the single-row rule yourself.

## Where they are enforced

A constraint belongs to an integrated **class**. Every source feeding that
class is filtered by it. You cannot give two sources of one class different
constraints.

Aran applies the same predicate twice: once inside each union member, the
`u_` views, and once above the union, in `int_`.

The second is not redundant. Selection distributes over union, so both give
the same rows. But the planner may read the named members directly, without
touching `int_`. If the predicate lived only above the union, such a plan
would silently stop enforcing it and start returning quarantined rows.

For the same reason, an enforced constraint stops the planner expanding the
class's wrapper, whatever its badge says — an expanded plan would read past
the `u_` view that carries the filter. Enforcing a constraint has a
performance cost on an
[Optimizable wrapper](../query-engine/wrapper-optimisation.md#optimizable-is-a-verdict-not-a-promise).

:::warning Constrain a column that every source of the class has
A source that does not map a column contributes NULL in it. A predicate over
that column is then UNKNOWN for every one of that source's rows, so all of
them leave the class — and none reaches the quarantine view. See [Denial
constraints in
practice](../query-engine/denial-constraints.md#null-fails-the-test).
:::

## See what a constraint excluded

The quarantine view `q_<class>` holds exactly the excluded rows:

```sql
SELECT * FROM q_IPaper LIMIT 100;
```

Run it through [SQL mode](../oql/sql-fallback.md). Use `explain` on the
`query-engine` operator to get the exact view names. Two caveats:

- `q_<class>` exists only while the class has at least one enforced
  constraint.
- A row whose compared value is NULL is in **neither** `int_` nor `q_`. To
  find those, compare `w_<dataset>_<class>` (before constraints) against
  the two.

## When a change takes effect

On the next query. Aran reads a class's enforced constraints each time it
answers one, so enforcing or un-enforcing takes effect immediately. You do
not have to recompile the model first.

The class does have to be in the active schema already. The engine forwards
only constraints whose class is in the active catalog; a constraint on a
class that is not there — never promoted, or since retired — is silently
skipped.

The planner's use of a constraint as evidence is best effort: a constraint
it cannot parse, or one holding a sub-query, is dropped from its reasoning
with a WARN while the views still enforce it. So `explain` output may not
reflect a constraint that is nonetheless filtering rows.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| *Forbidden construct — … A constraint may only reference the class's own columns.* | The expression uses a screened function or table reference. Reference only your own columns. |
| *Constraint may not contain ';', '--' or '/\*'* | Exactly that. |
| Validation passes, rows still appear | The class is not integrated and promoted yet — check `validated` in the result. Or the literal does not match the column's type; validation checks no types. |
| The search never finishes | A direct API call without `limit` reads all rows. Set one. |
| The search reports nothing | Either the data holds no rule, or `aprox` is too strict. Loosen it. |
| More rows vanished than expected | A constraint is stricter than intended, or it removed rows holding NULL. Read `q_<class>`, and see [Denial constraints in practice](../query-engine/denial-constraints.md#null-fails-the-test). |
| The **enforced** box is disabled | The constraint came from the search, or its class is no longer in the active schema. Both are explained beside the row. |

## Related

- [Denial constraints in practice](../query-engine/denial-constraints.md) — what the search finds, and what an enforced constraint does to rows.
- [Core concepts](../overview/core-concepts.md#views-src_-w_-u_-int_) — the view layers.
- [Write wrapper SQL](./write-wrapper-sql.md) — the same forbidden-construct screen.

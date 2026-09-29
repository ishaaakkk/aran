---
title: 'Typed columns and cost-based plans'
sidebar_label: 'Typed columns and costing'
sidebar_position: 1
description: 'Every integrated column carries a type, a value that will not convert becomes NULL instead of an error, and the planner prices each plan from measured column statistics.'
# Diátaxis mode: explanation
---

An integrated class collects rows from more than one source. Each column
therefore needs one type that every source can produce, and the planner needs
to know how large each source is before it chooses a plan. This page describes
both.

## Every integrated column has a type

Bootstrapping records a type for each column of each source. Aran derives the
integrated column's type from the types its sources recorded:

| Source records | Integrated column becomes |
| --- | --- |
| A whole number | A 64-bit integer |
| A fixed-point number | A decimal |
| A floating-point number | A double |
| A true/false value | A boolean |
| A date | A date |
| A date and time | A timestamp |
| Anything else, or nothing | Text |

A source that records no type for a column, including a column it does not
supply at all, contributes text, exactly like an unrecognised type. For this
reason a member of a multi-source class that does not supply every column is
never planned through as an
[Optimizable wrapper](./wrapper-optimisation.md): the class types the missing
column as text, and the member cannot produce it.

Where two sources of one class disagree about a column, Aran widens:

| The two sources say | The column becomes |
| --- | --- |
| The same type | That type |
| Integer and decimal | Decimal |
| Integer and double, or decimal and double | Double |
| Any other pair, including date and timestamp | Text |

Aran never fails a compile over a type disagreement. It falls back to text,
because every value can be read as text.

## A value that will not convert becomes NULL

Aran converts each source column into the integrated column's type, and the
conversion yields NULL for a value it cannot convert. One bad cell therefore
returns NULL in that row and column, while the query still returns every other
row and every other column of the same row.

The common case is a CSV holding `n/a` in a numeric column: that cell reads as
NULL and every other value in the column still reads as a number.

:::warning A NULL has two meanings
A NULL in a result means either "the source holds no value here" or "the
value did not convert". The result does not distinguish them. Open the
dataset's **Preview** tab to read the raw value when a NULL surprises you.
:::

### Literals convert too

Write a comparison against a typed column with the value quoted or unquoted.
Aran converts a quoted literal to the column's type:

```
WHERE IPaper.year = '1990'
```

A literal that cannot convert fails the query, so Aran never compares a
mistyped literal silently. The four messages:

```
Column 'C.p': literal '…' is not a valid BIGINT value.
Column 'C.p': literal '…' is not a valid DATE value (expected yyyy-MM-dd).
Column 'C.p': literal '…' is not a valid TIMESTAMP value (expected yyyy-MM-dd HH:mm:ss).
Column 'C.p': literal '…' is not a valid BOOLEAN value (expected true or false).
```

An unquoted number compared with a date or timestamp column fails with the
message above; quote the date instead. Against every other column the number
stays a number. Against a boolean column, `1` and `0` read as true and false.

Against a text column the number also stays a number, because a text column
can still hold numbers. Bootstrapping records every CSV column as text, yet a
CSV column of numbers is read as numbers, so `rating > 9.5` compares
numerically. A column that really holds text, such as one whose sources
disagree on a type, reads each cell as a number with its full value, so
`year = 2016` does not match `2016.4`. Such a column refuses `<`, `<=`, `>`
and `>=` against a number. A cell that is not a number, such as `n/a`, fails
the query. To compare that column as text, quote the value: `year = '2016'`.

## The planner prices plans from measured statistics

Aran profiles a dataset with FREYJA as soon as it bootstraps it. FREYJA
records each column's number of distinct values, its fraction of NULLs, and
whether its values are unique. The planner reads those numbers:

| The query says | The planner estimates from |
| --- | --- |
| `col = <literal>` | One divided by the column's distinct-value count |
| `col IS NULL` | The column's profiled NULL fraction |
| `col IS NOT NULL` | The complement of that fraction |
| A traversal of a join property | The distinct-value counts of the two columns it joins on |

A column FREYJA measured as unique becomes a **declared unique column**, which
sharpens every join estimate that reads it. At the integrated level, Aran
declares one only for a class fed by a **single** source, because two sources
can each contribute the same value and a union of them promises nothing about
uniqueness. The declaration mostly happens one level down, on the per-source
union members: each member is single-source by construction, so its measured
keys always count. This is why the planner registers the members as tables at
all.

Each source is priced against **its own** profile. A class fed by two sources
plans as a union of two scans, and Aran estimates each scan separately and adds
the two. One source's statistics never stand in for another's.

Profiling is best effort: it can fail, and it does not run when FREYJA is
absent. Without a profile the planner falls back to Calcite's fixed guesses:
an equality test keeps 15% of the rows, a range test 50%, `IS NOT NULL` 90%,
and any other predicate, `IS NULL` included, 25%. The plan is still correct, but Aran has no basis for preferring one plan
over another. Profile every dataset you intend to query. See [Bootstrap and profile a
dataset](../how-to/bootstrap-and-profile-a-dataset.md).

## An enforced constraint is evidence as well

An enforced denial constraint removes violating rows from the integrated
view. The planner reads that as a fact about the rows it will see as well as a
filter to apply. Two things follow:

- A query predicate the constraint already guarantees is **dropped**. The
  engine does not test `year >= 1990` again when the class enforces it.
- A query predicate the constraint **contradicts** collapses the plan to an
  empty result. Asking for `year < 1900` on a class that enforces
  `year >= 1990` returns no rows, and reads nothing.

Aran counts a constraint as evidence only while it is enforced. See
[Configure denial constraints](../how-to/configure-denial-constraints.md).

## Where to see the result

Open the **Workbench**, write a query, and open the **explain** tab. The plan
tree shows the shape Aran chose and the views it scans. Switch it to
**logical** for the plan as plain text, which carries the planner's row
estimate on every node. That is where you read the numbers this page
describes.

## Known limitations

- An unprofiled dataset gives the planner nothing, so it plans on constants.
- A profile describes one source, not an integrated class. Aran keeps them per
  source so that a large source cannot hide a small one.
- A live federated table's numbers age. While its wrapper is Opaque,
  FREYJA profiles it in place at every compile, over the live read. The
  numbers are exact as of that compile and drift as the table changes. Once
  its wrapper is Optimizable, it costs off the database's own catalog
  statistics instead (PostgreSQL only), which are approximations. Aran
  treats neither as proof that a column is unique. See [Query a database
  live](../how-to/query-a-database-live.md) and [Referential evidence and
  join removal](./referential-evidence.md).
- A type disagreement between sources yields text. Comparison and ordering
  then follow text rules, so `'100'` sorts before `'99'`.
- A fixed-point column is stored with 9 decimal places, and a source value with
  more fractional digits than that is rounded.

## Related

- [Bootstrap and profile a dataset](../how-to/bootstrap-and-profile-a-dataset.md): producing the statistics this page consumes.
- [The query pipeline](../deep-dives/query-pipeline.mdx): the stages the plan passes through.
- [Optimizable and opaque wrappers](./wrapper-optimisation.md): how much of a wrapper the planner can see into.

---
title: 'Denial constraints in practice'
sidebar_label: 'Denial constraints'
sidebar_position: 4
description: 'What constraint discovery finds today, why it adds nothing yet, what an enforced constraint does to your rows, and what happens to a constraint whose class is gone.'
# Diátaxis mode: explanation
---

A denial constraint is a predicate that a row must satisfy. Aran excludes
the rows that fail it.

You can write one by hand, and Aran can also search your data for candidates.
Only the first produces an enforceable constraint today; discovery currently adds
none, as explained below.

For the steps, see [Configure denial
constraints](../how-to/configure-denial-constraints.md).

## What Discover constraints does

The **Constraints** tab has one action per class, **Discover constraints**. It
runs as a job on the selected class.

From this tab, Aran runs the search over the rows the class's **wrapper**
produces, not over the raw file behind it, so the search sees what a query sees.
The tab also samples at most 10 000 rows, because uncapped the search
materialises every row in memory and its sampler is quadratic in the row count.

The `constraint-discovery` operator behaves differently when you invoke it
directly over the API: without a `wrapperSql` parameter it reads the **raw
snapshot**, and without a `limit` it reads **all rows**. See [the how-to's
API section](../how-to/configure-denial-constraints.md#ask-aran-to-look-for-constraints)
for the parameters that reproduce what the tab does.

Aran skips a class in these cases:

| Case | Why |
| --- | --- |
| The class has no snapshot | The search reads rows, and a live federated class stores none. |
| The class has no wrapper for this dataset | Nothing defines the rows to search. |
| The wrapper is **Optimizable** | Aran plans through such a wrapper rather than running it, so there is no wrapper output to sample. Aran does not fall back to the raw file. See [Optimizable and opaque wrappers](./wrapper-optimisation.md). |
| The wrapper calls a forbidden function | The same screen every wrapper gets. |
| The source is not part of any integrated class | A candidate stored against a source class could never be enforced, and would not appear in this tab. Aran logs a WARN naming the class. |

## Why the result can be zero

The search states what it finds over a **pair** of rows: "no two rows agree
on A and differ on B". A denial constraint in Aran is a predicate over **one**
row, and the first kind of statement cannot be reduced to the second.

:::warning Discovery adds no constraints today
Every dependency this search can state is a row-pair dependency, so discovery
persists nothing, for every dataset. Support for row-pair constraints is future
work. Treat discovery as evidence that a rule exists, and write the single-row
rule yourself.
:::

When the search finds row-pair structure, the tab keeps this sentence on
screen until you run the search again:

> Discovery found 2 dependencies that hold between PAIRS of rows, but none
> of them can be expressed as a constraint on a single row yet, so nothing
> was added. …

A run that finds nothing at all says so separately: *Discovery found no
candidates for this class.* A run that could not complete says:

> Discovery could not complete — `<reason>`. Any counts below are partial.

A zero from a broken run therefore never reads as a zero from a clean one. A
class with fewer than two rows returns a clean zero, because the search needs a
distinct pair to state anything.

## A discovered constraint is evidence, not a filter

Because discovery persists nothing, a new run adds no `discovered` row, but you
can still meet one that an earlier run stored. Such a row carries a violet
**discovered** chip, and its **enforced** checkbox is disabled with the reason
printed beside it:

> Discovered constraints are evidence only — enforcement requires
> inter-tuple semantics not yet implemented

The server refuses the same change with a 400, whose body states the whole
rationale:

> A discovered constraint is evidence only and cannot be enforced:
> constraint discovery states its result over a PAIR of rows, and
> enforcement can only apply a single-row filter, so what is stored here is
> the candidate collapsed to one row — not the constraint that was found.
> Enforcing it would filter nothing (or everything) and would silently drop
> NULL-bearing rows. Inter-tuple enforcement is not implemented; author an
> equivalent single-row constraint by hand to enforce one.

You can still turn such a row **off** or delete it, but you cannot turn it back
on. If you agree with a candidate, write the equivalent single-row predicate by
hand and enforce that.

## What an enforced constraint does to rows

Aran reads the enforced constraints of a class each time it answers a query and
filters the class's views with them, so enforcing one changes the next query's
answer. You do not have to recompile the model first, provided the class is
already in the active schema.

**The class stops returning the failing rows.** A constraint belongs to the
integrated class, not to one source, so every source feeding that class is
filtered by the same predicate. Aran applies it inside each source's own view
and again above the union of them. A plan that reads the sources separately
therefore enforces exactly what a plan that reads the union enforces.

**The failing rows are kept.** They move into the class's quarantine view,
`q_<class>`, so you can count them and look at them:

```sql
SELECT COUNT(*) FROM q_IPaper
```

`q_<class>` exists only while the class has at least one enforced
constraint. Rows whose compared value is NULL appear in neither `int_` nor
`q_` (see [NULL fails the test](#null-fails-the-test)). The per-source tables
that [SQL mode](../oql/sql-fallback.md) and the JDBC `sources` schema show are
built before constraints apply, so they still hold every failing row.

**The planner treats the constraint as a fact.** A query predicate the
constraint already guarantees is dropped. A query predicate it contradicts
returns no rows without reading anything. See [Typed columns and cost-based
plans](./typed-columns-and-costing.md#an-enforced-constraint-is-evidence-as-well).
This reasoning is best effort. A constraint the planner cannot parse, or one
holding a sub-query, is dropped from the planner's evidence with a WARN while
the views still enforce it, so the rows stay right and only the optimisation
is lost.

**The class's wrapper is no longer planned through.** Aran applies the
constraint inside each source's `u_` view, and an expanded wrapper plan would
read straight past it. Enforcing a constraint therefore forces every wrapper of
the class onto the opaque path, whatever its badge says. See
[Optimizable and opaque
wrappers](./wrapper-optimisation.md#optimizable-is-a-verdict-not-a-promise).

### One product mode feels it, and one does not

A [virtual data product](../deep-dives/virtual-data-products.md) runs its
query on every read, so it follows the rule above: enforcing a constraint
changes what that product returns on the very next statement, and un-enforcing
it brings the rows back the same way. A materialised product does not follow:
its rows were fixed at its last refresh, so the constraint reaches it only when
you refresh it.

### NULL fails the test

SQL compares a NULL to nothing, so a row whose compared value is NULL neither
passes nor fails a predicate: the answer is UNKNOWN.

Aran treats UNKNOWN as "not passing". Such a row leaves the class and does
**not** appear in the quarantine view either, so it is gone from both.

This catches people out with a constraint like `country <> 'ZZZ'` on a column
that holds NULLs: every NULL row disappears. Write `country IS NULL OR
country <> 'ZZZ'` when you mean to keep them.

:::warning Constrain a column that every source of the class has
Two sources of one class can map different properties. A source that does
not map a column contributes NULL in it, in every row.

If you enforce a constraint on that column, the NULL rule applies to that whole
source: **all** of its rows leave the class, and none of them reaches the
quarantine view. The other source is filtered normally, so the class quietly
loses one source's data.

The constraint editor lists every column of the class, whichever source has it,
so nothing stops you writing such a constraint. Check which sources map the
column before you enforce it.
:::

## A constraint whose class is gone

An alignment can merge an integrated class into a new one, and a rollback can
retire one. The constraints you wrote on the old class stay in the model, but
the query engine skips them because there is no class left to filter: each
query forwards only the constraints whose class is in the active catalog.

Aran calls these **orphaned**. The **Constraints** tab lists them above
everything else, under a count (*3 orphaned constraints*) and this line:

> not enforced — class no longer in the active schema. These were left
> behind when their integrated class was merged away or retired; the query
> engine skips them, so they filter nothing.

Each orphan shows its expression and its old class. Its **enforced**
checkbox is disabled, because the guarantee no longer holds. Delete it, or
write the constraint again on the class that survived.

The API agrees with the screen: an orphaned constraint is reported as not
enforced, whatever it was set to before. A model that has never been promoted
has no active classes, so nothing is marked orphaned there.

Aran does not move a constraint to the surviving class, because which class
should inherit it is your decision.

## Known limitations

- **Discovery cannot read a live federated class**, and does not read a
  class whose wrapper is Optimizable.
- **A constraint filters answers and never touches a source.** The rows that
  fail it stay in the source database or file; Aran only refuses to return them.
- **A constraint may reference its own class's columns only.** This is a
  security boundary. See [Configure denial
  constraints](../how-to/configure-denial-constraints.md#what-an-expression-may-contain).
- **A query cannot compare two columns.** OQL's `WHERE` takes a column, an
  operator and a literal, so a rule about two columns can be enforced as a
  constraint but not written as a query.

## Related

- [Configure denial constraints](../how-to/configure-denial-constraints.md): writing, validating, and enforcing one.
- [Typed columns and cost-based plans](./typed-columns-and-costing.md): how the planner uses an enforced constraint.
- [Optimizable and opaque wrappers](./wrapper-optimisation.md): why an enforced constraint stops Aran planning through a wrapper.
- [Materialised and virtual data products](../deep-dives/virtual-data-products.md): which products feel a constraint change at once.

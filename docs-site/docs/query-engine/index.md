---
title: 'Query optimisation'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'What the optimiser decides, the evidence it decides from, and where each decision is documented.'
# Diátaxis mode: explanation
---

Aran's optimiser turns an OQL query into a plan, then picks the cheapest plan it
can prove correct. This section covers what it decides and what evidence it uses.
For the syntax of a query, see [OQL](../oql/index.md).

## What it decides

| Decision | Made from | Page |
| --- | --- | --- |
| Join order and row-count estimates | Column profiles: distinct values, null fraction, row counts | [Statistics and costing](./typed-columns-and-costing.md) |
| Whether a wrapper's SQL can be looked inside, so predicates reach the source | A round-trip check of the wrapper against its snapshot | [Wrapper optimisation](./wrapper-optimisation.md) |
| Whether a join can be removed | Declared foreign keys and verified unique keys | [Referential evidence](./referential-evidence.md) |
| Whether a predicate is redundant or the result is provably empty | Enforced denial constraints | [Denial constraints](./denial-constraints.md) |
| Whether a table is read from its snapshot or from the live source | Freshness of the copy and the rows the query keeps | [Federated execution and placement](./federated-execution-and-placement.md) |

## The evidence

The optimiser never guesses when a measurement exists. For each source of a class
it takes the first evidence available, in this order:

1. For a class whose wrapper is opaque, the profile of the wrapper's own output.
2. For an opaque class of a federated dataset with no snapshot, the live profile
   of the source.
3. The profile of the source snapshot, with statistics harvested from a
   PostgreSQL catalogue filling in any column the snapshot profile lacks.

A class whose wrapper is optimisable always costs off its snapshot profile, because
the optimiser plans over the snapshot directly. Only exact profiles of a snapshot or
of a wrapper's output count as proof that a column is a key. Harvested and
live-profiled numbers are estimates: they feed row counts and distinct values, never
key detection.

## Planning is bounded

Planning cost grows with join depth and the number of sources behind each class.
Two guards stop it:

| Setting | Default | What happens when it trips |
| --- | --- | --- |
| `ARAN_PLANNER_PRODUCTION_BUDGET` | `50000` rule productions (`0` disables the count) | The optimiser serves the cheapest plan found so far. |
| `ARAN_PLANNER_WALL_CLOCK_GUARD` | `20s` | The same, but the plan now depends on how fast the machine is. |

The production budget is the primary guard because a count gives the same plan on
every machine. If no complete plan exists when a guard trips, Aran plans the query
structurally instead: predicates are placed and projections cleaned up, but joins
are not reordered or costed. Either way the result is correct.

The `rewrite-oql` and `run-oql` responses carry a `planning` object with the number
of productions, the budget, the elapsed time, which guard `tripped`, and a
`degradation` of `NONE`, `BEST_SO_FAR` or `STRUCTURAL_FALLBACK`. When it is not
`NONE`, the plan view shows a "planning cut short" notice.

## How to see the result

The plan view in the query workbench shows the optimised plan. Each scan carries an
access-mode chip: `MATERIALIZED` for a snapshot, `FEDERATED` for the live source, or
`MIXED` for an integrated class whose sources are read both ways. For the full path
from text to rows, see the [query pipeline](../deep-dives/query-pipeline.mdx).

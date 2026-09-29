---
title: 'Federated execution and placement'
sidebar_label: 'Federated execution and placement'
sidebar_position: 5
description: 'How Aran reads a source live, when it chooses the live source over a snapshot, and how it profiles a source it has no copy of.'
# Diátaxis mode: explanation
---

A federated source is read where it lives. Aran does not copy it into the lake
first. This page covers how a live read works, how Aran chooses between a live read
and a snapshot for a table that has both, and how it gets statistics for a source it
has never copied. To set one up, follow [Query a database live](../how-to/query-a-database-live.md).

## How a live read works

For PostgreSQL, MySQL and MariaDB, DuckDB attaches the source database read-only and
reads its tables in place. For an Iceberg table, Aran asks the catalogue for the
table's current metadata location at query time and reads it with `iceberg_scan`.
DuckDB's source extensions push filters and column selection into the source where
they can, so a selective query moves fewer rows. For MySQL and MariaDB, aggregate
pushdown is switched off, because on the pinned DuckDB version it breaks aggregates
over a view.

Credentials live on the DuckDB connection as a temporary secret. They never appear in a
plan, in a view definition or in the query text. The attach alias is reserved, and wrapper
SQL that names it is refused, because an attached catalogue exposes every table the account can see.

## Snapshot or live: the placement decision

A table can be **dual-access**: it was imported into the lake from a PostgreSQL, MySQL
or MariaDB repository that is also marked federated, so it has a snapshot and a live
source. For such a table Aran decides per query which one to read. Tables with only one path
are not candidates. Copies of Iceberg and object-storage tables are not candidates
either: once copied, they are always read from the copy.

The decision has two steps, and the order matters.

**1. Freshness decides what is allowed.** A snapshot may serve the query only if it is
fresh enough. A dataset with an `Every(interval)` refresh policy stays eligible until
its last successful refresh is older than `stale-grace-factor × interval`
(twice the interval by default), which tolerates one missed refresh and not a broken
one. A dataset with a manual refresh, no policy, a stale copy, or no successful
refresh is not eligible, and the query reads live. Setting an `Every` policy is how you
opt a copy into placement. A one-off import from a federated repository keeps reading live.

**2. Cost decides between what is allowed.** After the optimiser has pushed predicates
onto each leaf, Aran estimates the rows each option moves, summed over every scan of
the dataset in the plan:

```
cost(live)     = live-lambda  × rows the leaf returns after pushed predicates
cost(snapshot) = local-lambda × all rows in the snapshot
```

The live read wins only if it is strictly cheaper. A tie goes to the snapshot. With the
default weights of 25 and 1, a live read must keep fewer than 1 row in 25 to beat a local
scan. A selective filter on a large table goes live. An unfiltered scan reads the snapshot.

Aran makes the decision after optimisation, not inside the plan search. The two options return
identical rows and produce identical SQL, so offering them to the optimiser as alternatives
would enlarge the search without changing the plan.

When the snapshot wins, Aran reads the dataset's own latest copy, which is the copy the
freshness check measured, even if the active schema was built from an older one. If that
copy cannot be read, for example because the lake is down, the query falls back to the
live source. If the placement decision itself fails, every dataset keeps its default
access mode. Placement can make a plan slower, but it cannot make a query fail.

### Seeing and tuning it

The `rewrite-oql` and `run-oql` responses carry a `placement` list when the plan has at
least one dual-access dataset. Each entry has `datasetId`, `chosen` (`MATERIALIZED` or
`FEDERATED`), `liveCost`, `snapshotCost`, `effectiveRows`, `fullRows` and a `reason`,
which starts `snapshot leaf illegal:`, `live wins on cost:` or `snapshot wins on cost:`. The plan view marks
each scan with the access mode that ran.

| Setting | Default | Meaning |
| --- | --- | --- |
| `ARAN_PLACEMENT_ENABLED` | `true` | Turn per-query placement on or off. Off, every dataset keeps its default access mode |
| `ARAN_PLACEMENT_LIVE_LAMBDA` | `25.0` | Cost per row moved from the live source |
| `ARAN_PLACEMENT_LOCAL_LAMBDA` | `1.0` | Cost per row scanned from the snapshot |
| `ARAN_PLACEMENT_STALE_GRACE_FACTOR` | `2.0` | How many intervals old a copy may be |

The two weights are estimates, not measurements. A loopback benchmark measured the live
read at about 1.9 times a local scan at 100 000 rows, which is a lower bound because it
excludes real network latency. The same run measured a fixed setup cost of a few hundred
milliseconds per live query, which a per-row weight cannot express. Tune the weights against
your own network: `make perf-federated` (needs Docker) runs the same comparison against a
PostgreSQL container and writes `aran-operators/query-engine/build/perf/federated-report.md`. The
[status page](../overview/status.md) says how far this has been verified.

Placement applies to OQL over the integrated schema: workbench queries, exports, the JDBC
`graph` schema and the build of a materialised product. Raw SQL over the `sources` or
`products` schemas, and reads of virtual products, keep each dataset's default access mode.

## Statistics for a source with no snapshot

A snapshot lets FREYJA profile a column exactly. A live source has none, so Aran gets
statistics in two other ways.

**Harvested statistics (PostgreSQL only).** At enrolment and on every refresh, Aran reads
`pg_class.reltuples`, `pg_stats.n_distinct` and the declared foreign keys from the source's
catalogue. These give real row counts and distinct-value estimates instead of a fixed guess,
and the foreign keys feed [join elimination](./referential-evidence.md).
Harvest is best effort and has a 10-second connect timeout: a failure is logged and never
blocks enrolment or refresh. MySQL, MariaDB and the other engines have no harvest.

**Live profiling.** When Aran compiles an integration, it profiles each opaque wrapper class of a
federated dataset that has no snapshot. It sends FREYJA the scan expression and its setup
statements (`federated_scan` in the request), and FREYJA runs the whole
profiling suite in a local DuckDB bound to the source, so the source does the scanning and only aggregates
come back. Nothing is copied into the lake, whatever the size of the source. The
numbers are exact for the full relation and are stored as live-profile evidence.

Both kinds of evidence are estimates for costing. Neither counts as proof that a column is a
key, because a live source can change after it is measured. The costs are real: each compile scans
the source once per profiled class, and the FREYJA process connects to the source directly.
A class whose wrapper verifies as optimisable is not profiled this way. Federated
object-storage classes are skipped, because no path yet enrols them live.

## Related

- [Query a database live](../how-to/query-a-database-live.md)
- [Statistics and costing](./typed-columns-and-costing.md)
- [Referential evidence](./referential-evidence.md)
- [Configuration reference](../administration/configuration-reference.md)

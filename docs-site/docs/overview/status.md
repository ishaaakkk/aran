---
title: 'Status and limitations'
sidebar_label: 'Status and limitations'
sidebar_position: 4
description: 'What Aran has been verified against, what is preview, and what it does not support.'
# Diátaxis mode: reference
---

Aran is pre-1.0 research software. This page is the one place that says how far
each part has been tested. Other pages link here instead of repeating it.

**Verified** means someone ran it against a real service and watched it work.
**Preview** means it is implemented and tested in isolation but has not run
against the real thing. **Not supported** means there is no code path.

## Sources

| Source | Status | Notes |
| --- | --- | --- |
| File upload (CSV, JSON, Parquet) | Verified | The path the demo and walkthrough use. |
| PostgreSQL, snapshot and live | Verified | Live reads attach the database read-only through DuckDB. |
| MySQL 8.4 and MariaDB 11, snapshot and live | Verified | Integration tests import and read live from `mysql:8.4` and `mariadb:11` containers. |
| Iceberg REST catalog | Preview | Run against the Iceberg reference REST server over a `file://` warehouse. A data plane served over authenticated `http(s)` has been read only from Aran's own product catalog, in one process. Not run against Polaris, Lakekeeper, Glue or an `s3://` warehouse. |
| Object storage | Preview | Run against a Garage container. Not run against AWS S3, GCS or Azure. Live reads and registering a dataset from a bucket have no user-facing path yet. |
| Singer tap | Preview | Run against purpose-built test taps. No published tap has been run, and each run is a full re-pull with no incremental state. |
| Oracle and SQL Server, snapshot | Preview | Imported through the same generic JDBC extract as MySQL, but not run against a real Oracle or SQL Server. Neither can be read live, because DuckDB has no scanner for them. |
| HBase (Phoenix), import and live | Not supported | Aran connects and lists tables. Upload a file for the data. |
| Document stores, key-value stores, generic web APIs | Not supported | Export to a file, or use a Singer tap. |

## Engine

| Area | Status | Notes |
| --- | --- | --- |
| OQL planning and execution | Verified | Covered by the browser end-to-end suite and by plan-shape golden tests that run on every build. |
| Optimisable wrappers | Verified | A wrapper runs as written when it aggregates, when its class has an enforced denial constraint, or when it has a DECIMAL or TIMESTAMP column. |
| Join elimination from declared foreign keys | Verified for imported PostgreSQL tables | Only PostgreSQL reports foreign keys to Aran. The referenced key must be proved unique by a snapshot profile, so a table read live keeps its join. |
| Snapshot versus live placement | Preview | The cost weights (`aran.query.placement.*`) are guesses awaiting calibration, and no switch to live has been observed. See [Federated execution and placement](../query-engine/federated-execution-and-placement.md). |
| Wrapper output cache | Verified in benchmarks | In `make perf`, warm runs were 15% to 29% faster at 100 thousand to 1 million rows per source. Not yet watched on a running stack. |
| Discovered denial constraints | Display only | Discovery compares each column only with itself, and Aran drops such candidates, so today it stores none. A stored candidate cannot be enforced: Aran answers 400. Write the constraint by hand. See [Denial constraints in practice](../query-engine/denial-constraints.md). |

## Serving

| Interface | Status | Notes |
| --- | --- | --- |
| JDBC, REST export, Python SDK | Verified | |
| MCP | Verified | A real MCP client read products through the sidecar, a virtual one included. |
| Iceberg REST catalog (read-only) | Verified over HTTP | Read by DuckDB and by iceberg-core. PyIceberg cannot read a product without a custom file IO that sends the bearer token. |
| Virtual data products | Verified | The browser suite reads one over uploaded files, and integration tests read one over live MySQL and MariaDB tables. Object storage has no live path to read over. |
| Cubes | Off by default | Set `ARAN_CUBES_ENABLED=true` to turn them on. The API path has been run. The wizard has not. |

## Deployment

- **One backend instance.** Aran assumes a single running backend. At startup it fails every job left pending or running with `ORPHANED_ON_RESTART`, which would kill a second instance's jobs. Running two is not supported.
- **Trusted users on a trusted network.** Aran is not hardened for untrusted users or for public multi-tenant hosting.
- **Development credentials are public.** The development realm ships `alice` / `aran`. Never expose a development stack. Outside development, the first boot generates the owner's password and prints it once to the log; see [Run Aran standalone](../administration/standalone.md#the-first-run-administrator-password).
- The standalone stack boots from one Compose file, `compose.standalone.yml`. `scripts/standalone-smoke.sh` runs a first-time user's path against it end to end.

## Reading a badge

The Wrapper tab shows a wrapper as *Optimizable* or *Opaque*. The check behind
the badge does not know about enforced denial constraints or DECIMAL and
TIMESTAMP columns, so the badge can say *Optimizable* for a wrapper the planner
will run as written. The planner re-checks at query time, so results stay
correct; only the optimisation is missing.

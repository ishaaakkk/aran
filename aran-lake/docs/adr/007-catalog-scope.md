# ADR 007 — Catalog scope: physical vs logical (v0.2)

**Status:** Accepted, 2026-05-24
**Related:** ADR 001 (sidecar over library), ADR 006 (materialize)

## Context

In v0.1 the lake's `dataset` table carried `name`, `description`, and a
uniqueness constraint on `(tenant_id, name)`. Reviewing this against
Aran's actual model surfaced a duplication problem:

* **Aran's catalog** is about *wrappers* — the relational view per
  dataset, with an inferred schema, plus the GAV rules that map the
  integrated graph onto wrappers.
* **The lake's catalog** is about *physical artifacts* — which parquet
  file is which content-hashed snapshot of which dataset.

These are not the same thing. But `dataset.name` + `dataset.description`
in the lake were logical metadata that already lived in Aran. Two systems
holding the "same" string is a recipe for drift; renaming a dataset in
Aran's UI would never reach the lake, and the lake's idea of what a
dataset is would silently fork.

## Decision

The lake owns **only physical metadata**:

| Concept | Lives in | Owned by |
|---|---|---|
| Dataset name, description, schema | Aran's catalog | Aran |
| Wrapper definition (SQL, schema) | Aran's catalog | Aran |
| GAV rules, integrated graph | Aran's catalog | Aran |
| Snapshot bytes (parquet, CSV, …) | Garage / S3 | The lake |
| Snapshot content-hash + size + row count | The lake's catalog | The lake |
| Snapshot lineage (derived_from, derived_via_job) | The lake's catalog | The lake |
| Snapshot grouping (dataset_id only) | The lake's catalog | The lake |

The lake's `dataset` row is now bare: `id`, `tenant_id`, `created_at`.
It exists solely as a grouping key for snapshots. The `id` is opaque to
the lake — Aran supplies it. Aran can keep its dataset model evolving
without ever touching the lake's schema.

## Implementation notes

* `dataset.name` and `dataset.description` removed.
* `dataset.id` is no longer "1:1 with an Aran dataset id" — it's
  *equal to* an Aran dataset id. The lake never invents one.
* `SnapshotInitRequest` no longer carries `dataset_name`.
* `ImportRequest` no longer carries `dataset_name`.
* The lake records `created_by_caller` (which service initiated the
  write) alongside `created_by` (which user authorized it) on snapshots
  and jobs. Both are audit fields, not access-control inputs.

## Considered alternatives

1. **Keep `dataset.name`, mark it advisory.** Rejected because advisory
   fields invariably drift and become misleading. Better to not have it
   than to maintain a stale copy.

2. **Replace `dataset` table with a column on `snapshot`.** Considered:
   the `dataset` table is essentially a left side of a `(tenant_id,
   dataset_id)` index. Decided against because the row makes the
   referential integrity (snapshots → dataset_id) cheap and gives a
   natural place to track per-dataset metrics later (e.g. last write
   time) without a schema change.

3. **Inverse: move snapshot metadata into Aran.** Rejected because
   "operationally independent" is a non-negotiable property (ADR 001).
   Aran going down should not block the lake from serving queries on
   existing snapshots.

## Consequences

* The two catalogs no longer overlap conceptually. The lake is the
  data plane to Aran's control plane, cleanly.
* The lake's snapshot lineage (`derived_from_snapshot_id`) is the *only*
  cross-snapshot relationship it tracks — and it's purely physical
  (which bytes were produced from which other bytes). Logical lineage
  (which wrapper is materialized from which dataset) lives in Aran.
* Migration impact: v0.1 → v0.2 is a clean schema cut. We did not write
  an in-place migration tool because v0.1 was a scaffold with no
  production data (confirmed during v0.2 design review).

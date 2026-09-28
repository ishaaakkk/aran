# ADR 003 — Tenancy via single bucket + key prefix

**Status:** Accepted
**Date:** 2026-05-23

## Context

Aran is multi-tenant. The lake must enforce tenant isolation on every
read and write. Two storage tenancy models are common:

- **(a)** One bucket per tenant. Strong isolation, native Garage ACLs.
- **(b)** Single bucket, key-prefix per tenant. Simpler ops, weaker
  storage-side isolation, app-level enforcement.

## Decision

**Single bucket, per-tenant key prefix** (`{tenant_uuid}/...`). All
tenant enforcement happens in the lake service code; Garage sees one
bucket with many objects.

## Rationale

1. **Symmetry with Aran's existing pattern.** Aran uses shared-schema +
   tenant-discriminator in Postgres. The lake's storage tenancy mirrors
   that: shared bucket + tenant-discriminator in the key.

2. **Per-bucket Garage overhead.** Each bucket has metadata cost.
   Per-tenant buckets at 100+ tenants make Garage administration noisy
   without buying real isolation (the lake credentials see all buckets
   anyway — the boundary is in the lake, not Garage).

3. **Tenant deletion is just a prefix delete.** "Wipe tenant X" maps
   to one S3 list+delete loop with prefix `X/`. No bucket lifecycle
   complications.

4. **The enforcement chokepoint is in code.** Every key constructor
   takes a `tenant_id` as a required parameter. Every repository
   function takes `tenant_id` as a required parameter. We can verify
   isolation with unit tests rather than relying on storage-layer ACLs.

5. **The upgrade path is straightforward** if we ever need cryptographic
   per-tenant isolation (regulated tenants, sovereignty requirements):
   move them to dedicated buckets with their own KMS keys. The catalog
   gains a `bucket` column; the storage adapter routes by tenant. v0.1
   doesn't need it.

## Consequences

- Tenant isolation depends on code correctness. We mitigate with:
  - the `is_tenant_owned_key` defense-in-depth check before any
    mutation
  - tenant-id required everywhere in the repository signatures
  - explicit tests for cross-tenant denial (see `tests/test_tenancy.py`)
- A compromised lake service sees all tenant data. Same is true today
  of Aran against Postgres; the bar is consistent.
- We cannot use S3 ACLs to enforce isolation at the storage layer.
  Accepted.

## Alternatives considered

- **Bucket per tenant.** Rejected for operational and migration cost;
  reconsider if a regulated-tenant requirement emerges.
- **Bucket per tier (free/paid/enterprise) with tenant-prefix inside.**
  Rejected as unnecessary middle ground.

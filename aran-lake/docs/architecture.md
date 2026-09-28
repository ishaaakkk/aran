# aran-lake — Architecture (v0.2)

## The shape

```
   Aran services (any language)
   ├─ Aran Java (Spring Boot 4)
   ├─ FREYJA Python operators (profile, future ones)
   ├─ BOOTSTRAP / ALIGN-prep / data-product operators (Aran-side)
   └─ Future internal services
              │
              │  HTTP/JSON (control)
              │  Arrow IPC over HTTP (query results)
              │  Presigned GET URLs (raw bytes when needed)
              ▼
   aran-lake service
   ┌─────────────────────────────────────────────────────┐
   │   FastAPI HTTP layer                                 │
   │     /api/datasets/{id}/snapshots/init                │
   │     /api/snapshots/uploads/{id}/complete             │
   │     /api/snapshots/{id}/download   (NEW v0.2)        │
   │     /api/imports                                     │
   │     /api/query                                       │
   │     /api/jobs/{id}                                   │
   │     /health                                          │
   ├─────────────────────────────────────────────────────┤
   │   Per-caller bearer auth (v0.2) + tenant enforcement │
   ├─────────────────────────────────────────────────────┤
   │   Catalog repos │ Storage  │ DuckDB   │ Jobs        │
   │                 │           │ (format- │ (generic    │
   │                 │           │  aware)  │  registry)  │
   └────────┬────────────┬──────────┬──────────┬──────────┘
            │            │          │          │
            ▼            ▼          ▼          ▼
        Postgres     Garage      DuckDB    in-process
        (catalog)   (objects)   (memory)    worker
```

## The boundary (v0.2)

The lake is a **thin data plane**:

* It stores bytes in object storage (any format).
* It runs SQL queries against those bytes via DuckDB (format-aware).
* It runs typed async jobs (materialize today; imports + future
  operators-as-jobs later).
* It mints presigned URLs for direct byte access when callers need them.

Everything else lives in Aran:

* **What a dataset means** — names, descriptions, schemas. The lake's
  `dataset` row is a bare grouping key (id + tenant + created_at).
* **What a wrapper is** — the flattened relational view per dataset, its
  inferred schema, the GAV rules from the integrated graph. The lake
  knows snapshots, not wrappers.
* **What a profile is** — FREYJA computes profiles by running SQL
  against snapshots via `/api/query`. The lake has no profile concept.
* **What's "silver"** — the cleaned/materialized layer is emergent from
  Aran's catalog ("this wrapper points at materialized snapshot X"); the
  lake doesn't have a silver-zone table or tier.

See ADR 007 for the catalog scope discussion; ADR 006 for materialize;
ADR 005 for per-caller auth.

## Layers, top to bottom

### HTTP layer (`src/aran_lake/api/`)

FastAPI routers, one per resource. Every endpoint depends on:

* `require_bearer` — returns a `CallerIdentity` containing the
  caller-id (the entry in `ARAN_LAKE_SHARED_SECRETS` whose secret matched).
* `require_tenant` — extracts and validates the X-Tenant-Id UUID.
* `require_actor` — optional X-Actor-Id for audit.

The `CallerIdentity` propagates into the catalog: snapshots and jobs
record `created_by_caller`. Logs include both caller-id and tenant.

### Auth & tenancy

**Per-caller bearer auth.** `ARAN_LAKE_SHARED_SECRETS` is a JSON map of
`{caller_id: secret}`. The middleware checks the presented token against
all configured secrets in constant time and identifies the caller by
which one matched. The legacy single-secret form
(`ARAN_LAKE_SHARED_SECRET`) still works and promotes to caller-id
`default`.

**Tenant assertion.** The bearer secret proves the caller is one we
trust. The caller asserts the tenant via `X-Tenant-Id`. Every catalog
write includes `tenant_id`; every read filters on it. Tenant ownership
of object-storage keys is enforced separately at the key-construction
layer (`{tenant_id}/snapshots/...`).

### Catalog (`src/aran_lake/catalog/`)

Postgres-backed via SQLAlchemy 2.x async. Four tables (v0.2):

* `dataset` — `id`, `tenant_id`, `created_at`. **Nothing else.** Aran
  owns the name/description/schema.
* `snapshot` — content-hashed parquet/CSV/JSON/xlsx artifacts; carries
  `content_type` + `format_options` + `derived_from_snapshot_id` for
  materialize lineage.
* `snapshot_upload` — in-flight upload state between presigned-PUT and
  /complete.
* `job` — async work units. Type-tagged; payload + result are JSONB.

The v0.1 `profile` table is gone.

### Object storage (`src/aran_lake/storage/`)

S3-compatible (Garage in dev). The lake never proxies user-facing
bytes — uploads go via presigned PUT, downloads via presigned GET.
The one exception is the import handler, which writes its parquet to a
worker-local tempfile before uploading; this is an internal operation
initiated by the lake itself, not a user-facing path.

Per-tenant key prefix `{tenant_id}/...` enforced at construction and
checked again at API egress (defense in depth).

### Query engine (`src/aran_lake/query/`)

DuckDB in-memory per request. The runner dispatches on `content_type` to
bind the source view with the right reader (`read_parquet`,
`read_csv_auto`, `read_json`, `read_xlsx`). `format_options` get rendered
as named reader arguments.

The `/api/query` SQL screen rejects DDL/DML/COPY/PRAGMA before execution,
along with anything that reads outside the bound snapshot and any body
carrying more than one statement. `/api/query` is now the only path that
executes caller SQL — the materialize handler, which bypassed the screen
by constructing its own COPY around a wrapped SELECT, was deleted on
2026-08-27 and its statement-boundary check moved into the screen.

Results return as Arrow IPC stream by default (zero-copy in pyarrow /
Polars / Arrow-Java), or as JSON when `Accept: application/json` is set
(small results only, type fidelity lost).

### Job runner (`src/aran_lake/jobs/`)

In-process asyncio worker, claims one PENDING job at a time via
`SELECT ... FOR UPDATE SKIP LOCKED`. Handlers are pure async functions
keyed by `job_type`. One handler ships:

* `import.from_postgres` — fully implemented: server-side cursor over
  asyncpg, batched Arrow record batches, incremental ParquetWriter, then
  content-hash / dedup / upload registration.
  (This read "still a stub returning `not_implemented`" long after it
  shipped.)

`snapshot.materialize` shipped in v0.2 and was deleted on 2026-08-27 —
see the README and ADR 006.

The runner is unchanged from v0.1; new operators land as new
`job_type` entries in `HANDLERS` with no protocol change.

## What's *not* in the lake

* Dataset semantics (names, descriptions, schemas, wrappers, GAV rules).
* Profile-specific endpoints, tables, or handlers.
* User authentication (Aran does it).
* Per-tenant access control beyond "the caller asserted this tenant and
  we trust the caller."
* Streaming query results — Arrow IPC delivery is single-response, not
  chunked. Long-running work goes through the job runner.
* Webhooks / SSE / push notifications — clients poll.

## Lifecycle

The FastAPI lifespan initializes the async DB engine and starts the
in-process job worker. On shutdown the worker drains in-flight jobs
cleanly and the engine is disposed.

For scale, the worker can move to dedicated containers without a code
change — `FOR UPDATE SKIP LOCKED` makes concurrent workers correct by
construction. ADR 001 documents the sidecar-over-library decision.

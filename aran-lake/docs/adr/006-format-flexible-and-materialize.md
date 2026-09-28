# ADR 006 — Format-flexible uploads and materialize-as-snapshot (v0.2)

**Status:** Accepted, 2026-05-24 — **materialize half REVERSED 2026-08-27**
**Related:** ADR 002 (parquet snapshots), ADR 007 (catalog scope)

> **The materialize half of this ADR no longer holds.** The format-flexible
> upload/query half is current and unaffected; everything below about
> `snapshot.materialize`, `POST /api/snapshots/{id}/materialize` and the
> silver-zone flow describes code deleted on 2026-08-27.
>
> Why it was reversed: the ADR assumed BOOTSTRAP would materialize wrappers
> through the lake. Aran never built it that way — it materializes data
> products with its own `materialize-oql` operator, running DuckDB inside the
> Aran JVM, and no Aran code path ever called this endpoint. What was left was
> a second, divergent materializer that ran caller SQL with the `/api/query`
> safety screen **deliberately** bypassed, on a connection holding the
> bucket-wide S3 credential (audit 2026-08-26: DATA_PRODUCTS-11, blocker
> LAKE_SERVICE-1). Its statement-boundary check — the one thing `/api/query`
> lacked — moved into `_screen_sql` rather than being deleted with it.
>
> "Considered alternatives" below still reads correctly as the reasoning of
> the time; option 1 there ("Aran materializes client-side") is, in effect,
> what Aran actually did.

## Context

v0.1 effectively required parquet on upload. The query path hard-coded
`read_parquet(uri)`. Users who arrive with CSV, NDJSON, or Excel had to
convert client-side before they could put anything in the lake. This
didn't match the "Aran should be able to ingest whatever shape the source
has" requirement, and it pushed transformation work into every client.

Separately, the user-facing layer in Aran works in terms of **wrappers**:
flattened, typed, relational views over raw datasets. Producing the
wrapper view from raw input is BOOTSTRAP's job. BOOTSTRAP needs the lake
to be able to (a) read whatever the user uploaded and (b) write the
wrapper output as a new, queryable artifact. The "(b)" half — write a SQL
result as a new snapshot — did not exist in v0.1.

## Decision

Two coupled changes:

### Format-flexible uploads

* Snapshots carry an arbitrary `content_type` and a `format_options` JSONB
  blob. The storage layer treats them as opaque bytes.
* The DuckDB runner dispatches on `content_type` to pick the right
  reader: `read_parquet`, `read_csv_auto`, `read_json`, `read_xlsx`.
  `format_options` are rendered as named arguments to the reader
  (delimiter, header, encoding, sheet, etc.).
* v0.2 supports parquet, CSV (incl. TSV), NDJSON / JSON, and Excel
  (.xlsx via DuckDB's `excel` extension, lazy-loaded on first use). The
  set is documented in `docs/api.md`; adding new formats means a runner
  patch only.

### Materialize as a snapshot

* New job type `snapshot.materialize` with payload
  `{source_snapshot_id, sql, target_dataset_id?}`.
* Convenience endpoint `POST /api/snapshots/{id}/materialize` enqueues
  the job and returns `{job_id, poll_url}`.
* The handler:
    1. Opens DuckDB, binds the source snapshot as the view `snapshot`
       (format-aware, same path as `/api/query`).
    2. Runs `COPY (SELECT * FROM (<user SQL>) AS _src) TO '/tmp/...' (FORMAT PARQUET)`.
       The user's SQL is wrapped in a subquery so multi-statement piggyback
       fails at parse time inside the parentheses.
    3. SHA-256-hashes the local file, checks size against the cap, and
       compares against existing snapshots (content-addressed dedup).
    4. Uploads to the content-addressed final key (multipart for
       large files), registers a new snapshot row with
       `derived_from_snapshot_id` and `derived_via_job_id` populated.
    5. Cleans up the scratch file.

### How "silver zone" emerges from this

There's no "silver zone" table or storage tier in the lake (ADR 004
stands — no medallion). The silver zone is **emergent in Aran's
catalog**: Aran tracks "this wrapper is materialized; its bytes are
snapshot X in the lake." The lake doesn't know it's a wrapper. Aran's
query planner sees the wrapper, looks up its materialized snapshot, and
sends queries to it.

## Implementation notes

* **No streaming write to S3 in v0.2.** The handler writes to `/tmp`
  first, hashes, then uploads. The reason is correctness: we want the
  content hash to reflect the on-disk parquet bytes (so dedup works), and
  computing that hash while streaming to S3 is doable but adds
  complexity. The trade-off is that materialize jobs briefly occupy
  worker disk equal to the output size; `ARAN_LAKE_MATERIALIZE_MAX_BYTES`
  (default 10 GiB) caps the damage.
* **Excel extension is lazy-loaded.** `INSTALL excel; LOAD excel;` runs
  the first time a `.xlsx` snapshot is queried, then is remembered for
  the connection's lifetime.
* **Materialized snapshots are always parquet.** Even if the source is
  CSV/JSON/Excel, the output is always parquet. The whole point of
  materialize is to produce the fast, typed, queryable artifact.
* **The query screen still bans `COPY` on `/api/query`.** Materialize is
  the *only* endpoint that can write; it does so via a server-issued
  COPY around a wrapped user SELECT.

## Considered alternatives

1. **Reject non-parquet uploads.** Simplest. Pushes conversion to every
   client. Rejected because BOOTSTRAP would then need to do format
   conversion outside the lake, which puts a DuckDB dependency in every
   service that ingests data — the opposite of "the lake is the
   chokepoint."

2. **Convert to parquet on upload.** The lake automatically rewrites
   CSV/JSON to parquet at ingestion. Rejected because (a) format hints
   (delimiter, header, encoding, sheet) are often needed before you know
   how to convert, (b) some uses want to *query the raw CSV directly*
   without paying the rewrite cost, and (c) materialize gives the same
   benefit on demand, only for wrappers that need it.

3. **Generic-only materialize via `POST /api/jobs`.** No typed wrapper
   endpoint. Decided to keep the typed wrapper for discoverability
   (Java clients especially benefit) — it dispatches to the generic
   job runner underneath, so we don't lose the genericity.

## Consequences

* The lake now handles real heterogeneity at ingestion — no client-side
  format dance.
* BOOTSTRAP has the primitive it needs to produce wrappers without doing
  the writing itself.
* Wrapper lineage is recorded in the lake's catalog
  (`derived_from_snapshot_id`, `derived_via_job_id`), so Aran can
  re-materialize when the source changes.
* Adding a new ingestion format is now a runner patch (one entry in the
  content-type dispatch), not a protocol change.

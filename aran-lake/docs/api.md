# aran-lake — HTTP API (v0.2)

All endpoints require:

* `Authorization: Bearer <secret>` — one of the configured caller secrets
  (see ADR 005).
* `X-Tenant-Id: <uuid>` — the tenant the request operates on.
* `X-Actor-Id: <uuid>` — optional, identifies the human behind a request
  for audit.

Errors are JSON: `{"detail": "<message>"}`. Status codes follow standard
REST conventions.

## Snapshots

### `POST /api/datasets/{dataset_id}/snapshots/init`

Mint a presigned PUT URL for a new snapshot upload.

```json
{
  "declared_size_bytes": 12345,
  "content_type": "text/csv",
  "format_options": {"header": true, "delim": ";"}
}
```

* `content_type` is **required** in v0.2 (no default).
* `format_options` is optional; the lake stores it as opaque JSONB and
  passes it to the runner at query time. See "Supported formats" below.

Returns 201:

```json
{
  "upload_id": "<uuid>",
  "upload_url": "https://garage/...?X-Amz-Signature=...",
  "object_key": "<tenant>/_staging/<dataset>/<upload>.bin",
  "expires_at": "2026-05-24T10:15:00Z",
  "required_headers": {"Content-Type": "text/csv"}
}
```

The client then `PUT`s the bytes directly to `upload_url` with the
required headers — no auth on the PUT, the URL is signed.

### `POST /api/snapshots/uploads/{upload_id}/complete`

Confirm the PUT succeeded and register the snapshot.

```json
{
  "content_hash": "<sha256-hex>",
  "actual_size_bytes": 12345,
  "row_count": 100
}
```

The claimed `content_hash` is verified against the staged bytes before
anything is registered: the lake streams the object through SHA-256 and
returns **409** if it disagrees, naming both hashes. The staged object and
the upload record survive a 409, so a caller that miscomputed the digest can
retry `/complete` with the right one. Objects larger than
`ARAN_LAKE_HASH_VERIFY_MAX_BYTES` (default 1 GiB) are verified on size alone
and log a warning naming the unverified hash. The S3 ETag is deliberately
unused for this — it is MD5, and multipart-dependent.

Returns 201 with the snapshot row. If a snapshot with the same *verified*
content_hash already exists for this dataset, the staged upload is deleted
and the existing snapshot is returned (idempotent dedup). Verification comes
first because the hash is both the dedup key and the object key: an
unverified claim that collided with another snapshot's hash used to return
that snapshot with a 201 and delete the bytes just uploaded.

### `GET /api/snapshots/{snapshot_id}/download` *(NEW v0.2)*

Mint a short-lived presigned GET URL for the snapshot's raw bytes.

Returns 200:

```json
{
  "snapshot_id": "<uuid>",
  "object_key": "<tenant>/snapshots/<dataset>/<hash>.bin",
  "download_url": "https://garage/...?X-Amz-Signature=...",
  "expires_at": "2026-05-24T10:05:00Z",
  "content_type": "application/vnd.apache.parquet"
}
```

TTL defaults to 5 minutes (`ARAN_LAKE_DOWNLOAD_PRESIGN_TTL_SECONDS`).
Useful for streaming raw parquet through pyarrow, exporting to a
third-party engine, or any "give me the file" workflow that shouldn't
go through SQL.

## Query

### `POST /api/query`

Execute SQL against a snapshot. The source is bound as the view
`snapshot` using format-aware dispatch (parquet, CSV, NDJSON, Excel).

```json
{
  "snapshot_id": "<uuid>",
  "sql": "SELECT region, count(*) FROM snapshot GROUP BY region",
  "row_limit": 10000
}
```

Default response: Arrow IPC stream
(`Content-Type: application/vnd.apache.arrow.stream`). Send
`Accept: application/json` for a JSON-shaped response (small results
only — JSON loses type fidelity).

Response headers:

* `X-Aran-Lake-Row-Count` — rows returned.
* `X-Aran-Lake-Row-Limit-Hit` — `"true"` if the row cap was hit, `"false"`
  otherwise.

The SQL screen rejects DDL/DML/COPY/PRAGMA, anything that reads outside
the bound snapshot (`read_*`/`*_scan`/`duckdb_*`, a bare string literal in
`FROM` position), and any body that is more than one statement — a
semicolon anywhere but the very end is refused, because the runner
executes your SQL inside its own `SELECT * FROM (…) LIMIT n` wrapper.

Returns 400 on rejected SQL, 400 on unsupported snapshot format, 404 on
missing snapshot, 408 on query timeout.

## Imports

### `POST /api/imports`

Enqueue an async import from a remote source. v0.2 status: endpoint +
queue wired, handler still returns `status: not_implemented`.

```json
{
  "dataset_id": "<uuid>",
  "source": {
    "kind": "postgres",
    "connection_string": "postgresql://...",
    "query": "SELECT * FROM patients WHERE ..."
  }
}
```

Returns 202 with `{job_id, status, poll_url, note}`.

## Jobs

### `GET /api/jobs/{job_id}`

Poll job status. Returns 200 or 404.

```json
{
  "id": "<uuid>",
  "tenant_id": "<uuid>",
  "job_type": "import.from_postgres",
  "status": "SUCCEEDED",
  "payload": { ... },
  "result": {"snapshot_id": "<uuid>", "row_count": 100, "deduped": false},
  "error_message": null,
  "created_at": "...",
  "started_at": "...",
  "finished_at": "...",
  "created_by_caller": "aran"
}
```

State machine: `PENDING → RUNNING → {SUCCEEDED | FAILED}`.

## Health

### `GET /health`

No auth. Returns 200 with the service version. Liveness only — does not
check Postgres or Garage connectivity.

## Supported snapshot formats (query)

| content_type | reader | typical `format_options` |
|---|---|---|
| `application/vnd.apache.parquet` (and aliases) | `read_parquet` | usually empty |
| `text/csv`, `application/csv`, `text/tab-separated-values` | `read_csv_auto` | `header`, `delim`, `quote`, `escape`, `encoding`, `all_varchar` |
| `application/x-ndjson`, `application/jsonl`, `application/json` | `read_json` | `format` (`newline_delimited` \| `array`), `records`, `columns` |
| `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `application/vnd.ms-excel` | `read_xlsx` (DuckDB `excel` extension) | `sheet` |

Other content types can be *uploaded* (the lake stores them) but cannot
be queried until support is added. Querying an unsupported type returns
400.

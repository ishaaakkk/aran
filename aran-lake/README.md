# aran-lake

> Data-lake service for Aran. DuckDB query engine over S3-compatible
> object storage, exposed as a polyglot-friendly HTTP API with Arrow IPC
> for bulk query results.

**Status: v0.2, with format-flexible ingestion and per-caller bearer auth.
All handlers are implemented, including `import.from_postgres` (asyncpg
server-side cursor → batched Arrow → incremental ParquetWriter → snapshot
registration).**

> Lake-side roadmap lives in [`docs/v0.2-changes.md`](docs/v0.2-changes.md).

See [`docs/v0.2-changes.md`](docs/v0.2-changes.md) for what's new since v0.1.

## What this is

A standalone, containerized service that holds the data plane for Aran:

- **Storage**: snapshots in S3-compatible object storage (Garage in dev),
  per-tenant key prefix. Any format: parquet, CSV, NDJSON, Excel.
- **Catalog**: Postgres-backed *physical* metadata (snapshots, content
  hashes, lineage, jobs). Logical metadata (dataset names, wrapper
  definitions) lives in Aran (see ADR 007).
- **Query**: DuckDB in-process; format-aware view binding; results
  serialized as Arrow IPC streams.
- **Imports**: async job-based pull from remote sources. Implemented for
  PostgreSQL; the queue and endpoint are wired for further sources.

Materialize is **not** here. A `snapshot.materialize` job type existed in
v0.2 (ADR 006) and was deleted on 2026-08-27. Aran never called it: Aran
materializes with its own `materialize-oql` operator, running DuckDB in the
Aran JVM. What remained was a second materializer that ran caller SQL without
the `/api/query` safety screen.

Any Aran service can reach it over HTTP, in any language: the Aran Java
backend, FREYJA (Python), BOOTSTRAP and future operators.

## What this is NOT

- Not a lakehouse. No Iceberg, no Delta, no medallion (ADR 004).
- Not the system of record for analytics. Aran federates to source
  systems; this layer accelerates and characterizes.
- Not internet-exposed. Sits behind Aran, trusts Aran's auth.
- Not in charge of dataset semantics. The lake's `dataset` row is a bare
  grouping key; names, descriptions, schemas, wrappers, and GAV rules
  all live in Aran.

## Quick start

```bash
# Bring up Garage + Postgres + the lake service
docker compose up -d

# Verify
curl http://localhost:8080/health
# → {"status":"ok","version":"0.1.0","database":"ok"}

# OpenAPI docs
open http://localhost:8080/docs

# Run the example: upload a CSV, then query it
cd examples
pip install httpx pyarrow pandas
python upload_csv.py
```

## Development

```bash
# One-time setup
pip install uv==0.4.27
uv venv .venv
uv pip install --python .venv/bin/python -e ".[dev]"

# Lint / type-check / test
.venv/bin/ruff check src tests
.venv/bin/mypy src
.venv/bin/pytest -v        # uses Testcontainers for Postgres

# Run the service locally (without docker-compose)
docker compose up -d postgres garage garage-init
.venv/bin/uvicorn aran_lake.main:app --reload --port 8080
```

## Directory layout

```
aran-lake/
├── src/aran_lake/          # the service
│   ├── api/                # FastAPI routers (snapshots, query, imports, …)
│   ├── auth/               # per-caller bearer enforcement (v0.2)
│   ├── catalog/            # SQLAlchemy models + repos + sessions
│   ├── jobs/               # async-job runner + handlers
│   ├── query/              # DuckDB integration (format-aware)
│   ├── storage/            # S3-compatible adapter
│   ├── tenancy/            # X-Tenant-Id enforcement
│   ├── config.py           # pydantic-settings
│   └── main.py             # FastAPI app factory + lifespan
├── alembic/                # migrations (single v0.2 migration)
├── docker/garage/          # Garage TOML + idempotent init script
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── integration-with-aran.md   # Python + Java Arrow IPC recipes
│   ├── v0.2-changes.md
│   └── adr/                # ADRs 001–007
├── examples/
│   ├── aran_lake_client.py        # reference Python client (v0.2)
│   ├── upload_csv.py              # CSV-direct upload + query
│   ├── query_arrow.py
│   └── import_postgres.py
├── tests/                  # pytest, Postgres via Testcontainers
├── Dockerfile
├── docker-compose.yml
├── pyproject.toml          # uv-managed; ruff + mypy + pytest configured
└── .github/workflows/ci.yml
```

## Configuration

All env vars are prefixed `ARAN_LAKE_`. The most important ones:

| var | required | meaning |
|---|---|---|
| `ARAN_LAKE_DATABASE_URL` | yes | asyncpg URL for the catalog Postgres |
| `ARAN_LAKE_S3_ENDPOINT_URL` | yes | S3-compatible endpoint |
| `ARAN_LAKE_S3_BUCKET` | yes | single bucket for all tenants |
| `ARAN_LAKE_S3_ACCESS_KEY` | yes | S3 access key (secret) |
| `ARAN_LAKE_S3_SECRET_KEY` | yes | S3 secret key (secret) |
| `ARAN_LAKE_SHARED_SECRETS` | one of | per-caller secrets: `{"aran":"…","freyja":"…"}` |
| `ARAN_LAKE_SHARED_SECRET` | these two | legacy single secret (promoted to caller-id `default`) |
| `ARAN_LAKE_DOWNLOAD_PRESIGN_TTL_SECONDS` | no | TTL for `/api/snapshots/{id}/download` (default 300) |
| `ARAN_LAKE_MATERIALIZE_MAX_BYTES` | no | hard cap on job output size, e.g. an import's parquet (default 10 GiB) |
| `ARAN_LAKE_HASH_VERIFY_MAX_BYTES` | no | largest upload whose SHA-256 `/complete` verifies (default 1 GiB) |
| `ARAN_LAKE_PROFILE` | no | `dev` (default) or `prod`; `prod` rejects unsafe defaults |

## Documentation

- **[Architecture](docs/architecture.md)**: boundary, layers, what each
  layer owns.
- **[API reference](docs/api.md)**: endpoint contracts and the supported
  formats table.
- **[Integration with Aran](docs/integration-with-aran.md)**: Python
  and Java Arrow IPC client recipes, and how BOOTSTRAP and FREYJA fit in.
- **[v0.1 → v0.2 changes](docs/v0.2-changes.md)**
- **[ADRs](docs/adr/)**: seven recorded design decisions.

## What's not in v0.2

- **No mTLS.** Per-caller bearer secrets are the v0.2 step. mTLS is
  planned for a later release.
- **No streaming import to S3.** An import's parquet is written to a
  worker-local tempfile first, so it can be hashed before upload for
  dedup. A hard size cap protects against pathological inputs.
- **No Java client SDK.** The Python reference client lives in
  `examples/`, and the docs have the Arrow-in-Java recipe.
- **No Arrow Flight.** Arrow IPC over HTTP is the wire format.
- **No Kubernetes manifests.** `docker-compose.yml` only.

## License

Apache License 2.0. See [`LICENSE`](../LICENSE) and [`NOTICE`](../NOTICE) at the
repository root. Copyright the DTAK research group, Universitat Politècnica de Catalunya.

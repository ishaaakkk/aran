# aran-lake

The data-lake service for Aran: a DuckDB query engine over S3-compatible object
storage, exposed as an HTTP API that returns bulk query results as Arrow IPC.

The lake owns Aran's snapshots. Aran calls it; nothing else does. It holds the
data plane so the rest of the platform reads a stable copy instead of the source.

## What it does

It is a standalone, containerised service with four parts:

- **Storage**: snapshots in S3-compatible object storage (Garage in dev),
  per-tenant key prefix. Any format: parquet, CSV, NDJSON, Excel.
- **Catalog**: Postgres-backed *physical* metadata (snapshots, content
  hashes, lineage, jobs). Logical metadata (dataset names, wrapper
  definitions) lives in Aran (see ADR 007).
- **Query**: DuckDB in-process; format-aware view binding; results
  serialized as Arrow IPC streams.
- **Imports**: async job-based pull from remote sources. Implemented for
  PostgreSQL (`import.from_postgres`: an asyncpg server-side cursor, batched into
  Arrow, written incrementally with a `ParquetWriter`, then registered as a
  snapshot); the queue and endpoint are wired for further sources.

Materialisation is not here. Aran materialises with its own `materialize-oql`
operator, running DuckDB in the Aran JVM, so the lake has no job that runs
caller SQL.

Any Aran service can reach it over HTTP in any language: the Aran Java
backend, FREYJA (Python), BOOTSTRAP and future operators.

## What it is not

- It is not a lakehouse: no Iceberg, no Delta, no medallion layers (ADR 004).
- It is not the system of record for analytics. Aran federates to source
  systems; the lake accelerates queries and characterises data.
- It is not exposed to the internet. It sits behind Aran and trusts Aran's auth.
- It does not own dataset semantics. The lake's `dataset` row is a bare
  grouping key; names, descriptions, schemas, wrappers and GAV rules
  all live in Aran.

## Quick start

```bash
# Bring up Garage + Postgres + the lake service
docker compose up -d

# Verify (the compose file publishes the lake on host port 8085)
curl http://localhost:8085/health
# → {"status":"ok","version":"0.1.0","database":"ok"}

# OpenAPI docs
open http://localhost:8085/docs

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
cp .env.example .env                  # the settings are read from the environment or .env
.venv/bin/alembic upgrade head
.venv/bin/uvicorn aran_lake.main:app --reload --port 8080
```

To run the lake beside the rest of a development stack, use `make aran-lake` from the
repository root instead. It creates the venv if needed, starts the dependencies, applies
the migrations and serves on port 8085, where the Aran backend and FREYJA expect it.

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
├── alembic/                # catalog migrations (001–003)
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

All environment variables are prefixed `ARAN_LAKE_`. The main ones:

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

- [Architecture](docs/architecture.md): boundary, layers, what each layer owns.
- [API reference](docs/api.md): endpoint contracts and the supported formats table.
- [Integration with Aran](docs/integration-with-aran.md): Python and Java Arrow IPC
  client recipes, and how BOOTSTRAP and FREYJA fit in.
- [v0.1 → v0.2 changes](docs/v0.2-changes.md): history of the auth and ingestion changes.
- [ADRs](docs/adr/): seven recorded design decisions.
- The [Aran documentation site](https://dtak-upc.github.io/aran/) covers the whole
  platform. [CONTRIBUTING.md](../CONTRIBUTING.md) explains how to build and test changes.

## Not included

- mTLS. Callers authenticate with per-caller bearer secrets. mTLS is planned.
- Streaming import to S3. An import's parquet is written to a worker-local tempfile
  first, so it can be hashed before upload for dedup. A hard size cap limits
  pathological inputs.
- A Java client SDK. The Python reference client lives in `examples/`, and the docs
  have the Arrow-in-Java recipe.
- Arrow Flight. Arrow IPC over HTTP is the wire format.
- Kubernetes manifests. Only `docker-compose.yml` is provided.

## License

Apache License 2.0. See [`LICENSE`](../LICENSE) and [`NOTICE`](../NOTICE) at the
repository root. Copyright the DTAK research group, Universitat Politècnica de Catalunya.

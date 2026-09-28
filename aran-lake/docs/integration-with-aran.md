# aran-lake — Integration with Aran (v0.2)

This document describes the contract from Aran's perspective: how Aran
calls the lake, how it consumes results, and how operators like BOOTSTRAP
and FREYJA fit in.

## Where the boundary sits

Aran owns the *meaning* of data:

* Dataset names, descriptions, schemas.
* Wrappers (the relational view per dataset) and their inferred schemas.
* GAV rules from the integrated graph onto wrappers.
* User auth (Keycloak / JWT). The lake never sees a user JWT.
* Per-tenant access control. The lake trusts whatever tenant Aran
  asserts via `X-Tenant-Id`.

The lake owns the *bytes* and the SQL engine over them:

* Snapshots in object storage (parquet, CSV, NDJSON, Excel — any format).
* Format-aware SQL queries returning Arrow IPC.
* Async jobs that import data from external sources into new snapshots.
* Presigned URLs for direct byte access.

## Auth from Aran's side

Aran holds a bearer secret. From v0.2 onward each calling service has
its own secret — see ADR 005. Aran's deployment config keeps the secret
out of the JVM heap dumps (we recommend mounting it from a secret store
into env). Every request:

```
Authorization: Bearer <aran's secret>
X-Tenant-Id: <tenant uuid>
X-Actor-Id: <user uuid, optional>
```

The lake validates the bearer, identifies the caller, accepts the tenant
assertion, and records the actor for audit. Aran is responsible for:

1. Validating the user JWT (with Keycloak).
2. Resolving the user's tenant.
3. Enforcing per-tenant / per-dataset RBAC before calling the lake.

The lake does not double-check any of that. The whole point of the
boundary is to keep the lake's surface narrow.

## Where Aran's catalog meets the lake

Aran's catalog records, per dataset:

* The dataset id (UUID; opaque to the lake).
* The dataset name, description, owner — never sent to the lake.
* The wrapper: its SQL definition, its inferred schema, whether it's
  materialized.
* If materialized: the snapshot id (in the lake) that holds the
  materialized bytes.

When Aran's user uploads a file:

1. Aran's UI / API creates the dataset row in Aran's catalog.
2. Aran calls `POST /api/datasets/{id}/snapshots/init` on the lake.
3. Aran's client follows the presigned URL to upload bytes.
4. Aran calls `POST /api/snapshots/uploads/{id}/complete`.
5. Aran records the resulting `snapshot_id` against the dataset.

When Aran's user queries a wrapper:

1. Aran's query planner looks up the wrapper.
2. If the wrapper is materialized, the planner sends
   `POST /api/query` with the materialized snapshot's id and the user's
   SQL composed against the wrapper schema.
3. If not materialized, the planner composes the wrapper SQL into the
   user query and sends that against the *raw* snapshot.

## BOOTSTRAP, FREYJA, and other operators

These are **Aran-side services**. They run alongside Aran and call the
lake the same way Aran does:

### BOOTSTRAP (wrapper generation)

For a freshly-uploaded dataset:

1. BOOTSTRAP fetches the snapshot's metadata and (optionally) runs a few
   `SELECT … LIMIT 100` queries via `/api/query` to sniff the schema.
2. BOOTSTRAP generates the wrapper SQL — typically a flattening and
   typing pass over the raw structure (good for nested JSON, Excel with
   header weirdness, etc.) — and stores it in Aran's catalog.

The wrapper is **not** materialized into the lake. It is SQL Aran composes
into its own queries; where Aran does want a persisted artifact (a data
product), its own `materialize-oql` operator produces it, running DuckDB
in the Aran JVM. The lake had a materialize endpoint for this in v0.2 and
it was deleted on 2026-08-27, unused — see ADR 006.

### FREYJA (column profiling)

1. FREYJA runs profile queries against the snapshot — or against a
   wrapper's resolved output — via `/api/query`. For example:
   `SELECT count(*), count(DISTINCT col_x), min(col_x), max(col_x), ... FROM snapshot`
   composed across columns.
2. FREYJA writes the profile JSON wherever Aran keeps wrapper-side
   metadata (most naturally as a sibling row to the wrapper in Aran's
   catalog).

The lake has no profile concept in v0.2. There's no `/api/profile`
endpoint, no `profile` table, no `profile_blob` storage prefix.

### Future operators

ALIGN-prep, data products, retention scans — same pattern. Each lives
Aran-side. Whatever they need from the lake is one of: a SQL query
(`/api/query`), an import (`/api/imports`), or raw bytes
(`/api/snapshots/{id}/download`).

## Consuming Arrow IPC results

### Python (pyarrow, pandas, Polars)

```python
import io
import httpx
import pyarrow.ipc as ipc

resp = httpx.post(
    "http://aran-lake:8080/api/query",
    json={"snapshot_id": str(snapshot_id), "sql": "SELECT ..."},
    headers={
        "Authorization": f"Bearer {secret}",
        "X-Tenant-Id": str(tenant_id),
        "Accept": "application/vnd.apache.arrow.stream",
    },
    timeout=60.0,
)
resp.raise_for_status()

# Arrow IPC → pyarrow.Table (zero-copy on the network buffer)
table = ipc.open_stream(io.BytesIO(resp.content)).read_all()

# Then either:
df = table.to_pandas()                  # pandas
# import polars as pl; pl_df = pl.from_arrow(table)   # Polars
# numpy arrays per column:
ids = table.column("id").to_numpy()
```

The Python reference client (`examples/aran_lake_client.py`) wraps all
this in a small synchronous SDK; copy it as a starting point.

### Java / Spring Boot (arrow-vector)

```java
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.VectorSchemaRoot;

// 1. POST /api/query with Accept: application/vnd.apache.arrow.stream
HttpRequest req = HttpRequest.newBuilder()
    .uri(URI.create(endpoint + "/api/query"))
    .header("Authorization", "Bearer " + secret)
    .header("X-Tenant-Id", tenantId.toString())
    .header("Accept", "application/vnd.apache.arrow.stream")
    .POST(BodyPublishers.ofString(jsonBody))
    .build();
HttpResponse<byte[]> resp = httpClient.send(req, BodyHandlers.ofByteArray());

// 2. Read the IPC stream into a VectorSchemaRoot
try (RootAllocator alloc = new RootAllocator();
     ArrowStreamReader reader = new ArrowStreamReader(
         new ByteArrayInputStream(resp.body()), alloc)) {

    VectorSchemaRoot root = reader.getVectorSchemaRoot();
    while (reader.loadNextBatch()) {
        // Iterate rows via root.getVector("col").getObject(i),
        // or stream to your domain model.
        int n = root.getRowCount();
        for (int i = 0; i < n; i++) {
            Object id = root.getVector("id").getObject(i);
            Object region = root.getVector("region").getObject(i);
            // ...
        }
    }
}
```

The dependency you need is `org.apache.arrow:arrow-vector` and
`org.apache.arrow:arrow-memory-netty` (or `arrow-memory-unsafe`).

### Raw-bytes path (when SQL isn't enough)

When you want to stream the parquet itself (export, third-party engine,
column-wise scan with custom decoding), call
`GET /api/snapshots/{id}/download`, get back a presigned URL, and
follow it. In Python:

```python
url = client.download_url(snapshot.id)
# Stream via pyarrow without buffering the whole file in memory:
import pyarrow.parquet as pq
import pyarrow.fs as fs

# pyarrow can read directly from HTTP URLs via the filesystem layer,
# or you can pass the URL to fsspec / urllib for a streaming download.
```

## Recommended polling cadence

Import jobs are async. We don't push status:

* `/api/jobs/{id}` is cheap; poll every 0.5–2 s.
* An import takes seconds for small inputs, minutes for large ones.
  Set your client's overall timeout to a generous multiple of expected
  job duration; the lake's default `query_timeout` does not apply to
  jobs.

## Migration from v0.1 callers

Three breaking changes to be aware of:

1. **`dataset_name` removed** from `SnapshotInitRequest` and
   `ImportRequest`. Strip it from your payloads.
2. **`content_type` is required** on snapshot init — no default to parquet.
3. **Profile endpoints are gone.** Move profile logic to Aran-side; it
   can run as ordinary SQL via `/api/query` against any snapshot.

Auth is backward-compatible: the v0.1 `ARAN_LAKE_SHARED_SECRET` still
works (promoted to caller-id `default`). Set
`ARAN_LAKE_SHARED_SECRETS` to opt into per-caller identification.

"""
Job handlers — one async function per job_type.

The contract: each handler takes a ``Job`` row and a ``Settings`` object,
runs to completion or raises, and returns a dict that becomes the job's
``result`` field. Errors propagate as exceptions; the runner catches them
and marks the job FAILED with the exception message.

Handlers are *pure* with respect to the catalog session — they don't
take a session as a parameter. They open their own session via
:func:`aran_lake.catalog.session.session_scope` because they may need
multiple transactions (e.g. claim, do work, commit result).

Job types
---------

* ``import.from_postgres`` — stream a SELECT from a remote PostgreSQL
  into a parquet snapshot: server-side cursor, batched Arrow record
  batches, incremental ParquetWriter, then hash / size cap / dedup /
  content-addressed upload / snapshot registration.

The v0.1 ``profile.extract`` handler is **removed**. Profile is no longer
a lake concern; Aran runs it via the generic ``/api/query`` endpoint.

The v0.2 ``snapshot.materialize`` handler is **removed** too (2026-08-27,
audit DATA_PRODUCTS-11 / LAKE_SERVICE-1). Aran never called it: the product
materializer is Aran's own ``materialize-oql`` operator, which runs DuckDB
in the Aran JVM. What was left was a second, divergent materializer that ran
caller SQL with the ``/api/query`` safety screen deliberately bypassed, on a
connection holding the bucket-wide S3 credential. Its one useful check — the
statement-boundary refusal — moved into ``_screen_sql`` rather than dying
with it.
"""
from __future__ import annotations

import asyncio
import json
import os
import tempfile
from collections.abc import Awaitable, Callable
from typing import Any
from uuid import UUID

import asyncpg
import pyarrow as pa
import pyarrow.parquet as pq
import structlog

from aran_lake.catalog import repositories as repo
from aran_lake.catalog.models import Job
from aran_lake.catalog.session import session_scope
from aran_lake.config import Settings
from aran_lake.jobs.redaction import REDACTED
from aran_lake.storage.garage import (
    ObjectStore,
    sha256_of_file,
    snapshot_object_key,
)

log = structlog.get_logger(__name__)


# =============================================================================
# import.from_postgres
# =============================================================================
#
# Payload (written by api/imports.py):
#   {
#     "dataset_id": "<uuid>",
#     "source": {
#       "kind": "postgres",
#       "connection_string": "postgresql://user:pass@host:port/db",
#       "query": "SELECT ..."
#     }
#   }
#
# Result:
#   {"snapshot_id": "<uuid>", "row_count": N, ...}
#
# Process:
#   1. Connect with asyncpg; open a server-side cursor over the query.
#   2. Stream rows in batches of 10 000 into pyarrow RecordBatches (Arrow
#      schema inferred from the prepared statement's pg column types).
#   3. Write incrementally with pyarrow.parquet.ParquetWriter to a temp file.
#   4. Register: hash, size cap, dedup, content-addressed upload,
#      create_snapshot (contentType parquet, lineage recorded via
#      derived_via_job_id — NOT format_options; see the V39 note at the
#      create_snapshot call below for why). This step used to be described as
#      "reuse the materialize registration flow"; that handler was deleted on
#      2026-08-27 (audit LAKE_SERVICE-1) and this is the only one left.
#
# Security note — credentials at rest: the connection string arrives in the
# request body and is stored verbatim in the job's ``payload`` row (see
# ``repo.create_job`` in api/imports.py). Encrypting job payloads at rest is
# out of v1 scope, so we minimize exposure instead: once the job finishes
# (success OR failure) the handler's ``finally`` overwrites the stored
# payload's ``connection_string`` with ``"<redacted>"`` in its own session,
# so the secret only lives in the row while the import is actually running.

_IMPORT_BATCH_ROWS = 10_000

# Explicit pg-type → Arrow-type mapping. numeric goes to string (lossless;
# DuckDB casts on read), as do uuid/json/jsonb; bytea keeps its bytes as
# Arrow binary. A type that is not here lands in a string column — see
# ``_arrow_field_for`` for which of those are a designed encoding and which
# are a warning.
_PG_TO_ARROW: dict[str, pa.DataType] = {
    "int2": pa.int64(),
    "int4": pa.int64(),
    "int8": pa.int64(),
    "float4": pa.float64(),
    "float8": pa.float64(),
    "numeric": pa.string(),
    "bool": pa.bool_(),
    "text": pa.string(),
    "varchar": pa.string(),
    "bpchar": pa.string(),
    "name": pa.string(),
    "date": pa.date32(),
    "timestamp": pa.timestamp("us"),
    "timestamptz": pa.timestamp("us", tz="UTC"),
    "uuid": pa.string(),
    "json": pa.string(),
    "jsonb": pa.string(),
    "bytea": pa.binary(),
}

# asyncpg type kinds ("scalar" | "array" | "composite" | "range") whose values
# arrive as structured Python objects. They have no Arrow type we are willing
# to commit to across a whole column, but they are not *unknown* either: the
# converter JSON-encodes them into the string column, which round-trips.
# Only a genuinely unmapped scalar is a warning.
_JSON_ENCODED_KINDS = frozenset({"array", "composite"})


def _arrow_field_for(column_name: str, pg_type_name: str, pg_type_kind: str) -> pa.Field:
    arrow_type = _PG_TO_ARROW.get(pg_type_name)
    if arrow_type is None:
        if pg_type_kind in _JSON_ENCODED_KINDS:
            log.info(
                "import.from_postgres: structured pg type stored as JSON text",
                column=column_name, pg_type=pg_type_name, pg_type_kind=pg_type_kind,
            )
        else:
            # WARNING, not INFO: str() of an unmapped value is a lossy guess,
            # and a snapshot full of Python reprs looks healthy until someone
            # reads it back. The operator gets to see it happen.
            log.warning(
                "import.from_postgres: unmapped pg type, storing str() of each value",
                column=column_name, pg_type=pg_type_name, pg_type_kind=pg_type_kind,
            )
        arrow_type = pa.string()
    return pa.field(column_name, arrow_type)


def _to_string_cell(v: Any) -> str | None:
    """
    Coerce one value bound for a string column.

    Arrays and composite types are **JSON-encoded**, not str()-ed: asyncpg
    hands them back as Python lists/tuples/dicts, and ``str(['a', 'b'])`` is
    ``"['a', 'b']"`` — a Python repr no JSON reader takes back. Everything
    else non-str (uuid, Decimal, Range, and any unmapped scalar) keeps the
    str() fallback.
    """
    if v is None or isinstance(v, str):
        return v
    if isinstance(v, (list, tuple, dict)):
        return json.dumps(v, default=str)
    return str(v)


def _column_converter(arrow_type: pa.DataType):
    """Value converter for one column: coerce values bound for string columns;
    pass everything else (binary, ints, timestamps) through as asyncpg gave it."""
    if pa.types.is_string(arrow_type):
        return _to_string_cell
    return lambda v: v


async def handle_import_from_postgres(job: Job, settings: Settings) -> dict[str, Any]:
    payload = job.payload
    dataset_id = UUID(str(payload["dataset_id"]))
    source = payload["source"]
    connection_string = str(source["connection_string"])
    query = str(source["query"])

    tmp_dir = settings.materialize_temp_dir
    os.makedirs(tmp_dir, exist_ok=True)
    fd, local_path = tempfile.mkstemp(
        prefix=f"import-{job.id}-", suffix=".parquet", dir=tmp_dir,
    )
    os.close(fd)  # mkstemp returned an open fd we don't need

    try:
        row_count = 0
        con = await asyncpg.connect(connection_string, timeout=30)
        try:
            async with con.transaction():
                stmt = await con.prepare(query)
                fields = [
                    _arrow_field_for(attr.name, attr.type.name, attr.type.kind)
                    for attr in stmt.get_attributes()
                ]
                schema = pa.schema(fields)
                converters = [_column_converter(f.type) for f in fields]

                writer = pq.ParquetWriter(local_path, schema)
                try:
                    cursor = await stmt.cursor()
                    while True:
                        rows = await cursor.fetch(_IMPORT_BATCH_ROWS)
                        if not rows:
                            break
                        arrays = [
                            pa.array(
                                [converters[i](r[i]) for r in rows],
                                type=fields[i].type,
                            )
                            for i in range(len(fields))
                        ]
                        batch = pa.RecordBatch.from_arrays(arrays, schema=schema)
                        await asyncio.to_thread(writer.write_batch, batch)
                        row_count += len(rows)
                finally:
                    writer.close()
        finally:
            await con.close()

        # Hash + size from the on-disk file.
        content_hash, size_bytes = await asyncio.to_thread(sha256_of_file, local_path)

        if size_bytes > settings.materialize_max_bytes:
            raise ValueError(
                f"imported output is {size_bytes} bytes, exceeds cap of "
                f"{settings.materialize_max_bytes}"
            )

        async with session_scope() as session:
            await repo.upsert_dataset(
                session, tenant_id=job.tenant_id, dataset_id=dataset_id,
            )

            existing = await repo.find_snapshot_by_hash(
                session,
                tenant_id=job.tenant_id,
                dataset_id=dataset_id,
                content_hash=content_hash,
            )
            if existing is not None:
                log.info(
                    "import: dedup hit, reusing existing snapshot",
                    snapshot_id=str(existing.id),
                )
                return {
                    "snapshot_id": str(existing.id),
                    "content_hash": existing.content_hash,
                    "size_bytes": existing.size_bytes,
                    "row_count": existing.row_count,
                    "deduped": True,
                }

            final_key = snapshot_object_key(job.tenant_id, dataset_id, content_hash)
            store = ObjectStore(settings)
            await store.upload_file(
                local_path=local_path,
                object_key=final_key,
                content_type="application/vnd.apache.parquet",
            )

            snapshot = await repo.create_snapshot(
                session,
                tenant_id=job.tenant_id,
                dataset_id=dataset_id,
                object_key=final_key,
                content_hash=content_hash,
                size_bytes=size_bytes,
                row_count=row_count,
                content_type="application/vnd.apache.parquet",
                # V39: this used to be format_options={"lineage": "imported
                # from postgres"}. duckdb_runner._read_call_for_snapshot
                # spliced every format_options entry into the read_parquet()
                # call as a named argument, so that string became an invalid
                # reader parameter and DuckDB refused every read of an
                # imported snapshot — no profile, no join elimination, ever
                # (V39). A grep at fix time found
                # nothing that ever read the "lineage" key back out of
                # format_options, and derived_via_job_id below already lets
                # Aran follow this snapshot to the job (and from the job,
                # the import) that produced it — so there is no replacement
                # field: lineage was never format_options's job to carry.
                format_options={},
                derived_via_job_id=job.id,
                created_by_caller=job.created_by_caller,
            )

            return {
                "snapshot_id": str(snapshot.id),
                "content_hash": snapshot.content_hash,
                "size_bytes": snapshot.size_bytes,
                "row_count": snapshot.row_count,
                "deduped": False,
            }
    finally:
        try:
            if os.path.exists(local_path):
                os.unlink(local_path)
        except OSError:
            log.warning("could not unlink import scratch file", path=local_path)
        # Redact the connection string in the stored job payload now that the
        # import is over — see the security note above. Own session: the
        # runner's success/failure bookkeeping never touches ``payload``.
        await _redact_import_connection_string(job.tenant_id, job.id)


async def _redact_import_connection_string(tenant_id: UUID, job_id: UUID) -> None:
    """
    Overwrite ``payload.source.connection_string`` with ``"<redacted>"``.

    The belt to the runner's braces since IMPORTS-9: the claim already scrubbed this row
    before the handler started, so this normally rewrites a value that is already
    redacted. It stays because it is the only redaction that runs for a job claimed by
    an older worker, or by any future path that does not go through ``jobs.runner``.
    """
    try:
        async with session_scope() as session:
            row = await repo.get_job(session, tenant_id=tenant_id, job_id=job_id)
            if row is None:
                return
            payload = dict(row.payload or {})
            source = dict(payload.get("source") or {})
            if "connection_string" in source:
                source["connection_string"] = REDACTED
                payload["source"] = source
                row.payload = payload  # reassign: JSON columns need a new object
                await session.flush()
    except Exception:
        log.exception("could not redact import connection string", job_id=str(job_id))


# =============================================================================
# registry
# =============================================================================

HandlerFn = Callable[[Job, Settings], Awaitable[dict[str, Any]]]

HANDLERS: dict[str, HandlerFn] = {
    "import.from_postgres": handle_import_from_postgres,
}

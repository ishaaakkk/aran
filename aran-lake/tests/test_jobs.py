"""
Job-runner lifecycle tests.

Validates the SKIP-LOCKED claim semantics and the success/failure
recording. The runner is started by the app fixture; we just create
jobs and poll until they complete.
"""
from __future__ import annotations

import time
from uuid import UUID, uuid4

import pytest

from tests.conftest import auth_headers


def _wait_for_status(http_client, tenant_id, job_id, terminal_states, timeout_s=60):
    """Poll a job until it reaches one of the terminal states.

    The deadline is generous on purpose: the loop returns the moment a terminal
    state appears, but a gradle+Docker-saturated machine has pushed a real
    import past 10s (2026-08-27, full-gate flake).
    """
    deadline = time.monotonic() + timeout_s
    last = None
    while time.monotonic() < deadline:
        resp = http_client.get(f"/api/jobs/{job_id}", headers=auth_headers(tenant_id))
        if resp.status_code != 200:
            return None
        last = resp.json()
        if last["status"] in terminal_states:
            return last
        time.sleep(0.2)
    return last


@pytest.mark.asyncio
async def test_import_from_postgres_end_to_end(
    http_client, tenant_id, seed_session, database_url,
) -> None:
    """
    Real import: seed a table in the test-container Postgres, then import it
    *from that same database* (the cheapest true end-to-end). The S3 upload is
    mocked (no Garage in the unit suite); everything else — asyncpg cursor
    streaming, Arrow schema mapping, incremental parquet write, hashing,
    snapshot registration — runs for real. Also asserts the stored payload's
    connection string is redacted once the job is terminal.
    """
    from unittest.mock import patch

    import pyarrow.parquet as pq
    from sqlalchemy import text

    table = f"people_{uuid4().hex[:8]}"
    await seed_session.execute(text(
        f"CREATE TABLE public.{table} (id int, name text, h_index int)"
    ))
    await seed_session.execute(text(
        f"INSERT INTO public.{table} VALUES (1, 'ada', 12), (2, 'grace', NULL), (3, 'edsger', 40)"
    ))
    await seed_session.commit()

    # asyncpg wants a plain postgresql:// DSN, not SQLAlchemy's +asyncpg URL.
    dsn = database_url.replace("postgresql+asyncpg://", "postgresql://")

    # Capture the parquet bytes at the (mocked) upload boundary so we can
    # assert on real file contents before the handler deletes its temp file.
    captured: dict[str, object] = {}

    async def _fake_upload_file(self, *, local_path, object_key, content_type):
        captured["table"] = pq.read_table(local_path)
        captured["object_key"] = object_key
        captured["content_type"] = content_type

    dataset_id = str(uuid4())
    with patch("aran_lake.storage.garage.ObjectStore.upload_file", _fake_upload_file):
        resp = http_client.post(
            "/api/imports",
            json={
                "dataset_id": dataset_id,
                "source": {
                    "kind": "postgres",
                    "connection_string": dsn,
                    "query": f"SELECT id, name, h_index FROM public.{table} ORDER BY id",
                },
            },
            headers=auth_headers(tenant_id),
        )
        assert resp.status_code == 202, resp.text
        job_id = resp.json()["job_id"]

        final = _wait_for_status(http_client, tenant_id, job_id, {"SUCCEEDED", "FAILED"})

    assert final is not None
    assert final["status"] == "SUCCEEDED", final
    assert final["result"]["row_count"] == 3
    assert final["result"]["snapshot_id"]
    assert final["created_by_caller"] == "aran"

    # The parquet that reached the upload boundary carries the real rows,
    # typed columns, and the NULL.
    tbl = captured["table"]
    assert tbl.num_rows == 3
    assert tbl.column("name").to_pylist() == ["ada", "grace", "edsger"]
    assert tbl.column("h_index").to_pylist() == [12, None, 40]
    assert captured["content_type"] == "application/vnd.apache.parquet"

    # Credentials never rest in the payload after the job is terminal.
    assert final["payload"]["source"]["connection_string"] == "<redacted>"

    # V39: the snapshot this import creates must not carry a "lineage" key
    # (or anything else) in format_options — that used to poison every
    # subsequent read of an imported snapshot (see test_format_dispatch.py's
    # whitelist tests for the read-side half of this fix).
    from aran_lake.catalog import repositories as repo

    snapshot_row = await repo.get_snapshot(
        seed_session, tenant_id=tenant_id, snapshot_id=UUID(final["result"]["snapshot_id"]),
    )
    assert snapshot_row is not None
    assert snapshot_row.format_options == {}


@pytest.mark.asyncio
async def test_import_preserves_bytea_and_json_encodes_arrays(
    http_client, tenant_id, seed_session, database_url,
) -> None:
    """
    Types outside ``_PG_TO_ARROW`` must not reach parquet as Python reprs.

    Before the fix a ``bytea`` landed as the string ``"b'\\xde\\xad\\xbe\\xef'"``
    and a ``text[]`` as ``"['a', 'b']"`` — snapshots that look fine until someone
    tries to read them back. ``bytea`` now maps to Arrow binary, and arrays and
    composites are JSON-encoded into the string column. ``text[]`` is the
    discriminating case: a Python repr of a list of ints happens to be valid
    JSON, a repr of a list of strings is not.
    """
    import json
    from unittest.mock import patch

    import pyarrow as pa
    import pyarrow.parquet as pq
    from sqlalchemy import text

    table = f"payloads_{uuid4().hex[:8]}"
    await seed_session.execute(text(
        f"CREATE TABLE public.{table} "
        f"(id int, blob bytea, tags int[], labels text[])"
    ))
    await seed_session.execute(text(
        f"INSERT INTO public.{table} VALUES "
        f"(1, '\\xdeadbeef'::bytea, ARRAY[1,2,3], ARRAY['a','b']), "
        f"(2, NULL, NULL, NULL)"
    ))
    await seed_session.commit()

    dsn = database_url.replace("postgresql+asyncpg://", "postgresql://")
    captured: dict[str, object] = {}

    async def _fake_upload_file(self, *, local_path, object_key, content_type):
        captured["table"] = pq.read_table(local_path)

    with patch("aran_lake.storage.garage.ObjectStore.upload_file", _fake_upload_file):
        resp = http_client.post(
            "/api/imports",
            json={
                "dataset_id": str(uuid4()),
                "source": {
                    "kind": "postgres",
                    "connection_string": dsn,
                    "query": (
                        f"SELECT id, blob, tags, labels "
                        f"FROM public.{table} ORDER BY id"
                    ),
                },
            },
            headers=auth_headers(tenant_id),
        )
        assert resp.status_code == 202, resp.text
        job_id = resp.json()["job_id"]
        final = _wait_for_status(http_client, tenant_id, job_id, {"SUCCEEDED", "FAILED"})

    assert final is not None
    assert final["status"] == "SUCCEEDED", final

    tbl = captured["table"]

    # bytea is binary, and the bytes are the bytes.
    assert pa.types.is_binary(tbl.schema.field("blob").type), tbl.schema
    assert tbl.column("blob").to_pylist() == [b"\xde\xad\xbe\xef", None]

    # Arrays arrive as JSON text, parseable back into the original values.
    tags, labels = tbl.column("tags").to_pylist(), tbl.column("labels").to_pylist()
    assert json.loads(tags[0]) == [1, 2, 3]
    assert json.loads(labels[0]) == ["a", "b"]
    # ...which a Python repr would not be: str(['a', 'b']) is not JSON.
    assert "'" not in labels[0], labels[0]
    assert tags[1] is None
    assert labels[1] is None


def test_unmapped_scalar_pg_type_is_a_warning_not_an_info() -> None:
    """
    A str()-ed value is a lossy guess, so the operator has to hear about it —
    but an array is a designed JSON encoding, not a surprise, and must not cry
    wolf on every import that carries one.
    """
    from unittest.mock import patch

    import pyarrow as pa
    from structlog.testing import CapturingLogger

    from aran_lake.jobs import handlers
    from aran_lake.jobs.handlers import _arrow_field_for

    # Substituting the module logger rather than using ``capture_logs``: the app
    # configures structlog with ``cache_logger_on_first_use``, so a logger already
    # bound by an earlier test keeps the processor list it cached and would slip
    # past the capture depending on test order.
    cap = CapturingLogger()
    with patch.object(handlers, "log", cap):
        unknown = _arrow_field_for("location", "point", "scalar")
        array = _arrow_field_for("labels", "text[]", "array")
        known = _arrow_field_for("blob", "bytea", "scalar")

    assert pa.types.is_string(unknown.type)
    assert pa.types.is_string(array.type)
    assert pa.types.is_binary(known.type)

    by_column = {c.kwargs["column"]: c for c in cap.calls}
    assert by_column["location"].method_name == "warning"
    assert by_column["location"].kwargs["pg_type"] == "point"
    assert by_column["labels"].method_name == "info"
    assert "blob" not in by_column  # a mapped type says nothing at all


def test_import_redacts_connection_string_on_failure(http_client, tenant_id) -> None:
    """Even a failed import must not leave the connection string at rest."""
    resp = http_client.post(
        "/api/imports",
        json={
            "dataset_id": str(uuid4()),
            "source": {
                "kind": "postgres",
                "connection_string": "postgresql://user:secret@nonexistent.invalid:5432/db",
                "query": "SELECT 1",
            },
        },
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 202, resp.text
    job_id = resp.json()["job_id"]

    final = _wait_for_status(http_client, tenant_id, job_id, {"SUCCEEDED", "FAILED"})
    assert final is not None
    assert final["status"] == "FAILED"
    assert final["payload"]["source"]["connection_string"] == "<redacted>"


def test_job_poll_never_exposes_the_connection_string(http_client, tenant_id) -> None:
    """
    IMPORTS-9 / JOBS_EVENTS-5: Aran polls this endpoint every 2s from the moment the
    import is enqueued. Redaction used to happen only in the handler's ``finally``, so
    every poll before the job ended served the DSN — password included — and a crash
    mid-import served it forever. The response is now redacted for every status, which
    is what this asserts: the secret appears in no poll, terminal or not.
    """
    secret = "hunter2-never-served"
    resp = http_client.post(
        "/api/imports",
        json={
            "dataset_id": str(uuid4()),
            "source": {
                "kind": "postgres",
                "connection_string": f"postgresql://user:{secret}@nonexistent.invalid:5432/db",
                "query": "SELECT 1",
            },
        },
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 202, resp.text
    job_id = resp.json()["job_id"]

    seen = set()
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        poll = http_client.get(f"/api/jobs/{job_id}", headers=auth_headers(tenant_id))
        assert poll.status_code == 200
        assert secret not in poll.text, poll.text
        body = poll.json()
        assert body["payload"]["source"]["connection_string"] == "<redacted>"
        seen.add(body["status"])
        if body["status"] in {"SUCCEEDED", "FAILED"}:
            break
        time.sleep(0.05)

    assert seen, "the job was never polled"


@pytest.mark.asyncio
async def test_claim_redacts_the_stored_payload_before_the_handler_runs(
    http_client, tenant_id,
) -> None:
    """
    IMPORTS-9, the at-rest half: the credential is lifted out of the stored row when the
    job is claimed, not when it finishes, so a worker that dies mid-import leaves nothing
    behind. The handler still receives the real DSN — in memory only.
    """
    from unittest.mock import patch

    from aran_lake.catalog import repositories as repo
    from aran_lake.catalog.session import session_scope
    from aran_lake.jobs.handlers import HANDLERS

    dsn = "postgresql://user:claim-time-secret@nonexistent.invalid:5432/db"
    observed: dict[str, str] = {}

    async def _spy_handler(job, settings):
        # What the row holds while this job is RUNNING — a separate session, so this is
        # the committed state a crash would leave behind, not the runner's own copy.
        async with session_scope() as session:
            row = await repo.get_job(session, tenant_id=job.tenant_id, job_id=job.id)
            observed["stored"] = row.payload["source"]["connection_string"]
        observed["in_memory"] = job.payload["source"]["connection_string"]
        return {"spied": True}

    with patch.dict(HANDLERS, {"import.from_postgres": _spy_handler}):
        resp = http_client.post(
            "/api/imports",
            json={
                "dataset_id": str(uuid4()),
                "source": {"kind": "postgres", "connection_string": dsn, "query": "SELECT 1"},
            },
            headers=auth_headers(tenant_id),
        )
        assert resp.status_code == 202, resp.text
        final = _wait_for_status(
            http_client, tenant_id, resp.json()["job_id"], {"SUCCEEDED", "FAILED"},
        )

    assert final is not None
    assert final["status"] == "SUCCEEDED", final
    assert observed["stored"] == "<redacted>"
    assert observed["in_memory"] == dsn


def test_import_rejects_a_statement_that_is_not_a_query(http_client, tenant_id) -> None:
    """
    IMPORTS-10: the field documents "SELECT statement" and enforced nothing, so a DELETE
    ran with the caller's own credentials against their database.
    """
    for statement in ("DELETE FROM public.people", "  drop table public.people",
                      "INSERT INTO t VALUES (1)", "-- just a comment"):
        resp = http_client.post(
            "/api/imports",
            json={
                "dataset_id": str(uuid4()),
                "source": {
                    "kind": "postgres",
                    "connection_string": "postgresql://x@y/z",
                    "query": statement,
                },
            },
            headers=auth_headers(tenant_id),
        )
        assert resp.status_code == 400, (statement, resp.text)
        assert "SELECT" in resp.json()["detail"]


def test_import_accepts_a_query_behind_leading_comments(http_client, tenant_id) -> None:
    """The gate reads past whitespace and comments — a commented query is still a query."""
    for statement in ("-- daily pull\nSELECT 1",
                      "/* nightly */ SELECT 1",
                      "/* a /* nested */ comment */\n  WITH t AS (SELECT 1) SELECT * FROM t",
                      "\n\n  select 1"):
        resp = http_client.post(
            "/api/imports",
            json={
                "dataset_id": str(uuid4()),
                "source": {
                    "kind": "postgres",
                    "connection_string": "postgresql://x@y/z",
                    "query": statement,
                },
            },
            headers=auth_headers(tenant_id),
        )
        assert resp.status_code == 202, (statement, resp.text)


def test_get_unknown_job_returns_404(http_client, tenant_id) -> None:
    resp = http_client.get(f"/api/jobs/{uuid4()}", headers=auth_headers(tenant_id))
    assert resp.status_code == 404


def test_cross_tenant_job_lookup_returns_404(http_client) -> None:
    """A job created by tenant A must not be visible to tenant B."""
    tenant_a = uuid4()
    tenant_b = uuid4()
    resp = http_client.post(
        "/api/imports",
        json={
            "dataset_id": str(uuid4()),
            "source": {
                "kind": "postgres",
                "connection_string": "postgresql://x@y/z",
                "query": "SELECT 1",
            },
        },
        headers=auth_headers(tenant_a),
    )
    assert resp.status_code == 202
    job_id = resp.json()["job_id"]

    resp_b = http_client.get(f"/api/jobs/{job_id}", headers=auth_headers(tenant_b))
    assert resp_b.status_code == 404


def test_import_records_freyja_caller_when_freyja_calls(http_client, tenant_id) -> None:
    """If FREYJA's bearer is used, the job's caller-id is recorded as 'freyja'."""
    resp = http_client.post(
        "/api/imports",
        json={
            "dataset_id": str(uuid4()),
            "source": {
                "kind": "postgres",
                "connection_string": "postgresql://x@y/z",
                "query": "SELECT 1",
            },
        },
        headers=auth_headers(tenant_id, caller="freyja"),
    )
    assert resp.status_code == 202, resp.text
    job_id = resp.json()["job_id"]
    final = _wait_for_status(http_client, tenant_id, job_id, {"SUCCEEDED", "FAILED"})
    assert final is not None
    assert final["created_by_caller"] == "freyja"

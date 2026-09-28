"""
Query endpoint.

POST /api/query — execute SQL against a snapshot, return Arrow IPC.

Semantics:
  * Synchronous (the request blocks until the query finishes or times out).
  * Snapshot is identified by ``snapshot_id``; bound as the view name
    ``snapshot`` inside the query.
  * View binding is format-aware: parquet, CSV, NDJSON, Excel all work,
    using the snapshot's ``content_type`` and ``format_options`` to pick
    the right DuckDB reader.
  * Result is Arrow IPC stream — the language-neutral, columnar wire
    format that pyarrow, Polars, DuckDB, and Java/Arrow can all consume.
  * Hard row cap from ``settings.max_query_rows``. If hit, the response
    carries ``X-Aran-Lake-Row-Limit-Hit: true``.

v0.2 changes vs v0.1
--------------------

* Format-aware view binding (handled by the runner).
* Caller-id propagated into logs (no behavioural change at request time).
* Returns ``400`` with a clear message if the snapshot's content_type
  isn't supported by the runner.
"""
from __future__ import annotations

import asyncio
import json as _json
from typing import Annotated
from uuid import UUID

import duckdb
import structlog
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import Response
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from aran_lake.auth.bearer import CallerIdentity, require_bearer
from aran_lake.catalog import repositories as repo
from aran_lake.catalog.session import get_session
from aran_lake.config import Settings, get_settings
from aran_lake.query.duckdb_runner import (
    QueryRejected,
    UnsupportedFormat,
    arrow_table_to_ipc,
    row_limit_hit,
    run_sql_against_snapshot,
)
from aran_lake.tenancy.enforcement import require_tenant

log = structlog.get_logger(__name__)

router = APIRouter(prefix="/api", tags=["query"])


_ARROW_MIME = "application/vnd.apache.arrow.stream"
_JSON_MIME = "application/json"


class QueryRequest(BaseModel):
    snapshot_id: UUID
    sql: str = Field(..., min_length=1, max_length=64 * 1024)
    row_limit: int | None = Field(default=None, ge=1, le=10_000_000,
                                  description="Override the server default; cannot exceed server max")


@router.post("/query")
async def execute_query(
    body: QueryRequest,
    caller: Annotated[CallerIdentity, Depends(require_bearer)],
    tenant_id: Annotated[UUID, Depends(require_tenant)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
    accept: str | None = None,
) -> Response:
    """
    Execute SQL against a snapshot.

    Default response is Arrow IPC stream. Pass ``Accept: application/json``
    for a JSON-shaped response (small results only — JSON serialization
    loses type fidelity and is much slower).
    """
    snapshot = await repo.get_snapshot(
        session, tenant_id=tenant_id, snapshot_id=body.snapshot_id,
    )
    if snapshot is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="snapshot not found",
        )

    log.info(
        "query",
        caller_id=caller.caller_id,
        tenant_id=str(tenant_id),
        snapshot_id=str(body.snapshot_id),
        content_type=snapshot.content_type,
    )

    effective_limit = (
        min(body.row_limit, settings.max_query_rows)
        if body.row_limit is not None
        else settings.max_query_rows
    )

    try:
        table = await asyncio.wait_for(
            run_sql_against_snapshot(
                snapshot=snapshot,
                sql=body.sql,
                settings=settings,
                row_limit=effective_limit,
            ),
            timeout=settings.query_timeout_seconds,
        )
    except QueryRejected as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=str(e),
        ) from e
    except UnsupportedFormat as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"snapshot format not queryable: {e}",
        ) from e
    except TimeoutError as e:
        raise HTTPException(
            status_code=status.HTTP_408_REQUEST_TIMEOUT,
            detail=f"query exceeded {settings.query_timeout_seconds}s timeout",
        ) from e
    except duckdb.Error as e:
        # DuckDB parse/bind errors mean the caller's SQL is wrong — a client
        # error, and the engine message is what they need to fix it.
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"query failed: {type(e).__name__}: {e}",
        ) from e
    except Exception as e:
        # Anything else (object store down, bugs) is ours, not the caller's:
        # 500, generic detail, full traceback in the logs only.
        log.exception("query execution failed", snapshot_id=str(snapshot.id))
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="internal error while executing query",
        ) from e

    headers = {
        "X-Aran-Lake-Row-Count": str(table.num_rows),
        "X-Aran-Lake-Row-Limit-Hit": "true" if row_limit_hit(table) else "false",
    }

    wants_json = accept is not None and _JSON_MIME in accept.lower()
    if wants_json:
        return Response(
            content=_table_to_json_bytes(table),
            media_type=_JSON_MIME,
            headers=headers,
        )

    return Response(
        content=arrow_table_to_ipc(table),
        media_type=_ARROW_MIME,
        headers=headers,
    )


def _table_to_json_bytes(table) -> bytes:
    rows = table.to_pylist()
    schema = [{"name": f.name, "type": str(f.type)} for f in table.schema]
    return _json.dumps({"schema": schema, "rows": rows}, default=str).encode("utf-8")

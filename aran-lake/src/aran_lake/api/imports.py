"""
Imports endpoint — async, job-based.

POST /api/imports   enqueue an import from a remote source

The handler (``jobs.handlers.handle_import_from_postgres``) streams the
query result into a parquet snapshot. When a new source type lands, it's
a new ``job_type`` (e.g. ``import.from_s3``, ``import.from_mysql``) —
routing is by job_type in the handler registry.

v0.2 changes vs v0.1
--------------------

* ``dataset_name`` removed from the request — Aran owns the name.
* Caller-id from bearer is now recorded on the resulting job for audit.
"""
from __future__ import annotations

import re
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from aran_lake.auth.bearer import CallerIdentity, require_bearer
from aran_lake.catalog import repositories as repo
from aran_lake.catalog.session import get_session
from aran_lake.tenancy.enforcement import require_actor, require_tenant

router = APIRouter(prefix="/api", tags=["imports"])

#: What a query may begin with, once whitespace and comments are out of the way.
#: ``\b`` so ``selective_thing`` is not a SELECT.
_QUERY_START = re.compile(r"(select|with)\b", re.IGNORECASE)


def _without_leading_comments(sql: str) -> str:
    """
    ``sql`` from its first token onwards — leading whitespace, ``--`` line comments and
    (nestable) ``/* */`` block comments removed.

    A prefix check that does not read past a comment is not a check: ``-- x\\nDELETE …``
    would sail through one, and a generated query routinely opens with a comment.
    """
    i, n = 0, len(sql)
    while i < n:
        if sql[i].isspace():
            i += 1
        elif sql.startswith("--", i):
            newline = sql.find("\n", i)
            i = n if newline < 0 else newline + 1
        elif sql.startswith("/*", i):
            depth = 0
            while i < n:
                if sql.startswith("/*", i):
                    depth += 1
                    i += 2
                elif sql.startswith("*/", i):
                    depth -= 1
                    i += 2
                    if depth == 0:
                        break
                else:
                    i += 1
        else:
            break
    return sql[i:]


class PostgresSource(BaseModel):
    kind: Literal["postgres"] = "postgres"
    connection_string: str = Field(...,
        description="postgresql:// URL. Credentials should be embedded; the lake "
                    "does not store them long-term.")
    query: str = Field(..., min_length=1,
        description="SELECT (or WITH … SELECT) statement to materialize as a snapshot; "
                    "anything else is refused with 400")


class ImportRequest(BaseModel):
    dataset_id: UUID
    source: PostgresSource


class ImportJobResponse(BaseModel):
    job_id: UUID
    status: str
    poll_url: str
    note: str | None = None


@router.post(
    "/imports",
    response_model=ImportJobResponse,
    status_code=status.HTTP_202_ACCEPTED,
    dependencies=[Depends(require_bearer)],
)
async def create_import(
    body: ImportRequest,
    caller: Annotated[CallerIdentity, Depends(require_bearer)],
    tenant_id: Annotated[UUID, Depends(require_tenant)],
    actor_id: Annotated[UUID | None, Depends(require_actor)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ImportJobResponse:
    """
    Enqueue an async import. Returns a job_id; poll for status. On success,
    ``result.snapshot_id`` will reference the new snapshot.
    """
    # IMPORTS-10: the field has always documented "SELECT statement" and enforced
    # nothing, so an import could just as well run a DELETE — with the caller's own
    # credentials, against the caller's own database, and with the lake reporting it as
    # a failed import. Checked before the dataset row is upserted so a refused request
    # leaves nothing behind. (A second statement after a ``;`` is refused further down
    # by asyncpg, which will not prepare multiple commands.)
    if not _QUERY_START.match(_without_leading_comments(body.source.query)):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="source.query must be a read-only query: it has to start with "
                   "SELECT or WITH.",
        )

    await repo.upsert_dataset(
        session, tenant_id=tenant_id, dataset_id=body.dataset_id,
    )

    if body.source.kind == "postgres":
        job_type = "import.from_postgres"
    else:  # pragma: no cover - Literal narrows this
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"unknown source kind: {body.source.kind}",
        )

    job = await repo.create_job(
        session,
        tenant_id=tenant_id,
        job_type=job_type,
        payload=body.model_dump(mode="json"),
        created_by=actor_id,
        created_by_caller=caller.caller_id,
    )

    return ImportJobResponse(
        job_id=job.id,
        status=job.status,
        poll_url=f"/api/jobs/{job.id}",
    )

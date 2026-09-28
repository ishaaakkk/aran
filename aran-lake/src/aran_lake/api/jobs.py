"""
Jobs endpoint.

GET /api/jobs/{job_id}   poll job status

The other endpoints (snapshots/profile/imports) create jobs;
this endpoint is the read side. Status machine:

  PENDING  → RUNNING → SUCCEEDED   (result available in ``result`` field)
                     → FAILED      (error_message populated)

Polling cadence is the client's choice. We don't push (no SSE, no
WebSockets, no webhooks in v0.1) — short polls every few seconds are
adequate for the job durations we expect.
"""
from __future__ import annotations

from datetime import datetime
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, field_validator
from sqlalchemy.ext.asyncio import AsyncSession

from aran_lake.auth.bearer import require_bearer
from aran_lake.catalog import repositories as repo
from aran_lake.catalog.session import get_session
from aran_lake.jobs.redaction import redacted
from aran_lake.tenancy.enforcement import require_tenant

router = APIRouter(
    prefix="/api",
    tags=["jobs"],
    dependencies=[Depends(require_bearer)],
)


class JobResponse(BaseModel):
    id: UUID
    tenant_id: UUID
    job_type: str
    status: str
    payload: dict[str, Any]
    result: dict[str, Any] | None
    error_message: str | None
    created_at: datetime
    started_at: datetime | None
    finished_at: datetime | None
    created_by_caller: str | None

    @field_validator("payload", "result")
    @classmethod
    def _redact_credentials(cls, value: Any) -> Any:
        """
        IMPORTS-9 / JOBS_EVENTS-5: an import payload carries the source DSN, password
        included, and Aran polls this endpoint every 2s from the moment the job is
        enqueued. Redaction used to live only in the handler's ``finally``, so every
        poll before the job ended served the credential and a crash served it forever.

        Enforced on the response model rather than at the one call site below: the
        redaction is a property of what this endpoint may emit, not a courtesy the
        caller can forget. The stored row is scrubbed at claim time as well (see
        ``jobs.runner``) — this is the layer that holds for the window before that.
        """
        return redacted(value)


@router.get("/jobs/{job_id}", response_model=JobResponse)
async def get_job(
    job_id: UUID,
    tenant_id: Annotated[UUID, Depends(require_tenant)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> JobResponse:
    job = await repo.get_job(session, tenant_id=tenant_id, job_id=job_id)
    if job is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="job not found")
    return JobResponse(
        id=job.id,
        tenant_id=job.tenant_id,
        job_type=job.job_type,
        status=job.status,
        payload=job.payload,
        result=job.result,
        error_message=job.error_message,
        created_at=job.created_at,
        started_at=job.started_at,
        finished_at=job.finished_at,
        created_by_caller=job.created_by_caller,
    )

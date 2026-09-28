"""Liveness / readiness probes."""
from __future__ import annotations

from fastapi import APIRouter, status
from pydantic import BaseModel
from sqlalchemy import text

from aran_lake.catalog.session import get_session_factory

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: str
    version: str
    database: str


@router.get("/health", status_code=status.HTTP_200_OK)
async def health() -> HealthResponse:
    """
    Liveness + DB-reachability probe.

    Deliberately *not* under ``/api`` and *not* auth-gated — the container
    healthcheck and orchestrator probes must be able to reach it.
    """
    from aran_lake import __version__
    db_status = "ok"
    try:
        factory = get_session_factory()
        async with factory() as session:
            await session.execute(text("SELECT 1"))
    except Exception as e:
        db_status = f"error: {type(e).__name__}"
    return HealthResponse(status="ok", version=__version__, database=db_status)

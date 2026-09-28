"""
FastAPI application factory.

Lifespan responsibilities:

* Initialize the async DB engine (sized for the process).
* Start the in-process job worker.
* On shutdown: stop the worker (drain in-flight jobs cleanly), dispose
  the engine.

Tests construct the app via :func:`create_app` with overridden settings
(notably the database URL, pointing at a Testcontainers Postgres).
Production starts via uvicorn pointed at ``aran_lake.main:app``.
"""
from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from aran_lake import __version__
from aran_lake.api import health, imports, jobs, query, snapshots, storage
from aran_lake.catalog.session import dispose_engine, init_engine
from aran_lake.config import Settings, get_settings
from aran_lake.jobs.runner import JobWorker


def _configure_logging(settings: Settings) -> None:
    """JSON-structured logs that integrate with whatever log pipeline ships them."""
    level = getattr(logging, settings.log_level, logging.INFO)
    logging.basicConfig(level=level, format="%(message)s")
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(level),
        cache_logger_on_first_use=True,
    )


def create_app(settings: Settings | None = None) -> FastAPI:
    """
    Build the application.

    ``settings`` is parameterised so tests can construct fully-configured
    apps without touching the environment.
    """
    s = settings or get_settings()
    _configure_logging(s)
    log = structlog.get_logger(__name__)

    worker = JobWorker(s)

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        log.info("starting aran-lake", version=__version__, profile=s.profile.value)
        init_engine(s.database_url)
        await worker.start()
        try:
            yield
        finally:
            log.info("stopping aran-lake")
            await worker.stop()
            await dispose_engine()

    app = FastAPI(
        title="aran-lake",
        version=__version__,
        description=(
            "Aran data-lake service. Polyglot-friendly HTTP API in "
            "front of DuckDB query execution over Garage / S3 object storage."
        ),
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    # --- routers ---
    app.include_router(health.router)
    app.include_router(snapshots.router)
    app.include_router(query.router)
    app.include_router(imports.router)
    app.include_router(jobs.router)
    app.include_router(storage.router)

    # --- catch-all exception handler ---
    @app.exception_handler(Exception)
    async def _unhandled(_request: Request, exc: Exception) -> JSONResponse:
        # In v0.1 we surface the exception type for developer ergonomics.
        # Production should sanitize this to "internal error" + a request id.
        log.exception("unhandled exception", exc_type=type(exc).__name__)
        return JSONResponse(
            status_code=500,
            content={"detail": f"internal error: {type(exc).__name__}"},
        )

    return app


# Uvicorn entry point — referenced from the Dockerfile and docker-compose.
app: FastAPI = create_app()

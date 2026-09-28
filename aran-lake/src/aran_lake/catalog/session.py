"""
Async DB session lifecycle.

One engine per process (created at app startup), one session per request.
Sessions are injected into request handlers via FastAPI's dependency system.

The engine + session_factory live as module-level state because FastAPI's
DI container is per-request — we want the connection pool to be shared
across requests. ``init_engine`` is called once from the lifespan; tests
override via ``override_engine_for_tests``.
"""
from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

_engine: AsyncEngine | None = None
_session_factory: async_sessionmaker[AsyncSession] | None = None


def init_engine(database_url: str, *, echo: bool = False) -> None:
    """
    Create the process-wide async engine and session factory.
    Idempotent — safe to call multiple times in tests; the second call
    disposes the first engine.
    """
    global _engine, _session_factory
    if _engine is not None:
        # Dispose synchronously is impossible; rely on caller to await
        # ``dispose_engine`` first. This is the test reset path.
        raise RuntimeError(
            "engine already initialized; call dispose_engine() first"
        )
    _engine = create_async_engine(
        database_url,
        echo=echo,
        pool_pre_ping=True,
        pool_size=10,
        max_overflow=10,
    )
    _session_factory = async_sessionmaker(
        _engine,
        expire_on_commit=False,
        class_=AsyncSession,
    )


async def dispose_engine() -> None:
    """Release pooled connections. Called from the lifespan shutdown."""
    global _engine, _session_factory
    if _engine is not None:
        await _engine.dispose()
    _engine = None
    _session_factory = None


def get_session_factory() -> async_sessionmaker[AsyncSession]:
    if _session_factory is None:
        raise RuntimeError("session factory not initialized")
    return _session_factory


@asynccontextmanager
async def session_scope() -> AsyncIterator[AsyncSession]:
    """
    Context-managed session for use outside the FastAPI request lifecycle —
    notably the background job runner.

    Commits on exit; rolls back on exception. Closes either way.
    """
    factory = get_session_factory()
    async with factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


async def get_session() -> AsyncIterator[AsyncSession]:
    """
    FastAPI dependency: per-request session, auto-committed on success
    and auto-rolled-back on exception (mirroring ``session_scope``).
    """
    factory = get_session_factory()
    async with factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise

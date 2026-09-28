"""
Pytest fixtures for the lake's test suite.

We use testcontainers-python to spin up Postgres in a Docker container
for the integration tests. Garage is *not* spun up — we mock the
``ObjectStore`` for unit tests, and the Garage integration is covered
end-to-end via the docker-compose dev stack rather than the test suite.

v0.2 changes:
  * ``test_settings`` configures the auth using the v0.2 multi-secret
    form by default (one entry under caller-id "aran"); the legacy
    single-secret form is also covered in ``test_auth.py``.
  * Helpers default to the "aran" caller for tests that don't care which
    caller they are.
"""
from __future__ import annotations

import json
import os
from collections.abc import AsyncIterator, Iterator
from pathlib import Path
from uuid import uuid4

import pytest
import pytest_asyncio
from alembic import command
from alembic.config import Config as AlembicConfig
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.pool import NullPool
from testcontainers.postgres import PostgresContainer

from aran_lake.catalog.session import (
    dispose_engine,
    get_session_factory,
    init_engine,
)
from aran_lake.config import Profile, Settings, get_settings

REPO_ROOT = Path(__file__).resolve().parent.parent

# Canonical test secrets — keep these in sync with the headers helpers.
TEST_SECRETS = {
    "aran":   "test-secret-aran",
    "freyja": "test-secret-freyja",
}
DEFAULT_TEST_CALLER = "aran"


# --- Environment, session-scoped ------------------------------------------

@pytest.fixture(scope="session", autouse=True)
def _import_main_with_valid_env() -> None:
    """
    Import ``aran_lake.main`` once, under an environment its import can survive.

    ``main`` builds the uvicorn entry-point app at module scope
    (``app = create_app()``), which constructs ``Settings()`` from the
    environment. Nothing can intercept a call that happens at import time — not
    a fixture, not a dependency override — so the environment has to be valid at
    that moment. We set it, force the import (later imports hit the sys.modules
    cache), then put the environment back: ``test_auth`` asserts on what
    ``Settings()`` does when secrets are *absent*, so leaving these behind would
    quietly invalidate it.

    Tests get their real settings from ``http_client``'s dependency overrides;
    the entry-point app built here is never exercised.
    """
    seeded = {
        "ARAN_LAKE_DATABASE_URL":    "postgresql+asyncpg://unused:unused@localhost/unused",
        "ARAN_LAKE_S3_ENDPOINT_URL": "http://localhost:3900",
        "ARAN_LAKE_S3_REGION":       "garage",
        "ARAN_LAKE_S3_BUCKET":       "aran-lake-test",
        "ARAN_LAKE_S3_ACCESS_KEY":   "test-access",
        "ARAN_LAKE_S3_SECRET_KEY":   "test-secret",
        "ARAN_LAKE_SHARED_SECRETS":  json.dumps(TEST_SECRETS),
        "ARAN_LAKE_PROFILE":         "dev",
    }
    previous = {k: os.environ.get(k) for k in seeded}
    os.environ.update(seeded)
    try:
        import aran_lake.main  # noqa: F401 — module-level create_app() runs here
    finally:
        for key, was in previous.items():
            if was is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = was
        get_settings.cache_clear()


# --- Postgres container, session-scoped (slow to start, fast to reuse) ----

# Written by the session setup when the sandbox can't do the
# bridge networking testcontainers needs;
# its presence means a Postgres is already listening and testcontainers
# should not try to start its own.
CONTAINER_INFO_PATH = REPO_ROOT / ".container-info.json"


def _use_preexisting_container() -> bool:
    return CONTAINER_INFO_PATH.exists()


def _preexisting_database_url(driver: str = "asyncpg") -> str:
    info = json.loads(CONTAINER_INFO_PATH.read_text())
    user, password = info["user"], info["password"]
    host, port, dbname = info["host"], info["port"], info["dbname"]
    return f"postgresql+{driver}://{user}:{password}@{host}:{port}/{dbname}"


@pytest.fixture(scope="session")
def database_url() -> Iterator[str]:
    """A live Postgres connection URL for the duration of the test session."""
    if _use_preexisting_container():
        yield _preexisting_database_url()
        return

    with PostgresContainer(
        image="postgres:17-alpine",
        username="aran_lake",
        password="aran_lake_dev",
        dbname="aran_lake",
    ) as pg:
        # testcontainers gives us a psycopg-flavoured URL; we want asyncpg.
        raw = pg.get_connection_url()
        yield raw.replace("postgresql+psycopg2://", "postgresql+asyncpg://").replace(
            "postgresql://", "postgresql+asyncpg://"
        )


@pytest.fixture(scope="session", autouse=True)
def _run_migrations(database_url: str) -> None:
    """Apply alembic migrations once per session, before any test runs."""
    cfg = AlembicConfig(str(REPO_ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(REPO_ROOT / "alembic"))
    os.environ["ARAN_LAKE_DATABASE_URL"] = database_url
    command.upgrade(cfg, "head")


# --- Settings + engine, per-test wiring -----------------------------------

@pytest.fixture
def test_settings(database_url: str) -> Settings:
    """A Settings instance configured for the test container, with v0.2 multi-secret auth."""
    get_settings.cache_clear()
    return Settings(
        database_url=database_url,
        s3_endpoint_url="http://localhost:3900",
        s3_region="garage",
        s3_bucket="aran-lake-test",
        s3_access_key="test-access",       # type: ignore[arg-type]
        s3_secret_key="test-secret",       # type: ignore[arg-type]
        # v0.2 multi-secret form. Field alias is ARAN_LAKE_SHARED_SECRETS
        # but the field name is shared_secrets_json — pass via the field name.
        shared_secrets_json=json.dumps(TEST_SECRETS),
        profile=Profile.DEV,
    )


@pytest_asyncio.fixture
async def db_engine(test_settings: Settings) -> AsyncIterator[None]:
    """Initialize the process-wide async engine for one test, dispose after."""
    init_engine(test_settings.database_url)
    try:
        yield
    finally:
        await dispose_engine()


@pytest_asyncio.fixture
async def db_session(db_engine: None) -> AsyncIterator[AsyncSession]:
    """
    A scoped session for repository-level tests.

    Uses the process-wide engine, so it cannot be combined with ``http_client``
    — the app's lifespan initializes that same engine and would collide. Tests
    that need to seed rows *and* call the API want ``seed_session``.
    """
    factory = get_session_factory()
    async with factory() as session:
        yield session


@pytest_asyncio.fixture
async def seed_session(database_url: str) -> AsyncIterator[AsyncSession]:
    """
    A session on a private engine, for tests that seed rows and then call the
    API through ``http_client``.

    Such a test spans two event loops: ``TestClient`` drives the app on
    Starlette's portal loop, while the test body runs on pytest-asyncio's. An
    asyncpg connection belongs to the loop that opened it, so the two sides must
    not share an engine — the app keeps the process-wide one (its lifespan owns
    that lifecycle), and seeding gets this one, opened and disposed on the test's
    own loop. NullPool keeps it to a single connection with nothing pooled behind
    it.
    """
    engine = create_async_engine(database_url, poolclass=NullPool)
    factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
    try:
        async with factory() as session:
            yield session
    finally:
        await engine.dispose()


# --- A clean tenant per test, so tests don't see each other's data --------

@pytest.fixture
def tenant_id():
    return uuid4()


# --- HTTP test client for endpoint-level tests ----------------------------

@pytest.fixture
def http_client(test_settings: Settings) -> Iterator[TestClient]:
    """
    Build a FastAPI app with the test settings and wrap it in TestClient.

    TestClient handles the lifespan (init engine, start worker) by
    triggering an event loop transition. The worker is real — tests that
    create jobs can poll for completion.

    Settings reach the request handlers through FastAPI's dependency_overrides,
    not by monkey-patching ``aran_lake.config.get_settings``. Patching the module
    attribute cannot work: the routers and ``auth.bearer`` bind ``get_settings``
    by name at import time, so they keep calling the original, which builds a
    ``Settings()`` from a bare environment and dies on the required S3 fields.
    """
    from aran_lake.auth.bearer import _settings_dep
    from aran_lake.main import create_app

    app = create_app(test_settings)
    app.dependency_overrides[get_settings] = lambda: test_settings
    app.dependency_overrides[_settings_dep] = lambda: test_settings

    with TestClient(app) as client:
        yield client

    app.dependency_overrides.clear()
    get_settings.cache_clear()


# --- Helpers --------------------------------------------------------------

def auth_headers(
    tenant_id,
    *, caller: str = DEFAULT_TEST_CALLER,
) -> dict[str, str]:
    """
    Build a valid Authorization + X-Tenant-Id header pair for ``caller``.
    Defaults to the Aran caller, which is what most tests want.
    """
    secret = TEST_SECRETS[caller]
    return {
        "Authorization": f"Bearer {secret}",
        "X-Tenant-Id": str(tenant_id),
    }

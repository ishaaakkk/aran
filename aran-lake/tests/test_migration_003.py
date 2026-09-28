"""
Alembic migration 003 tests — cleaning polluted ``snapshot.format_options``
(V39, "imported snapshots readable again").

``alembic/versions`` isn't a package (no ``__init__.py``, matching 001/002),
so the migration module is loaded from its file path rather than imported
by dotted name — same trick you'd use for any script alembic itself expects
to load standalone.

Two things are covered here:

* the pure helper ``clean_format_options`` migration 003's ``upgrade()``
  calls per row — exercised directly, no DB, for every format family plus
  the edge cases (unrecognised content_type, ``None`` options, idempotence)
* the migration wired end-to-end through real alembic ``downgrade``/
  ``upgrade`` calls against the Testcontainers Postgres: a row is seeded
  with polluted ``format_options`` while the schema sits at revision 002
  (before this migration exists, so the row lands exactly as a pre-fix
  ``import.from_postgres`` would have left it), then the schema is brought
  back to head and the row is read back to confirm it came out clean.
"""
from __future__ import annotations

import asyncio
import importlib.util
import os
from uuid import UUID, uuid4

from alembic import command
from alembic.config import Config as AlembicConfig
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from tests.conftest import REPO_ROOT

_MIGRATION_PATH = REPO_ROOT / "alembic" / "versions" / "003_clean_snapshot_format_options.py"


def _load_migration_003():
    spec = importlib.util.spec_from_file_location("migration_003", _MIGRATION_PATH)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


migration_003 = _load_migration_003()


# --- clean_format_options: pure helper, no DB ------------------------------

def test_lineage_key_stripped_for_parquet() -> None:
    cleaned = migration_003.clean_format_options(
        "application/vnd.apache.parquet", {"lineage": "imported from postgres"},
    )
    assert cleaned == {}


def test_lineage_key_stripped_for_csv() -> None:
    cleaned = migration_003.clean_format_options(
        "text/csv", {"lineage": "imported from postgres"},
    )
    assert cleaned == {}


def test_legitimate_csv_option_survives_alongside_a_stray_key() -> None:
    """The whitelist must not eat a real reader option just because junk rode along."""
    cleaned = migration_003.clean_format_options(
        "text/csv", {"delim": ";", "lineage": "imported from postgres"},
    )
    assert cleaned == {"delim": ";"}


def test_legitimate_options_survive_untouched() -> None:
    cleaned = migration_003.clean_format_options(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        {"sheet": "Data"},
    )
    assert cleaned == {"sheet": "Data"}


def test_unrecognised_content_type_strips_everything() -> None:
    """No format -> no known reader arguments -> nothing survives the whitelist."""
    cleaned = migration_003.clean_format_options("application/x-arrow", {"anything": "goes"})
    assert cleaned == {}


def test_none_and_empty_options_are_a_noop() -> None:
    assert migration_003.clean_format_options("text/csv", None) == {}
    assert migration_003.clean_format_options("text/csv", {}) == {}


def test_already_clean_options_are_idempotent() -> None:
    once = migration_003.clean_format_options("text/csv", {"delim": ";", "header": True})
    twice = migration_003.clean_format_options("text/csv", once)
    assert once == twice == {"delim": ";", "header": True}


# --- migration wired end-to-end, real Postgres ------------------------------

def test_upgrade_cleans_a_polluted_row(database_url: str) -> None:
    """
    Seed a snapshot the way `import.from_postgres` used to (polluted
    format_options), while the schema is pinned at 002 — before this
    migration exists — then upgrade to head and confirm the row comes out
    clean, proving the migration (not just the helper) does the job.

    Runs the ORM bits in their own short-lived event loops rather than as an
    async test: alembic's env.py drives migrations via `asyncio.run(...)`
    internally, which cannot nest inside an already-running loop.
    """
    from aran_lake.catalog import repositories as repo

    cfg = AlembicConfig(str(REPO_ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(REPO_ROOT / "alembic"))
    os.environ["ARAN_LAKE_DATABASE_URL"] = database_url

    command.downgrade(cfg, "002")

    tenant_id = uuid4()
    dataset_id = uuid4()
    snapshot_id: dict[str, UUID] = {}

    async def _seed_polluted_row() -> None:
        engine = create_async_engine(database_url, poolclass=NullPool)
        factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
        try:
            async with factory() as session:
                await repo.upsert_dataset(session, tenant_id=tenant_id, dataset_id=dataset_id)
                snap = await repo.create_snapshot(
                    session,
                    tenant_id=tenant_id,
                    dataset_id=dataset_id,
                    object_key=f"{tenant_id}/snapshots/{dataset_id}/legacy.parquet",
                    content_hash="d" * 64,
                    size_bytes=10,
                    row_count=1,
                    content_type="application/vnd.apache.parquet",
                    format_options={"lineage": "imported from postgres"},
                )
                await session.commit()
                snapshot_id["value"] = snap.id
        finally:
            await engine.dispose()

    asyncio.run(_seed_polluted_row())

    command.upgrade(cfg, "head")

    async def _read_back() -> dict:
        engine = create_async_engine(database_url, poolclass=NullPool)
        factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
        try:
            async with factory() as session:
                refreshed = await repo.get_snapshot(
                    session, tenant_id=tenant_id, snapshot_id=snapshot_id["value"],
                )
                assert refreshed is not None
                return refreshed.format_options
        finally:
            await engine.dispose()

    assert asyncio.run(_read_back()) == {}


def test_upgrade_is_a_noop_on_an_already_clean_row(database_url: str) -> None:
    """Re-running the cleanup (e.g. a second downgrade/upgrade cycle) must not error or change a clean row."""
    from aran_lake.catalog import repositories as repo

    cfg = AlembicConfig(str(REPO_ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(REPO_ROOT / "alembic"))
    os.environ["ARAN_LAKE_DATABASE_URL"] = database_url

    command.downgrade(cfg, "002")

    tenant_id = uuid4()
    dataset_id = uuid4()
    snapshot_id: dict[str, UUID] = {}

    async def _seed_clean_row() -> None:
        engine = create_async_engine(database_url, poolclass=NullPool)
        factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
        try:
            async with factory() as session:
                await repo.upsert_dataset(session, tenant_id=tenant_id, dataset_id=dataset_id)
                snap = await repo.create_snapshot(
                    session,
                    tenant_id=tenant_id,
                    dataset_id=dataset_id,
                    object_key=f"{tenant_id}/snapshots/{dataset_id}/clean.csv",
                    content_hash="e" * 64,
                    size_bytes=10,
                    row_count=1,
                    content_type="text/csv",
                    format_options={"delim": ";"},
                )
                await session.commit()
                snapshot_id["value"] = snap.id
        finally:
            await engine.dispose()

    asyncio.run(_seed_clean_row())

    command.upgrade(cfg, "head")

    async def _read_back() -> dict:
        engine = create_async_engine(database_url, poolclass=NullPool)
        factory = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
        try:
            async with factory() as session:
                refreshed = await repo.get_snapshot(
                    session, tenant_id=tenant_id, snapshot_id=snapshot_id["value"],
                )
                assert refreshed is not None
                return refreshed.format_options
        finally:
            await engine.dispose()

    assert asyncio.run(_read_back()) == {"delim": ";"}

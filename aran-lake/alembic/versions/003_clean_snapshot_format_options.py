"""clean stray non-reader keys out of snapshot.format_options (V39)

``import.from_postgres`` (``aran_lake/jobs/handlers.py``) used to write
``format_options={"lineage": "imported from postgres"}`` on every snapshot it
produced. ``duckdb_runner._read_call_for_snapshot`` turned *every*
``format_options`` entry into a DuckDB reader's named argument, so that
string became ``read_parquet(uri, lineage = 'imported from postgres')`` —
DuckDB refuses an unrecognised named parameter, so every snapshot an import
ever produced was unreadable: no profile, no join elimination (V39).

The runtime fix (``duckdb_runner._options_to_args``, same commit) now
whitelists the known reader arguments per format and drops anything else
with a WARN, so a stray key can no longer poison a *new* read. This
migration repairs snapshots that were already written with the bad key, so
they become readable again without a re-import: it strips any
``format_options`` entry that is not a known reader argument for the
snapshot's ``content_type``.

The whitelist below is a snapshot of ``duckdb_runner.py``'s at the time this
migration was authored, copied rather than imported — a migration must keep
producing the same result even after the live whitelist grows, so it must
not depend on application code that can change out from under it.

Revision ID: 003
Revises: 002
"""
from __future__ import annotations

from collections.abc import Sequence
from typing import Any

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "003"
down_revision: str | None = "002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# ---- content_type -> format family, mirrors duckdb_runner.py -------------

_PARQUET_TYPES = {
    "application/vnd.apache.parquet",
    "application/x-parquet",
    "application/parquet",
}
_CSV_TYPES = {
    "text/csv",
    "application/csv",
    "text/tab-separated-values",
}
_JSON_TYPES = {
    "application/x-ndjson",
    "application/jsonl",
    "application/json",
}
_EXCEL_TYPES = {
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
}

_PARQUET_READER_OPTIONS = frozenset({
    "binary_as_string", "filename", "file_row_number", "hive_partitioning",
    "union_by_name", "compression",
})
_CSV_READER_OPTIONS = frozenset({
    "header", "delim", "sep", "quote", "escape", "encoding", "columns",
    "all_varchar", "dateformat", "timestampformat", "skip", "null_padding",
    "sample_size", "types", "names", "compression",
})
_JSON_READER_OPTIONS = frozenset({
    "format", "records", "columns", "auto_detect", "sample_size",
    "maximum_object_size", "compression", "dateformat", "timestampformat",
})
_EXCEL_READER_OPTIONS = frozenset({"sheet", "range", "header", "all_varchar"})


def _allowed_keys_for(content_type: str | None) -> frozenset[str]:
    ct = (content_type or "").lower().split(";", 1)[0].strip()
    if ct in _PARQUET_TYPES:
        return _PARQUET_READER_OPTIONS
    if ct in _CSV_TYPES:
        return _CSV_READER_OPTIONS
    if ct in _JSON_TYPES:
        return _JSON_READER_OPTIONS
    if ct in _EXCEL_TYPES:
        return _EXCEL_READER_OPTIONS
    return frozenset()


def clean_format_options(
    content_type: str | None, options: dict[str, Any] | None,
) -> dict[str, Any]:
    """
    Strip any key from ``options`` that is not a known reader argument for
    ``content_type``. Pure function, no DB — unit-tested directly in
    ``tests/test_migration_003.py``, and it's what :func:`upgrade` below
    calls for every polluted row.

    Idempotent: an already-clean dict (or one whose format has no whitelist
    at all, i.e. an unrecognised content_type) maps to itself or to ``{}``,
    both stable under a second application.
    """
    if not options:
        return {}
    allowed = _allowed_keys_for(content_type)
    return {k: v for k, v in options.items() if k.lower() in allowed}


# Ad hoc table description, deliberately not imported from
# aran_lake.catalog.models — a migration's data manipulation must stay
# correct against the schema as it stood at this revision, independent of
# how the live ORM model evolves later.
_snapshot = sa.table(
    "snapshot",
    sa.column("id", postgresql.UUID(as_uuid=True)),
    sa.column("content_type", sa.String()),
    sa.column("format_options", postgresql.JSONB()),
)


def upgrade() -> None:
    # Fetch every row rather than trying to pre-filter "dirty" ones in SQL —
    # the snapshot table is metadata-sized, not data-sized, and a Python-side
    # no-op check (below) is simpler to get right than a jsonb inequality
    # predicate.
    conn = op.get_bind()
    rows = conn.execute(
        sa.select(_snapshot.c.id, _snapshot.c.content_type, _snapshot.c.format_options)
    ).fetchall()

    for row in rows:
        cleaned = clean_format_options(row.content_type, row.format_options)
        if cleaned != (row.format_options or {}):
            conn.execute(
                sa.update(_snapshot)
                .where(_snapshot.c.id == row.id)
                .values(format_options=cleaned)
            )


def downgrade() -> None:
    # One-directional cleanup: the keys this migration strips were never
    # read by anything (see V39 — a grep across the repo at fix time found
    # no consumer), so there is nothing to restore. A downgrade that put
    # `lineage` back would only resurrect the bug, not undo a real change.
    pass

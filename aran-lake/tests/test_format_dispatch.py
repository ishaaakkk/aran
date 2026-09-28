"""
Format-aware view binding tests (v0.2).

These are pure-function tests on the read-call builder in
``duckdb_runner._read_call_for_snapshot``. They don't need S3, Postgres,
or a live DuckDB — they verify that for each supported content_type the
right ``read_*`` function is selected, and ``format_options`` are
rendered correctly into the SQL fragment.

End-to-end format coverage (actually reading parquet/CSV/JSON/Excel from
Garage) is part of the docker-compose integration story rather than the
unit suite.
"""
from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest

from aran_lake.catalog.models import Snapshot
from aran_lake.query.duckdb_runner import (
    UnsupportedFormat,
    _read_call_for_snapshot,
    needs_excel_extension,
)


def _make_snapshot(
    content_type: str, format_options: dict | None = None,
) -> Snapshot:
    """Build a transient (non-persisted) Snapshot instance for testing."""
    return Snapshot(
        id=uuid4(),
        tenant_id=uuid4(),
        dataset_id=uuid4(),
        object_key="t/snapshots/d/h.bin",
        content_hash="h" * 64,
        size_bytes=100,
        row_count=10,
        content_type=content_type,
        format_options=format_options or {},
        derived_from_snapshot_id=None,
        derived_via_job_id=None,
        created_at=datetime.now(UTC),
        created_by=None,
        created_by_caller=None,
    )


# --- parquet ---

def test_parquet_default_options() -> None:
    s = _make_snapshot("application/vnd.apache.parquet")
    call = _read_call_for_snapshot(s, "s3://bucket/key")
    assert call == "read_parquet('s3://bucket/key')"


def test_parquet_aliases() -> None:
    for ct in ("application/x-parquet", "application/parquet"):
        s = _make_snapshot(ct)
        call = _read_call_for_snapshot(s, "s3://b/k")
        assert call.startswith("read_parquet(")


# --- CSV ---

def test_csv_with_header_and_delim() -> None:
    s = _make_snapshot(
        "text/csv", {"header": True, "delim": ";"},
    )
    call = _read_call_for_snapshot(s, "s3://bucket/file.csv")
    assert call.startswith("read_csv_auto(")
    assert "header = TRUE" in call
    assert "delim = ';'" in call


def test_csv_no_options() -> None:
    s = _make_snapshot("text/csv")
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert call == "read_csv_auto('s3://b/k')"


def test_tsv_treated_as_csv() -> None:
    s = _make_snapshot("text/tab-separated-values", {"delim": "\t"})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert "read_csv_auto" in call


def test_csv_none_option_is_skipped() -> None:
    """A None-valued option should be omitted from the SQL (not rendered as NULL=NULL)."""
    s = _make_snapshot("text/csv", {"header": True, "encoding": None})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert "encoding" not in call
    assert "header = TRUE" in call


def test_csv_with_special_characters_in_option_is_escaped() -> None:
    """Single quotes in option values must be SQL-escaped (doubled)."""
    s = _make_snapshot("text/csv", {"quote": "'"})
    call = _read_call_for_snapshot(s, "s3://b/k")
    # Embedded single-quote rendered as '' inside a quoted string
    assert "quote = ''''" in call


# --- JSON / NDJSON ---

def test_ndjson() -> None:
    s = _make_snapshot("application/x-ndjson", {"format": "newline_delimited"})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert call.startswith("read_json(")
    assert "format = 'newline_delimited'" in call


def test_json_array_form() -> None:
    s = _make_snapshot("application/json", {"format": "array"})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert "read_json" in call
    assert "format = 'array'" in call


# --- Excel ---

def test_xlsx() -> None:
    s = _make_snapshot(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        {"sheet": "Data"},
    )
    call = _read_call_for_snapshot(s, "s3://b/k.xlsx")
    assert call.startswith("read_xlsx(")
    assert "sheet = 'Data'" in call


def test_needs_excel_extension() -> None:
    s_xlsx = _make_snapshot(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )
    assert needs_excel_extension(s_xlsx)

    s_parquet = _make_snapshot("application/vnd.apache.parquet")
    assert not needs_excel_extension(s_parquet)


# --- unsupported ---

def test_unsupported_content_type_raises() -> None:
    s = _make_snapshot("application/x-arrow")
    with pytest.raises(UnsupportedFormat):
        _read_call_for_snapshot(s, "s3://b/k")


def test_content_type_with_charset_parameter_is_handled() -> None:
    """A content_type like 'text/csv; charset=utf-8' should still match CSV."""
    s = _make_snapshot("text/csv; charset=utf-8", {"header": True})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert "read_csv_auto" in call


# --- option type rendering ---

def test_integer_option_renders_unquoted() -> None:
    s = _make_snapshot("text/csv", {"skip": 2})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert "skip = 2" in call


def test_boolean_option_renders_as_keyword() -> None:
    s = _make_snapshot("text/csv", {"all_varchar": False})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert "all_varchar = FALSE" in call


# --- reader-argument whitelist (V39) ---
#
# `import.from_postgres` used to write format_options={"lineage": "..."} on
# every snapshot it produced (aran_lake/jobs/handlers.py). Before this
# whitelist, _options_to_args spliced *every* format_options entry into the
# reader call as a named argument, so that string became
# `read_parquet(uri, lineage = 'imported from postgres')` — DuckDB rejects
# unknown named parameters, so the snapshot could never be read (no profile,
# no join elimination). These tests pin the fix: an unrecognised key for the
# format in play is dropped, never rendered into the SQL.

def test_unknown_key_dropped_for_parquet() -> None:
    s = _make_snapshot(
        "application/vnd.apache.parquet",
        {"lineage": "imported from postgres"},
    )
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert call == "read_parquet('s3://b/k')"
    assert "lineage" not in call


def test_unknown_key_dropped_for_csv() -> None:
    s = _make_snapshot("text/csv", {"lineage": "imported from postgres"})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert call == "read_csv_auto('s3://b/k')"
    assert "lineage" not in call


def test_unknown_key_dropped_for_json() -> None:
    s = _make_snapshot("application/x-ndjson", {"lineage": "imported from postgres"})
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert call == "read_json('s3://b/k')"
    assert "lineage" not in call


def test_unknown_key_dropped_for_excel() -> None:
    s = _make_snapshot(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        {"lineage": "imported from postgres"},
    )
    call = _read_call_for_snapshot(s, "s3://b/k.xlsx")
    assert call == "read_xlsx('s3://b/k.xlsx')"
    assert "lineage" not in call


def test_unknown_key_dropped_but_legitimate_option_survives() -> None:
    """A stray unknown key must not shadow a real reader option in the same dict."""
    s = _make_snapshot(
        "text/csv", {"delim": ";", "lineage": "imported from postgres"},
    )
    call = _read_call_for_snapshot(s, "s3://b/k")
    assert "delim = ';'" in call
    assert "lineage" not in call

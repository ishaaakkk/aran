"""
Every DuckDB session the lake opens runs in UTC (decision D3).

What these prove and do not: the value assertions state the contract, and on
a host whose zone is not UTC they fail without the pin (verified on macOS
with the system zone Europe/Madrid — an unpinned connection there reports
``Europe/Madrid``). On a UTC host they pass either way, which is what the
source-level guard below is for: it fails on a *new* connection site that
never reached the helper, on any host.

Executing SQL against a real snapshot needs the object store, which the unit
suite does not have — so the runner's own connection is covered by the guard
rather than by an end-to-end read.
"""
from __future__ import annotations

import re
from pathlib import Path

from aran_lake.query import duckdb_session

# The zone-dependent probe: 12:00+02 is 10:00 UTC, and a naive timestamp
# promoted to TIMESTAMPTZ is interpreted in the session's zone.
_PROBE = (
    "SELECT CAST('2020-06-01 12:00:00+02:00' AS TIMESTAMPTZ)::VARCHAR,"
    " (TIMESTAMP '2020-06-01 12:00:00')::TIMESTAMPTZ::VARCHAR"
)


def test_connect_reports_utc() -> None:
    con = duckdb_session.connect()
    try:
        assert con.execute("SELECT current_setting('TimeZone')").fetchone()[0] == "UTC"
    finally:
        con.close()


def test_zone_dependent_casts_render_as_utc() -> None:
    con = duckdb_session.connect()
    try:
        assert con.execute(_PROBE).fetchone() == (
            "2020-06-01 10:00:00+00",
            "2020-06-01 12:00:00+00",
        )
    finally:
        con.close()


def test_probe_is_zone_sensitive() -> None:
    """The control: without this, the assertions above prove nothing on a UTC host."""
    con = duckdb_session.connect()
    try:
        con.execute("SET TimeZone='Europe/Madrid'")
        assert con.execute(_PROBE).fetchone() == (
            "2020-06-01 12:00:00+02",
            "2020-06-01 12:00:00+02",
        )
    finally:
        con.close()


def test_no_other_lake_module_calls_duckdb_connect() -> None:
    """
    The structural guard. A value assertion cannot notice a new connection
    site, and on a UTC-zoned CI host nothing else would either.
    """
    package = Path(__file__).resolve().parents[1] / "src" / "aran_lake"
    allowed = package / "query" / "duckdb_session.py"
    offenders = [
        str(path.relative_to(package.parent))
        for path in package.rglob("*.py")
        if path != allowed and re.search(r"\bduckdb\.connect\s*\(", path.read_text())
    ]
    assert offenders == [], (
        "these open a DuckDB session outside aran_lake.query.duckdb_session, so it is "
        f"not pinned to UTC: {offenders}"
    )

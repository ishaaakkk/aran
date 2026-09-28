"""
The one place freyja opens a DuckDB connection, so that every session it
opens runs in UTC.

DuckDB resolves ``TIMESTAMPTZ`` casts and ``TIMESTAMP`` → ``TIMESTAMPTZ``
promotion against the session's ``TimeZone`` setting, which defaults to the
host's zone. Unpinned, the same profiling query answers
``2020-06-01 12:00:00+02`` in a sidecar container started on a laptop in
Madrid and ``2020-06-01 10:00:00+00`` in one started in UTC — and a naive
timestamp promoted to ``TIMESTAMPTZ`` is *interpreted* in the session zone,
so the instant itself moves, not only its rendering. Profile evidence
(min/max bounds on a temporal column, distinct counts over a cast) would
then depend on which machine happened to run the sidecar.

This is decision D3's pre-tag line: ``SET
TimeZone='UTC'`` on every DuckDB session — the JVM's sessions
(``edu.upc.dtak.aran.core.util.DuckDbSession``), the lake's
(``aran_lake.query.duckdb_session``) and this one. A process boundary does
not make the zone agree by itself.

The pin happens at connect, before any lockdown: ``scan.py`` disables local
file access on a fresh connection, and a session that has been confined is
no longer a good place to discover a ``SET`` was forgotten.
"""
from __future__ import annotations

import duckdb

#: Executed on every connection. Kept as a constant so a test can name the
#: exact statement rather than a copy of it.
PIN_UTC = "SET TimeZone='UTC'"


def connect(database: str = ":memory:") -> duckdb.DuckDBPyConnection:
    """Open a DuckDB connection pinned to UTC. See the module docstring."""
    con = duckdb.connect(database=database)
    try:
        con.execute(PIN_UTC)
    except BaseException:
        con.close()
        raise
    return con

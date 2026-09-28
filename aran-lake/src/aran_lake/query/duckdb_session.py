"""
The one place the lake opens a DuckDB connection, so that every session it
opens runs in UTC.

DuckDB resolves ``TIMESTAMPTZ`` casts and ``TIMESTAMP`` → ``TIMESTAMPTZ``
promotion against the session's ``TimeZone`` setting, which defaults to the
host's zone. Unpinned, ``/api/query`` returns one instant when the lake
container inherits Europe/Madrid and another when it runs in UTC, for the
same snapshot and the same SQL — and a naive timestamp promoted to
``TIMESTAMPTZ`` is *interpreted* in the session zone, so the instant moves
rather than only its rendering. The rows then differ from what the Aran JVM
computes over the very same object, which is the disagreement this pin
exists to remove.

This is decision D3's pre-tag line: ``SET
TimeZone='UTC'`` on every DuckDB session — the JVM's
(``edu.upc.dtak.aran.core.util.DuckDbSession``), freyja's
(``freyja.executors.duckdb_session``) and this one. A process boundary does
not make the zone agree by itself.
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

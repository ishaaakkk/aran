"""
QueryExecutor protocol.

The single boundary between FREYJA's profiling algorithm and the thing
that runs SQL for it. One implementation lives alongside this module:

* ``LakeQueryExecutor`` — POSTs queries to ``aran-lake``'s
  ``/api/query`` endpoint and parses the Arrow IPC response.

The protocol stays a protocol with one implementation on purpose: the
profiler composes SQL, hands it to the executor, and consumes the result,
knowing nothing about where it ran. A ``LocalDuckDBExecutor`` for research
mode (a folder of CSVs on disk) also lived here and was deleted on
2026-08-27 — nothing imported it, and the standalone workflow it was
written for does not exist in this repository. Re-adding one is a file, not
a redesign, which is the property this boundary is here to keep.

Concurrency note: ``fetch_row``/``fetch_table`` may be called from
multiple threads — the profiler may fan out per-column queries with a
small thread pool. The lake executor is naturally thread-safe
(httpx.Client is); an implementation over something that is not (a DuckDB
connection, say) owes the caller its own serialization.
"""
from __future__ import annotations

from collections.abc import Mapping
from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class QueryExecutor(Protocol):
    """
    Minimal query interface.

    ``fetch_row``     — one-row result, returned as a dict keyed by alias.
                        Used for scalar aggregates.
    ``fetch_table``   — multi-row result, returned as list[dict]. Used
                        for the (rare) cases where we need raw rows
                        (e.g., the column-listing query).
    ``fetch_columns`` — convenience for ``DESCRIBE snapshot``-style
                        introspection. Implementations can override
                        this with a cheaper path (e.g., the lake's
                        snapshot metadata endpoint).

    All implementations must accept SQL with the table reference written
    as ``snapshot`` (matching aran-lake's view-binding contract). The
    local executor rewrites ``snapshot`` to the actual local table name
    transparently.
    """

    def fetch_row(self, sql: str) -> Mapping[str, Any]:
        ...

    def fetch_table(self, sql: str) -> list[Mapping[str, Any]]:
        ...

    def fetch_columns(self) -> list[tuple[str, str]]:
        """Return ``[(column_name, sql_type), ...]`` for the bound snapshot."""
        ...

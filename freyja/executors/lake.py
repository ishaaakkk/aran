"""
LakeQueryExecutor — aran-lake adapter.

Talks to ``aran-lake``'s ``POST /api/query`` endpoint, sends SQL,
parses the Arrow IPC response. This is FREYJA's only contact point
with the lake.

Why Arrow IPC instead of JSON: the lake's ``/api/query`` returns Arrow
IPC by default because (a) it preserves DuckDB-inferred types
(decimals, timestamps, lists), (b) it's roughly 10x smaller than JSON
for the same data, and (c) it's zero-copy into pyarrow. We accept the
extra dependency on pyarrow for the type fidelity.

Auth: bearer token in the ``Authorization`` header, tenant in
``X-Tenant-Id``. Actor (the human user, for audit) optional. FREYJA
gets its own bearer (per-caller secret in the lake's
``SHARED_SECRETS`` map) so the lake's audit log can distinguish
FREYJA queries from Aran queries.

Multiple snapshots: a ``LakeQueryExecutor`` is *bound to one snapshot*
for its lifetime. The lake's view-binding contract resolves
``snapshot`` in user SQL to that snapshot's bytes. To profile a
different snapshot, construct a different executor (cheap — the
underlying ``httpx.Client`` can be shared via ``client=``).

Wrapper-output profiling (Phase 3B, §3.1): construct with ``wrapper_sql``
and every query the profiler composes against ``snapshot`` is redirected
to run against the wrapper's OUTPUT instead — the lake is not told
anything new and gets no code change. ``wrapper_sql`` carries Aran's
``${SNAPSHOT}`` placeholder (the same one ``QueryEngineOperator``
resolves for query execution, and ``WrapperSqlValidator`` requires the
FROM clause to be alone); this executor resolves that placeholder to the
bare identifier ``snapshot`` — the lake's own bound view name — and then
substitutes that resolved text, parenthesized as a derived table, for
every bare ``snapshot`` reference the profiler's SQL builders emit. The
lake still binds ``snapshot`` from the physical parquet exactly as
before, sees the same kind of SQL string on the same endpoint, and so
applies the same resource limits and forbidden-pattern screen it always
did — there is no new surface to secure. Absent ``wrapper_sql``, none of
this fires: SQL passes through byte-identical to before this feature
existed.
"""
from __future__ import annotations

import io
import os
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any
from uuid import UUID

import httpx
import pyarrow.ipc as ipc

# The substitution machinery — pattern, collision guard, ${SNAPSHOT}
# resolution — lives in freyja/executors/binding.py, shared with the
# federated scan executor. WrapperSqlRejected is re-exported here because
# it predates the split and callers import it from this module.
from freyja.executors.binding import (
    WrapperSqlRejected,
    reject_colliding_columns,
    resolve_wrapper_sql,
    substitute_wrapper,
)

__all__ = [
    "LakeConfig",
    "LakeQueryError",
    "LakeQueryExecutor",
    "WrapperSqlRejected",
]

_ARROW_MIME = "application/vnd.apache.arrow.stream"


@dataclass(frozen=True, slots=True)
class LakeConfig:
    """Static config for talking to the lake. One per FREYJA process."""

    endpoint: str
    bearer_token: str
    timeout_seconds: float = 60.0

    @classmethod
    def from_env(cls) -> LakeConfig:
        return cls(
            endpoint=os.environ["ARAN_LAKE_URL"].rstrip("/"),
            bearer_token=os.environ["ARAN_LAKE_FREYJA_SECRET"],
            timeout_seconds=float(os.environ.get("ARAN_LAKE_TIMEOUT", "60")),
        )


class LakeQueryError(RuntimeError):
    """Raised when the lake returns a non-2xx, or the result can't be parsed."""


class LakeQueryExecutor:
    """
    Implements ``QueryExecutor`` against a single snapshot in aran-lake.

    Reusing a single ``httpx.Client`` across executors is recommended
    (HTTP/2 connection reuse, keep-alive). Pass it in via ``client=``;
    otherwise one is created lazily and closed in ``close()``.
    """

    def __init__(
        self,
        config: LakeConfig,
        tenant_id: UUID,
        snapshot_id: UUID,
        *,
        actor_id: UUID | None = None,
        client: httpx.Client | None = None,
        row_limit: int | None = None,
        wrapper_sql: str | None = None,
    ) -> None:
        # ${SNAPSHOT} is Aran's placeholder for "the bound view" in stored
        # wrapper SQL (WrapperSqlValidator / QueryEngineOperator, Java side).
        # Here "the bound view" is the lake's own `snapshot` view, so
        # resolving it is just one substitution — the lake still does the
        # actual binding, unchanged. Resolved (and semicolon-checked) before
        # any resource is allocated (the httpx.Client below) so a rejected
        # wrapper_sql never leaks an owned client.
        self._resolved_wrapper_sql = resolve_wrapper_sql(wrapper_sql)
        self._config = config
        self._tenant_id = str(tenant_id)
        self._snapshot_id = str(snapshot_id)
        self._actor_id = str(actor_id) if actor_id else None
        self._owned_client = client is None
        self._client = client or httpx.Client(timeout=config.timeout_seconds)
        self._row_limit = row_limit
        self._schema_cache: list[tuple[str, str]] | None = None

    def close(self) -> None:
        if self._owned_client:
            self._client.close()

    def __enter__(self) -> LakeQueryExecutor:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # -- public API ---------------------------------------------------

    def fetch_row(self, sql: str) -> Mapping[str, Any]:
        table = self._query_arrow(self._resolve_sql(sql))
        if table.num_rows == 0:
            return {}
        # to_pydict is cheap for a one-row table; gives us native Python
        # scalars (ints, floats, strings, datetimes) instead of pyarrow
        # scalars. Keeps the metric post-processing simple.
        return {name: col[0] for name, col in table.to_pydict().items()}

    def fetch_table(self, sql: str) -> list[Mapping[str, Any]]:
        table = self._query_arrow(self._resolve_sql(sql))
        return table.to_pylist()

    def fetch_columns(self) -> list[tuple[str, str]]:
        # The lake exposes snapshot columns via ``DESCRIBE snapshot`` — or,
        # with a wrapper bound, ``DESCRIBE (<resolved wrapper sql>)``, which
        # reports the WRAPPER's output columns. That's what makes column
        # selection downstream (_select_columns in profiler.py) profile the
        # wrapper's output instead of the raw snapshot's.
        #
        # Built directly here rather than via _resolve_sql/fetch_table:
        # DESCRIBE does not accept a trailing alias after a parenthesized
        # query (confirmed against DuckDB directly — `DESCRIBE (...) AS x`
        # is a parse error, `DESCRIBE (...)` is not), so the wrapper form
        # below intentionally omits the alias _resolve_sql would add.
        #
        # We cache so subsequent calls within the same profile run are free.
        if self._schema_cache is not None:
            return self._schema_cache
        describe_sql = (
            f"DESCRIBE ({self._resolved_wrapper_sql})"
            if self._resolved_wrapper_sql is not None
            else "DESCRIBE snapshot"
        )
        table = self._query_arrow(describe_sql)
        rows = table.to_pylist()
        # DuckDB's DESCRIBE returns columns named: column_name,
        # column_type, null, key, default, extra.
        columns = [(r["column_name"], r["column_type"]) for r in rows]
        if self._resolved_wrapper_sql is not None:
            # A collision would corrupt the composed metrics SQL into a
            # bind error whose "column ... not found" message is exactly
            # the substring execute_profile's error-mapping heuristic uses
            # to (wrongly) report a missing *snapshot* — see binding.py.
            reject_colliding_columns(columns)
        self._schema_cache = columns
        return self._schema_cache

    # -- internals ----------------------------------------------------

    def _resolve_sql(self, sql: str) -> str:
        """
        Redirect the profiler's ``snapshot`` reference to the bound
        wrapper's output (``freyja.executors.binding``); a no-op when no
        wrapper is bound.

        Only ``fetch_row``/``fetch_table`` route through here: they carry
        the profiler's generic, ``snapshot``-shaped SQL. ``fetch_columns``
        builds its own DESCRIBE text and calls ``_query_arrow`` directly
        (see its docstring) — re-running this substitution over
        already-resolved wrapper SQL, which legitimately contains the bare
        word ``snapshot`` meant to survive untouched to the lake, would
        double-substitute it.
        """
        return substitute_wrapper(sql, self._resolved_wrapper_sql)

    def _query_arrow(self, sql: str):
        headers = {
            "Authorization": f"Bearer {self._config.bearer_token}",
            "X-Tenant-Id": self._tenant_id,
            "Accept": _ARROW_MIME,
        }
        if self._actor_id is not None:
            headers["X-Actor-Id"] = self._actor_id

        body: dict[str, Any] = {"snapshot_id": self._snapshot_id, "sql": sql}
        if self._row_limit is not None:
            body["row_limit"] = self._row_limit

        try:
            resp = self._client.post(
                f"{self._config.endpoint}/api/query",
                json=body,
                headers=headers,
            )
        except httpx.HTTPError as e:
            raise LakeQueryError(f"transport error talking to lake: {e}") from e

        if resp.status_code >= 400:
            # The lake returns JSON error bodies; surface them.
            try:
                detail = resp.json().get("detail", resp.text)
            except Exception:
                detail = resp.text
            raise LakeQueryError(
                f"lake returned {resp.status_code}: {detail}"
            )

        if resp.headers.get("X-Aran-Lake-Row-Limit-Hit", "").lower() == "true":
            # Caller probably wants to know. For profile queries this
            # would be alarming — they're aggregates returning one row.
            # Surface it as a warning via the exception channel; the
            # operator catches and decides what to do.
            raise LakeQueryError(
                "row limit hit on profile query — query was probably wrong"
            )

        try:
            return ipc.open_stream(io.BytesIO(resp.content)).read_all()
        except Exception as e:
            raise LakeQueryError(f"failed to parse Arrow IPC response: {e}") from e

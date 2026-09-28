"""
LakeQueryExecutor — wrapper-output profiling (Phase 3B, §3.1).

These tests fake the lake with an ``httpx.MockTransport`` whose handler runs
the incoming SQL against a REAL in-memory DuckDB connection that already has
a ``snapshot`` view bound — the same shape aran_lake.query.duckdb_runner's
``bind_snapshot_view`` + ``run_sql_against_snapshot`` produce — without
importing aran_lake or spinning up a server. That makes the fan-out proof
below a real DuckDB execution, not a string/pattern check on composed SQL.
"""
from __future__ import annotations

import json
from uuid import uuid4

import duckdb
import httpx
import pyarrow as pa
import pytest
from freyja.executors.lake import (
    LakeConfig,
    LakeQueryError,
    LakeQueryExecutor,
    WrapperSqlRejected,
)
from freyja.profiling import metrics as metrics_module
from freyja.profiling.metrics import LAKE_STATEMENT_MAX_BYTES
from freyja.profiling.profiler import ProfileOptions, profile_snapshot


def _arrow_ipc_bytes(table: pa.Table) -> bytes:
    sink = pa.BufferOutputStream()
    with pa.ipc.new_stream(sink, table.schema) as writer:
        writer.write_table(table)
    return sink.getvalue().to_pybytes()


class _FakeLake:
    """
    Minimal stand-in for aran-lake's ``POST /api/query``: binds a
    ``snapshot`` view over fixed rows on a real DuckDB connection, runs
    whatever SQL the executor sends it, and returns Arrow IPC bytes —
    same as the real endpoint. Records every request body it saw so
    tests can assert on the SQL actually sent over the wire.
    """

    def __init__(self, rows: list[tuple], columns: list[str]) -> None:
        self.requests: list[dict] = []
        self.con = duckdb.connect(":memory:")
        col_list = ", ".join(columns)
        values = ", ".join(
            "(" + ", ".join(repr(v) for v in row) + ")" for row in rows
        )
        self.con.execute(
            f"CREATE OR REPLACE VIEW snapshot AS "
            f"SELECT * FROM (VALUES {values}) AS t({col_list})"
        )

    def raw_row_count(self) -> int:
        return self.con.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0]

    def handler(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        self.requests.append(body)
        # The lake's own limit, enforced here because it is part of the
        # contract freyja composes SQL against: QueryRequest.sql carries
        # max_length=64*1024 (aran-lake/src/aran_lake/api/query.py), and
        # pydantic answers 422 before the query ever runs. Without this the
        # harness accepts statements production rejects — which is how
        # S13-1 (no profiles at all above ~16 columns) went unnoticed.
        if len(body["sql"].encode("utf-8")) > LAKE_STATEMENT_MAX_BYTES:
            return httpx.Response(
                422,
                json={"detail": [{
                    "type": "string_too_long",
                    "loc": ["body", "sql"],
                    "msg": "String should have at most 65536 characters",
                }]},
            )
        table = self.con.execute(body["sql"]).fetch_arrow_table()
        return httpx.Response(
            200,
            content=_arrow_ipc_bytes(table),
            headers={"X-Aran-Lake-Row-Limit-Hit": "false"},
        )

    def client(self) -> httpx.Client:
        return httpx.Client(transport=httpx.MockTransport(self.handler))


def _executor(lake: _FakeLake, **kwargs) -> LakeQueryExecutor:
    return LakeQueryExecutor(
        config=LakeConfig(endpoint="http://mock-lake", bearer_token="secret"),
        tenant_id=uuid4(),
        snapshot_id=uuid4(),
        client=lake.client(),
        **kwargs,
    )


# ---- absent wrapper_sql: byte-identical regression pin ---- #

def test_no_wrapper_sql_sends_the_composed_sql_unchanged():
    lake = _FakeLake(rows=[(1, "a"), (2, "b")], columns=["id", "val"])
    executor = _executor(lake)

    row = executor.fetch_row("SELECT COUNT(*) AS n FROM snapshot")

    assert row["n"] == 2
    assert lake.requests[-1]["sql"] == "SELECT COUNT(*) AS n FROM snapshot"


def test_no_wrapper_sql_describe_is_unchanged():
    lake = _FakeLake(rows=[(1, "a")], columns=["id", "val"])
    executor = _executor(lake)

    cols = executor.fetch_columns()

    assert [c[0] for c in cols] == ["id", "val"]
    assert lake.requests[-1]["sql"] == "DESCRIBE snapshot"


# ---- fan-out proof: wrapper OUTPUT gets profiled, not the raw snapshot ---- #

def test_wrapper_sql_fans_out_row_count():
    lake = _FakeLake(rows=[(1, "a"), (2, "b")], columns=["id", "val"])
    executor = _executor(
        lake,
        wrapper_sql="SELECT * FROM ${SNAPSHOT} UNION ALL SELECT * FROM ${SNAPSHOT}",
    )

    row = executor.fetch_row("SELECT COUNT(*) AS n FROM snapshot")

    assert lake.raw_row_count() == 2          # the fixture really has 2 rows
    assert row["n"] == 4                      # the §3.1 payoff: wrapper output, not raw
    assert row["n"] == lake.raw_row_count() * 2


def test_wrapper_sql_changes_reported_columns():
    """fetch_columns() must reflect the wrapper's OUTPUT schema — this is
    what makes the profiler select and profile the wrapper's columns
    instead of the raw snapshot's."""
    lake = _FakeLake(rows=[(1, "a"), (2, "b")], columns=["id", "val"])
    executor = _executor(lake, wrapper_sql="SELECT id AS renamed_id FROM ${SNAPSHOT}")

    cols = executor.fetch_columns()

    assert [c[0] for c in cols] == ["renamed_id"]
    assert lake.requests[-1]["sql"] == "DESCRIBE (SELECT id AS renamed_id FROM snapshot)"


def test_wrapper_sql_is_substituted_as_a_parenthesized_relation():
    lake = _FakeLake(rows=[(1, "a")], columns=["id", "val"])
    executor = _executor(lake, wrapper_sql="SELECT * FROM ${SNAPSHOT}")

    executor.fetch_row("SELECT COUNT(*) AS n FROM snapshot")

    sent = lake.requests[-1]["sql"]
    assert sent == "SELECT COUNT(*) AS n FROM (SELECT * FROM snapshot) AS _wrapper_output"


def test_wrapper_sql_does_not_clobber_snapshot_id_like_tokens():
    """The word-boundary regex must not touch identifiers that merely
    contain 'snapshot' as a substring."""
    lake = _FakeLake(rows=[(1, "a")], columns=["snapshot_id", "val"])
    executor = _executor(lake, wrapper_sql="SELECT * FROM ${SNAPSHOT}")

    executor.fetch_row('SELECT "snapshot_id" AS n FROM snapshot')

    sent = lake.requests[-1]["sql"]
    assert sent == 'SELECT "snapshot_id" AS n FROM (SELECT * FROM snapshot) AS _wrapper_output'


# ---- collision guard: a wrapper output column named like the token itself ---- #

def test_wrapper_output_column_named_snapshot_is_rejected():
    """
    The exact repro reported in review: aliasing a wrapper output column to
    the bare token 'snapshot' is ordinary in this domain (e.g. a
    snapshot-timestamp column) but would corrupt _resolve_sql's token-based
    substitution — it cannot tell that quoted column reference from the
    relation reference it's meant to redirect. Unguarded, the corrupted SQL
    still parses (a quoted identifier can contain anything) but fails to
    bind, and the resulting DuckDB message contains "not found" — which
    trips execute_profile's 404 heuristic and misreports a missing
    *snapshot* instead of the caller's actual mistake. fetch_columns() must
    catch this the moment the wrapper's real output schema is known, before
    any metrics SQL referencing it is ever composed or sent.
    """
    lake = _FakeLake(rows=[(1, "a"), (2, "b")], columns=["id", "val"])
    executor = _executor(lake, wrapper_sql="SELECT val AS snapshot FROM ${SNAPSHOT}")

    with pytest.raises(WrapperSqlRejected, match="collides with the profiling relation binding"):
        executor.fetch_columns()

    # Only the DESCRIBE call happened: the corrupted metrics SQL this would
    # otherwise produce must never reach the lake.
    assert len(lake.requests) == 1
    assert lake.requests[0]["sql"].startswith("DESCRIBE")


def test_wrapper_output_column_matching_the_internal_alias_is_safe():
    """
    Loop-closer, not hypothetical: freyja's own substitution introduces the
    identifier `_wrapper_output` as a table alias. A wrapper's OUTPUT could
    legitimately contain a column of that same name — table aliases and
    column names live in different SQL positions, so this must never
    collide. Pinned end-to-end (not just "fetch_columns doesn't raise") so
    a future change to the substitution can't silently break it.
    """
    lake = _FakeLake(rows=[(1, "a"), (2, "b")], columns=["id", "val"])
    executor = _executor(lake, wrapper_sql="SELECT val AS _wrapper_output FROM ${SNAPSHOT}")

    cols = executor.fetch_columns()
    assert [c[0] for c in cols] == ["_wrapper_output"]

    result = profile_snapshot(executor, ProfileOptions(max_concurrent_column_queries=1))
    assert result.row_count == 2
    col = result.column("_wrapper_output")
    assert col is not None
    assert col.metrics["cardinality"] == 2


# ---- end-to-end: the full profiler, not just the executor ---- #

def test_profile_snapshot_profiles_wrapper_output_end_to_end():
    """
    Exercises freyja.profiling.profiler.profile_snapshot (the real PROFILE
    algorithm Aran calls), not just the executor in isolation. row_count on
    the returned ProfileSet must reflect the wrapper's fan-out.
    """
    lake = _FakeLake(rows=[(1, "a"), (2, "b")], columns=["id", "val"])
    executor = _executor(
        lake,
        wrapper_sql="SELECT * FROM ${SNAPSHOT} UNION ALL SELECT * FROM ${SNAPSHOT}",
    )

    result = profile_snapshot(executor, ProfileOptions(max_concurrent_column_queries=1))

    assert lake.raw_row_count() == 2
    assert result.row_count == 4
    val_col = result.column("val")
    assert val_col is not None
    assert val_col.metrics["cardinality"] == 2   # "a" and "b", each now appearing twice


# ---- S13-1: the lake's statement cap, and the width it used to cost us ---- #

_NAME_BASE = "customer_account_reference"

# Realistic 20-30 character column names: the cap bites at a width, and a
# fixture of 4-character names would clear it while production did not.
def _wide_names(n: int) -> list[str]:
    return [f"{_NAME_BASE[: 16 + (i % 11)]}_{i:03d}" for i in range(n)]


# The payload columns both fixtures below share, so a wide (chunked) profile
# can be compared metric-for-metric against a narrow (single-statement) one.
_PAYLOAD = [
    ("alice smith", "BCN", "x", "one two three"),
    ("bob jones", "BCN", "y", "one two"),
    ("carol", "PAR", "x", "four"),
    ("dave", "BCN", "z", "one two three"),
    ("erin", "MAD", "x", "five six"),
]


def _wide_rows(n_columns: int) -> list[tuple]:
    return [
        payload + tuple(f"pad{c}_{r % 3}" for c in range(len(payload), n_columns))
        for r, payload in enumerate(_PAYLOAD)
    ]


def test_the_fake_lake_enforces_the_statement_cap():
    """Harness guard for the guard: if this stops rejecting, the width tests
    below stop proving anything."""
    lake = _FakeLake(rows=[(1, "a")], columns=["id", "val"])
    executor = _executor(lake)
    padding = "x" * LAKE_STATEMENT_MAX_BYTES

    with pytest.raises(LakeQueryError, match="422"):
        executor.fetch_row(f"SELECT COUNT(*) AS n FROM snapshot /* {padding} */")


def test_a_wide_snapshot_is_profiled_instead_of_losing_every_profile():
    """
    S13-1's headline: 40 columns is an ordinary width, and the single
    pre-change scalar statement (~172 KB at these name lengths) was refused
    by the lake with a 422 — which reached Aran as one WARN and left the
    dataset with no profiles at all, not merely fewer.
    """
    names = _wide_names(40)
    lake = _FakeLake(rows=_wide_rows(40), columns=names)

    result = profile_snapshot(
        _executor(lake),
        ProfileOptions(varchar_only=False, max_concurrent_column_queries=1),
    )

    assert result.row_count == 5
    assert {c.column_name for c in result.columns} == set(names)
    assert result.errors == {}


def test_chunking_is_invisible_to_the_per_column_metrics(monkeypatch):
    """
    A column's metrics must not depend on which chunk it landed in. Compared
    against a narrow table profiled in ONE statement — the pre-change shape —
    over byte-identical column values.
    """
    narrow_names = _wide_names(4)
    narrow_lake = _FakeLake(rows=_PAYLOAD, columns=narrow_names)
    narrow = profile_snapshot(
        _executor(narrow_lake),
        ProfileOptions(varchar_only=False, max_concurrent_column_queries=1),
    )
    assert len(_scalar_statements(narrow_lake)) == 1

    # A budget far below one statement's worth, so the 40-column table is
    # chunked hard rather than only just.
    monkeypatch.setattr(metrics_module, "SCALAR_STATEMENT_BUDGET_BYTES", 4096)
    wide_lake = _FakeLake(rows=_wide_rows(40), columns=_wide_names(40))
    wide = profile_snapshot(
        _executor(wide_lake),
        ProfileOptions(varchar_only=False, max_concurrent_column_queries=1),
    )
    assert len(_scalar_statements(wide_lake)) > 1

    assert wide.row_count == narrow.row_count
    for name in narrow_names:
        assert wide.column(name).metrics == narrow.column(name).metrics


def test_wrapper_substitution_survives_the_cte_form():
    """
    The scalar statement is now a CTE chain, and wrapper-output profiling
    redirects it by rewriting every bare ``snapshot`` token
    (freyja/executors/binding.py). The CTE form must leave exactly ONE such
    token — both so the redirect stays correct and so the wrapper text is
    substituted once, which is what the chunk budget's headroom assumes.
    """
    lake = _FakeLake(rows=[(1, "a"), (2, "b")], columns=["id", "val"])
    executor = _executor(
        lake,
        wrapper_sql="SELECT * FROM ${SNAPSHOT} UNION ALL SELECT * FROM ${SNAPSHOT}",
    )

    result = profile_snapshot(
        executor, ProfileOptions(varchar_only=False, max_concurrent_column_queries=1),
    )

    scalar_sql = _scalar_statements(lake)[0]
    assert scalar_sql.startswith("WITH")
    assert scalar_sql.count("_wrapper_output") == 1
    assert result.row_count == 4                       # the wrapper's fan-out
    assert result.column("val").metrics["cardinality"] == 2


def _scalar_statements(lake: _FakeLake) -> list[str]:
    return [r["sql"] for r in lake.requests if "AS row_count" in r["sql"]]


# ---- semicolon rejection ---- #

def test_wrapper_sql_with_semicolon_is_rejected():
    lake = _FakeLake(rows=[(1, "a")], columns=["id", "val"])
    with pytest.raises(WrapperSqlRejected):
        _executor(lake, wrapper_sql="SELECT 1; DROP TABLE snapshot")


def test_semicolon_rejection_happens_before_any_lake_call():
    lake = _FakeLake(rows=[(1, "a")], columns=["id", "val"])
    with pytest.raises(WrapperSqlRejected):
        _executor(lake, wrapper_sql="SELECT 1;")
    assert lake.requests == []

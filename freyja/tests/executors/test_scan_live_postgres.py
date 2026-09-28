"""
V54 at the freyja level: the whole profiling suite over a LIVE Postgres read.

``test_scan.py`` proves the suite over a local parquet stand-in with the
test-only ``allow_local_files=True``; what it registers as unproven is the
remote scan itself. Here the scan is real — a Testcontainers PostgreSQL,
reached by DuckDB's postgres extension — and the executor runs in its
production mode: the expression passes the fail-closed screen as-is, and
``disabled_filesystems='LocalFileSystem'`` is locked in after the extension
loads (one of V54's own failure hypotheses was that the scanner would not
survive that lockdown).

The request goes through the real HTTP endpoint and the real operator, shaped
exactly as ``ProfilingOperator.putFederatedScan`` sends it: the expression is
spelled the way ``PostgresEngine.federatedScan`` spells it — an attached
catalog's table, after its ``INSTALL``/``LOAD``, secret and ``ATTACH``
(``FederatedProfilingBinding`` forwards exactly those). The lake executor
is replaced by a tripwire and the lake URL points at a closed port, so
"nothing is staged" is asserted, not assumed.

The numbers are exact and full-relation by construction: the rows are
generated so every count is known in closed form, and the assertions are
equalities, never ceilings.
"""
from __future__ import annotations

import os
from pathlib import Path
from unittest.mock import patch

import pytest

tc_postgres = pytest.importorskip("testcontainers.postgres")

from fastapi.testclient import TestClient  # noqa: E402 — after the importorskip guard
from freyja.api.profile_api import app  # noqa: E402

pytestmark = pytest.mark.docker

REPO_ROOT = Path(__file__).resolve().parents[3]

# Same escape hatch the Java and lake suites honour: its presence means no
# bridge networking, so a second, independent database cannot be started.
CONTAINER_INFO_PATH = REPO_ROOT / ".container-info.json"

AUTH_SECRET = "test-shared-secret"
AUTH = {"Authorization": f"Bearer {AUTH_SECRET}"}

ROWS = 20_000
CLASS_NS = "http://www.essi.upc.edu/DTIM/NextiaDI/DataSource/Schema/venue/"

SEED_SQL = f"""
CREATE TABLE venue AS
SELECT i                                            AS venue_id,
       'city_' || (i % 7)                           AS city,
       (i % 97)::bigint                             AS capacity,
       CASE WHEN i % 10 = 0 THEN NULL ELSE 'n' || (i % 250) END AS note
FROM generate_series(1, {ROWS}) AS i;
"""


def _docker_available() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:
        return False


@pytest.fixture(scope="module")
def source():
    if CONTAINER_INFO_PATH.exists():
        pytest.skip("constrained Docker: no bridge networking")
    if not _docker_available():
        pytest.skip("no Docker daemon")
    with tc_postgres.PostgresContainer(
        image="postgres:17-alpine",
        username="srcuser",
        password="srcpw",
        dbname="srcdb",
    ) as pg:
        result = pg.exec(
            ["psql", "-U", "srcuser", "-d", "srcdb", "-v", "ON_ERROR_STOP=1", "-c", SEED_SQL]
        )
        assert result.exit_code == 0, result.output
        yield pg


def _sql_literal(value: str) -> str:
    return value.replace("'", "''")


_REPO_HEX = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
_ALIAS = f"aran_attach_{_REPO_HEX}"


def _postgres_binding(pg, table: str) -> tuple[str, list[str]]:
    """PostgresEngine.federatedScan, spelled the same way: (expression, setup_sql)."""
    secret = f"aran_pg_{_REPO_HEX}"
    setup = [
        "INSTALL postgres",
        "LOAD postgres",
        f"CREATE OR REPLACE TEMPORARY SECRET {secret} (TYPE postgres"
        f", HOST '{_sql_literal(pg.get_container_host_ip())}'"
        f", PORT {pg.get_exposed_port(5432)}"
        f", DATABASE 'srcdb', USER 'srcuser', PASSWORD 'srcpw')",
        f"ATTACH IF NOT EXISTS '' AS {_ALIAS} (TYPE postgres, SECRET {secret}, READ_ONLY)",
    ]
    return f'{_ALIAS}."public"."{table}"', setup


def _flat_wrapper(columns: list[str]) -> str:
    """WrapperSqlBuilder.buildFlat: each column projected AS its source-property IRI."""
    projections = ", ".join(f'"{c}" AS "{CLASS_NS}{c}"' for c in columns)
    return f"SELECT {projections} FROM ${{SNAPSHOT}}"


class _LakeTripwire:
    def __init__(self, *args, **kwargs):
        raise AssertionError("the federated path must never construct a lake executor")


@patch.dict(
    os.environ,
    {
        # A closed loopback port: anything that tried to reach a lake would fail loudly.
        "ARAN_LAKE_URL": "http://127.0.0.1:9",
        "ARAN_LAKE_FREYJA_SECRET": "unused",
        "FREYJA_SHARED_SECRET": AUTH_SECRET,
    },
)
def test_the_whole_suite_runs_over_a_live_postgres_read_with_nothing_staged(source):
    columns = ["venue_id", "city", "capacity", "note"]
    expression, setup_sql = _postgres_binding(source, "venue")
    with patch("freyja.operator.profile_operator.LakeQueryExecutor", _LakeTripwire), \
            TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "federated_scan": {
                    "expression": expression,
                    "setup_sql": setup_sql,
                },
                "wrapper_sql": _flat_wrapper(columns),
                "varchar_only": False,
                "max_concurrent_column_queries": 4,
            },
        )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["snapshot_id"] is None
    assert body["errors"] == {}
    # Exact, full-relation: every row of the remote table, no sample, no ceiling.
    assert body["row_count"] == ROWS

    by_column = {c["column_name"]: c["metrics"] for c in body["columns"]}
    assert set(by_column) == {CLASS_NS + c for c in columns}

    venue_id = by_column[CLASS_NS + "venue_id"]
    assert venue_id["cardinality"] == ROWS
    assert venue_id["uniqueness"] == 1.0
    assert venue_id["incompleteness"] == 0

    assert by_column[CLASS_NS + "city"]["cardinality"] == 7
    assert by_column[CLASS_NS + "capacity"]["cardinality"] == 97

    note = by_column[CLASS_NS + "note"]
    # i % 250 keeps i's residue mod 10 (250 is a multiple of 10), so the
    # non-NULL cells take the 225 residues in 0..249 not divisible by ten.
    assert note["cardinality"] == 225
    assert note["incompleteness"] == pytest.approx(0.1)


def test_the_scan_binding_is_the_only_relation_and_the_local_filesystem_is_shut(source):
    from freyja.executors.scan import ScanQueryError, ScanQueryExecutor

    expression, setup_sql = _postgres_binding(source, "venue")
    with ScanQueryExecutor(expression, setup_sql=setup_sql) as ex:
        assert ex.fetch_row("SELECT COUNT(*) AS n FROM snapshot") == {"n": ROWS}
        # The local catalog holds the one view; the only other catalog is the
        # attachment the setup made, under its reserved alias.
        relations = ex.fetch_table(
            "SELECT table_name, table_type FROM information_schema.tables"
            " WHERE table_catalog = current_database()"
        )
        assert relations == [{"table_name": "snapshot", "table_type": "VIEW"}]
        catalogs = ex.fetch_table(
            "SELECT database_name FROM duckdb_databases() WHERE NOT internal"
        )
        assert {c["database_name"] for c in catalogs} == {"memory", _ALIAS}
        # Nothing on the connection hands the password back: the view body
        # names the attached table, and the secret is redacted.
        bodies = ex.fetch_table("SELECT sql FROM duckdb_views() WHERE NOT internal")
        assert "srcpw" not in str(bodies)
        secrets = ex.fetch_table("SELECT secret_string FROM duckdb_secrets()")
        assert "srcpw" not in str(secrets)
        with pytest.raises(ScanQueryError, match=r"(?i)file system LocalFileSystem has been disabled"):
            ex.fetch_row("SELECT * FROM read_csv('/etc/hostname')")

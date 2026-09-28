"""
The whole profiling suite over a LIVE MySQL 8.4, through the attached-catalog
binding Aran's ``MySqlEngine.federatedScan`` emits.

DuckDB 1.5.5's mysql extension has no scan function: the only way in is a
``CREATE SECRET (TYPE mysql, …)`` plus a read-only ``ATTACH``, which must pass
``scan.py``'s allowlist exactly as Java spells it (Java's
``FederatedScanExpressionTest`` pins the same strings). What this proves
beyond the offline shape tests: the setup binds a real server, the snapshot
view over an attached table survives the local-filesystem lockdown, and the
profiler's aggregates — COUNT(*) over a view — run, which they only do with
``mysql_aggregate_pushdown_enabled`` off.

The request goes through the real HTTP endpoint with the lake replaced by a
tripwire, so "nothing is staged" is asserted, not assumed.
"""
from __future__ import annotations

import os
from pathlib import Path
from unittest.mock import patch

import pytest

tc_core = pytest.importorskip("testcontainers.core.container")

from fastapi.testclient import TestClient  # noqa: E402 — after the importorskip guard
from freyja.api.profile_api import app  # noqa: E402
from testcontainers.core.waiting_utils import wait_for_logs  # noqa: E402

pytestmark = pytest.mark.docker

REPO_ROOT = Path(__file__).resolve().parents[3]
CONTAINER_INFO_PATH = REPO_ROOT / ".container-info.json"

AUTH_SECRET = "test-shared-secret"
AUTH = {"Authorization": f"Bearer {AUTH_SECRET}"}

ROWS = 20_000
CLASS_NS = "http://www.essi.upc.edu/DTIM/NextiaDI/DataSource/Schema/venue/"
REPO_HEX = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"

# Every character a value has to survive, plus the ';' that is legal only
# inside the secret's literals.
HOSTILE_USER = "quoted"
HOSTILE_PASSWORD = "p w'd\\x\"q; host=evil"

SEED_SQL = f"""
SET SESSION cte_max_recursion_depth = {ROWS + 1};
CREATE TABLE venue AS
WITH RECURSIVE seq(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < {ROWS})
SELECT i                                            AS venue_id,
       CONCAT('city_', i % 7)                       AS city,
       CAST(i % 97 AS SIGNED)                       AS capacity,
       CASE WHEN i % 10 = 0 THEN NULL ELSE CONCAT('n', i % 250) END AS note
FROM seq;
CREATE USER '{HOSTILE_USER}'@'%' IDENTIFIED BY '{HOSTILE_PASSWORD.replace(chr(92), chr(92) * 2).replace("'", "''")}';
GRANT SELECT ON srcdb.* TO '{HOSTILE_USER}'@'%';
"""


def _docker_available() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:
        return False


def _image(name: str) -> str:
    """
    The Java suites' registry mirror (``hub.image.name.prefix`` in
    ``~/.testcontainers.properties``) when Python's own prefix variable is
    unset, so both languages pull the same image from the same place.
    """
    if os.environ.get("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX"):
        return name
    props = Path.home() / ".testcontainers.properties"
    if props.exists():
        for line in props.read_text().splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "hub.image.name.prefix" and value.strip():
                return value.strip() + name
    return name


@pytest.fixture(scope="module")
def source():
    if CONTAINER_INFO_PATH.exists():
        pytest.skip("constrained Docker: no bridge networking")
    if not _docker_available():
        pytest.skip("no Docker daemon")
    container = (
        tc_core.DockerContainer(_image("mysql:8.4"))
        .with_env("MYSQL_ROOT_PASSWORD", "rootpw")
        .with_env("MYSQL_DATABASE", "srcdb")
        .with_env("MYSQL_USER", "srcuser")
        .with_env("MYSQL_PASSWORD", "srcpw")
        .with_exposed_ports(3306)
    )
    with container:
        # The init server logs "ready" on port 0; only the real one listens on
        # 3306 (the trailing space keeps the X plugin's 33060 out).
        wait_for_logs(container, r"port: 3306 ", timeout=180)
        result = container.exec(["mysql", "-uroot", "-prootpw", "srcdb", "-e", SEED_SQL])
        assert result.exit_code == 0, result.output
        yield container


def _literal(value: str) -> str:
    return value.replace("'", "''")


def _binding(container, user: str, password: str, table: str) -> tuple[str, list[str]]:
    """MySqlEngine.federatedScan, spelled the same way."""
    alias = "aran_attach_" + REPO_HEX
    secret = "aran_mysql_" + REPO_HEX
    host = container.get_container_host_ip()
    port = container.get_exposed_port(3306)
    setup = [
        "INSTALL mysql",
        "LOAD mysql",
        "SET mysql_aggregate_pushdown_enabled=false",
        f"CREATE OR REPLACE TEMPORARY SECRET {secret} (TYPE mysql, HOST '{host}', "
        f"PORT {port}, DATABASE 'srcdb', USER '{_literal(user)}', "
        f"PASSWORD '{_literal(password)}')",
        f"ATTACH IF NOT EXISTS '' AS {alias} (TYPE mysql, SECRET {secret}, READ_ONLY)",
    ]
    return f'{alias}."srcdb"."{table}"', setup


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
        "ARAN_LAKE_URL": "http://127.0.0.1:9",
        "ARAN_LAKE_FREYJA_SECRET": "unused",
        "FREYJA_SHARED_SECRET": AUTH_SECRET,
    },
)
def test_the_whole_suite_runs_over_a_live_attached_mysql_table(source):
    columns = ["venue_id", "city", "capacity", "note"]
    expression, setup = _binding(source, "srcuser", "srcpw", "venue")
    with patch("freyja.operator.profile_operator.LakeQueryExecutor", _LakeTripwire), \
            TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "federated_scan": {"expression": expression, "setup_sql": setup},
                "wrapper_sql": _flat_wrapper(columns),
                "varchar_only": False,
                "max_concurrent_column_queries": 4,
            },
        )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["errors"] == {}
    assert body["row_count"] == ROWS

    by_column = {c["column_name"]: c["metrics"] for c in body["columns"]}
    assert set(by_column) == {CLASS_NS + c for c in columns}
    assert by_column[CLASS_NS + "venue_id"]["cardinality"] == ROWS
    assert by_column[CLASS_NS + "venue_id"]["uniqueness"] == 1.0
    assert by_column[CLASS_NS + "city"]["cardinality"] == 7
    assert by_column[CLASS_NS + "capacity"]["cardinality"] == 97
    note = by_column[CLASS_NS + "note"]
    assert note["cardinality"] == 225
    assert note["incompleteness"] == pytest.approx(0.1)


def test_a_hostile_password_authenticates_and_the_local_filesystem_is_shut(source):
    from freyja.executors.scan import ScanQueryError, ScanQueryExecutor

    expression, setup = _binding(source, HOSTILE_USER, HOSTILE_PASSWORD, "venue")
    with ScanQueryExecutor(expression, setup_sql=setup) as ex:
        assert ex.fetch_row("SELECT COUNT(*) AS n FROM snapshot") == {"n": ROWS}
        with pytest.raises(ScanQueryError, match=r"(?i)file system LocalFileSystem has been disabled"):
            ex.fetch_row("SELECT * FROM read_csv('/etc/hostname')")


def test_a_wrong_password_fails_at_the_attach_without_echoing_it(source):
    from freyja.executors.scan import ScanQueryError, ScanQueryExecutor

    expression, setup = _binding(source, "srcuser", "wrong-sentinel-pw", "venue")
    with pytest.raises(ScanQueryError, match=r"statement 4 \(ATTACH\)") as e:
        ScanQueryExecutor(expression, setup_sql=setup)
    assert "wrong-sentinel-pw" not in str(e.value)

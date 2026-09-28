"""
execute_profile() — operator-layer plumbing for wrapper_sql.

The real, DuckDB-backed proof that wrapper SQL changes what gets profiled
lives in tests/executors/test_lake.py (LakeQueryExecutor is the layer that
does the substitution — see that file's module docstring). These tests
cover the operator's own responsibility instead: that wrapper_sql actually
reaches the executor it constructs, and that a rejected wrapper_sql
surfaces as the operator's own exception type — the one profile_api.py
maps to a 4xx — rather than falling into the generic 500/502 paths meant
for lake-side failures that are not the caller's fault.
"""
from __future__ import annotations

from unittest.mock import MagicMock, patch
from uuid import uuid4

import pytest
from freyja.executors.lake import LakeConfig, WrapperSqlRejected
from freyja.executors.scan import ScanQueryError
from freyja.operator.profile_operator import (
    FederatedSourceError,
    InvalidFederatedScanError,
    InvalidWrapperSqlError,
    execute_profile,
)

_LAKE_CONFIG = LakeConfig(endpoint="http://mock-lake", bearer_token="secret")


def _empty_profile_set() -> MagicMock:
    return MagicMock(row_count=0, columns=[], errors={})


@patch("freyja.operator.profile_operator.profile_snapshot")
@patch("freyja.operator.profile_operator.LakeQueryExecutor")
def test_wrapper_sql_is_forwarded_to_the_executor(mock_executor_cls, mock_profile_snapshot):
    mock_profile_snapshot.return_value = _empty_profile_set()

    execute_profile(
        tenant_id=uuid4(),
        snapshot_id=uuid4(),
        lake_config=_LAKE_CONFIG,
        wrapper_sql="SELECT * FROM ${SNAPSHOT}",
    )

    _, kwargs = mock_executor_cls.call_args
    assert kwargs["wrapper_sql"] == "SELECT * FROM ${SNAPSHOT}"


@patch("freyja.operator.profile_operator.profile_snapshot")
@patch("freyja.operator.profile_operator.LakeQueryExecutor")
def test_absent_wrapper_sql_is_passed_through_as_none(mock_executor_cls, mock_profile_snapshot):
    """Regression pin: the kwarg must still be sent explicitly as None, not
    omitted — omitting it would silently depend on LakeQueryExecutor's own
    default staying None forever instead of this call site meaning it."""
    mock_profile_snapshot.return_value = _empty_profile_set()

    execute_profile(tenant_id=uuid4(), snapshot_id=uuid4(), lake_config=_LAKE_CONFIG)

    _, kwargs = mock_executor_cls.call_args
    assert kwargs["wrapper_sql"] is None


@patch("freyja.operator.profile_operator.LakeQueryExecutor")
def test_rejected_wrapper_sql_becomes_operator_error(mock_executor_cls):
    mock_executor_cls.side_effect = WrapperSqlRejected("no semicolons")

    with pytest.raises(InvalidWrapperSqlError):
        execute_profile(
            tenant_id=uuid4(),
            snapshot_id=uuid4(),
            lake_config=_LAKE_CONFIG,
            wrapper_sql="SELECT 1; DROP TABLE x",
        )


@patch("freyja.operator.profile_operator.profile_snapshot")
@patch("freyja.operator.profile_operator.LakeQueryExecutor")
def test_wrapper_sql_rejected_lazily_by_profile_snapshot_becomes_operator_error(
    mock_executor_cls, mock_profile_snapshot,
):
    """
    The 'snapshot'-column-collision guard lives inside fetch_columns(),
    which only runs once profile_snapshot() calls it — i.e. it can only be
    discovered lazily, unlike the semicolon check that fires at
    construction. execute_profile must catch WrapperSqlRejected raised from
    THIS call too (not just from constructing the executor), and it must
    still close the executor it already built.
    """
    mock_profile_snapshot.side_effect = WrapperSqlRejected(
        "wrapper output column 'snapshot' may not be named 'snapshot' — it "
        "collides with the profiling relation binding"
    )

    with pytest.raises(InvalidWrapperSqlError):
        execute_profile(
            tenant_id=uuid4(),
            snapshot_id=uuid4(),
            lake_config=_LAKE_CONFIG,
            wrapper_sql="SELECT val AS snapshot FROM ${SNAPSHOT}",
        )

    mock_executor_cls.return_value.close.assert_called_once()


# ---- federated-scan routing (the Gap 2 follow-up's transport) ---- #

def test_snapshot_and_scan_are_mutually_exclusive():
    with pytest.raises(InvalidFederatedScanError):
        execute_profile(
            tenant_id=uuid4(), snapshot_id=uuid4(), lake_config=_LAKE_CONFIG,
            scan_expression="iceberg_scan('s3://b/metadata.json')",
        )
    with pytest.raises(InvalidFederatedScanError):
        execute_profile(tenant_id=uuid4(), lake_config=_LAKE_CONFIG)


@patch("freyja.operator.profile_operator.profile_snapshot")
@patch("freyja.operator.profile_operator.ScanQueryExecutor")
def test_scan_expression_routes_to_the_scan_executor(mock_scan_cls, mock_profile_snapshot):
    """A federated request must never touch the lake: the ScanQueryExecutor
    is constructed with the expression, setup and wrapper, and no
    LakeConfig is even resolved (lake_config stays None here on purpose —
    resolving it would need ARAN_LAKE_URL, which this test does not set)."""
    mock_profile_snapshot.return_value = _empty_profile_set()

    execute_profile(
        tenant_id=uuid4(),
        scan_expression="iceberg_scan('s3://b/metadata.json')",
        scan_setup_sql=["INSTALL httpfs", "LOAD httpfs"],
        wrapper_sql="SELECT * FROM ${SNAPSHOT}",
    )

    args, kwargs = mock_scan_cls.call_args
    assert args == ("iceberg_scan('s3://b/metadata.json')",)
    assert kwargs["setup_sql"] == ["INSTALL httpfs", "LOAD httpfs"]
    assert kwargs["wrapper_sql"] == "SELECT * FROM ${SNAPSHOT}"
    mock_scan_cls.return_value.close.assert_called_once()


def test_rejected_scan_expression_becomes_operator_error():
    # Straight through the real executor's fail-closed screen: a local
    # path in a read_* expression is the caller's mistake (400), and no
    # lake config is needed to find that out.
    with pytest.raises(InvalidFederatedScanError):
        execute_profile(
            tenant_id=uuid4(),
            scan_expression="read_parquet('/etc/passwd')",
        )


@patch("freyja.operator.profile_operator.ScanQueryExecutor")
def test_failing_scan_construction_becomes_upstream_error(mock_scan_cls):
    """An unreachable source or bad credential fails at construction
    (setup + view binding touch the remote); that is the upstream's fault,
    mapped to the 502-shaped operator error, not the caller's 400."""
    mock_scan_cls.side_effect = ScanQueryError("binding the federated scan failed: IO Error")

    with pytest.raises(FederatedSourceError):
        execute_profile(
            tenant_id=uuid4(),
            scan_expression="iceberg_scan('s3://b/metadata.json')",
        )

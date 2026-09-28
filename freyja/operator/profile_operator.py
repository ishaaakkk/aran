"""
PROFILE operator — Aran-facing entrypoint.

This is the function Aran's PROFILE operator dispatcher calls. It is a
plain Python function (no HTTP, no framework) so that Aran can either:

* call it as a library through whatever Python-from-Java bridge Aran
  uses (GraalPy, Py4J, subprocess + stdio), or
* call it over HTTP via the optional sidecar in ``api/profile_api.py``.

The contract:

  Input:   (tenant_id, snapshot_id | scan_expression, options,
            lake_config, [actor_id])
  Output:  ProfileSet
  Errors:  domain-typed exceptions (ProfileOperatorError subclasses)

The function does no I/O outside the bound source — the lake on the
snapshot path, the federated scan's own remote on the scan path. It does
not write profiles to disk, does not push them into Aran's catalog —
those are Aran's job. FREYJA is the *algorithm*; persistence is the
orchestrator.

Concurrency: this function is safe to call from multiple threads with
different sources. Each call gets its own executor (``LakeQueryExecutor``
bound to the snapshot, or ``ScanQueryExecutor`` bound to the scan). A
single shared ``httpx.Client`` (passed via ``shared_http_client``) is
recommended in long-running services for HTTP keep-alive on the lake
path.
"""
from __future__ import annotations

import logging
import time
from uuid import UUID

import httpx

from freyja.executors.lake import (
    LakeConfig,
    LakeQueryError,
    LakeQueryExecutor,
    WrapperSqlRejected,
)
from freyja.executors.scan import (
    FederatedScanRejected,
    ScanQueryError,
    ScanQueryExecutor,
)
from freyja.models.profile import ProfileSet
from freyja.profiling.profiler import ProfileOptions, profile_snapshot

logger = logging.getLogger(__name__)


# ---- exceptions ---- #

class ProfileOperatorError(RuntimeError):
    """Base class for operator-level failures."""


class SnapshotNotFoundError(ProfileOperatorError):
    """The lake reported the snapshot doesn't exist or isn't visible to this tenant."""


class LakeUnavailableError(ProfileOperatorError):
    """The lake is unreachable or returned a transient error."""


class InvalidWrapperSqlError(ProfileOperatorError):
    """
    ``wrapper_sql`` failed FREYJA's pre-flight check
    (``freyja.executors.binding.WrapperSqlRejected``).

    A distinct type from ``LakeUnavailableError`` on purpose: this is the
    caller's mistake, not the lake's — ``profile_api.py`` maps it to 4xx,
    not 502.
    """


class InvalidFederatedScanError(ProfileOperatorError):
    """
    The federated scan expression or a setup statement failed the scan
    executor's fail-closed shape check
    (``freyja.executors.scan.FederatedScanRejected``). The caller's
    mistake — 400, not 502.
    """


class FederatedSourceError(ProfileOperatorError):
    """
    The federated source itself failed — setup, view binding, or a
    profiling query on the live scan. The upstream's fault, not the
    caller's — ``profile_api.py`` maps it to 502 like an unwell lake.
    """


# ---- entrypoint ---- #

def execute_profile(
    *,
    tenant_id: UUID,
    snapshot_id: UUID | None = None,
    options: ProfileOptions = ProfileOptions(),
    lake_config: LakeConfig | None = None,
    actor_id: UUID | None = None,
    shared_http_client: httpx.Client | None = None,
    wrapper_sql: str | None = None,
    scan_expression: str | None = None,
    scan_setup_sql: list[str] | None = None,
) -> ProfileSet:
    """
    Run the PROFILE operator against one snapshot, or against one
    federated scan.

    Exactly one of ``snapshot_id`` and ``scan_expression`` must be set:
    the profiling suite is the same either way — what differs is where
    the ``snapshot`` relation is bound (the lake's view over stored
    bytes, or a live scan of the source in place).

    Parameters
    ----------
    tenant_id
        The tenant that owns the source. Forwarded to the lake as the
        ``X-Tenant-Id`` header on the snapshot path. Aran supplies this;
        FREYJA does not decide it.
    snapshot_id
        The lake snapshot to profile. The lake binds this as the
        ``snapshot`` view in the SQL queries FREYJA composes.
    options
        Per-call profiling options (varchar-only filter, concurrency,
        etc.). On the snapshot path the ``source_label`` field of options
        is overwritten with the snapshot UUID so the resulting ProfileSet
        is self-identifying; on the scan path the caller's label is kept.
    lake_config
        Endpoint + bearer token. Defaults to ``LakeConfig.from_env()``.
        Unused on the scan path.
    actor_id
        Optional user UUID for audit. Forwarded to the lake as
        ``X-Actor-Id``.
    shared_http_client
        Optional ``httpx.Client`` for connection reuse across calls.
        If None, one is created and closed per call. Unused on the scan
        path.
    wrapper_sql
        Optional resolved-view SQL (Aran's ``${SNAPSHOT}``-templated
        wrapper body — see ``freyja.executors.binding`` module docs). When
        set, every column and metric this call profiles comes from the
        wrapper's OUTPUT, not the raw relation — ``row_count`` on the
        returned ``ProfileSet`` reflects that too. ``None`` (the default)
        profiles the raw relation exactly as before this parameter
        existed. FREYJA does not parse this SQL — Aran's
        ``WrapperSqlValidator`` already did — it only rejects a
        semicolon (raises ``InvalidWrapperSqlError``).
    scan_expression
        A federated scan expression (``iceberg_scan('…')``, an attached
        catalog's table, …) to bind ``snapshot`` to, instead of a
        lake snapshot — see ``freyja.executors.scan`` for the fail-closed
        shape check it must pass. The profiling SQL executes over the
        live source; only aggregates come back.
    scan_setup_sql
        Ordered INSTALL/LOAD/CREATE SECRET statements the scan's
        connection needs first. Only meaningful with ``scan_expression``.

    Returns
    -------
    ProfileSet with one ColumnProfile per profiled column.
    """
    if (snapshot_id is None) == (scan_expression is None):
        raise InvalidFederatedScanError(
            "exactly one of snapshot_id and scan_expression must be set"
        )

    # Override the source_label so the returned ProfileSet carries the
    # snapshot UUID without the caller having to thread it through. A
    # federated scan has no snapshot to name, so the caller's label stays.
    options = ProfileOptions(
        varchar_only=options.varchar_only,
        explicit_columns=options.explicit_columns,
        max_concurrent_column_queries=options.max_concurrent_column_queries,
        source_label=str(snapshot_id) if snapshot_id is not None else options.source_label,
    )

    started = time.monotonic()
    logger.info(
        "PROFILE start tenant=%s source=%s varchar_only=%s concurrency=%d wrapper=%s",
        tenant_id,
        # The scan expression itself can carry a DSN — never log it.
        snapshot_id if snapshot_id is not None else "federated-scan",
        options.varchar_only, options.max_concurrent_column_queries,
        wrapper_sql is not None,
    )

    try:
        if scan_expression is not None:
            executor = ScanQueryExecutor(
                scan_expression,
                setup_sql=scan_setup_sql,
                wrapper_sql=wrapper_sql,
            )
        else:
            executor = LakeQueryExecutor(
                config=lake_config if lake_config is not None else LakeConfig.from_env(),
                tenant_id=tenant_id,
                snapshot_id=snapshot_id,
                actor_id=actor_id,
                client=shared_http_client,
                wrapper_sql=wrapper_sql,
            )
    except WrapperSqlRejected as e:
        # Caller's mistake (or Aran's screen missing something FREYJA's
        # cheap check catches) — not the source's, so a distinct type from
        # the LakeQueryError handling below.
        raise InvalidWrapperSqlError(str(e)) from e
    except FederatedScanRejected as e:
        raise InvalidFederatedScanError(str(e)) from e
    except ScanQueryError as e:
        # ScanQueryExecutor touches the remote source at construction
        # (setup + view binding), so an unreachable source or a bad
        # credential surfaces here rather than at the first query.
        raise FederatedSourceError(str(e)) from e

    try:
        try:
            result = profile_snapshot(executor, options)
        except WrapperSqlRejected as e:
            # Unlike the semicolon check above (which fires at construction,
            # before any query), a wrapper output column colliding with
            # the `snapshot` token is only discoverable once fetch_columns()
            # has actually seen the wrapper's resolved schema — i.e. lazily,
            # from inside profile_snapshot(). Same caller mistake, same
            # mapping, just caught one step later: must NOT fall through to
            # the LakeQueryError handling below, whose "not found" heuristic
            # would misreport this as a missing snapshot (404) rather than
            # the caller's actual mistake (400).
            raise InvalidWrapperSqlError(str(e)) from e
        except ScanQueryError as e:
            raise FederatedSourceError(str(e)) from e
        except LakeQueryError as e:
            # Map lake-layer errors to operator-layer types so Aran's
            # dispatcher can decide what to do (retry / 404 / 500).
            msg = str(e)
            if "404" in msg or "not found" in msg.lower():
                raise SnapshotNotFoundError(msg) from e
            raise LakeUnavailableError(msg) from e
    finally:
        executor.close()

    elapsed = time.monotonic() - started
    logger.info(
        "PROFILE done tenant=%s source=%s columns=%d row_count=%d errors=%d elapsed=%.3fs",
        tenant_id,
        snapshot_id if snapshot_id is not None else "federated-scan",
        len(result.columns), result.row_count, len(result.errors), elapsed,
    )
    return result

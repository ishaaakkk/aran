"""
Refactored profiler.

Single entrypoint: ``profile_snapshot(executor, options) -> ProfileSet``.

The profiler is a *library* in the sense called out by the lake
architecture: it does not own a DuckDB connection, does not read CSVs,
does not write files. It receives a ``QueryExecutor`` (either the
local DuckDB one or the lake one) and a small options object, and
returns a structured ``ProfileSet``.

Round-trip behaviour (against the lake):

1.  ``fetch_columns()`` — 1 lake call (or 0 if column list pre-supplied).
2.  ``fetch_row(...)`` for each statement ``ScalarMetrics.build_queries``
    composes — 1 lake call for every scalar metric across every column,
    and more only when the table is too wide to ask for in one statement
    the lake will accept (S13-1).
3.  ``fetch_row(DistributionAndCommonMetrics.build_query(c))``
    — 1 lake call per column.

Total: ``1 + S + C`` round-trips, ``S`` being 1 for any ordinary width.
Concurrency: the per-column queries (step 3) are
independent and can be fanned out across a small thread pool. The
``executor`` is thread-safe (httpx.Client for lake, lock for local
DuckDB).
"""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass

from freyja.executors.base import QueryExecutor
from freyja.models.profile import ColumnProfile, ProfileSet
from freyja.profiling.metrics import (
    ColumnSpec,
    DistributionAndCommonMetrics,
    ScalarMetrics,
)

logger = logging.getLogger(__name__)


# ----- Options ----- #

@dataclass(frozen=True, slots=True)
class ProfileOptions:
    """Per-call profiling options."""

    # Which columns to profile.
    varchar_only: bool = True
    explicit_columns: tuple[str, ...] | None = None   # if set, profile only these

    # Concurrency of the per-column distribution queries. Set to 1 for
    # sequential (predictable behaviour, useful for debugging). 4–8 is a
    # good default against the lake; higher values can saturate the
    # lake's query workers.
    max_concurrent_column_queries: int = 4

    # Logical identifier of *what* we're profiling. Carried into the
    # result for downstream alignment; not used in the SQL. May be a
    # snapshot UUID, a dataset name, or whatever the caller wants.
    source_label: str = "snapshot"


# ----- Top-level profile function ----- #

def profile_snapshot(
    executor: QueryExecutor,
    options: ProfileOptions = ProfileOptions(),
) -> ProfileSet:
    """
    Profile every column the options select. Returns a ``ProfileSet``.

    Pure function in spirit — no side effects, no I/O outside the
    executor calls. Safe to call concurrently with different
    executors.
    """
    # 1. Resolve columns to profile.
    columns = _select_columns(executor, options)
    if not columns:
        logger.warning("no columns matched the profile options")
        return ProfileSet(source_label=options.source_label, columns=[])

    # 2. Scalar metrics for every column: one query for a table narrow
    #    enough to state in one, more for a wide one (S13-1 — the lake caps a
    #    statement at 64 KB and used to refuse the whole suite, hence the
    #    whole profile, from ~16 columns up). Each chunk is self-contained and
    #    reports the same table's row count, so the last one's answer stands.
    row_count = 0
    per_column_scalars: dict[int, dict] = {}
    for scalar_sql, chunk in ScalarMetrics.build_queries(columns):
        scalar_row = executor.fetch_row(scalar_sql)
        row_count, chunk_scalars = ScalarMetrics.parse_result(scalar_row, chunk)
        per_column_scalars.update(chunk_scalars)

    # If the table is empty, every column profiles as None. Short-circuit.
    if row_count == 0:
        logger.info("snapshot is empty (row_count=0); emitting no profiles")
        return ProfileSet(
            source_label=options.source_label,
            columns=[],
            row_count=0,
        )

    # 3. Per-column distribution + common values. Concurrent fan-out.
    distribution_results: dict[int, dict] = {}
    column_errors: dict[int, str] = {}

    columns_with_data = [
        c for c in columns
        if per_column_scalars[c.index]["_non_null"] > 0
    ]

    if options.max_concurrent_column_queries <= 1:
        # Sequential path. Easier to debug.
        for c in columns_with_data:
            try:
                row = executor.fetch_row(
                    DistributionAndCommonMetrics.build_query(c)
                )
                distribution_results[c.index] = (
                    DistributionAndCommonMetrics.parse_result(row, row_count)
                )
            except Exception as e:
                column_errors[c.index] = f"{type(e).__name__}: {e}"
                logger.warning("distribution query failed for %s: %s", c.name, e)
    else:
        with ThreadPoolExecutor(
            max_workers=options.max_concurrent_column_queries,
            thread_name_prefix="freyja-col",
        ) as pool:
            futures = {
                pool.submit(
                    executor.fetch_row,
                    DistributionAndCommonMetrics.build_query(c),
                ): c
                for c in columns_with_data
            }
            for fut in as_completed(futures):
                c = futures[fut]
                try:
                    row = fut.result()
                    distribution_results[c.index] = (
                        DistributionAndCommonMetrics.parse_result(row, row_count)
                    )
                except Exception as e:
                    column_errors[c.index] = f"{type(e).__name__}: {e}"
                    logger.warning(
                        "distribution query failed for %s: %s", c.name, e,
                    )

    # 4. Stitch scalar + distribution into one ColumnProfile per column.
    profiles: list[ColumnProfile] = []
    for c in columns:
        scalar = per_column_scalars[c.index]
        non_null = scalar.pop("_non_null")
        if non_null == 0:
            logger.warning(
                "column %s is entirely NULL — excluded from profile; "
                "downstream join discovery will not see this column",
                c.name,
            )
            continue
        dist = distribution_results.get(c.index)
        if dist is None and c.index not in column_errors:
            # Distribution query was never scheduled — shouldn't happen.
            continue
        merged = {**scalar, **(dist or {})}
        profiles.append(
            ColumnProfile(
                column_name=c.name,
                column_index=c.index,
                source_type=c.sql_type,
                metrics=_round_floats(merged),
            )
        )

    return ProfileSet(
        source_label=options.source_label,
        columns=profiles,
        row_count=row_count,
        errors={c.name: err for c, err in (
            (next((cc for cc in columns if cc.index == i), None), err)
            for i, err in column_errors.items()
        ) if c is not None},
    )


# ----- Helpers ----- #

def _select_columns(
    executor: QueryExecutor,
    options: ProfileOptions,
) -> list[ColumnSpec]:
    """Decide which columns to profile, given the options."""
    schema = executor.fetch_columns()  # [(name, sql_type), ...]

    if options.explicit_columns is not None:
        wanted = set(options.explicit_columns)
        filtered = [(n, t) for n, t in schema if n in wanted]
    elif options.varchar_only:
        # Match the old behavior — VARCHAR + BOOLEAN.
        filtered = [
            (n, t) for n, t in schema
            if t.upper() in ("VARCHAR", "BOOLEAN", "STRING", "TEXT")
        ]
    else:
        filtered = list(schema)

    return [
        ColumnSpec(name=n, index=i, sql_type=t)
        for i, (n, t) in enumerate(filtered)
    ]


def _round_floats(metrics: dict, decimals: int = 9) -> dict:
    """Round floats to keep profile diffs stable."""
    def _r(v):
        if isinstance(v, float):
            return round(v, decimals)
        if isinstance(v, list):
            return [_r(x) for x in v]
        return v
    return {k: _r(v) for k, v in metrics.items()}

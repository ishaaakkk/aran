"""
Aran-facing API schemas.

Replaces the path-based ``app/models/schemas.py``. Everything is
UUID-keyed; nothing accepts or returns a filesystem path.

These models are the wire shape between Aran and FREYJA's optional
sidecar. The library-mode entrypoint (``execute_profile``) uses the
native types (``UUID``, ``ProfileOptions``) and doesn't go through
Pydantic at all — the API is the only place that validates.
"""
from __future__ import annotations

from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

# ---- Profile ---- #

class FederatedScan(BaseModel):
    """
    A live scan to bind the ``snapshot`` relation to, instead of a lake
    snapshot — federated profiling's whole transport. The expression and
    setup statements are built by Aran from the same single authorities
    the query path uses; ``freyja.executors.scan`` re-screens both
    fail-closed. They can carry credentials (a DSN, a CREATE SECRET), so
    they are never logged and never echoed into a response.
    """

    model_config = ConfigDict(extra="forbid")

    expression: str = Field(
        description=(
            "A DuckDB table-function call (iceberg_scan/read_parquet over a "
            "remote URL), or a table of a Postgres or MySQL catalog the setup "
            "attaches, exactly as the query engine's snapshot "
            "resolver emits it."
        ),
    )
    setup_sql: list[str] = Field(
        default_factory=list,
        description=(
            "Ordered INSTALL/LOAD/CREATE SECRET statements (plus, for a "
            "relational source, a read-only ATTACH, and for MySQL one "
            "setting) the scan's connection needs "
            "before the expression can bind."
        ),
    )


class ProfileRequest(BaseModel):
    """The PROFILE operator request from Aran."""

    model_config = ConfigDict(extra="forbid")

    tenant_id: UUID = Field(
        description="Tenant that owns the source. Aran supplies this.",
    )
    snapshot_id: UUID | None = Field(
        default=None,
        description=(
            "Lake snapshot to profile. The lake binds it as 'snapshot'. "
            "Exactly one of snapshot_id and federated_scan must be set."
        ),
    )
    federated_scan: FederatedScan | None = Field(
        default=None,
        description=(
            "Profile over this live federated scan instead of a lake "
            "snapshot — the profiling SQL executes against the source in "
            "place and only aggregates come back."
        ),
    )

    @model_validator(mode="after")
    def _exactly_one_source(self) -> ProfileRequest:
        if (self.snapshot_id is None) == (self.federated_scan is None):
            raise ValueError(
                "exactly one of snapshot_id and federated_scan must be set"
            )
        return self

    # --- options ---
    varchar_only: bool = Field(
        default=True,
        description=(
            "If true (default), profile only VARCHAR/BOOLEAN columns. "
            "Profiling numeric columns slows the process and is rarely "
            "useful for join discovery — flip only when you actually "
            "need it."
        ),
    )
    explicit_columns: list[str] | None = Field(
        default=None,
        description=(
            "If set, profile only these columns (overrides varchar_only). "
            "Use when Aran already knows which wrapper columns matter."
        ),
    )
    max_concurrent_column_queries: int = Field(
        default=4, ge=1, le=32,
        description="Per-column distribution-query concurrency.",
    )

    # --- wrapper-output profiling (Phase 3B, §3.1) ---
    wrapper_sql: str | None = Field(
        default=None,
        description=(
            "When set, profile this wrapper's resolved output instead of "
            "the raw snapshot. Uses Aran's ${SNAPSHOT} placeholder for the "
            "bound view, exactly like a stored nx:wrapperSql. Must be a "
            "single statement (no semicolons) — FREYJA does not parse "
            "SQL; Aran's WrapperSqlValidator already screened it before "
            "this request was sent."
        ),
    )

    # --- optional audit context ---
    actor_id: UUID | None = Field(
        default=None,
        description="Optional user UUID forwarded to the lake as X-Actor-Id.",
    )


class ColumnProfileResponse(BaseModel):
    """One column's profile, the wire shape."""

    model_config = ConfigDict(extra="forbid")

    column_name: str
    column_index: int
    source_type: str
    # Metrics dict is intentionally untyped at the wire layer — the
    # metric set is a research-evolving artifact and we don't want to
    # version-lock the schema on every new metric. Consumers iterate
    # over ``METRICS`` from ``profiling/metrics.py`` for the canonical
    # list.
    metrics: dict[str, Any]


class ProfileResponse(BaseModel):
    """The PROFILE operator response to Aran."""

    model_config = ConfigDict(extra="forbid")

    tenant_id: UUID
    # None on the federated-scan path, which profiles no lake snapshot.
    snapshot_id: UUID | None = None
    row_count: int
    columns: list[ColumnProfileResponse]
    errors: dict[str, str] = Field(
        default_factory=dict,
        description="Map of column_name to per-column failure message.",
    )
    elapsed_seconds: float | None = None


# ---- Distance ---- #
# Distances are an in-process step in Aran's pipeline (PROFILE → ALIGN);
# they don't talk to the lake. The schemas exist so the sidecar can
# expose them for development/debugging, but Aran's production path
# probably calls the library directly.

class DistancesRequest(BaseModel):
    """
    Compute distances between one query column and a candidate set.

    Profiles are passed in (from Aran's catalog) — FREYJA does not
    fetch them.
    """

    model_config = ConfigDict(extra="forbid")

    tenant_id: UUID
    query: ColumnProfileResponse = Field(
        description="The query column's profile (raw, not normalized)."
    )
    candidates: list[ColumnProfileResponse] = Field(
        description="Candidate column profiles to compare against."
    )
    population_stats: dict[str, dict[str, float]] | None = Field(
        default=None,
        description=(
            "Z-score normalization parameters keyed by metric name: "
            "``{metric: {mean: m, std: s}, ...}``. If None, distances "
            "are computed on raw values — typically only sensible for "
            "training data, not online discovery."
        ),
    )


class DistanceRow(BaseModel):
    model_config = ConfigDict(extra="forbid")

    candidate_column: str
    candidate_index: int
    metric_distances: dict[str, float]
    name_distance: float


class DistancesResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tenant_id: UUID
    query_column: str
    distances: list[DistanceRow]


# ---- Model / ranking ---- #

class RankingRequest(BaseModel):
    """Rank candidates by predicted join quality."""

    model_config = ConfigDict(extra="forbid")

    tenant_id: UUID
    distances: list[DistanceRow]
    top_k: int = Field(default=10, ge=1, le=10_000)
    method: str = Field(default="ml", description="Method to rank: 'ml' (predict using GB model) or 'exact' (calculate using MJ and K)")
    strictness: float = Field(default=0.25, ge=0.0, le=1.0, description="Strictness parameter for exact join quality calculation")


class RankedCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    candidate_column: str
    candidate_index: int
    score: float
    rank: int


class RankingResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tenant_id: UUID
    ranking: list[RankedCandidate]

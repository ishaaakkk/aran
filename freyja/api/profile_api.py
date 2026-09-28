"""
PROFILE operator — optional FastAPI sidecar.

Deploy this if Aran cannot import FREYJA in-process and prefers to
call it over HTTP. The library entrypoint
(``operator.profile_operator.execute_profile``) is the canonical
surface; this file is a ~50-line wrapper around it.

Three deliberate choices:

1. **Shared HTTP client.** A single ``httpx.Client`` is created on
   startup and shared across all requests (keep-alive to the lake).
   Closed on shutdown.

2. **No model loading here.** This sidecar exposes only PROFILE.
   Distance and ranking endpoints would each be similar 10-line
   wrappers around their library functions; omitted for clarity.

3. **No file paths anywhere.** The API takes UUIDs and returns
   structured data. If you find yourself adding a ``path`` field,
   you're using the wrong sidecar — that's the research API
   (``app/api/*`` in the old code).
"""
from __future__ import annotations

import logging
import os
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import httpx
import joblib
import joblib.numpy_pickle
import Levenshtein
import pandas as pd
from fastapi import APIRouter, Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import JSONResponse

from freyja.api.security import require_bearer, resolve_caller_secrets
from freyja.executors.lake import LakeConfig
from freyja.models.schemas import (
    ColumnProfileResponse,
    DistanceRow,
    DistancesRequest,
    DistancesResponse,
    ProfileRequest,
    ProfileResponse,
    RankedCandidate,
    RankingRequest,
    RankingResponse,
)
from freyja.operator.profile_operator import (
    FederatedSourceError,
    InvalidFederatedScanError,
    InvalidWrapperSqlError,
    LakeUnavailableError,
    SnapshotNotFoundError,
    execute_profile,
)
from freyja.profiling import value_sketch
from freyja.profiling.profiler import ProfileOptions

logger = logging.getLogger(__name__)

# The pre-trained ML model for join discovery.
MODEL_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "models", "model.pkl")


class _CompatibleUnpickler(joblib.numpy_pickle.NumpyUnpickler):
    def find_class(self, module, name):
        if module == '_loss' or module.startswith('_loss'):
            import sklearn._loss
            if hasattr(sklearn._loss, name):
                return getattr(sklearn._loss, name)
            import sklearn._loss.loss
            if hasattr(sklearn._loss.loss, name):
                return getattr(sklearn._loss.loss, name)
            import sklearn._loss._loss
            if hasattr(sklearn._loss._loss, name):
                return getattr(sklearn._loss._loss, name)
        return super().find_class(module, name)


def _load_model() -> Any | None:
    """Load the join-discovery estimator, or return None with the reason logged."""
    if not os.path.exists(MODEL_PATH):
        logger.warning("Pre-trained ML model NOT found at %s", MODEL_PATH)
        return None
    try:
        loaded = joblib.load(MODEL_PATH)
        logger.info("Loaded pre-trained ML model for join discovery from %s", MODEL_PATH)
        return loaded
    except Exception as e:
        logger.warning("Standard joblib.load failed (%s), attempting compatible unpickler...", e)
        try:
            with open(MODEL_PATH, 'rb') as f:
                u = _CompatibleUnpickler(MODEL_PATH, f, ensure_native_byte_order=False)
                loaded = u.load()
                logger.info("Loaded pre-trained ML model using compatible unpickler from %s", MODEL_PATH)
                return loaded
        except Exception as e2:
            logger.exception("Failed to load pre-trained ML model from %s: %s", MODEL_PATH, e2)
            return None


def get_optional_model(http_request: Request) -> Any | None:
    """The estimator, or None. Optional on purpose: ``method="exact"`` needs no model."""
    return getattr(http_request.app.state, "model", None)


def compute_levenshtein(a, b) -> int:
    """Calculate Levenshtein distance between two values."""
    if a is None and b is None:
        return 0
    if a is None:
        return len(str(b))
    if b is None:
        return len(str(a))
    return Levenshtein.distance(str(a), str(b))



# ---- lifespan: shared HTTP client to the lake ---- #

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    # Before anything else. An unauthenticated FREYJA is a cross-tenant read
    # primitive (see freyja/api/security.py), so a process that cannot authenticate
    # callers must not reach the point of listening on a port. Raising here fails
    # uvicorn's startup, which is the loud, unmissable failure we want — as opposed
    # to the warning-per-request this replaced.
    resolve_caller_secrets()

    cfg = LakeConfig.from_env()
    # We use the sync httpx.Client because the FREYJA library is sync.
    # FastAPI runs sync route handlers in a thread pool, so this is fine.
    client = httpx.Client(timeout=cfg.timeout_seconds)
    app.state.lake_config = cfg
    app.state.http = client
    app.state.model = _load_model()
    logger.info(
        "FREYJA sidecar ready — lake=%s, join-discovery model=%s",
        cfg.endpoint,
        "loaded" if app.state.model is not None else "MISSING (rank will answer 503)",
    )
    try:
        yield
    finally:
        client.close()


app = FastAPI(title="FREYJA — PROFILE operator", lifespan=lifespan)


# Auth is declared on the ROUTER, not on each endpoint, so an endpoint added later
# cannot be reachable unauthenticated by forgetting a decorator. /healthz is
# registered on the app instead and stays open for probes.
router = APIRouter(
    prefix="/operators",
    tags=["profile"],
    dependencies=[Depends(require_bearer)],
)


@router.post("/profile", response_model=ProfileResponse)
def profile(request: ProfileRequest) -> ProfileResponse:
    """Execute the PROFILE operator against one snapshot or one federated scan."""
    options = ProfileOptions(
        varchar_only=request.varchar_only,
        explicit_columns=(
            tuple(request.explicit_columns)
            if request.explicit_columns is not None else None
        ),
        max_concurrent_column_queries=request.max_concurrent_column_queries,
        # source_label: overwritten inside execute_profile on the snapshot
        # path; the federated-scan path has no snapshot to name.
        source_label=(
            str(request.snapshot_id)
            if request.snapshot_id is not None else "federated-scan"
        ),
    )

    started = time.monotonic()
    try:
        result = execute_profile(
            tenant_id=request.tenant_id,
            snapshot_id=request.snapshot_id,
            options=options,
            lake_config=app.state.lake_config,
            actor_id=request.actor_id,
            shared_http_client=app.state.http,
            wrapper_sql=request.wrapper_sql,
            scan_expression=(
                request.federated_scan.expression
                if request.federated_scan is not None else None
            ),
            scan_setup_sql=(
                request.federated_scan.setup_sql
                if request.federated_scan is not None else None
            ),
        )
    except (InvalidWrapperSqlError, InvalidFederatedScanError) as e:
        # The caller's mistake (bad wrapper_sql or a scan expression that
        # fails the fail-closed shape check), not an upstream's — 400,
        # not the 502/500 below meant for an unwell upstream.
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=str(e),
        ) from e
    except SnapshotNotFoundError as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=str(e),
        ) from e
    except (LakeUnavailableError, FederatedSourceError) as e:
        # 502 — we are reachable, but our upstream (the lake, or the
        # federated source itself) isn't.
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail=str(e),
        ) from e
    except Exception as e:
        logger.exception("profile operator failed")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e),
        ) from e

    elapsed = time.monotonic() - started
    return ProfileResponse(
        tenant_id=request.tenant_id,
        snapshot_id=request.snapshot_id,
        row_count=result.row_count,
        columns=[
            ColumnProfileResponse(
                column_name=c.column_name,
                column_index=c.column_index,
                source_type=c.source_type,
                metrics=c.metrics,
            )
            for c in result.columns
        ],
        errors=result.errors,
        elapsed_seconds=round(elapsed, 4),
    )


@router.post("/distance", response_model=DistancesResponse)
def distance(request: DistancesRequest) -> DistancesResponse:
    from freyja.profiling.metrics import DISTANCE_PATTERNS

    query = request.query
    distances = []
    unsampled: set[str] = set()

    for c in request.candidates:
        metric_distances = {}
        for col_name, pattern in DISTANCE_PATTERNS.items():
            q_val = query.metrics.get(col_name)
            c_val = c.metrics.get(col_name)

            if pattern == "substraction":
                if q_val is None or c_val is None:
                    dist_val = 0.0
                else:
                    try:
                        dist_val = float(q_val) - float(c_val)
                    except (ValueError, TypeError):
                        dist_val = 0.0
            elif pattern == "containment":
                if q_val and c_val:
                    q_set = set(q_val)
                    c_set = set(c_val)
                    union = len(q_set | c_set)
                    dist_val = len(q_set & c_set) / union if union > 0 else 0.0
                else:
                    dist_val = 0.0
            elif pattern == "levenshtein":
                dist_val = float(compute_levenshtein(q_val, c_val))
            else:
                dist_val = 0.0

            metric_distances[col_name] = dist_val

        name_dist = float(compute_levenshtein(query.column_name, c.column_name))

        q_card = float(query.metrics.get("cardinality") or 0.0)
        c_card = float(c.metrics.get("cardinality") or 0.0)

        # MJ over each column's bottom-k value-hash sample (value_sketch), never over its
        # top-10 list: the top-10 is chosen by frequency, so a unique key's is its
        # lexicographic prefix — shared by every other surrogate key — and a foreign key's
        # rarely meets it. Scoring from those lists ranked an unrelated 1..300 serial column
        # above the real foreign key of a 1..1000 key (S13-2's closing review). A pair
        # without a sample on both sides is left UNSCORED — no exact_mj — rather than scored
        # from the top-10 again: rank() then gives it 0, and discovery drops a zero.
        q_sample = value_sketch.sample_of(query.metrics)
        c_sample = value_sketch.sample_of(c.metrics)
        if q_sample is not None and c_sample is not None:
            metric_distances["exact_mj"] = value_sketch.multiset_jaccard(q_sample, c_sample)
        else:
            unsampled.update(
                col.column_name for col, sample in ((query, q_sample), (c, c_sample))
                if sample is None
            )

        max_card = max(q_card, c_card)
        exact_k = min(q_card, c_card) / max_card if max_card > 0 else 0.0

        metric_distances["exact_k"] = exact_k

        distances.append(DistanceRow(
            candidate_column=c.column_name,
            candidate_index=c.column_index,
            metric_distances=metric_distances,
            name_distance=name_dist
        ))

    if unsampled:
        # The only trace of an unscored pair: discovery drops its zero score without a word.
        logger.warning(
            "exact join score: no value sample for %s — profiled before samples existed; "
            "re-run profiling to score these columns", sorted(unsampled),
        )

    return DistancesResponse(
        tenant_id=request.tenant_id,
        query_column=query.column_name,
        distances=distances
    )


@router.post("/rank", response_model=RankingResponse)
def rank(
    request: RankingRequest,
    model: Any | None = Depends(get_optional_model),
) -> RankingResponse:
    candidates = []

    if request.method == "exact":
        from scipy.stats import truncnorm

        strictness = request.strictness
        mu_v = [0.44 + 0.25 * strictness, 0.0 + 0.25 * strictness]
        sigma_v = [0.19, 0.28]

        a1, b1 = (-mu_v[0]) / sigma_v[0], (1.0 - mu_v[0]) / sigma_v[0]
        a2, b2 = (-mu_v[1]) / sigma_v[1], (1.0 - mu_v[1]) / sigma_v[1]

        MJmodel = truncnorm(a1, b1, scale=sigma_v[0], loc=mu_v[0])
        Kmodel = truncnorm(a2, b2, scale=sigma_v[1], loc=mu_v[1])

        for d in request.distances:
            # An unscored pair (/distance found no value sample on one side) has no exact_mj;
            # the MJ prior's CDF at 0 is 0, so it scores 0 and discovery drops it.
            mj = d.metric_distances.get("exact_mj", 0.0)
            k = d.metric_distances.get("exact_k", 0.0)

            score = float(MJmodel.cdf(mj) * Kmodel.cdf(k))
            candidates.append({
                "candidate_column": d.candidate_column,
                "candidate_index": d.candidate_index,
                "score": score
            })
    else:
        if model is None:
            # 503, not 500: the caller did nothing wrong — the sidecar is not able to serve
            # this method. /healthz reports the same condition, so an orchestrator can act.
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Gradient Boosting model is not loaded in sidecar",
            )

        rows = []
        for d in request.distances:
            row = {
                "name_dist": d.name_distance,
                **d.metric_distances
            }
            rows.append(row)

        df = pd.DataFrame(rows)

        features = [
            'name_dist', 'frequency_max', 'uniqueness', 'first_word', 'frequency_4qo',
            'freq_word_containment', 'len_avg_word', 'words_cnt_max', 'frequency_6qo',
            'len_max_word', 'frequency_min', 'frequency_3qo', 'is_empty', 'frequency_iqr',
            'entropy', 'val_pct_std', 'words_cnt_min', 'cardinality', 'words_cnt_sd',
            'val_pct_max', 'len_min_word', 'words_cnt_avg'
        ]

        for f in features:
            if f not in df.columns:
                df[f] = 0.0

        df = df[features]

        distances_numeric = df.apply(pd.to_numeric, errors='coerce')
        distances_numeric = distances_numeric.fillna(0.0)

        try:
            predicted_scores = model.predict(distances_numeric)
        except Exception as e:
            logger.exception("Failed to run model prediction: %s", e)
            raise HTTPException(status_code=500, detail=f"Model prediction failed: {e}") from e

        for i, d in enumerate(request.distances):
            score = float(predicted_scores[i])
            candidates.append({
                "candidate_column": d.candidate_column,
                "candidate_index": d.candidate_index,
                "score": score
            })

    candidates.sort(key=lambda x: x["score"], reverse=True)

    ranked_candidates = []
    for rank_idx, cand in enumerate(candidates, 1):
        ranked_candidates.append(RankedCandidate(
            candidate_column=cand["candidate_column"],
            candidate_index=cand["candidate_index"],
            score=cand["score"],
            rank=rank_idx
        ))

    return RankingResponse(
        tenant_id=request.tenant_id,
        ranking=ranked_candidates
    )


app.include_router(router)


@app.get("/healthz")
def healthz(http_request: Request) -> JSONResponse:
    """Reports the model, so a missing one fails the probe instead of surfacing per-request.

    This used to return "ok" unconditionally, which meant a sidecar that had failed to load
    its estimator passed every health check and then answered every rank request with an
    error.
    """
    model_loaded = getattr(http_request.app.state, "model", None) is not None
    body = {"status": "ok" if model_loaded else "degraded", "model": "loaded" if model_loaded else "missing"}
    code = status.HTTP_200_OK if model_loaded else status.HTTP_503_SERVICE_UNAVAILABLE
    return JSONResponse(status_code=code, content=body)

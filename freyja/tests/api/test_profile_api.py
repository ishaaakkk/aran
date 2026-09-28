import logging
import os
import random
from unittest.mock import MagicMock, patch

import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from fastapi.encoders import jsonable_encoder
from fastapi.testclient import TestClient
from freyja.api.profile_api import app, compute_levenshtein, get_optional_model
from freyja.executors.scan import ScanQueryExecutor
from freyja.models.profile import ColumnProfile, ProfileSet
from freyja.operator.profile_operator import InvalidWrapperSqlError, SnapshotNotFoundError
from freyja.profiling.profiler import ProfileOptions, profile_snapshot
from freyja.profiling.value_sketch import value_hash
from freyja.tests.executors.test_lake import _FakeLake
from freyja.tests.executors.test_scan import (
    _PG_UNREACHABLE_TABLE,
    _SENTINEL,
    _postgres_attach_setup,
    _postgres_extension_loads,
)

# The sidecar refuses to start without a shared secret, and rejects operator
# calls that do not present it. Both are deliberate — see freyja/api/security.py.
AUTH_SECRET = "test-shared-secret"
AUTH = {"Authorization": f"Bearer {AUTH_SECRET}"}

def test_compute_levenshtein():
    assert compute_levenshtein(None, None) == 0
    assert compute_levenshtein(None, "hello") == 5
    assert compute_levenshtein("hello", None) == 5
    assert compute_levenshtein("hello", "hello") == 0
    assert compute_levenshtein("kitten", "sitting") == 3


@patch("freyja.api.profile_api.execute_profile")
@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_success(mock_execute):
    mock_execute.return_value = ProfileSet(
        source_label="tenant1-snap1",
        row_count=100,
        columns=[
            ColumnProfile(
                column_name="my_col",
                column_index=0,
                source_type="VARCHAR",
                metrics={"cardinality": 10, "entropy": 2.5}
            )
        ],
        errors={}
    )

    with TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "snapshot_id": "00000000-0000-0000-0000-000000000001",
                "actor_id": "00000000-0000-0000-0000-000000000003",
                "varchar_only": True
            }
        )

    assert response.status_code == 200
    data = response.json()
    assert data["row_count"] == 100
    assert len(data["columns"]) == 1
    assert data["columns"][0]["column_name"] == "my_col"
    assert data["columns"][0]["metrics"]["cardinality"] == 10

    # Regression pin: a request that never mentions wrapper_sql must still
    # reach execute_profile with an explicit None, not an omitted kwarg.
    _, kwargs = mock_execute.call_args
    assert kwargs["wrapper_sql"] is None


@patch("freyja.api.profile_api.execute_profile")
@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_forwards_wrapper_sql(mock_execute):
    mock_execute.return_value = ProfileSet(source_label="tenant1-snap1", row_count=4, columns=[], errors={})

    with TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "snapshot_id": "00000000-0000-0000-0000-000000000001",
                "wrapper_sql": "SELECT * FROM ${SNAPSHOT} UNION ALL SELECT * FROM ${SNAPSHOT}",
            },
        )

    assert response.status_code == 200
    assert response.json()["row_count"] == 4
    _, kwargs = mock_execute.call_args
    assert kwargs["wrapper_sql"] == "SELECT * FROM ${SNAPSHOT} UNION ALL SELECT * FROM ${SNAPSHOT}"


@patch("freyja.api.profile_api.execute_profile")
@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_rejects_invalid_wrapper_sql(mock_execute):
    # Whatever Aran's own screen missed, FREYJA's cheap semicolon check
    # catches — and it must surface as 4xx (the caller's mistake), not the
    # 500/502 paths that mean "the lake itself is unwell".
    mock_execute.side_effect = InvalidWrapperSqlError("wrapper_sql must be a single statement (no semicolons)")

    with TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "snapshot_id": "00000000-0000-0000-0000-000000000001",
                "wrapper_sql": "SELECT 1; DROP TABLE x",
            },
        )

    assert response.status_code == 400
    assert "semicolon" in response.json()["detail"].lower()


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_rejects_wrapper_output_column_named_snapshot():
    """
    The exact repro reported in review, through the REAL chain end to end —
    execute_profile is NOT mocked here, unlike the other wrapper_sql tests
    above, specifically because this is exercising the lazy-discovery path
    (WrapperSqlRejected raised from inside fetch_columns(), deep inside
    profile_snapshot()) rather than the API layer's own exception mapping in
    isolation. Must be 400 with the clear message — NOT the 404 a
    misdiagnosed "not found" would otherwise produce.
    """
    lake = _FakeLake(rows=[(1, "a"), (2, "b")], columns=["id", "val"])

    with TestClient(app) as client:
        # Swap the sidecar's shared HTTP client for one whose transport is
        # backed by a real in-memory DuckDB standing in for the lake — see
        # freyja/tests/executors/test_lake.py's module docstring.
        app.state.http = lake.client()
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "snapshot_id": "00000000-0000-0000-0000-000000000001",
                "wrapper_sql": "SELECT val AS snapshot FROM ${SNAPSHOT}",
            },
        )

    assert response.status_code == 400
    assert response.status_code != 404
    assert "collides with the profiling relation binding" in response.json()["detail"]


@patch("freyja.api.profile_api.execute_profile")
@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_forwards_federated_scan(mock_execute):
    mock_execute.return_value = ProfileSet(
        source_label="federated-scan", row_count=7, columns=[], errors={})

    with TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "federated_scan": {
                    "expression": "iceberg_scan('s3://b/metadata.json')",
                    "setup_sql": ["INSTALL httpfs", "LOAD httpfs"],
                },
                "wrapper_sql": "SELECT * FROM ${SNAPSHOT}",
                "varchar_only": False,
            },
        )

    assert response.status_code == 200
    # No snapshot on the federated path — the response says so honestly.
    assert response.json()["snapshot_id"] is None
    _, kwargs = mock_execute.call_args
    assert kwargs["snapshot_id"] is None
    assert kwargs["scan_expression"] == "iceberg_scan('s3://b/metadata.json')"
    assert kwargs["scan_setup_sql"] == ["INSTALL httpfs", "LOAD httpfs"]


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_rejects_local_read_in_federated_scan():
    # Through the REAL chain: the scan executor's fail-closed screen fires
    # before any DuckDB work, and the API maps it to 400 — the caller's
    # mistake, not an unwell upstream.
    with TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "federated_scan": {"expression": "read_parquet('/etc/passwd')"},
            },
        )

    assert response.status_code == 400
    assert "remote" in response.json()["detail"]


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_never_echoes_a_federated_dsn_password(caplog):
    # Through the REAL chain, against a port nothing listens on: the ATTACH
    # that dials the source fails, Aran logs this 502's body and stores it as
    # the job's error message, so no DuckDB text may carry the password.
    if not _postgres_extension_loads():
        pytest.skip("DuckDB's postgres extension is not installable here")
    caplog.set_level(logging.DEBUG)
    with TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "federated_scan": {
                    "expression": _PG_UNREACHABLE_TABLE,
                    "setup_sql": _postgres_attach_setup(_SENTINEL),
                },
            },
        )

    assert response.status_code == 502
    detail = response.json()["detail"]
    assert "hunter2" not in detail
    assert "127.0.0.1" in detail, "the connection diagnostic survives"
    assert "hunter2" not in caplog.text


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_rejects_snapshot_plus_federated_scan():
    # The request model itself refuses an ambiguous source — 422 from
    # pydantic, before any operator code runs.
    with TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "snapshot_id": "00000000-0000-0000-0000-000000000001",
                "federated_scan": {"expression": "iceberg_scan('s3://b/m.json')"},
            },
        )

    assert response.status_code == 422


@patch("freyja.api.profile_api.execute_profile")
@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_profile_endpoint_not_found(mock_execute):
    mock_execute.side_effect = SnapshotNotFoundError("Snapshot not found")

    with TestClient(app) as client:
        response = client.post(
            "/operators/profile",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "snapshot_id": "00000000-0000-0000-0000-000000000001",
                "actor_id": "00000000-0000-0000-0000-000000000003"
            }
        )

    assert response.status_code == 404
    assert "not found" in response.json()["detail"].lower()


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_healthz():
    with TestClient(app) as client:
        response = client.get("/healthz")
    assert response.status_code == 200
    # The probe reports the join-discovery model too: a sidecar that failed to load it used
    # to pass every health check and then fail every rank request.
    assert response.json() == {"status": "ok", "model": "loaded"}


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_distance_endpoint():
    with TestClient(app) as client:
        response = client.post(
            "/operators/distance",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "query": {
                    "column_name": "id",
                    "column_index": 0,
                    "source_type": "VARCHAR",
                    "metrics": {
                        "cardinality": 100,
                        "freq_word_containment": ["a", "b", "c"],
                        "value_hash_sample": [value_hash(v) for v in "abc"],
                    }
                },
                "candidates": [
                    {
                        "column_name": "id",
                        "column_index": 0,
                        "source_type": "VARCHAR",
                        "metrics": {
                            "cardinality": 100,
                            "freq_word_containment": ["a", "b", "c"],
                            "value_hash_sample": [value_hash(v) for v in "abc"],
                        }
                    },
                    {
                        "column_name": "user_id",
                        "column_index": 1,
                        "source_type": "VARCHAR",
                        "metrics": {
                            "cardinality": 50,
                            "freq_word_containment": ["x", "y"],
                            "value_hash_sample": [value_hash(v) for v in "xy"],
                        }
                    }
                ]
            }
        )
    assert response.status_code == 200
    data = response.json()
    assert data["query_column"] == "id"
    assert len(data["distances"]) == 2

    # Perfect match
    d1 = data["distances"][0]
    assert d1["candidate_column"] == "id"
    assert d1["name_distance"] == 0.0
    # Identical value samples: Jaccard 1, which is MJ 0.5 on the paper's scale.
    assert d1["metric_distances"]["exact_mj"] == 0.5
    assert d1["metric_distances"]["exact_k"] == 1.0  # min(100,100)/max(100,100)
    assert d1["metric_distances"]["cardinality"] == 0.0

    # Disjoint match
    d2 = data["distances"][1]
    assert d2["candidate_column"] == "user_id"
    assert d2["name_distance"] > 0
    assert d2["metric_distances"]["exact_mj"] == 0.0
    assert d2["metric_distances"]["exact_k"] == 0.5  # 50 / 100


# aran-web/src/lib/joinScore.ts colours an 'exact' score from these; the anchor test below
# derives them, and the regressions assert against them.
EXACT_STRONG = 0.29
EXACT_GOOD = 0.14

_ENV = {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret",
        "FREYJA_SHARED_SECRET": AUTH_SECRET}
_TENANT = "00000000-0000-0000-0000-000000000002"


def _profiled(tmp_path, table, **columns):
    """Profile generated rows with the real profiler SQL and return each column as Aran ships
    it to /distance — so a test's value sample is whatever the profiler really computes, not a
    list someone chose."""
    path = tmp_path / f"{table}.parquet"
    data = {name: pa.array(values, pa.string()) for name, values in columns.items()}
    pq.write_table(pa.table(data), path)
    with ScanQueryExecutor(f"read_parquet('{path}')", allow_local_files=True) as ex:
        profiled = profile_snapshot(ex, ProfileOptions())
    return {
        c.column_name: {
            "column_name": f"{table}.{c.column_name}",
            "column_index": c.column_index,
            "source_type": c.source_type,
            "metrics": jsonable_encoder(c.metrics),
        }
        for c in profiled.columns
    }


def _ranking(client, query, candidates):
    distances = client.post(
        "/operators/distance", headers=AUTH,
        json={"tenant_id": _TENANT, "query": query, "candidates": candidates},
    ).json()["distances"]
    ranking = client.post(
        "/operators/rank", headers=AUTH,
        json={"tenant_id": _TENANT, "method": "exact", "strictness": 0.25,
              "distances": distances},
    ).json()["ranking"]
    return [(r["candidate_column"], r["score"]) for r in ranking], distances


def _score(client, query, candidate):
    return _ranking(client, query, [candidate])[0][0][1]


def _legacy(name, cardinality, top_values):
    """A profile persisted before the value sample existed: top values, no sample."""
    return {
        "column_name": name,
        "column_index": 0,
        "source_type": "VARCHAR",
        "metrics": {"cardinality": cardinality, "freq_word_containment": top_values},
    }


@patch.dict(os.environ, _ENV)
def test_a_skewed_foreign_key_outranks_unrelated_serial_keys_against_its_primary_key(tmp_path):
    """S13-2's closing review. customers.id is a 1..1000 key; orders.customer_id references
    it at random. A unique column's top-10 is its lexicographic prefix ('1', '10', '100', …),
    which products.id (1..300) shares almost entirely, while the foreign key's top-10 is picked
    by reference count — so scoring from top-10 lists ranked products.id first (0.26, the
    'good' band) and the real foreign key LAST (0.005). The value-hash sample is drawn alike
    from every column of one domain, and the foreign key comes first, in the strong band."""
    rng = random.Random(20260924)
    customers = _profiled(tmp_path, "customers", id=[str(i) for i in range(1, 1001)])
    orders = _profiled(
        tmp_path, "orders",
        id=[str(i) for i in range(1, 5001)],
        customer_id=[str(rng.randint(1, 1000)) for _ in range(5000)],
    )
    products = _profiled(tmp_path, "products", id=[str(i) for i in range(1, 301)])

    # The fixture must be the case that failed: a foreign key whose most frequent values are
    # not the key's lexicographic prefix. Handing both the same top-10 would pass either way.
    top10 = "freq_word_containment"
    assert set(orders["customer_id"]["metrics"][top10]) != set(customers["id"]["metrics"][top10])

    with TestClient(app) as client:
        ranking, _ = _ranking(
            client, customers["id"], [products["id"], orders["id"], orders["customer_id"]])

    assert [name for name, _ in ranking] == ["orders.customer_id", "products.id", "orders.id"]
    assert ranking[0][1] >= EXACT_STRONG


@patch.dict(os.environ, _ENV)
def test_an_unrelated_unique_column_does_not_reach_the_good_band_against_a_primary_key(tmp_path):
    """Two surrogate keys: 1..300 against 1..1000 covers 30% of the larger domain, and nothing
    else relates them. Their top-10 lists were nine-tenths identical, which scored 0.26 —
    painted as a good join in the UI."""
    customers = _profiled(tmp_path, "customers", id=[str(i) for i in range(1, 1001)])
    products = _profiled(tmp_path, "products", id=[str(i) for i in range(1, 301)])

    with TestClient(app) as client:
        assert _score(client, customers["id"], products["id"]) < EXACT_GOOD


@patch.dict(os.environ, _ENV)
def test_exact_score_anchors_the_join_discovery_colour_bands_are_drawn_from(tmp_path):
    """aran-web/src/lib/joinScore.ts colours an 'exact' score strong from 0.29 and good from
    0.14: the scores, at the Alignments view's default strictness (0.25), of two
    equal-cardinality columns sharing 80% and 60% of their distinct values. Below 65 distinct
    values the sample is the whole value set, so these are exact, not estimates. No exact score
    reaches the ML model's 0.8 band — identical value sets top out at 0.495. A change to
    rank()'s prior moves these numbers, and the UI's bands have to move with them."""
    values = [f"v{i:02d}" for i in range(50)]

    def sharing(shared):
        column = values[:shared] + [f"w{i:02d}" for i in range(50 - shared)]
        return _profiled(tmp_path, f"shares{shared}", v=column)["v"]

    base = _profiled(tmp_path, "base", v=values)["v"]
    with TestClient(app) as client:
        assert round(_score(client, base, sharing(40)), 4) == 0.2932
        assert round(_score(client, base, sharing(30)), 4) == 0.1404
        assert round(_score(client, base, sharing(50)), 4) == 0.4949


@patch.dict(os.environ, _ENV)
def test_a_legacy_profile_whose_top_values_are_its_value_set_scores_as_before():
    """Profiles persisted before the sample carry only their top values. Up to ten distinct
    values that list IS the value set, so small fixtures (the e2e corpus's author_ref/rid, and
    its reviewer_ref near-miss) keep their exact scores."""
    with TestClient(app) as client:
        _, distances = _ranking(
            client,
            _legacy("reviewer_ref", 10, [f"r{i}" for i in range(10)]),
            [_legacy("rid", 3, ["r0", "r1", "x"])])
    assert distances[0]["metric_distances"]["exact_mj"] == pytest.approx(2 / (10 + 3))


@patch.dict(os.environ, _ENV)
def test_a_legacy_profile_with_no_sample_of_its_value_set_is_left_unscored():
    """A 1,000-value column's top ten are chosen by frequency — the evidence the sample
    replaced. Without a sample there is no honest MJ: the pair gets none, scores 0, and
    discovery drops it, rather than being ranked from its top values again."""
    keys = [str(i) for i in range(10)]
    with TestClient(app) as client:
        ranking, distances = _ranking(
            client, _legacy("customer_id", 1000, keys), [_legacy("id", 1000, keys)])
    assert "exact_mj" not in distances[0]["metric_distances"]
    assert ranking == [("id", 0.0)]


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_rank_endpoint_exact():
    with TestClient(app) as client:
        response = client.post(
            "/operators/rank",
            headers=AUTH,
            json={
                "tenant_id": "00000000-0000-0000-0000-000000000002",
                "method": "exact",
                "strictness": 0.5,
                "top_k": 10,
                "distances": [
                    {
                        "candidate_column": "perfect_match",
                        "candidate_index": 0,
                        "name_distance": 0.0,
                        "metric_distances": {
                            "exact_mj": 1.0,
                            "exact_k": 1.0
                        }
                    },
                    {
                        "candidate_column": "poor_match",
                        "candidate_index": 1,
                        "name_distance": 5.0,
                        "metric_distances": {
                            "exact_mj": 0.0,
                            "exact_k": 0.1
                        }
                    }
                ]
            }
        )
    assert response.status_code == 200
    data = response.json()
    ranking = data["ranking"]
    assert len(ranking) == 2

    # Check ranking order
    assert ranking[0]["candidate_column"] == "perfect_match"
    assert ranking[1]["candidate_column"] == "poor_match"
    assert ranking[0]["score"] > ranking[1]["score"]
    assert ranking[0]["rank"] == 1
    assert ranking[1]["rank"] == 2


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_rank_endpoint_ml():
    import numpy as np

    # dependency_overrides, not patching a module global: the model now reaches the handler
    # through Depends(get_optional_model), which is what makes it overridable like this.
    mock_model = MagicMock()
    mock_model.predict.return_value = np.array([0.9, 0.2])
    app.dependency_overrides[get_optional_model] = lambda: mock_model

    try:
        with TestClient(app) as client:
            response = client.post(
                "/operators/rank",
                headers=AUTH,
                json={
                    "tenant_id": "00000000-0000-0000-0000-000000000002",
                    "method": "ml",
                    "distances": [
                        {
                            "candidate_column": "good_match",
                            "candidate_index": 0,
                            "name_distance": 0.0,
                            "metric_distances": {"cardinality": 0.0}
                        },
                        {
                            "candidate_column": "bad_match",
                            "candidate_index": 1,
                            "name_distance": 10.0,
                            "metric_distances": {"cardinality": 100.0}
                        }
                    ]
                }
            )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 200
    data = response.json()
    ranking = data["ranking"]
    assert len(ranking) == 2
    assert ranking[0]["candidate_column"] == "good_match"
    assert ranking[0]["score"] == 0.9
    assert ranking[1]["candidate_column"] == "bad_match"
    assert ranking[1]["score"] == 0.2


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_healthz_reports_degraded_when_the_model_is_missing():
    """The probe must fail when the sidecar cannot do its job.

    Previously /healthz returned "ok" unconditionally, so a sidecar whose model failed to
    load passed every check, received traffic, and errored on every rank request. Delete the
    model check in healthz and this goes red.
    """
    app.dependency_overrides[get_optional_model] = lambda: None
    try:
        with TestClient(app) as client:
            # The dependency override does not reach app.state, which healthz reads, so
            # clear it directly — this is the state a failed load actually leaves behind.
            app.state.model = None
            response = client.get("/healthz")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 503
    assert response.json() == {"status": "degraded", "model": "missing"}


@patch.dict(os.environ, {"ARAN_LAKE_URL": "http://mock-lake", "ARAN_LAKE_FREYJA_SECRET": "secret", "FREYJA_SHARED_SECRET": AUTH_SECRET})
def test_rank_endpoint_ml_model_missing():
    """503, not 500 — the caller did nothing wrong, the sidecar cannot serve this method."""
    app.dependency_overrides[get_optional_model] = lambda: None
    try:
        with TestClient(app) as client:
            response = client.post(
                "/operators/rank",
                headers=AUTH,
                json={
                    "tenant_id": "00000000-0000-0000-0000-000000000002",
                    "method": "ml",
                    "distances": []
                }
            )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 503
    assert "not loaded" in response.json()["detail"]

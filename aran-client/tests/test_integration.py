"""Register a DataFrame, get proposals, confirm one.

The rule these tests defend: nothing here matches anything by itself. Every candidate is a proposal carrying a score, and confirming is a
separate, explicit call that a human makes.
"""

from __future__ import annotations

import json

import pandas as pd
import pyarrow as pa
import pytest

import aran_client as aran
from aran_client.errors import AranError

TOKEN = "aran_pat_abcdef123456"
PROJECT = "11111111-1111-1111-1111-111111111111"
REPOSITORY = "22222222-2222-2222-2222-222222222222"

# Fixtures below mirror the backend's wire records field for field, named after the Java
# DTO each one stands in for. A stub that invents a shape lets the SDK pass against a
# server that does not exist — which is how three of its calls shipped broken (S20-1/2/3).

# ModelDtos.ModelMappingDto, as POST /model/suggest returns it: kind is one of
# MappingKindDto's JSON values (class|datatype|object|join), status one of
# MappingStatusDto's (SUGGESTED|ACCEPTED|REJECTED).
SUGGESTIONS = [
    {
        "id": "m1",
        "kind": "datatype",
        "sourceIri": "http://aran/ds1#title",
        "targetIri": "http://aran/ds2#name",
        "label": "title ↔ name",
        "status": "SUGGESTED",
        # The breakdown the engine already computes: 0.4 × linguistic + 0.6 × value overlap.
        "evidence": {"linguisticScore": 0.9, "valueScore": 0.5, "score": 0.66},
        "author": None,
        "comment": None,
        "createdAt": "2026-09-23T10:00:00Z",
    },
    {
        "id": "m2",
        "kind": "datatype",
        "sourceIri": "http://aran/ds1#year",
        "targetIri": "http://aran/ds2#published",
        "label": None,
        "status": "SUGGESTED",
        "evidence": None,
        "author": None,
        "comment": None,
        "createdAt": "2026-09-23T10:00:00Z",
    },
]


def dataset_created(dataset_id="ds-1", snapshot_id="snap-1", bootstrap_job_id="job-boot"):
    """DatasetDtos.DatasetCreatedResponse — flat, with the bootstrap job's id beside it."""
    return {
        "id": dataset_id,
        "repositoryId": REPOSITORY,
        "name": "papers",
        "description": "Registered from a DataFrame",
        "snapshotId": snapshot_id,
        "bootstrapStatus": "PENDING",
        "bootstrapJobId": bootstrap_job_id,
    }


def json_server(server, routes):
    def responder(path, body):
        for prefix, payload in routes.items():
            if path.startswith(prefix):
                return {
                    "status": 200,
                    "payload": json.dumps(payload).encode(),
                    "headers": {"Content-Type": "application/json"},
                }
        return {"status": 404, "payload": b"{}"}

    server.reply_with(responder)


def connect(server):
    return aran.Connection(server.base_url, TOKEN)


class TestProposals:
    def test_candidates_carry_the_score_breakdown_not_just_a_number(self, server):
        # A 0.66 that is mostly name similarity and a 0.66 that is mostly value overlap
        # deserve different amounts of trust from whoever confirms them.
        json_server(server, {"/api/projects": SUGGESTIONS})

        candidates = aran.propose_mapping(
            connect(server), project_id=PROJECT,
            left_dataset_id="a", right_dataset_id="b")

        assert len(candidates) == 2
        assert candidates[0].score == pytest.approx(0.66)
        assert candidates[0].name_score == pytest.approx(0.9)
        assert candidates[0].value_score == pytest.approx(0.5)

    def test_a_candidate_with_no_evidence_reports_no_score_rather_than_zero(self, server):
        # Reporting 0.0 would read as "the engine is confident this is wrong".
        json_server(server, {"/api/projects": SUGGESTIONS})

        candidates = aran.propose_mapping(
            connect(server), project_id=PROJECT,
            left_dataset_id="a", right_dataset_id="b")

        assert candidates[1].score is None
        assert candidates[1].name_score is None

    def test_asks_the_server_for_a_comparison_between_two_datasets(self, server):
        json_server(server, {"/api/projects": SUGGESTIONS})

        aran.propose_mapping(connect(server), project_id=PROJECT,
                             left_dataset_id="left-id", right_dataset_id="right-id")

        request = server.requests[-1]
        assert request["path"] == f"/api/projects/{PROJECT}/model/suggest"
        assert request["body"] == {"leftDatasetId": "left-id", "rightDatasetId": "right-id"}

    def test_proposing_changes_nothing(self, server):
        json_server(server, {"/api/projects": SUGGESTIONS})

        aran.propose_mapping(connect(server), project_id=PROJECT,
                             left_dataset_id="a", right_dataset_id="b")

        # One call, to /suggest. Nothing was written.
        assert [r["path"] for r in server.requests] == [f"/api/projects/{PROJECT}/model/suggest"]

    def test_a_non_list_response_is_an_error_not_a_silent_empty(self, server):
        json_server(server, {"/api/projects": {"unexpected": True}})

        with pytest.raises(AranError, match="expected a list"):
            aran.propose_mapping(connect(server), project_id=PROJECT,
                                 left_dataset_id="a", right_dataset_id="b")


class TestConfirm:
    def test_confirms_the_candidate_as_proposed(self, server):
        json_server(server, {"/api/projects": {"id": "written"}})
        candidate = aran.MappingCandidate(SUGGESTIONS[0])

        aran.confirm_mapping(connect(server), candidate, project_id=PROJECT)

        body = server.requests[-1]["body"]
        assert body["sourceIri"] == "http://aran/ds1#title"
        assert body["targetIri"] == "http://aran/ds2#name"

    def test_either_side_can_be_overridden(self, server):
        # The expected workflow: read the proposals, fix the one the engine got wrong,
        # confirm. Without this the API would be accept-or-nothing.
        json_server(server, {"/api/projects": {"id": "written"}})
        candidate = aran.MappingCandidate(SUGGESTIONS[0])

        aran.confirm_mapping(connect(server), candidate, project_id=PROJECT,
                             target="http://aran/ds2#corrected")

        body = server.requests[-1]["body"]
        assert body["targetIri"] == "http://aran/ds2#corrected"
        assert body["sourceIri"] == "http://aran/ds1#title"

    def test_writes_through_the_endpoint_the_ui_uses(self, server):
        json_server(server, {"/api/projects": {"id": "written"}})

        aran.confirm_mapping(connect(server), aran.MappingCandidate(SUGGESTIONS[0]),
                             project_id=PROJECT)

        assert server.requests[-1]["path"] == f"/api/projects/{PROJECT}/model/mappings"

    def test_the_body_is_an_add_mapping_request_with_the_proposed_kind(self, server):
        # ModelDtos.AddMappingRequest. The old default kind="PROPERTY" is not a
        # MappingKindDto value, so every confirm without an explicit kind was a 400.
        json_server(server, {"/api/projects": {"id": "written"}})

        aran.confirm_mapping(connect(server), aran.MappingCandidate(SUGGESTIONS[0]),
                             project_id=PROJECT, comment="checked by hand")

        assert server.requests[-1]["body"] == {
            "kind": "datatype",
            "sourceIri": "http://aran/ds1#title",
            "targetIri": "http://aran/ds2#name",
            "label": "title ↔ name",
            "comment": "checked by hand",
        }

    def test_an_explicit_kind_wins_and_is_sent_as_the_server_spells_it(self, server):
        json_server(server, {"/api/projects": {"id": "written"}})

        aran.confirm_mapping(connect(server), aran.MappingCandidate(SUGGESTIONS[0]),
                             project_id=PROJECT, kind="OBJECT")

        assert server.requests[-1]["body"]["kind"] == "object"

    def test_no_kind_anywhere_is_refused_before_anything_is_sent(self, server):
        json_server(server, {"/api/projects": {"id": "written"}})
        kindless = aran.MappingCandidate({"sourceIri": "a", "targetIri": "b", "label": "x"})

        with pytest.raises(ValueError, match="pass kind="):
            aran.confirm_mapping(connect(server), kindless, project_id=PROJECT)

        assert server.requests == []


class TestRegisterDataFrame:
    def test_uploads_the_frame_and_returns_its_dataset_id(self, server):
        json_server(server, {"/api/projects": dataset_created("ds-123")})
        frame = pd.DataFrame({"title": ["Bell"], "year": [1964]})

        # profile=False keeps this test about upload mechanics; the profiling path has
        # its own class below.
        dataset_id = aran.register_dataframe(
            connect(server), frame, project_id=PROJECT, repository_id=REPOSITORY,
            name="papers", profile=False)

        assert dataset_id == "ds-123"
        assert server.requests[-1]["path"] == (
            f"/api/projects/{PROJECT}/repositories/{REPOSITORY}/datasets")

    def test_polars_and_arrow_are_accepted_too(self, server):
        json_server(server, {"/api/projects": dataset_created("ds-1")})
        table = pa.table({"title": ["Bell"]})

        assert aran.register_dataframe(connect(server), table, project_id=PROJECT,
                                       repository_id=REPOSITORY, name="t",
                                       profile=False) == "ds-1"

        import polars as pl
        assert aran.register_dataframe(connect(server), pl.DataFrame({"title": ["Bell"]}),
                                       project_id=PROJECT, repository_id=REPOSITORY,
                                       name="t", profile=False) == "ds-1"

    def test_something_that_is_not_a_frame_is_refused_clearly(self, server):
        with pytest.raises(TypeError, match="pandas DataFrame"):
            aran.register_dataframe(connect(server), {"not": "a frame"},
                                    project_id=PROJECT, repository_id=REPOSITORY, name="x")

    def test_a_response_without_a_dataset_id_is_an_error(self, server):
        # The upload may have succeeded; returning None would make the caller's next
        # call fail somewhere far away from the cause.
        json_server(server, {"/api/projects": dataset_created(dataset_id=None)})

        with pytest.raises(AranError, match="no dataset id"):
            aran.register_dataframe(connect(server), pd.DataFrame({"a": [1]}),
                                    project_id=PROJECT, repository_id=REPOSITORY, name="x")


def _json(payload):
    return {"status": 200, "payload": json.dumps(payload).encode(),
            "headers": {"Content-Type": "application/json"}}


def _frame():
    return pd.DataFrame({"title": ["a"], "year": [1]})


class TestMetricCatalog:
    """Aran will ship a catalog of similarity metrics, not the two it has today."""

    def test_reports_every_metric_the_engine_sends(self):
        # The point: a metric added server-side must reach the notebook with no client
        # release. Hardcoding today's two would silently drop tomorrow's.
        c = aran.MappingCandidate({"sourceIri": "a", "targetIri": "b", "evidence": {
            "score": 0.7, "linguisticScore": 0.9, "valueScore": 0.5,
            "embeddingCosine": 0.81, "distributionKs": 0.44}})

        assert c.metrics == {
            "linguisticScore": 0.9, "valueScore": 0.5,
            "embeddingCosine": 0.81, "distributionKs": 0.44}

    def test_the_combined_score_is_not_one_of_the_metrics(self):
        c = aran.MappingCandidate({"sourceIri": "a", "targetIri": "b",
                                   "evidence": {"score": 0.7, "linguisticScore": 0.9}})

        assert c.score == pytest.approx(0.7)
        assert "score" not in c.metrics

    def test_non_numeric_evidence_is_not_a_metric(self):
        # Booleans are ints in Python; a flag would otherwise appear as 1.0 and be
        # rendered next to real scores in the widget.
        c = aran.MappingCandidate({"sourceIri": "a", "targetIri": "b", "evidence": {
            "linguisticScore": 0.9, "typeCompatible": True, "matcher": "jaro-winkler"}})

        assert c.metrics == {"linguisticScore": 0.9}

    def test_the_two_conveniences_resolve_through_the_catalog(self):
        c = aran.MappingCandidate({"sourceIri": "a", "targetIri": "b", "evidence": {
            "nameSimilarity": 0.8, "valueOverlap": 0.3}})

        # Different server-side spelling; the aliases still find them.
        assert c.name_score == pytest.approx(0.8)
        assert c.value_score == pytest.approx(0.3)

    def test_no_evidence_means_no_metrics_rather_than_zeros(self):
        c = aran.MappingCandidate({"sourceIri": "a", "targetIri": "b"})

        assert c.metrics == {}
        assert c.score is None
        assert c.name_score is None


class TestProfilingOnRegistration:
    """Profiling is part of registration, because the value metrics need it."""

    @staticmethod
    def _server(server, *, statuses, dataset=None, bootstrap_statuses=("SUCCEEDED",)):
        """Upload → wait for bootstrap → submit profiling → poll, each job walking its
        own list of statuses (JobDtos.JobDto; JobDtos.JobSubmission for the submit)."""
        state = {"polls": {"job-boot": 0, "job-1": 0}}
        ds = dataset if dataset is not None else dataset_created()
        walks = {"job-boot": list(bootstrap_statuses), "job-1": list(statuses)}

        def responder(path, body):
            if path.endswith("/datasets"):
                return _json(ds)
            if path == "/api/operators/profiling/submit":
                return _json({"jobId": "job-1"})
            if path.startswith("/api/jobs/"):
                job_id = path.rsplit("/", 1)[-1]
                walk = walks[job_id]
                i = min(state["polls"][job_id], len(walk) - 1)
                state["polls"][job_id] += 1
                status = walk[i]
                return _json({"id": job_id, "status": status,
                              "errorMessage": "freyja unreachable" if status == "FAILED" else None})
            return _json({})

        server.reply_with(responder)
        return state

    def test_registration_profiles_and_waits(self, server):
        self._server(server, statuses=["RUNNING", "RUNNING", "SUCCEEDED"])

        ds = aran.register_dataframe(connect(server), _frame(), project_id=PROJECT,
                                     repository_id=REPOSITORY, name="papers", poll_interval=0)

        assert ds == "ds-1"
        paths = [r["path"] for r in server.requests]
        assert "/api/operators/profiling/submit" in paths
        assert paths.count("/api/jobs/job-1") == 3

    def test_the_profiling_job_is_an_invocation_request_for_the_profile_operation(self, server):
        # OperatorInvocationController.InvocationRequest(operation, parameters) — the old
        # flat {snapshotId, datasetId} body bound to a null operation and was refused.
        # Parameter names are ProfilingOperator's manifest; values are strings, since the
        # parameters are a Map<String, String>.
        self._server(server, statuses=["SUCCEEDED"])

        aran.register_dataframe(connect(server), _frame(), project_id=PROJECT,
                                repository_id=REPOSITORY, name="papers", poll_interval=0)

        submit = next(r for r in server.requests
                      if r["path"] == "/api/operators/profiling/submit")
        assert submit["body"] == {
            "operation": "profile",
            "parameters": {"snapshotId": "snap-1", "datasetId": "ds-1", "varcharOnly": "false"},
        }

    def test_profiling_waits_for_the_bootstrap_job_first(self, server):
        # Bootstrapping ends with its own profiling run; submitting ours before it finished
        # would race it over the same profile rows.
        self._server(server, statuses=["SUCCEEDED"], bootstrap_statuses=["RUNNING", "SUCCEEDED"])

        aran.register_dataframe(connect(server), _frame(), project_id=PROJECT,
                                repository_id=REPOSITORY, name="papers", poll_interval=0)

        paths = [r["path"] for r in server.requests]
        submit_at = paths.index("/api/operators/profiling/submit")
        assert paths[:submit_at].count("/api/jobs/job-boot") == 2

    def test_a_failed_bootstrap_raises_and_nothing_is_profiled(self, server):
        self._server(server, statuses=["SUCCEEDED"], bootstrap_statuses=["FAILED"])

        with pytest.raises(AranError, match="job-boot did not succeed"):
            aran.register_dataframe(connect(server), _frame(), project_id=PROJECT,
                                    repository_id=REPOSITORY, name="papers", poll_interval=0)

        assert "/api/operators/profiling/submit" not in [r["path"] for r in server.requests]

    def test_a_failed_profiling_job_raises_with_its_own_message(self, server):
        # Continuing would propose mappings from statistics that were never computed.
        self._server(server, statuses=["RUNNING", "FAILED"])

        with pytest.raises(AranError, match="freyja unreachable"):
            aran.register_dataframe(connect(server), _frame(), project_id=PROJECT,
                                    repository_id=REPOSITORY, name="papers", poll_interval=0)

    def test_timing_out_says_so_rather_than_returning_a_half_profiled_dataset(self, server):
        self._server(server, statuses=["RUNNING"])

        with pytest.raises(AranError, match="still RUNNING"):
            aran.register_dataframe(connect(server), _frame(), project_id=PROJECT,
                                    repository_id=REPOSITORY, name="papers",
                                    timeout=0, poll_interval=0)

    def test_profile_false_skips_it_entirely(self, server):
        self._server(server, statuses=["SUCCEEDED"])

        aran.register_dataframe(connect(server), _frame(), project_id=PROJECT,
                                repository_id=REPOSITORY, name="papers", profile=False)

        assert all(r["path"].endswith("/datasets") for r in server.requests)

    def test_a_missing_snapshot_id_is_refused_rather_than_silently_unprofiled(self, server):
        # Returning here would hand back a dataset that looks registered and scores badly.
        self._server(server, statuses=["SUCCEEDED"], dataset=dataset_created(snapshot_id=None))

        with pytest.raises(AranError, match="no snapshotId"):
            aran.register_dataframe(connect(server), _frame(), project_id=PROJECT,
                                    repository_id=REPOSITORY, name="papers", poll_interval=0)

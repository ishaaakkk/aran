"""Track E: the accept/override click-through.

The browser half cannot be tested here — that needs a real browser (V9). What
*can* be pinned is the state machine either side of the click, which is where the decisions
that matter live: what counts as accepted, what a skip does, and whether an un-reviewed
widget can write anything. Those are the properties ground rule #1 turns on.
"""

from __future__ import annotations

import json

import pytest

import aran_client as aran
from aran_client.errors import AranError

TOKEN = "aran_pat_abcdef123456"
PROJECT = "11111111-1111-1111-1111-111111111111"


def candidate(source: str, target: str, score=None, name=None, value=None):
    evidence = {}
    if score is not None:
        evidence["score"] = score
    if name is not None:
        evidence["linguisticScore"] = name
    if value is not None:
        evidence["valueScore"] = value
    # kind as ModelDtos.MappingKindDto spells it on the wire.
    return aran.MappingCandidate(
        {"sourceIri": source, "targetIri": target, "kind": "datatype", "evidence": evidence}
    )


def two_proposals():
    return [
        candidate("http://o/ds1#title", "http://o/ds2#name", 0.66, 0.9, 0.5),
        candidate("http://o/ds1#year", "http://o/ds2#published", 0.30),
    ]


def confirm(widget, decisions):
    """Stand in for the click-through: what the front end assigns on Confirm."""
    widget.widget.decisions = decisions
    widget.widget.submitted = True


class TestBeforeReview:
    def test_a_widget_nobody_has_reviewed_has_accepted_nothing(self):
        # Returning the defaults would mean a set of mappings nobody agreed to, which is
        # the exact failure the propose/confirm split exists to prevent.
        w = aran.review_mappings(two_proposals())

        assert w.submitted is False
        assert w.accepted == []

    def test_applying_an_unreviewed_widget_is_refused(self, server):
        w = aran.review_mappings(two_proposals())
        conn = aran.Connection(server.base_url, TOKEN)

        with pytest.raises(AranError, match="nothing has been confirmed"):
            w.apply(conn, project_id=PROJECT)

        assert server.requests == []


class TestDecisions:
    def test_accepting_everything_yields_every_proposal_unchanged(self):
        w = aran.review_mappings(two_proposals())

        confirm(w, [
            {"index": 0, "action": "accept", "target": "http://o/ds2#name"},
            {"index": 1, "action": "accept", "target": "http://o/ds2#published"},
        ])

        assert [c.target for c in w.accepted] == [
            "http://o/ds2#name", "http://o/ds2#published"]

    def test_an_override_replaces_the_target(self):
        w = aran.review_mappings(two_proposals())

        confirm(w, [
            {"index": 0, "action": "override", "target": "http://o/ds2#corrected"},
            {"index": 1, "action": "accept", "target": "http://o/ds2#published"},
        ])

        assert w.accepted[0].target == "http://o/ds2#corrected"
        # The source is never editable: the column being mapped is a fact, not a judgement.
        assert w.accepted[0].source == "http://o/ds1#title"

    def test_a_skip_is_left_out(self):
        w = aran.review_mappings(two_proposals())

        confirm(w, [
            {"index": 0, "action": "skip", "target": "http://o/ds2#name"},
            {"index": 1, "action": "accept", "target": "http://o/ds2#published"},
        ])

        assert len(w.accepted) == 1
        assert w.accepted[0].source == "http://o/ds1#year"

    def test_skips_are_still_visible_in_decisions(self):
        # accepted() is what gets written; decisions() is what the human did. Losing the
        # second would make "why was this column not mapped" unanswerable.
        w = aran.review_mappings(two_proposals())

        confirm(w, [{"index": 0, "action": "skip", "target": "x"}])

        assert w.decisions[0]["action"] == "skip"

    def test_an_out_of_range_index_is_ignored_rather_than_crashing(self):
        # The index comes from the browser. A stale or hand-edited one must not map onto
        # the wrong candidate, and must not take the notebook down either.
        w = aran.review_mappings(two_proposals())

        confirm(w, [
            {"index": 99, "action": "accept", "target": "x"},
            {"index": 0, "action": "accept", "target": "http://o/ds2#name"},
        ])

        assert len(w.accepted) == 1


class TestApply:
    def test_writes_only_what_was_accepted(self, server):
        server.reply_with(lambda path, body: {
            "status": 200, "payload": json.dumps({"id": "written"}).encode(),
            "headers": {"Content-Type": "application/json"}})
        w = aran.review_mappings(two_proposals())
        confirm(w, [
            {"index": 0, "action": "accept", "target": "http://o/ds2#name"},
            {"index": 1, "action": "skip", "target": "http://o/ds2#published"},
        ])

        results = w.apply(aran.Connection(server.base_url, TOKEN), project_id=PROJECT)

        assert len(results) == 1
        assert len(server.requests) == 1
        assert server.requests[0]["body"]["targetIri"] == "http://o/ds2#name"

    def test_writes_through_the_same_endpoint_the_ui_uses(self, server):
        server.reply_with(lambda path, body: {
            "status": 200, "payload": json.dumps({"id": "x"}).encode(),
            "headers": {"Content-Type": "application/json"}})
        w = aran.review_mappings(two_proposals()[:1])
        confirm(w, [{"index": 0, "action": "override", "target": "http://o/ds2#fixed"}])

        w.apply(aran.Connection(server.base_url, TOKEN), project_id=PROJECT)

        assert server.requests[0]["path"] == f"/api/projects/{PROJECT}/model/mappings"
        assert server.requests[0]["body"]["targetIri"] == "http://o/ds2#fixed"

    def test_writes_the_kind_the_engine_proposed(self, server):
        # apply() passes no kind, so it sends the candidate's own. It used to inherit
        # confirm_mapping's default "PROPERTY", which the server refuses — every Confirm
        # failed.
        server.reply_with(lambda path, body: {
            "status": 200, "payload": json.dumps({"id": "x"}).encode(),
            "headers": {"Content-Type": "application/json"}})
        w = aran.review_mappings(two_proposals()[:1])
        confirm(w, [{"index": 0, "action": "accept", "target": "http://o/ds2#name"}])

        w.apply(aran.Connection(server.base_url, TOKEN), project_id=PROJECT)

        assert server.requests[0]["body"]["kind"] == "datatype"


class TestFrontEndContract:
    def test_rows_carry_the_score_breakdown_and_a_readable_column_name(self):
        # The front end renders from these and nothing else, so an IRI with no local name
        # must still produce something a person can read.
        w = aran.review_mappings(two_proposals())

        rows = w.widget.candidates
        assert rows[0]["source_label"] == "title"
        # The whole catalog reaches the front end, which iterates it — so a metric Aran
        # adds later renders without this file or the ESM changing.
        assert rows[0]["metrics"] == {"linguisticScore": 0.9, "valueScore": 0.5}
        assert rows[1]["metrics"] == {}

    def test_the_front_end_is_self_contained(self):
        # A notebook may be offline and Colab sandboxes the output frame cross-origin, so
        # any import or CDN fetch in the ESM would fail exactly where it is hardest to see.
        from aran_client._widget import _ESM

        assert "import " not in _ESM
        assert "http://" not in _ESM
        assert "https://" not in _ESM

    def test_an_empty_proposal_set_is_displayable_rather_than_an_error(self):
        w = aran.review_mappings([])

        assert w.widget.candidates == []
        assert w.accepted == []

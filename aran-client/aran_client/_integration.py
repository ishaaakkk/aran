"""Register a DataFrame with Aran and align it against data already integrated.

This is the propose/confirm half of the notebook story: a DataFrame becomes a source
Aran can see, the existing alignment engine proposes column mappings with confidence
scores, and a human accepts or overrides them. **Nothing here matches anything by
itself** — every candidate comes from the same operators that back the UI's Alignments
view, which is the point.

Two things about the shape of this API are consequences of the server, not choices:

*The DataFrame is uploaded, not scanned in place.* Aran's planner, schema graph and
tenancy live server-side and there is no embedded mode, so "scan the DataFrame into
DuckDB" is only possible on the machine Aran runs on. The frame is written to parquet
and POSTed, exactly as a browser upload is.

*Proposals are between two datasets, not onto a target class.* ``POST /model/suggest``
takes ``leftDatasetId`` and ``rightDatasetId``: the engine compares two source schemas
and proposes correspondences. There is no "map these columns onto class X" entry point,
so this module does not pretend to offer one.
"""

from __future__ import annotations

import io
import json
import time
from collections.abc import Mapping, Sequence
from typing import TYPE_CHECKING, Any

from .errors import AranError

if TYPE_CHECKING:  # pragma: no cover - typing only
    from ._connection import Connection

__all__ = [
    "MappingCandidate",
    "confirm_mapping",
    "profile_dataset",
    "propose_mapping",
    "register_dataframe",
    "wait_for_job",
]


class MappingCandidate:
    """One proposed correspondence between a column on each side.

    ``score`` is the engine's combined confidence in [0, 1]. :attr:`metrics` is the
    **catalog of individual similarity metrics** behind it, as a name → value mapping.

    The catalog is read generically, never as a fixed pair. Aran ships two metrics today
    (a linguistic score over normalised names and a value-overlap score) and intends to
    ship more; a client that hardcoded those two would silently drop every metric added
    afterwards, which is the worst possible failure for a number a human is using to
    decide. Anything numeric the engine reports as evidence appears here under the
    server's own name, so a new metric shows up the day the engine ships it with no
    client release.

    :attr:`name_score` and :attr:`value_score` remain as conveniences for the two that
    exist today, resolved *through* the catalog rather than alongside it.

    Why expose the breakdown at all: a 0.6 that is all name similarity and a 0.6 that is
    all value overlap warrant different amounts of trust from the person confirming it.

    A candidate is a *proposal*. It changes nothing until :func:`confirm_mapping`.
    """

    __slots__ = ("_raw",)

    def __init__(self, raw: Mapping[str, Any]):
        self._raw = dict(raw)

    @property
    def id(self) -> str | None:
        return self._raw.get("id")

    @property
    def source(self) -> str:
        return str(self._raw.get("sourceIri", ""))

    @property
    def target(self) -> str:
        return str(self._raw.get("targetIri", ""))

    @property
    def label(self) -> str | None:
        return self._raw.get("label")

    @property
    def kind(self) -> str | None:
        """The mapping kind the engine proposed — ``class``, ``datatype``, ``object`` or
        ``join`` — which :func:`confirm_mapping` sends back unless told otherwise."""
        return self._raw.get("kind")

    @property
    def status(self) -> str:
        return str(self._raw.get("status", ""))

    @property
    def evidence(self) -> Mapping[str, Any]:
        """Whatever the engine recorded about *why* — the scores live here."""
        raw = self._raw.get("evidence")
        return raw if isinstance(raw, dict) else {}

    #: Evidence keys that carry the *combined* score rather than an individual metric.
    #: Excluded from :attr:`metrics` so the catalog is metrics only.
    _COMBINED_KEYS = ("score", "combinedScore", "confidence")

    #: Aliases for the two metrics Aran ships today. Only used to resolve the two
    #: convenience properties — never to filter the catalog.
    _NAME_METRIC_ALIASES = ("linguisticScore", "nameScore", "nameSimilarity")
    _VALUE_METRIC_ALIASES = ("valueScore", "valueOverlapScore", "valueOverlap")

    @property
    def score(self) -> float | None:
        """The engine's combined confidence, if it reports one."""
        return _first_float(self.evidence, self._COMBINED_KEYS)

    @property
    def metrics(self) -> Mapping[str, float]:
        """Every individual similarity metric the engine reported, by its own name.

        Read generically on purpose: Aran will ship a catalog of metrics rather than the
        two it has today, and this must surface a new one without a client release. Any
        numeric evidence value that is not the combined score is a metric.
        """
        out = {}
        for key, value in self.evidence.items():
            if key in self._COMBINED_KEYS:
                continue
            number = _as_float(value)
            if number is not None:
                out[key] = number
        return out

    @property
    def name_score(self) -> float | None:
        """The name-similarity metric, whichever name this engine version gives it."""
        return _first_float(self.metrics, self._NAME_METRIC_ALIASES)

    @property
    def value_score(self) -> float | None:
        """The value-overlap metric, whichever name this engine version gives it."""
        return _first_float(self.metrics, self._VALUE_METRIC_ALIASES)

    def as_dict(self) -> Mapping[str, Any]:
        return dict(self._raw)

    def __repr__(self) -> str:
        score = "?" if self.score is None else f"{self.score:.2f}"
        return f"<MappingCandidate {self.source} → {self.target} score={score}>"


def _as_float(value: Any) -> float | None:
    """A number, or None. Booleans are excluded — in Python they *are* ints, and a flag
    reported alongside the metrics would otherwise appear in the catalog as 1.0."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    return None


def _first_float(evidence: Mapping[str, Any], keys: Sequence[str]) -> float | None:
    for key in keys:
        number = _as_float(evidence.get(key))
        if number is not None:
            return number
    return None


#: Job states that will not change again.
_TERMINAL_JOB_STATES = frozenset({"SUCCEEDED", "FAILED", "CANCELLED"})


def register_dataframe(
    connection: Connection,
    frame: Any,
    *,
    project_id: str,
    repository_id: str,
    name: str,
    profile: bool = True,
    timeout: float = 300.0,
    poll_interval: float = 1.0,
) -> str:
    """Upload ``frame`` as a dataset, profile it, and return its dataset id.

    Written to parquet and POSTed to Aran, which uploads it to the lake itself — the
    client never receives a bucket or a URL. Requires a ``FULL``-scoped token: this
    writes.

    **Profiling is part of registration, not an optional extra.** The alignment engine
    scores a candidate from a catalog of similarity metrics, and the ones that compare
    *values* need column statistics that only profiling produces. Registering without it
    leaves :func:`propose_mapping` scoring on names alone — which does not fail, it just
    quietly proposes worse matches, and a human reviewing them has no way to tell why.
    That is exactly the kind of silent degradation the propose/confirm split exists to
    avoid, so the default is to profile and wait.

    Pass ``profile=False`` only when something else will profile the dataset — the UI
    does it on bootstrap — and be aware that proposals made before it finishes are
    weaker than they look.

    :param timeout: seconds to wait for bootstrapping and profiling together. Raises
        rather than returning a dataset whose statistics are still being computed.
    """
    payload = _to_parquet(frame)
    metadata = json.dumps({"name": name, "description": "Registered from a DataFrame"})

    response = connection.post_multipart(
        f"/api/projects/{project_id}/repositories/{repository_id}/datasets",
        fields={"dataset": (None, metadata, "application/json")},
        files={"file": (f"{name}.parquet", payload, "application/vnd.apache.parquet")},
    )
    # The server answers DatasetCreatedResponse: the dataset's own fields at the top level
    # plus bootstrapJobId — there is no envelope around them.
    created = response if isinstance(response, dict) else {}
    dataset_id = created.get("id")
    if not dataset_id:
        raise AranError(f"Aran accepted the upload but returned no dataset id: {response}")

    if profile:
        snapshot_id = created.get("snapshotId")
        if not snapshot_id:
            raise AranError(
                "Aran registered the dataset but reported no snapshotId, so it cannot be "
                "profiled. Pass profile=False to register without statistics, knowing "
                "that proposals will score on names alone."
            )
        deadline = time.monotonic() + timeout
        bootstrap_job_id = created.get("bootstrapJobId")
        if bootstrap_job_id:
            # Bootstrapping runs as a job and ends with its own best-effort profiling run.
            # Profiling before it finishes would race that run over the same profile rows,
            # and a bootstrap that failed would surface later as weak proposals instead of
            # here, as the failure it is.
            wait_for_job(connection, str(bootstrap_job_id), timeout=timeout,
                         poll_interval=poll_interval)
        profile_dataset(connection, dataset_id=str(dataset_id),
                        snapshot_id=str(snapshot_id),
                        timeout=max(0.0, deadline - time.monotonic()),
                        poll_interval=poll_interval)

    return str(dataset_id)


def profile_dataset(
    connection: Connection,
    *,
    dataset_id: str,
    snapshot_id: str,
    timeout: float = 300.0,
    poll_interval: float = 1.0,
) -> Mapping[str, Any]:
    """Compute and persist column statistics, waiting for the job to finish.

    Submitted as a job rather than invoked inline because profiling reads every column of
    the snapshot through the freyja sidecar — this is the same asynchronous operation the
    UI runs on bootstrap, not a client-side shortcut around it.
    """
    # OperatorInvocationController binds {operation, parameters}, parameters being a
    # string map. varcharOnly=false matches the run bootstrapping makes: the operator's
    # default profiles varchar columns only, which is not what the value metrics need.
    submission = connection.post_json(
        "/api/operators/profiling/submit",
        {
            "operation": "profile",
            "parameters": {
                "snapshotId": snapshot_id,
                "datasetId": dataset_id,
                "varcharOnly": "false",
            },
        },
    )
    job_id = (submission or {}).get("jobId") if isinstance(submission, dict) else None
    if not job_id:
        raise AranError(f"Aran accepted the profiling request but returned no job id: {submission}")
    return wait_for_job(connection, str(job_id), timeout=timeout, poll_interval=poll_interval)


def wait_for_job(
    connection: Connection,
    job_id: str,
    *,
    timeout: float = 300.0,
    poll_interval: float = 1.0,
) -> Mapping[str, Any]:
    """Poll a job until it reaches a terminal state.

    Raises on FAILED or CANCELLED, carrying the job's own error message: a caller that
    continued past a failed profiling run would go on to propose mappings from statistics
    that were never computed.
    """
    deadline = time.monotonic() + timeout
    while True:
        job = connection.get_json(f"/api/jobs/{job_id}")
        job = job if isinstance(job, dict) else {}
        status = str(job.get("status", "")).upper()

        if status in _TERMINAL_JOB_STATES:
            if status != "SUCCEEDED":
                detail = job.get("errorMessage") or status
                raise AranError(f"job {job_id} did not succeed: {detail}")
            return job

        if time.monotonic() >= deadline:
            raise AranError(
                f"job {job_id} was still {status or 'pending'} after {timeout:.0f}s. "
                "It may still finish; raise timeout= if this dataset is large."
            )
        time.sleep(poll_interval)


def propose_mapping(
    connection: Connection,
    *,
    project_id: str,
    left_dataset_id: str,
    right_dataset_id: str,
) -> list[MappingCandidate]:
    """Ask Aran's alignment engine to propose correspondences between two datasets.

    Nothing is confirmed. Every result is a candidate for a human to accept or override —
    schema matching is an open problem and this API will not pretend otherwise.

    A newly registered dataset must have been **profiled** before this is worth much: the
    engine's value-overlap half needs column statistics, and without them only the weaker
    name-similarity half contributes.
    """
    body = {"leftDatasetId": left_dataset_id, "rightDatasetId": right_dataset_id}
    raw = connection.post_json(f"/api/projects/{project_id}/model/suggest", body)
    if not isinstance(raw, list):
        raise AranError(f"expected a list of candidates, got {type(raw).__name__}")
    return [MappingCandidate(item) for item in raw]


def confirm_mapping(
    connection: Connection,
    candidate: MappingCandidate,
    *,
    project_id: str,
    source: str | None = None,
    target: str | None = None,
    kind: str | None = None,
    comment: str | None = None,
) -> Mapping[str, Any]:
    """Accept a candidate — optionally overriding either side of it.

    Overriding is the whole reason ``source`` and ``target`` exist: the expected workflow
    is that a person reads the proposals, fixes the one or two the engine got wrong, and
    confirms. Writes through the same endpoint the UI's Alignments view uses, so a mapping
    made from a notebook is indistinguishable from one made by clicking.

    ``kind`` defaults to the kind the engine proposed (:attr:`MappingCandidate.kind`);
    the server accepts ``class``, ``datatype``, ``object`` or ``join``.
    """
    resolved_kind = kind or candidate.kind
    if not resolved_kind:
        raise ValueError(
            "the candidate carries no kind — pass kind= (class, datatype, object or join)"
        )
    body = {
        "kind": resolved_kind.lower(),
        "sourceIri": source or candidate.source,
        "targetIri": target or candidate.target,
        "label": candidate.label,
        "comment": comment,
    }
    return connection.post_json(f"/api/projects/{project_id}/model/mappings", body)


def _to_parquet(frame: Any) -> bytes:
    """Serialise pandas, polars or an Arrow table to parquet bytes.

    Parquet rather than CSV because it carries types: a CSV round trip turns every column
    into text and hands the profiler nothing useful to compute value overlap from.
    """
    import pyarrow as pa
    import pyarrow.parquet as pq

    if isinstance(frame, pa.Table):
        table = frame
    elif hasattr(frame, "to_arrow"):          # polars
        table = frame.to_arrow()
    elif hasattr(frame, "to_parquet") or hasattr(frame, "dtypes"):   # pandas
        table = pa.Table.from_pandas(frame)
    else:
        raise TypeError(
            "expected a pandas DataFrame, a polars DataFrame or a pyarrow Table, "
            f"got {type(frame).__name__}"
        )

    sink = io.BytesIO()
    pq.write_table(table, sink)
    return sink.getvalue()

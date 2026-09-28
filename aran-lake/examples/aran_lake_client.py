"""
Reference Python client for aran-lake (v0.2).

A minimal, dependency-light client that demonstrates the v0.2 API contract.
Not packaged separately — meant as a starting point for whoever builds
the real Python SDK, and as a reference for porting to other languages.

What's new vs v0.1
------------------

* ``upload_snapshot`` no longer takes ``dataset_name`` (Aran owns the name).
* New ``content_type`` parameter on upload — any format works; CSV, NDJSON,
  Excel, parquet are queryable today.
* New ``format_options`` parameter on upload for per-format read hints.
* New ``download_url`` method — mint a short-lived presigned GET for
  consumers that need raw bytes (export, streaming through pyarrow, etc).
* The ``profile`` / ``fetch_profile_blob`` methods are **removed** —
  profile is an Aran concern in v0.2 and is computed by running SQL via
  the regular ``query`` method.
* The ``materialize`` / ``materialize_async`` methods are **removed**
  (2026-08-27) along with the endpoint behind them. Aran materializes with
  its own ``materialize-oql`` operator; the lake's second materializer ran
  caller SQL with the ``/api/query`` safety screen bypassed.

Usage:

    from aran_lake_client import LakeClient

    client = LakeClient(
        endpoint="http://localhost:8080",
        bearer_token="dev-shared-secret-change-in-prod",
        tenant_id="00000000-0000-0000-0000-000000000001",
    )

    # Upload a CSV directly (no need to convert to parquet first):
    snap = client.upload_snapshot(
        dataset_id="...",
        local_path="data.csv",
        content_type="text/csv",
        format_options={"header": True, "delim": ","},
    )

    # Query it — the lake reads it as CSV under the hood:
    table = client.query(snap.id, "SELECT count(*) FROM snapshot")
"""
from __future__ import annotations

import hashlib
import io
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from uuid import UUID

import httpx
import pyarrow as pa
import pyarrow.ipc as ipc


@dataclass(frozen=True, slots=True)
class Snapshot:
    id: str
    dataset_id: str
    object_key: str
    content_hash: str
    size_bytes: int
    row_count: int | None
    content_type: str
    format_options: dict[str, Any]
    derived_from_snapshot_id: str | None
    derived_via_job_id: str | None


class LakeClientError(Exception):
    """Base error for client-side failures."""


class LakeClient:
    """
    Synchronous client. For async use, swap httpx.Client for httpx.AsyncClient
    and add ``await`` to the call sites — the contract is identical.

    The ``bearer_token`` parameter is the v0.2 name for what v0.1 called
    ``shared_secret``. The old name is still accepted for backward compat.
    """

    def __init__(
        self,
        *,
        endpoint: str,
        bearer_token: str | None = None,
        shared_secret: str | None = None,   # v0.1 compat
        tenant_id: str | UUID,
        actor_id: str | UUID | None = None,
        request_timeout: float = 30.0,
    ) -> None:
        token = bearer_token if bearer_token is not None else shared_secret
        if token is None:
            raise ValueError("either bearer_token or shared_secret must be provided")
        self._endpoint = endpoint.rstrip("/")
        self._headers = {
            "Authorization": f"Bearer {token}",
            "X-Tenant-Id": str(tenant_id),
        }
        if actor_id is not None:
            self._headers["X-Actor-Id"] = str(actor_id)
        self._http = httpx.Client(timeout=request_timeout)

    # --- snapshots ---

    def upload_snapshot(
        self,
        *,
        dataset_id: str | UUID,
        local_path: str | Path,
        content_type: str,
        format_options: dict[str, Any] | None = None,
    ) -> Snapshot:
        """
        Upload a local file as a new snapshot. Performs the full
        three-step flow (init → presigned PUT → complete) so the caller
        doesn't have to deal with presigned URLs directly.

        Any ``content_type`` is accepted by the lake at upload time. The
        runner currently knows how to query parquet, CSV (incl. TSV),
        NDJSON, and Excel; other types are stored but won't be queryable
        until support is added.
        """
        path = Path(local_path)
        if not path.exists():
            raise FileNotFoundError(local_path)
        body = path.read_bytes()
        content_hash = hashlib.sha256(body).hexdigest()

        # Step 1 — init
        init = self._post_json(
            f"/api/datasets/{dataset_id}/snapshots/init",
            {
                "declared_size_bytes": len(body),
                "content_type": content_type,
                "format_options": format_options or {},
            },
        )

        # Step 2 — PUT directly to Garage via the presigned URL
        # (No auth headers; the URL is signed)
        put_resp = self._http.put(
            init["upload_url"],
            content=body,
            headers=init["required_headers"],
        )
        if put_resp.status_code not in (200, 204):
            raise LakeClientError(
                f"presigned PUT failed: {put_resp.status_code} {put_resp.text[:200]}"
            )

        # Step 3 — complete
        result = self._post_json(
            f"/api/snapshots/uploads/{init['upload_id']}/complete",
            {
                "content_hash": content_hash,
                "actual_size_bytes": len(body),
            },
        )
        return _to_snapshot(result)

    def download_url(self, snapshot_id: str | UUID) -> str:
        """
        Mint a short-lived presigned GET URL for the snapshot's raw bytes.

        Use cases: streaming the raw parquet/CSV via pyarrow, exporting to
        a third-party engine, regulatory ship-out, archival. Anything that
        wants the *file*, not the result of a SQL query.
        """
        r = self._get(f"/api/snapshots/{snapshot_id}/download")
        return r["download_url"]

    # --- query ---

    def query(
        self,
        snapshot_id: str | UUID,
        sql: str,
        *,
        row_limit: int | None = None,
    ) -> pa.Table:
        """
        Execute SQL against a snapshot and return the result as an
        Arrow Table. The view name ``snapshot`` is bound to the snapshot's
        bytes inside the lake using format-aware dispatch (parquet, CSV,
        NDJSON, Excel — whatever the snapshot is).
        """
        body: dict[str, Any] = {"snapshot_id": str(snapshot_id), "sql": sql}
        if row_limit is not None:
            body["row_limit"] = row_limit
        resp = self._http.post(
            f"{self._endpoint}/api/query",
            json=body,
            headers={**self._headers,
                     "Accept": "application/vnd.apache.arrow.stream"},
        )
        if resp.status_code != 200:
            raise LakeClientError(
                f"query failed: {resp.status_code} {resp.text[:200]}"
            )
        return ipc.open_stream(io.BytesIO(resp.content)).read_all()

    # --- jobs ---

    def get_job(self, job_id: str | UUID) -> dict[str, Any]:
        return self._get(f"/api/jobs/{job_id}")

    def _wait_for_job(
        self, job_id: str, poll_interval: float, timeout: float,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            j = self.get_job(job_id)
            if j["status"] == "SUCCEEDED":
                return j["result"]
            if j["status"] == "FAILED":
                raise LakeClientError(f"job {job_id} failed: {j.get('error_message')}")
            time.sleep(poll_interval)
        raise LakeClientError(f"job {job_id} did not complete within {timeout}s")

    # --- internal ---

    def _post_json(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        resp = self._http.post(
            f"{self._endpoint}{path}", json=body, headers=self._headers,
        )
        if resp.status_code not in (200, 201, 202):
            raise LakeClientError(
                f"POST {path} failed: {resp.status_code} {resp.text[:200]}"
            )
        return resp.json()

    def _get(self, path: str) -> Any:
        resp = self._http.get(f"{self._endpoint}{path}", headers=self._headers)
        if resp.status_code != 200:
            raise LakeClientError(
                f"GET {path} failed: {resp.status_code} {resp.text[:200]}"
            )
        return resp.json()

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> LakeClient:
        return self

    def __exit__(self, *_a: object) -> None:
        self.close()


def _to_snapshot(r: dict[str, Any]) -> Snapshot:
    return Snapshot(
        id=r["id"],
        dataset_id=r["dataset_id"],
        object_key=r["object_key"],
        content_hash=r["content_hash"],
        size_bytes=r["size_bytes"],
        row_count=r.get("row_count"),
        content_type=r.get("content_type", "application/vnd.apache.parquet"),
        format_options=r.get("format_options") or {},
        derived_from_snapshot_id=r.get("derived_from_snapshot_id"),
        derived_via_job_id=r.get("derived_via_job_id"),
    )

"""
Example: kick off a Postgres-source import job (still scaffolded in v0.2).

The endpoint and queue infrastructure are wired; the handler still returns
``status: not_implemented``. This example demonstrates the API contract
that Aran integrates against.
"""
from __future__ import annotations

from uuid import uuid4

from aran_lake_client import LakeClient, LakeClientError

TENANT_ID = "00000000-0000-0000-0000-000000000001"

with LakeClient(
    endpoint="http://localhost:8080",
    bearer_token="dev-shared-secret-change-in-prod",
    tenant_id=TENANT_ID,
) as client:
    resp = client._post_json("/api/imports", {  # using internal for demo brevity
        "dataset_id": str(uuid4()),
        "source": {
            "kind": "postgres",
            "connection_string": "postgresql://demo:demo@source-db:5432/ehr",
            "query": "SELECT * FROM patients WHERE updated_at > '2026-01-01'",
        },
    })
    print(f"Job enqueued: {resp['job_id']}")

    try:
        result = client._wait_for_job(resp["job_id"], poll_interval=0.5, timeout=30)
        print(f"Job succeeded with result: {result}")
        if result.get("status") == "not_implemented":
            print("(This is the v0.2 stub — full impl in a later release.)")
    except LakeClientError as e:
        print(f"Job failed: {e}")

"""
Tenancy is the single most important invariant in the lake. These tests
verify the enforcement at multiple levels:

1. require_tenant rejects missing/malformed headers.
2. require_bearer rejects missing/wrong secrets.
3. Repository functions only return rows for the supplied tenant.
4. Key-construction utilities namespace by tenant.

v0.2 changes vs v0.1:
  * Dataset upsert no longer takes ``name`` / ``description``.
  * profile_blob_key is gone.
"""
from __future__ import annotations

from uuid import uuid4

import pytest

from aran_lake.catalog import repositories as repo
from aran_lake.storage.garage import (
    is_tenant_owned_key,
    snapshot_object_key,
    upload_staging_key,
)

# --- Repository-layer isolation ---

@pytest.mark.asyncio
async def test_get_snapshot_returns_none_across_tenants(db_session) -> None:
    tenant_a = uuid4()
    tenant_b = uuid4()
    dataset_id = uuid4()

    await repo.upsert_dataset(
        db_session, tenant_id=tenant_a, dataset_id=dataset_id,
    )
    snap = await repo.create_snapshot(
        db_session,
        tenant_id=tenant_a,
        dataset_id=dataset_id,
        object_key=f"{tenant_a}/snapshots/{dataset_id}/h.bin",
        content_hash="h" * 64,
        size_bytes=100,
        row_count=10,
    )
    await db_session.commit()

    found = await repo.get_snapshot(
        db_session, tenant_id=tenant_b, snapshot_id=snap.id,
    )
    assert found is None, "tenant B should not see tenant A's snapshot"

    found_a = await repo.get_snapshot(
        db_session, tenant_id=tenant_a, snapshot_id=snap.id,
    )
    assert found_a is not None


@pytest.mark.asyncio
async def test_list_snapshots_scoped_to_tenant(db_session) -> None:
    tenant_a = uuid4()
    tenant_b = uuid4()
    dataset_id = uuid4()  # same dataset id, different tenants — paranoid case

    await repo.upsert_dataset(db_session, tenant_id=tenant_a, dataset_id=dataset_id)
    await repo.upsert_dataset(db_session, tenant_id=tenant_b, dataset_id=dataset_id)
    await repo.create_snapshot(
        db_session, tenant_id=tenant_a, dataset_id=dataset_id,
        object_key="key-a", content_hash="a" * 64, size_bytes=1, row_count=1,
    )
    await repo.create_snapshot(
        db_session, tenant_id=tenant_b, dataset_id=dataset_id,
        object_key="key-b", content_hash="b" * 64, size_bytes=1, row_count=1,
    )
    await db_session.commit()

    list_a = await repo.list_snapshots(db_session, tenant_id=tenant_a, dataset_id=dataset_id)
    list_b = await repo.list_snapshots(db_session, tenant_id=tenant_b, dataset_id=dataset_id)
    assert len(list_a) == 1
    assert list_a[0].content_hash.startswith("a")
    assert len(list_b) == 1
    assert list_b[0].content_hash.startswith("b")


# --- HTTP-layer enforcement ---

def test_request_without_bearer_token_is_rejected(http_client, tenant_id) -> None:
    resp = http_client.get(
        f"/api/snapshots/{uuid4()}/download",
        headers={"X-Tenant-Id": str(tenant_id)},
    )
    assert resp.status_code == 401


def test_request_without_tenant_header_is_rejected(http_client) -> None:
    resp = http_client.get(
        f"/api/snapshots/{uuid4()}/download",
        headers={"Authorization": "Bearer test-secret-aran"},
    )
    assert resp.status_code == 400


def test_request_with_malformed_tenant_header_is_rejected(http_client) -> None:
    resp = http_client.get(
        f"/api/snapshots/{uuid4()}/download",
        headers={
            "Authorization": "Bearer test-secret-aran",
            "X-Tenant-Id": "not-a-uuid",
        },
    )
    assert resp.status_code == 400


def test_request_with_wrong_bearer_secret_is_rejected(http_client, tenant_id) -> None:
    resp = http_client.get(
        f"/api/snapshots/{uuid4()}/download",
        headers={
            "Authorization": "Bearer wrong-secret",
            "X-Tenant-Id": str(tenant_id),
        },
    )
    assert resp.status_code == 401


# --- Key namespacing ---

def test_keys_are_namespaced_by_tenant() -> None:
    tenant_a = uuid4()
    tenant_b = uuid4()
    dataset_id = uuid4()
    h = "x" * 64

    a_key = snapshot_object_key(tenant_a, dataset_id, h)
    b_key = snapshot_object_key(tenant_b, dataset_id, h)

    assert a_key.startswith(f"{tenant_a}/")
    assert b_key.startswith(f"{tenant_b}/")
    assert a_key != b_key  # same dataset+hash but tenants differ


def test_is_tenant_owned_key_recognizes_prefix() -> None:
    tenant = uuid4()
    key = snapshot_object_key(tenant, uuid4(), "h" * 64)
    assert is_tenant_owned_key(key, tenant)
    assert not is_tenant_owned_key(key, uuid4())


def test_staging_key_is_tenant_scoped() -> None:
    tenant = uuid4()
    key = upload_staging_key(tenant, uuid4(), uuid4())
    assert is_tenant_owned_key(key, tenant)
    assert "_staging" in key

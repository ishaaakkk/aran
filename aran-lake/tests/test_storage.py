"""
Storage accounting.

The one number Aran cannot derive from its own event log: events say what moved,
not what remains, and nothing removes a snapshot. These tests pin the two totals
apart, because the gap between them is the whole reason there are two.
"""
from __future__ import annotations

from uuid import uuid4

import pytest

from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


async def _snapshot(session, *, tenant_id, dataset_id, content_hash, size_bytes):
    from aran_lake.catalog import repositories as repo

    await repo.upsert_dataset(session, tenant_id=tenant_id, dataset_id=dataset_id)
    return await repo.create_snapshot(
        session,
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        object_key=f"{tenant_id}/snapshots/{dataset_id}/{content_hash[:8]}.parquet",
        content_hash=content_hash,
        size_bytes=size_bytes,
        row_count=None,
        content_type="application/vnd.apache.parquet",
        format_options={},
    )


async def test_empty_tenant_reports_zero_rather_than_failing(
    http_client, tenant_id,
) -> None:
    resp = http_client.get(
        f"/api/tenants/{tenant_id}/storage", headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["snapshot_count"] == 0
    assert body["logical_bytes"] == 0
    assert body["physical_bytes"] == 0
    assert body["oldest_snapshot_at"] is None
    assert body["datasets"] == []


async def test_logical_counts_every_row_and_physical_deduplicates(
    http_client, tenant_id, seed_session,
) -> None:
    """
    Three rows, two distinct hashes: the same bytes uploaded twice occupy the
    bucket once, so physical must not double-count them.
    """
    dataset_a, dataset_b = uuid4(), uuid4()
    await _snapshot(seed_session, tenant_id=tenant_id, dataset_id=dataset_a,
                    content_hash="a" * 64, size_bytes=1000)
    # Same content, different dataset — the deduplication case.
    await _snapshot(seed_session, tenant_id=tenant_id, dataset_id=dataset_b,
                    content_hash="a" * 64, size_bytes=1000)
    await _snapshot(seed_session, tenant_id=tenant_id, dataset_id=dataset_b,
                    content_hash="b" * 64, size_bytes=500)
    await seed_session.commit()

    body = http_client.get(
        f"/api/tenants/{tenant_id}/storage", headers=auth_headers(tenant_id),
    ).json()

    assert body["snapshot_count"] == 3
    assert body["logical_bytes"] == 2500
    assert body["physical_bytes"] == 1500
    assert body["oldest_snapshot_at"] is not None


async def test_equal_sizes_with_different_content_are_not_deduplicated(
    http_client, tenant_id, seed_session,
) -> None:
    """
    The trap in writing this as SUM(DISTINCT size_bytes): two unrelated files that
    happen to be the same length are two objects, and collapsing them would
    under-report the bill.
    """
    dataset_id = uuid4()
    await _snapshot(seed_session, tenant_id=tenant_id, dataset_id=dataset_id,
                    content_hash="c" * 64, size_bytes=777)
    await _snapshot(seed_session, tenant_id=tenant_id, dataset_id=dataset_id,
                    content_hash="d" * 64, size_bytes=777)
    await seed_session.commit()

    body = http_client.get(
        f"/api/tenants/{tenant_id}/storage", headers=auth_headers(tenant_id),
    ).json()

    assert body["logical_bytes"] == 1554
    assert body["physical_bytes"] == 1554


async def test_datasets_are_broken_out_largest_first(
    http_client, tenant_id, seed_session,
) -> None:
    small, large = uuid4(), uuid4()
    await _snapshot(seed_session, tenant_id=tenant_id, dataset_id=small,
                    content_hash="e" * 64, size_bytes=10)
    await _snapshot(seed_session, tenant_id=tenant_id, dataset_id=large,
                    content_hash="f" * 64, size_bytes=9000)
    await seed_session.commit()

    body = http_client.get(
        f"/api/tenants/{tenant_id}/storage", headers=auth_headers(tenant_id),
    ).json()

    assert [d["dataset_id"] for d in body["datasets"]] == [str(large), str(small)]
    assert body["datasets"][0]["logical_bytes"] == 9000


async def test_another_tenants_bytes_are_never_counted(
    http_client, tenant_id, seed_session,
) -> None:
    """Storage is a billing number; leaking someone else's into it is not a rounding error."""
    other = uuid4()
    await _snapshot(seed_session, tenant_id=other, dataset_id=uuid4(),
                    content_hash="9" * 64, size_bytes=123_456)
    await seed_session.commit()

    body = http_client.get(
        f"/api/tenants/{tenant_id}/storage", headers=auth_headers(tenant_id),
    ).json()
    assert body["logical_bytes"] == 0

    # And the path id is decoration — the header decides, so asking about someone
    # else's tenant returns your own numbers rather than theirs.
    spoofed = http_client.get(
        f"/api/tenants/{other}/storage", headers=auth_headers(tenant_id),
    ).json()
    assert spoofed["logical_bytes"] == 0
    assert spoofed["tenant_id"] == str(tenant_id)

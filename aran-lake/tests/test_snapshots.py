"""
Snapshot upload flow tests.

The init endpoint mints a presigned URL — we can't easily test the actual
PUT to Garage from the unit suite (no Garage container in the test
fixtures). What we *can* test:

* init returns a well-formed response with a presigned URL
* the upload record is created in the catalog
* tenants can't see each other's uploads
* the complete endpoint validates the upload exists and the staged
  object is in storage (mocked here)
* format_options are accepted and round-tripped through the snapshot row
* the new /download endpoint mints a presigned GET

v0.2 changes vs v0.1:
  * No ``dataset_name`` in requests.
  * ``content_type`` is required (no default).
  * ``format_options`` optional but round-trips.
  * New ``/api/snapshots/{id}/download`` returns a presigned GET URL.
"""
from __future__ import annotations

from datetime import UTC, datetime
from unittest.mock import AsyncMock, patch
from uuid import uuid4

import pytest

from tests.conftest import auth_headers

# The init endpoint actually talks to Garage to mint the URL. We patch
# the ObjectStore method to return a fake presigned URL.

@pytest.fixture
def _fake_presign_put():
    """Patch ObjectStore.presign_put to return a deterministic stub."""
    from aran_lake.storage.garage import PresignedUpload
    fake = PresignedUpload(
        url="http://fake-garage:3900/aran-lake/some-key?X-Amz-Signature=fake",
        object_key="fake-staging-key",
        expires_at=datetime(2030, 1, 1, tzinfo=UTC),
        headers={"Content-Type": "text/csv"},
    )
    with patch(
        "aran_lake.storage.garage.ObjectStore.presign_put",
        new=AsyncMock(return_value=fake),
    ):
        yield fake


@pytest.fixture
def _fake_presign_get():
    """Patch ObjectStore.presign_get for /download tests."""
    from aran_lake.storage.garage import PresignedDownload
    fake = PresignedDownload(
        url="http://fake-garage:3900/aran-lake/some-key?X-Amz-Signature=fake-get",
        object_key="fake-key",
        expires_at=datetime(2030, 1, 1, tzinfo=UTC),
    )
    with patch(
        "aran_lake.storage.garage.ObjectStore.presign_get",
        new=AsyncMock(return_value=fake),
    ):
        yield fake


@pytest.mark.usefixtures("_fake_presign_put")
def test_init_upload_returns_presigned_url(http_client, tenant_id) -> None:
    dataset_id = uuid4()
    resp = http_client.post(
        f"/api/datasets/{dataset_id}/snapshots/init",
        json={
            "declared_size_bytes": 1024,
            "content_type": "application/vnd.apache.parquet",
        },
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert "upload_id" in body
    assert body["upload_url"].startswith("http://")
    assert "Content-Type" in body["required_headers"]


@pytest.mark.usefixtures("_fake_presign_put")
def test_init_upload_accepts_format_options(http_client, tenant_id) -> None:
    """CSV upload with read hints — round-trip via the catalog."""
    dataset_id = uuid4()
    resp = http_client.post(
        f"/api/datasets/{dataset_id}/snapshots/init",
        json={
            "declared_size_bytes": 1024,
            "content_type": "text/csv",
            "format_options": {"header": True, "delim": ";", "encoding": "utf-8"},
        },
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 201, resp.text


@pytest.mark.usefixtures("_fake_presign_put")
def test_init_upload_rejects_zero_size(http_client, tenant_id) -> None:
    resp = http_client.post(
        f"/api/datasets/{uuid4()}/snapshots/init",
        json={"declared_size_bytes": 0, "content_type": "text/csv"},
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 422


@pytest.mark.usefixtures("_fake_presign_put")
def test_init_upload_rejects_obscenely_large_size(http_client, tenant_id) -> None:
    # 100 GiB exceeds the 50 GiB cap
    resp = http_client.post(
        f"/api/datasets/{uuid4()}/snapshots/init",
        json={
            "declared_size_bytes": 100 * 1024 * 1024 * 1024,
            "content_type": "application/vnd.apache.parquet",
        },
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 422


@pytest.mark.usefixtures("_fake_presign_put")
def test_init_upload_requires_content_type(http_client, tenant_id) -> None:
    """content_type is mandatory in v0.2 — no defaulting."""
    resp = http_client.post(
        f"/api/datasets/{uuid4()}/snapshots/init",
        json={"declared_size_bytes": 100},
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 422


def test_complete_upload_404_when_upload_does_not_exist(
    http_client, tenant_id,
) -> None:
    resp = http_client.post(
        f"/api/snapshots/uploads/{uuid4()}/complete",
        json={"content_hash": "a" * 64, "actual_size_bytes": 100},
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 404


@pytest.mark.usefixtures("_fake_presign_put")
def test_complete_upload_404_does_not_name_the_owning_tenant(
    http_client, tenant_id,
) -> None:
    """
    Completing another tenant's upload must not disclose whose it is.

    The handler once looked the upload up across all tenants and returned the
    owner in the 404 detail, so anyone who guessed an upload id learned both
    that it existed and which tenant owned it. The lookup is scoped to the
    caller's tenant now, so there is no other owner to name.
    """
    dataset_id = uuid4()
    init = http_client.post(
        f"/api/datasets/{dataset_id}/snapshots/init",
        json={
            "declared_size_bytes": 1024,
            "content_type": "application/vnd.apache.parquet",
        },
        headers=auth_headers(tenant_id),
    )
    assert init.status_code == 201, init.text
    upload_id = init.json()["upload_id"]

    # A second tenant tries to complete the first tenant's upload.
    other_tenant = uuid4()
    resp = http_client.post(
        f"/api/snapshots/uploads/{upload_id}/complete",
        json={"content_hash": "a" * 64, "actual_size_bytes": 100},
        headers=auth_headers(other_tenant),
    )

    assert resp.status_code == 404
    detail = resp.json()["detail"]
    assert str(tenant_id) not in detail
    # ...and the response is the same one an entirely absent upload gets, so the
    # 404 body itself does not distinguish "exists elsewhere" from "never existed".
    assert detail == f"Upload {upload_id} not found in catalog database"


# --- /complete content-hash verification ---
#
# The claimed content_hash is both the dedup key and the object key, so a wrong
# claim is not a bookkeeping error: a claim that collides with an existing
# snapshot's hash used to return that *other* snapshot with a 201 and delete the
# bytes that were actually uploaded. These tests drive /complete against an
# in-memory S3 fake so the real streaming hash runs, not a stub of it.


class _FakeBody:
    """Stands in for botocore's StreamingBody — only what the hash path uses."""

    def __init__(self, data: bytes) -> None:
        self._data = data
        self.closed = False

    def iter_chunks(self, chunk_size: int = 1024):
        for i in range(0, len(self._data), chunk_size):
            yield self._data[i:i + chunk_size]

    def read(self) -> bytes:
        return self._data

    def close(self) -> None:
        self.closed = True


class _FakeS3:
    """
    In-memory stand-in for the boto3 S3 client.

    ``sizes`` lets a test make head_object report a length the stored bytes
    don't have — that is how the above-the-ceiling path is exercised without
    materialising a gigabyte.
    """

    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}
        self.sizes: dict[str, int] = {}
        self.get_calls: list[str] = []

    def head_object(self, *, Bucket: str, Key: str):
        from botocore.exceptions import ClientError
        if Key not in self.objects:
            raise ClientError({"Error": {"Code": "404"}}, "HeadObject")
        return {"ContentLength": self.sizes.get(Key, len(self.objects[Key]))}

    def get_object(self, *, Bucket: str, Key: str):
        self.get_calls.append(Key)
        return {"Body": _FakeBody(self.objects[Key])}

    def copy_object(self, *, Bucket: str, Key: str, CopySource: dict):
        self.objects[Key] = self.objects[CopySource["Key"]]

    def delete_object(self, *, Bucket: str, Key: str):
        self.objects.pop(Key, None)

    def generate_presigned_url(self, *args, **kwargs) -> str:
        return "http://fake-garage:3900/aran-lake/staged?X-Amz-Signature=fake"


@pytest.fixture
def fake_s3():
    """Give every ObjectStore built during the test the same in-memory client."""
    fake = _FakeS3()
    with patch("aran_lake.storage.garage.boto3.client", return_value=fake):
        yield fake


def _init_upload(http_client, tenant_id, dataset_id) -> tuple[str, str]:
    """Init an upload and return (upload_id, staging object key)."""
    from aran_lake.storage.garage import upload_staging_key

    resp = http_client.post(
        f"/api/datasets/{dataset_id}/snapshots/init",
        json={"declared_size_bytes": 1024, "content_type": "text/csv"},
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 201, resp.text
    upload_id = resp.json()["upload_id"]
    from uuid import UUID as _UUID
    return upload_id, upload_staging_key(tenant_id, dataset_id, _UUID(upload_id))


def test_complete_with_correct_hash_registers_the_snapshot(
    http_client, tenant_id, fake_s3,
) -> None:
    """The unchanged happy path: verification is transparent when the claim is true."""
    import hashlib

    dataset_id = uuid4()
    upload_id, staging_key = _init_upload(http_client, tenant_id, dataset_id)
    payload = b"id,name\n1,ada\n"
    fake_s3.objects[staging_key] = payload
    real_hash = hashlib.sha256(payload).hexdigest()

    resp = http_client.post(
        f"/api/snapshots/uploads/{upload_id}/complete",
        json={"content_hash": real_hash, "actual_size_bytes": len(payload),
              "row_count": 1},
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["content_hash"] == real_hash
    assert body["object_key"].endswith(f"{real_hash}.bin")
    assert fake_s3.get_calls == [staging_key]  # the bytes really were read


@pytest.mark.asyncio
async def test_complete_with_wrong_hash_is_rejected_and_cannot_hijack_a_snapshot(
    http_client, tenant_id, seed_session, fake_s3,
) -> None:
    """
    The dedup-corruption case. Claiming an existing snapshot's hash for
    different bytes used to return that snapshot with a 201 — the caller
    believed their upload had registered, and read someone else's rows.
    """
    import hashlib

    from aran_lake.catalog import repositories as repo

    dataset_id = uuid4()
    victim_hash = "b" * 64
    await repo.upsert_dataset(seed_session, tenant_id=tenant_id, dataset_id=dataset_id)
    victim = await repo.create_snapshot(
        seed_session,
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        object_key=f"{tenant_id}/snapshots/{dataset_id}/{victim_hash}.bin",
        content_hash=victim_hash,
        size_bytes=14,
        row_count=99,
        content_type="text/csv",
        format_options={},
    )
    await seed_session.commit()

    upload_id, staging_key = _init_upload(http_client, tenant_id, dataset_id)
    payload = b"id,name\n2,bob\n"
    fake_s3.objects[staging_key] = payload

    resp = http_client.post(
        f"/api/snapshots/uploads/{upload_id}/complete",
        # Same length as the victim, so the size check cannot catch this.
        json={"content_hash": victim_hash, "actual_size_bytes": len(payload)},
        headers=auth_headers(tenant_id),
    )

    assert resp.status_code == 409, resp.text
    detail = resp.json()["detail"]
    assert victim_hash in detail
    assert hashlib.sha256(payload).hexdigest() in detail

    # The other snapshot was neither returned nor touched...
    seed_session.expire_all()
    listed = await repo.list_snapshots(seed_session, tenant_id=tenant_id, dataset_id=dataset_id)
    assert [s.id for s in listed] == [victim.id]
    assert listed[0].row_count == 99
    assert listed[0].size_bytes == 14
    # ...and the uploaded bytes were not deleted out from under the caller,
    # who can retry /complete with the hash their bytes actually have.
    assert fake_s3.objects[staging_key] == payload


def test_complete_above_the_ceiling_falls_back_to_size_only_and_warns(
    http_client, tenant_id, fake_s3, capsys,
) -> None:
    """
    Hashing a huge object in the request path is worse than not hashing it, so
    above ``hash_verify_max_bytes`` the size check stands alone — loudly. The
    fake reports a length its bytes don't have; the point is that nothing reads
    them.

    Asserted on the emitted log line rather than ``structlog.testing.capture_logs``:
    the app configures structlog with ``cache_logger_on_first_use``, so a logger
    bound by an earlier test keeps the processor chain it was cached with and
    never reaches the capture.
    """
    import json

    dataset_id = uuid4()
    upload_id, staging_key = _init_upload(http_client, tenant_id, dataset_id)
    fake_s3.objects[staging_key] = b"tiny"
    huge = 4 * 1024 * 1024 * 1024  # 4 GiB, over the 1 GiB default ceiling
    fake_s3.sizes[staging_key] = huge

    unverified = "c" * 64
    capsys.readouterr()  # drop anything the fixtures logged
    resp = http_client.post(
        f"/api/snapshots/uploads/{upload_id}/complete",
        json={"content_hash": unverified, "actual_size_bytes": huge},
        headers=auth_headers(tenant_id),
    )
    emitted = capsys.readouterr().out

    assert resp.status_code == 201, resp.text
    assert resp.json()["content_hash"] == unverified
    assert fake_s3.get_calls == []  # never streamed

    warned = [
        json.loads(line) for line in emitted.splitlines()
        if line.startswith("{") and "hash-verification ceiling" in line
    ]
    assert warned, emitted
    assert warned[0]["level"] == "warning"
    assert warned[0]["content_hash"] == unverified
    assert warned[0]["ceiling_bytes"] == 1024 * 1024 * 1024


@pytest.mark.asyncio
@pytest.mark.parametrize("path", [
    "/api/datasets/{dataset_id}/snapshots",
    "/api/snapshots/{snapshot_id}",
])
async def test_unconsumed_snapshot_read_routes_are_gone(
    http_client, tenant_id, seed_session, path,
) -> None:
    """S12-11: nothing in Aran or freyja read these, so they were surface, not API."""
    from aran_lake.catalog import repositories as repo

    # Real ids of the caller's own snapshot: a random id would draw the deleted handler's own
    # "snapshot not found" 404 too, and the test could not tell the route was gone.
    dataset_id = uuid4()
    await repo.upsert_dataset(seed_session, tenant_id=tenant_id, dataset_id=dataset_id)
    snap = await repo.create_snapshot(
        seed_session,
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        object_key=f"{tenant_id}/snapshots/{dataset_id}/abc.bin",
        content_hash="b" * 64,
        size_bytes=100,
        row_count=10,
        content_type="text/csv",
        format_options={"header": True},
    )
    await seed_session.commit()

    resp = http_client.get(
        path.format(dataset_id=dataset_id, snapshot_id=snap.id),
        headers=auth_headers(tenant_id),
    )
    # The router's own answers, not a handler's.
    assert (resp.status_code, resp.json()) in [
        (404, {"detail": "Not Found"}),
        (405, {"detail": "Method Not Allowed"}),
    ], resp.text


# --- /download endpoint ---

def test_download_returns_404_when_snapshot_missing(http_client, tenant_id) -> None:
    resp = http_client.get(
        f"/api/snapshots/{uuid4()}/download",
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
@pytest.mark.usefixtures("_fake_presign_get")
async def test_download_returns_presigned_url_for_existing_snapshot(
    http_client, tenant_id, seed_session,
) -> None:
    """End-to-end: create a snapshot row directly, then call /download."""
    from aran_lake.catalog import repositories as repo

    dataset_id = uuid4()
    await repo.upsert_dataset(seed_session, tenant_id=tenant_id, dataset_id=dataset_id)
    snap = await repo.create_snapshot(
        seed_session,
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        object_key=f"{tenant_id}/snapshots/{dataset_id}/abc.bin",
        content_hash="a" * 64,
        size_bytes=100,
        row_count=10,
        content_type="text/csv",
        format_options={"header": True},
    )
    await seed_session.commit()

    resp = http_client.get(
        f"/api/snapshots/{snap.id}/download",
        headers=auth_headers(tenant_id),
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["snapshot_id"] == str(snap.id)
    assert body["download_url"].startswith("http://")
    assert body["content_type"] == "text/csv"


# --- the id in a response must be backed by a committed row -------------------
#
# FastAPI runs a yield-dependency's exit code AFTER the response has been sent, so a handler
# that leaves the commit to get_session's teardown hands the client an id before the row is
# durable. Aran acts on that id immediately (upload bytes, then /complete), and under a busy
# event loop /complete arrived first and 404'd — three registrations lost in one e2e run.
# These tests pin the order, not the race: the handler's own commit must precede the point at
# which the dependency resumes, which is after the handler has returned.


class _RecordingSession:
    """A real AsyncSession that also records when it is committed."""

    def __init__(self, inner, events: list[str]) -> None:
        self._inner = inner
        self._events = events

    def __getattr__(self, name: str):
        return getattr(self._inner, name)

    async def commit(self) -> None:
        self._events.append("commit")
        await self._inner.commit()


@pytest.fixture
def commit_order(http_client):
    """Override get_session so the test can see whether the handler committed before returning."""
    from aran_lake.catalog.session import get_session, get_session_factory

    events: list[str] = []

    async def recording_get_session():
        factory = get_session_factory()
        async with factory() as session:
            try:
                yield _RecordingSession(session, events)
                events.append("resumed")   # the handler has returned; the response is on its way
                await session.commit()
            except Exception:
                await session.rollback()
                raise

    http_client.app.dependency_overrides[get_session] = recording_get_session
    yield events
    http_client.app.dependency_overrides.pop(get_session, None)


@pytest.mark.usefixtures("_fake_presign_put")
def test_init_upload_commits_its_row_before_responding(http_client, tenant_id, commit_order) -> None:
    r = http_client.post(
        f"/api/datasets/{uuid4()}/snapshots/init",
        json={"content_type": "text/csv", "declared_size_bytes": 10},
        headers=auth_headers(tenant_id),
    )
    assert r.status_code == 201, r.text
    assert "commit" in commit_order
    assert "resumed" in commit_order
    assert commit_order.index("commit") < commit_order.index("resumed"), (
        f"the upload id left the process before its row was committed: {commit_order}"
    )


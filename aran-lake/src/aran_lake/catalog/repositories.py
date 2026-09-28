"""
Repository functions — the only allowed way to read/write catalog rows.

Every function takes ``tenant_id`` as an explicit, non-optional parameter
and includes it in every WHERE clause. This is the *single chokepoint*
for tenant isolation: if the API layer hands you the wrong tenant, you
get the wrong data, but no caller can ever read without supplying one.

Why functions, not a class? Because async, stateless, and easy to test
without a fixture cascade. The session is passed in, not stored.

v0.2 changes vs v0.1
--------------------

* All ``Profile`` repo functions are **removed**.
* ``upsert_dataset`` no longer takes ``name`` or ``description``; it just
  records that the dataset_id exists for the tenant.
* ``create_snapshot`` / ``create_snapshot_upload`` accept ``format_options``.
* ``create_snapshot`` accepts ``derived_from_snapshot_id`` / ``derived_via_job_id``
  for materialized-snapshot provenance.
* Snapshot and Job creation accept an optional ``created_by_caller`` for audit.
"""
from __future__ import annotations

from datetime import UTC, datetime
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from aran_lake.catalog.models import Dataset, Job, Snapshot, SnapshotUpload

# =============================================================================
# datasets
# =============================================================================

async def get_dataset(
    session: AsyncSession, *, tenant_id: UUID, dataset_id: UUID,
) -> Dataset | None:
    stmt = select(Dataset).where(
        and_(Dataset.id == dataset_id, Dataset.tenant_id == tenant_id)
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def upsert_dataset(
    session: AsyncSession, *,
    tenant_id: UUID, dataset_id: UUID,
) -> Dataset:
    """
    Insert a dataset row if absent; return the existing row otherwise.

    The lake's ``Dataset`` is just a bare grouping key — no name,
    no description, no schema. Aran owns those things; we only record
    that snapshots exist under this dataset_id for this tenant.
    """
    existing = await get_dataset(session, tenant_id=tenant_id, dataset_id=dataset_id)
    if existing is not None:
        return existing
    row = Dataset(
        id=dataset_id, tenant_id=tenant_id, created_at=datetime.now(UTC),
    )
    session.add(row)
    await session.flush()
    return row


# =============================================================================
# snapshot uploads (in-flight)
# =============================================================================

async def create_snapshot_upload(
    session: AsyncSession, *,
    upload_id: UUID | None = None,
    tenant_id: UUID,
    dataset_id: UUID,
    object_key: str,
    declared_size_bytes: int,
    content_type: str,
    format_options: dict[str, Any] | None,
    expires_at: datetime,
    multipart_upload_id: str | None = None,
    created_by: UUID | None = None,
) -> SnapshotUpload:
    upload = SnapshotUpload(
        id=upload_id or uuid4(),
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        object_key=object_key,
        declared_size_bytes=declared_size_bytes,
        content_type=content_type,
        format_options=format_options or {},
        multipart_upload_id=multipart_upload_id,
        created_at=datetime.now(UTC),
        expires_at=expires_at,
        created_by=created_by,
    )
    session.add(upload)
    await session.flush()
    return upload


async def get_snapshot_upload(
    session: AsyncSession, *, tenant_id: UUID, upload_id: UUID,
) -> SnapshotUpload | None:
    stmt = select(SnapshotUpload).where(
        and_(SnapshotUpload.id == upload_id, SnapshotUpload.tenant_id == tenant_id)
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def delete_snapshot_upload(session: AsyncSession, upload: SnapshotUpload) -> None:
    # AsyncSession.delete IS a coroutine — it may cascade-load relationships before marking
    # the delete. The comment that used to sit here claimed the opposite and the await was
    # missing, so this built a coroutine and dropped it: the row was never marked, every
    # completed upload leaked its snapshot_uploads row, and the only signal was a
    # RuntimeWarning that pyproject's filterwarnings does not escalate. (Session.add, used
    # above, genuinely is synchronous — that asymmetry is the trap.)
    await session.delete(upload)


# =============================================================================
# snapshots
# =============================================================================

async def create_snapshot(
    session: AsyncSession, *,
    tenant_id: UUID,
    dataset_id: UUID,
    object_key: str,
    content_hash: str,
    size_bytes: int,
    row_count: int | None,
    content_type: str = "application/vnd.apache.parquet",
    format_options: dict[str, Any] | None = None,
    derived_from_snapshot_id: UUID | None = None,
    derived_via_job_id: UUID | None = None,
    created_by: UUID | None = None,
    created_by_caller: str | None = None,
) -> Snapshot:
    snapshot = Snapshot(
        id=uuid4(),
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        object_key=object_key,
        content_hash=content_hash,
        size_bytes=size_bytes,
        row_count=row_count,
        content_type=content_type,
        format_options=format_options or {},
        derived_from_snapshot_id=derived_from_snapshot_id,
        derived_via_job_id=derived_via_job_id,
        created_at=datetime.now(UTC),
        created_by=created_by,
        created_by_caller=created_by_caller,
    )
    session.add(snapshot)
    await session.flush()
    return snapshot


async def get_snapshot(
    session: AsyncSession, *, tenant_id: UUID, snapshot_id: UUID,
) -> Snapshot | None:
    stmt = select(Snapshot).where(
        and_(Snapshot.id == snapshot_id, Snapshot.tenant_id == tenant_id)
    )
    return (await session.execute(stmt)).scalar_one_or_none()


async def list_snapshots(
    session: AsyncSession, *, tenant_id: UUID, dataset_id: UUID,
    limit: int = 100,
) -> list[Snapshot]:
    stmt = (
        select(Snapshot)
        .where(and_(Snapshot.tenant_id == tenant_id, Snapshot.dataset_id == dataset_id))
        .order_by(Snapshot.created_at.desc())
        .limit(limit)
    )
    return list((await session.execute(stmt)).scalars().all())


async def find_snapshot_by_hash(
    session: AsyncSession, *,
    tenant_id: UUID, dataset_id: UUID, content_hash: str,
) -> Snapshot | None:
    stmt = select(Snapshot).where(and_(
        Snapshot.tenant_id == tenant_id,
        Snapshot.dataset_id == dataset_id,
        Snapshot.content_hash == content_hash,
    ))
    return (await session.execute(stmt)).scalar_one_or_none()


# =============================================================================
# jobs
# =============================================================================

async def create_job(
    session: AsyncSession, *,
    tenant_id: UUID,
    job_type: str,
    payload: dict[str, Any],
    created_by: UUID | None = None,
    created_by_caller: str | None = None,
) -> Job:
    job = Job(
        id=uuid4(),
        tenant_id=tenant_id,
        job_type=job_type,
        status="PENDING",
        payload=payload,
        created_at=datetime.now(UTC),
        created_by=created_by,
        created_by_caller=created_by_caller,
    )
    session.add(job)
    await session.flush()
    return job


async def get_job(
    session: AsyncSession, *, tenant_id: UUID, job_id: UUID,
) -> Job | None:
    stmt = select(Job).where(and_(Job.id == job_id, Job.tenant_id == tenant_id))
    return (await session.execute(stmt)).scalar_one_or_none()


async def claim_next_pending_job(session: AsyncSession) -> Job | None:
    """
    Worker side: atomically claim one PENDING job for processing.

    Uses ``SELECT ... FOR UPDATE SKIP LOCKED`` to make this safe under
    concurrent workers — each worker gets a different job, no double-runs.
    No tenant filter here because workers are tenant-agnostic.
    """
    stmt = (
        select(Job)
        .where(Job.status == "PENDING")
        .order_by(Job.created_at)
        .limit(1)
        .with_for_update(skip_locked=True)
    )
    job = (await session.execute(stmt)).scalar_one_or_none()
    if job is None:
        return None
    job.status = "RUNNING"
    job.started_at = datetime.now(UTC)
    await session.flush()
    return job


async def mark_job_succeeded(
    session: AsyncSession, *, job: Job, result: dict[str, Any],
) -> None:
    job.status = "SUCCEEDED"
    job.result = result
    job.finished_at = datetime.now(UTC)
    await session.flush()


async def mark_job_failed(
    session: AsyncSession, *, job: Job, error_message: str,
) -> None:
    job.status = "FAILED"
    job.error_message = error_message[:2000]  # cap; full trace goes to logs
    job.finished_at = datetime.now(UTC)
    await session.flush()


# =============================================================================
# storage accounting
# =============================================================================

async def storage_summary(
    session: AsyncSession, *, tenant_id: UUID,
) -> dict[str, Any]:
    """
    How many bytes this tenant is holding, right now.

    Two totals, because they answer different questions and the gap between them
    is worth seeing:

    ``logical_bytes``
        the sum over every snapshot row — what the tenant believes it has stored.

    ``physical_bytes``
        the sum over *distinct* ``content_hash`` values. Snapshots are
        content-addressed, so re-uploading identical bytes produces a second row
        pointing at one object. This is what actually occupies the bucket, and
        therefore what a storage bill is drawn against.

    Naming them apart matters: a tenant that re-uploads the same file nightly will
    watch ``logical_bytes`` climb while its real cost stays flat, and a single
    "storage" number would make that look like runaway growth.
    """
    row = (await session.execute(
        select(
            func.count(Snapshot.id),
            func.coalesce(func.sum(Snapshot.size_bytes), 0),
            func.min(Snapshot.created_at),
            func.max(Snapshot.created_at),
        ).where(Snapshot.tenant_id == tenant_id)
    )).one()

    # Deduplicated: one size per distinct content hash. Done as a subquery rather
    # than SUM(DISTINCT size_bytes), which would be wrong for the common case of
    # two genuinely different files that happen to be the same length.
    distinct_sizes = (
        select(Snapshot.content_hash, func.min(Snapshot.size_bytes).label("size"))
        .where(Snapshot.tenant_id == tenant_id)
        .group_by(Snapshot.content_hash)
        .subquery()
    )
    physical = (await session.execute(
        select(func.coalesce(func.sum(distinct_sizes.c.size), 0))
    )).scalar_one()

    return {
        "tenant_id": tenant_id,
        "snapshot_count": row[0],
        "logical_bytes": int(row[1]),
        "physical_bytes": int(physical),
        "oldest_snapshot_at": row[2],
        "newest_snapshot_at": row[3],
    }


async def storage_by_dataset(
    session: AsyncSession, *, tenant_id: UUID, limit: int = 50,
) -> list[dict[str, Any]]:
    """
    The same accounting per dataset, largest first.

    A tenant total says storage is growing; this says *what* is growing, which is
    the only form of the number anyone can act on.
    """
    stmt = (
        select(
            Snapshot.dataset_id,
            func.count(Snapshot.id),
            func.coalesce(func.sum(Snapshot.size_bytes), 0),
            func.max(Snapshot.created_at),
        )
        .where(Snapshot.tenant_id == tenant_id)
        .group_by(Snapshot.dataset_id)
        .order_by(func.coalesce(func.sum(Snapshot.size_bytes), 0).desc())
        .limit(limit)
    )
    return [
        {
            "dataset_id": r[0],
            "snapshot_count": r[1],
            "logical_bytes": int(r[2]),
            "newest_snapshot_at": r[3],
        }
        for r in (await session.execute(stmt)).all()
    ]


async def snapshot_inventory(
    session: AsyncSession, *, tenant_id: UUID, limit: int = 5000, offset: int = 0,
) -> list[dict[str, Any]]:
    """
    Every snapshot the tenant owns: id, dataset, size.

    Exists so Aran can work out which of these nothing points at any more. The lake
    cannot answer that itself — it has no idea what a data product or a schema
    fingerprint is, and deliberately does not; it stores bytes and who they belong
    to. Reachability is Aran's question, so Aran gets the raw list and does the
    subtraction.

    Paged, and ordered by ``created_at`` so paging is stable while rows are being
    inserted underneath it. Ordering by id would be stable too but would interleave
    new snapshots into pages already read, which quietly drops rows from the answer.
    """
    stmt = (
        select(
            Snapshot.id,
            Snapshot.dataset_id,
            Snapshot.size_bytes,
            Snapshot.content_hash,
            Snapshot.created_at,
        )
        .where(Snapshot.tenant_id == tenant_id)
        .order_by(Snapshot.created_at.asc(), Snapshot.id.asc())
        .limit(limit)
        .offset(offset)
    )
    return [
        {
            "id": r[0],
            "dataset_id": r[1],
            "size_bytes": int(r[2]),
            "content_hash": r[3],
            "created_at": r[4],
        }
        for r in (await session.execute(stmt)).all()
    ]

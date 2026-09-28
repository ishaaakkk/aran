"""
Storage accounting.

GET /api/tenants/{tenant_id}/storage   how many bytes this tenant is holding

The lake is the only component that knows this. Aran records *flow* — bytes
uploaded, bytes exported, bytes written — as events, and no amount of summing
events gives you the level: events say what moved, not what remains, and
nothing so far ever removes a snapshot. Storage has to be measured where the
rows are.

Deliberately a *reading*, not a subscription or a stream. Aran samples this on
a schedule and keeps the samples; see ``storage_sample`` on Aran's side.
"""
from __future__ import annotations

from datetime import datetime
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from aran_lake.auth.bearer import require_bearer
from aran_lake.catalog import repositories as repo
from aran_lake.catalog.session import get_session
from aran_lake.tenancy.enforcement import require_tenant

router = APIRouter(
    prefix="/api",
    tags=["storage"],
    dependencies=[Depends(require_bearer)],
)


class DatasetStorage(BaseModel):
    dataset_id: UUID
    snapshot_count: int
    logical_bytes: int
    newest_snapshot_at: datetime | None


class StorageResponse(BaseModel):
    tenant_id: UUID
    snapshot_count: int
    #: Sum over every snapshot row — what the tenant believes it stored.
    logical_bytes: int
    #: Sum over distinct content hashes — what actually occupies the bucket, and
    #: therefore what a storage bill is drawn against. Lower than ``logical_bytes``
    #: by whatever content-addressing deduplicated.
    physical_bytes: int
    oldest_snapshot_at: datetime | None
    newest_snapshot_at: datetime | None
    #: Largest datasets first. A total says storage is growing; this says what is.
    datasets: list[DatasetStorage]


@router.get("/tenants/{tenant_id}/storage", response_model=StorageResponse)
async def get_tenant_storage(
    tenant_id: UUID,
    caller_tenant: Annotated[UUID, Depends(require_tenant)],
    session: Annotated[AsyncSession, Depends(get_session)],
    dataset_limit: int = 50,
) -> StorageResponse:
    # The path id is decoration; the header-derived tenant is the authority, exactly
    # as everywhere else in the lake. A caller asking about someone else's tenant gets
    # their own numbers rather than an error, because answering "does that tenant
    # exist?" is itself a disclosure.
    summary = await repo.storage_summary(session, tenant_id=caller_tenant)
    datasets = await repo.storage_by_dataset(
        session, tenant_id=caller_tenant, limit=dataset_limit,
    )
    return StorageResponse(**summary, datasets=[DatasetStorage(**d) for d in datasets])


class SnapshotInventoryItem(BaseModel):
    id: UUID
    dataset_id: UUID
    size_bytes: int
    content_hash: str
    created_at: datetime


@router.get(
    "/tenants/{tenant_id}/snapshot-inventory",
    response_model=list[SnapshotInventoryItem],
)
async def get_snapshot_inventory(
    tenant_id: UUID,
    caller_tenant: Annotated[UUID, Depends(require_tenant)],
    session: Annotated[AsyncSession, Depends(get_session)],
    limit: int = 5000,
    offset: int = 0,
) -> list[SnapshotInventoryItem]:
    """
    The raw snapshot list, so Aran can find the ones nothing references.

    The lake does not know what "referenced" means — data products and schema
    fingerprints are Aran's concepts. It hands over ids and sizes; Aran subtracts.
    """
    rows = await repo.snapshot_inventory(
        session, tenant_id=caller_tenant, limit=min(limit, 20_000), offset=max(offset, 0),
    )
    return [SnapshotInventoryItem(**r) for r in rows]

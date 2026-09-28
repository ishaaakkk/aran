"""
Snapshots endpoint.

  POST /api/datasets/{dataset_id}/snapshots/init      mint a presigned upload URL
  POST /api/snapshots/uploads/{upload_id}/complete    confirm upload, register snapshot
  GET  /api/snapshots/{snapshot_id}/download          presigned GET for raw bytes

v0.2 changes vs v0.1
--------------------

* ``SnapshotInitRequest`` no longer carries ``dataset_name`` — Aran owns
  what a dataset means; the lake only records the opaque ``dataset_id``.
* ``SnapshotInitRequest`` and ``SnapshotResponse`` carry ``format_options``
  (CSV delimiter, JSON record path, etc).
* ``content_type`` is no longer defaulted to parquet — caller must pick.
* New ``GET /api/snapshots/{id}/download`` mints a presigned GET URL.
* Snapshot responses include ``derived_from_snapshot_id`` and ``derived_via_job_id``
  for materialize lineage.
* Snapshot creation records the bearer caller-id for audit.
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Annotated, Any, Literal
from uuid import UUID, uuid4

import structlog
from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator
from sqlalchemy.ext.asyncio import AsyncSession

from aran_lake.auth.bearer import CallerIdentity, require_bearer
from aran_lake.catalog import repositories as repo
from aran_lake.catalog.session import get_session
from aran_lake.config import Settings, get_settings
from aran_lake.storage.garage import (
    ObjectStore,
    is_tenant_owned_key,
    snapshot_object_key,
    upload_staging_key,
)
from aran_lake.tenancy.enforcement import require_actor, require_tenant

log = structlog.get_logger(__name__)

router = APIRouter(
    prefix="/api",
    tags=["snapshots"],
)


# =============================================================================
# Typed format-option models — validated at init time, not at query time.
# extra="forbid" rejects unknown keys immediately with a 422, rather than
# letting DuckDB emit a cryptic error at query execution.
# =============================================================================

class ParquetFormatOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")

class CSVFormatOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    delim: str       = Field(default=",",     max_length=10)
    header: bool     = Field(default=True)
    quote: str       = Field(default='"',     max_length=5)
    escape: str|None = Field(default=None,    max_length=5)
    encoding: str    = Field(default="utf-8", max_length=64)
    all_varchar: bool = Field(default=False)

class JSONFormatOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    format: Literal["newline_delimited", "array"] = Field(default="newline_delimited")
    records: Literal["auto", "true", "false"]     = Field(default="auto")

class ExcelFormatOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sheet: str|int|None = Field(default=None)

_PARQUET_PREFIXES = ("application/vnd.apache.parquet", "application/x-parquet", "application/parquet")
_CSV_PREFIXES     = ("text/csv", "application/csv", "text/tab-separated-values")
_JSON_PREFIXES    = ("application/x-ndjson", "application/jsonl", "application/json")
_EXCEL_PREFIXES   = (
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
)

def _validate_format_options(content_type: str, options: dict[str, Any]) -> None:
    """Raise ValueError with a readable message if options don't match content_type."""
    ct = content_type.lower().split(";", 1)[0].strip()
    if ct in _PARQUET_PREFIXES:
        model, label = ParquetFormatOptions, "Parquet"
    elif ct in _CSV_PREFIXES:
        model, label = CSVFormatOptions, "CSV"
    elif ct in _JSON_PREFIXES:
        model, label = JSONFormatOptions, "JSON/NDJSON"
    elif ct in _EXCEL_PREFIXES:
        model, label = ExcelFormatOptions, "Excel"
    else:
        return  # unknown types are accepted; they'll fail at query time with a clear error
    try:
        model.model_validate(options)
    except ValidationError as exc:
        raise ValueError(f"invalid format_options for {label}: {exc}") from exc


# =============================================================================
# request / response models
# =============================================================================

class SnapshotInitRequest(BaseModel):
    declared_size_bytes: int = Field(..., ge=1, le=50 * 1024 * 1024 * 1024,
                                     description="Estimated upload size in bytes")
    content_type: str = Field(
        ...,
        description=(
            "MIME type of the uploaded bytes. Parquet, CSV, NDJSON, "
            "Excel (xlsx) are queryable; other types are stored but not "
            "queryable until a future release adds support."
        ),
    )
    format_options: dict[str, Any] = Field(
        default_factory=dict,
        description=(
            "Per-format read hints. CSV: delim, header, quote, encoding. "
            "JSON: format ('newline_delimited' | 'array'), records. "
            "Excel: sheet. Parquet: usually empty."
        ),
    )

    @model_validator(mode="after")
    def check_format_options(self) -> SnapshotInitRequest:
        try:
            _validate_format_options(self.content_type, self.format_options)
        except ValueError as exc:
            raise ValueError(str(exc)) from exc
        return self


class SnapshotInitResponse(BaseModel):
    upload_id: UUID
    upload_url: str
    object_key: str
    expires_at: datetime
    required_headers: dict[str, str]


class SnapshotCompleteRequest(BaseModel):
    # The pattern is the load-bearing half. This value is not only stored: it becomes a path
    # segment in `snapshot_object_key(tenant, dataset, content_hash)`, and it is the only
    # segment of an object key that is not a UUID the lake parsed itself. A length bound alone
    # admits `../../<other-tenant>/…` padded to 64 characters — S3 treats that as a literal key,
    # but a path-style endpoint puts it in a URL path, and normalising proxies have made that a
    # real traversal before. Requiring hex removes the question rather than reasoning about it.
    content_hash: str = Field(..., min_length=64, max_length=128,
                              pattern=r"^[0-9a-fA-F]+$",
                              description="SHA-256 hex digest of uploaded bytes")
    actual_size_bytes: int = Field(..., ge=1)
    row_count: int | None = Field(default=None, ge=0)


class SnapshotResponse(BaseModel):
    id: UUID
    tenant_id: UUID
    dataset_id: UUID
    object_key: str
    content_hash: str
    size_bytes: int
    row_count: int | None
    content_type: str
    format_options: dict[str, Any]
    derived_from_snapshot_id: UUID | None
    derived_via_job_id: UUID | None
    created_at: datetime


class SnapshotDownloadResponse(BaseModel):
    snapshot_id: UUID
    object_key: str
    download_url: str
    expires_at: datetime
    content_type: str


# =============================================================================
# routes
# =============================================================================

@router.post(
    "/datasets/{dataset_id}/snapshots/init",
    response_model=SnapshotInitResponse,
    status_code=status.HTTP_201_CREATED,
)
async def init_snapshot_upload(
    dataset_id: UUID,
    body: SnapshotInitRequest,
    caller: Annotated[CallerIdentity, Depends(require_bearer)],
    tenant_id: Annotated[UUID, Depends(require_tenant)],
    actor_id: Annotated[UUID | None, Depends(require_actor)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> SnapshotInitResponse:
    """
    Mint a presigned upload URL for a new snapshot. The client uploads
    bytes directly to Garage; on success they call ``/complete`` to
    register the snapshot.

    The lake records that the dataset_id exists for the tenant but stores
    no logical metadata (name, description, schema) — those live in Aran.
    """
    await repo.upsert_dataset(
        session, tenant_id=tenant_id, dataset_id=dataset_id,
    )

    upload_id = uuid4()
    staging_key = upload_staging_key(tenant_id, dataset_id, upload_id)

    store = ObjectStore(settings)
    presigned = await store.presign_put(
        object_key=staging_key,
        content_type=body.content_type,
        content_length=body.declared_size_bytes,
    )

    await repo.create_snapshot_upload(
        session,
        upload_id=upload_id,
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        object_key=staging_key,
        declared_size_bytes=body.declared_size_bytes,
        content_type=body.content_type,
        format_options=body.format_options,
        expires_at=datetime.now(UTC) + timedelta(seconds=settings.presign_ttl_seconds),
        created_by=actor_id,
    )
    # Committed HERE, not by get_session's teardown. FastAPI runs a yield-dependency's exit
    # code after the response has been sent (fastapi/routing.py awaits the response inside the
    # dependency exit stacks), so the client held this upload_id before the row behind it was
    # durable. Aran uploads the bytes and calls /complete straight away; under a busy event loop
    # that call arrived before the commit and found nothing — "Upload … not found in catalog
    # database", three times in one e2e run, with no tenant mismatch. An id handed to a client
    # must exist before the client can quote it back.
    await session.commit()

    return SnapshotInitResponse(
        upload_id=upload_id,
        upload_url=presigned.url,
        object_key=presigned.object_key,
        expires_at=presigned.expires_at,
        required_headers=presigned.headers,
    )


@router.post(
    "/snapshots/uploads/{upload_id}/complete",
    response_model=SnapshotResponse,
    status_code=status.HTTP_201_CREATED,
)
async def complete_snapshot_upload(
    upload_id: UUID,
    body: SnapshotCompleteRequest,
    caller: Annotated[CallerIdentity, Depends(require_bearer)],
    tenant_id: Annotated[UUID, Depends(require_tenant)],
    actor_id: Annotated[UUID | None, Depends(require_actor)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> SnapshotResponse:
    """
    Confirm a presigned upload completed and register it as a snapshot.

    The claimed ``content_hash`` is verified against the staged bytes (up to
    ``hash_verify_max_bytes``); a mismatch is a 409 and registers nothing.

    If a snapshot with the same *verified* content_hash already exists for the
    dataset (deduplication), we delete the staged upload and return the
    existing snapshot — no error.
    """
    upload = await repo.get_snapshot_upload(
        session, tenant_id=tenant_id, upload_id=upload_id,
    )
    if upload is None:
        log.warning("upload not found in catalog for tenant", upload_id=upload_id, tenant_id=tenant_id)
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Upload {upload_id} not found in catalog database",
        )

    if not is_tenant_owned_key(upload.object_key, tenant_id):
        log.warning("staging key does not match tenant", upload_id=upload_id, tenant_id=tenant_id, object_key=upload.object_key)
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="staging key does not match tenant",
        )

    store = ObjectStore(settings)

    head = await store.head(upload.object_key)
    if head is None:
        log.warning("staged object not found in S3 storage", upload_id=upload_id, object_key=upload.object_key)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="staged object not found in object storage; upload may have failed",
        )

    actual_in_storage = int(head.get("ContentLength", -1))  # type: ignore[arg-type]
    if actual_in_storage != body.actual_size_bytes:
        log.warning("size mismatch", upload_id=upload_id, client_size=body.actual_size_bytes, storage_size=actual_in_storage)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"size mismatch: client reports {body.actual_size_bytes}, "
                f"storage shows {actual_in_storage}"
            ),
        )

    # Verify the claimed hash against the bytes BEFORE the dedup lookup below.
    # The claim is the dedup key *and* the object key, so an unverified one is
    # not a bookkeeping detail: a wrong claim that collides with an existing
    # snapshot's hash used to return that other snapshot with a 201 and delete
    # the bytes just uploaded — the caller was told their upload had registered,
    # and then read someone else's rows. Size alone cannot catch that.
    if actual_in_storage <= settings.hash_verify_max_bytes:
        computed_hash, _ = await store.sha256_of_object(object_key=upload.object_key)
        if computed_hash.lower() != body.content_hash.lower():
            log.warning(
                "content hash mismatch", upload_id=str(upload_id),
                claimed_hash=body.content_hash, computed_hash=computed_hash,
            )
            # The staged object and its upload row both stay: the caller can
            # retry /complete with the hash their bytes actually have.
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"content hash mismatch: client claims {body.content_hash}, "
                    f"stored bytes hash to {computed_hash}"
                ),
            )
    else:
        # Hashing gigabytes inline would cost more than the check is worth here,
        # so the size check stands alone — but never silently.
        log.warning(
            "staged object exceeds hash-verification ceiling; registered on size alone",
            upload_id=str(upload_id), size_bytes=actual_in_storage,
            ceiling_bytes=settings.hash_verify_max_bytes,
            content_hash=body.content_hash,
        )

    existing = await repo.find_snapshot_by_hash(
        session, tenant_id=tenant_id,
        dataset_id=upload.dataset_id, content_hash=body.content_hash,
    )
    if existing is not None:
        # Delete the DB row first so it is committed with the session; then attempt
        # S3 cleanup.  If S3 deletion fails the staging object is orphaned (swept later)
        # but the catalog stays consistent — no orphaned upload row.
        await repo.delete_snapshot_upload(session, upload)
        try:
            await store.delete_object(object_key=upload.object_key)
        except Exception:
            log.warning(
                "staging object cleanup failed during dedup; will be swept on next gc run",
                upload_id=str(upload.id), object_key=upload.object_key,
            )
        await session.commit()   # same rule as init: durable before the caller reads it back
        return _to_response(existing)

    final_key = snapshot_object_key(tenant_id, upload.dataset_id, body.content_hash)
    await store.copy_object(source_key=upload.object_key, dest_key=final_key)
    # Delete staging object; a transient S3 failure must not abort the snapshot registration.
    try:
        await store.delete_object(object_key=upload.object_key)
    except Exception:
        log.warning(
            "staging object cleanup failed after copy; will be swept on next gc run",
            upload_id=str(upload.id), object_key=upload.object_key,
        )

    snapshot = await repo.create_snapshot(
        session,
        tenant_id=tenant_id,
        dataset_id=upload.dataset_id,
        object_key=final_key,
        content_hash=body.content_hash,
        size_bytes=body.actual_size_bytes,
        row_count=body.row_count,
        content_type=upload.content_type,
        format_options=upload.format_options,
        created_by=actor_id,
        created_by_caller=caller.caller_id,
    )

    await repo.delete_snapshot_upload(session, upload)
    # Aran reads /download the moment this returns — the snapshot row has
    # to be committed before its id is in the response, for the same reason as in init.
    await session.commit()
    return _to_response(snapshot)


@router.get(
    "/snapshots/{snapshot_id}/download",
    response_model=SnapshotDownloadResponse,
    dependencies=[Depends(require_bearer)],
)
async def download_snapshot(
    snapshot_id: UUID,
    tenant_id: Annotated[UUID, Depends(require_tenant)],
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> SnapshotDownloadResponse:
    """
    Mint a short-lived presigned GET URL for the snapshot's raw bytes.

    Use cases:
      * A consuming service needs the raw parquet/CSV (export to a
        third-party engine, archive, regulatory ship-out).
      * A FREYJA worker wants to stream columns directly via pyarrow rather
        than going through ``/api/query``.
      * Any "give me the file" workflow that shouldn't go through SQL.

    TTL is controlled by ``ARAN_LAKE_DOWNLOAD_PRESIGN_TTL_SECONDS`` and
    defaults to 5 minutes — shorter than the upload TTL because download
    URLs directly expose data.
    """
    snapshot = await repo.get_snapshot(
        session, tenant_id=tenant_id, snapshot_id=snapshot_id,
    )
    if snapshot is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="snapshot not found")

    if not is_tenant_owned_key(snapshot.object_key, tenant_id):
        # Defense-in-depth: should never happen, but if a row's object_key
        # is somehow under a different tenant prefix, refuse.
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="snapshot object_key does not match tenant prefix",
        )

    store = ObjectStore(settings)
    presigned = await store.presign_get(object_key=snapshot.object_key)
    return SnapshotDownloadResponse(
        snapshot_id=snapshot.id,
        object_key=snapshot.object_key,
        download_url=presigned.url,
        expires_at=presigned.expires_at,
        content_type=snapshot.content_type,
    )


# --- helpers ---

def _to_response(s) -> SnapshotResponse:
    return SnapshotResponse(
        id=s.id,
        tenant_id=s.tenant_id,
        dataset_id=s.dataset_id,
        object_key=s.object_key,
        content_hash=s.content_hash,
        size_bytes=s.size_bytes,
        row_count=s.row_count,
        content_type=s.content_type,
        format_options=s.format_options or {},
        derived_from_snapshot_id=s.derived_from_snapshot_id,
        derived_via_job_id=s.derived_via_job_id,
        created_at=s.created_at,
    )

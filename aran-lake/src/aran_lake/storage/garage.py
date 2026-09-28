"""
S3-compatible object-storage adapter.

The lake speaks the S3 protocol exclusively — Garage in dev, the same
protocol in production (AWS S3, MinIO, Ceph, or a real Garage cluster).

We use boto3 (synchronous) wrapped in ``asyncio.to_thread`` rather than
aiobotocore: the surface we use is tiny, the calls are short, and the
extra dependency isn't worth the trouble.

Key design points:

* Per-tenant key prefix. Every object lives under ``{tenant_id}/...``.
  This is the *implementation* of the tenancy decision recorded in ADR 003.
  No caller of this module should ever assemble a key without including
  the tenant prefix.

* Presigned URLs for browser-direct uploads/downloads. The lake never
  proxies *user-facing* bytes — it mints permission and lets Garage handle
  the I/O.

v0.2 changes vs v0.1
--------------------

* ``profile_blob_key`` is removed (Profile is no longer a lake concern).
* New ``upload_file`` helper for streaming a local file → S3 (used by the
  materialize handler which writes COPY output to /tmp first).
"""
from __future__ import annotations

import asyncio
import hashlib
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Final
from uuid import UUID

import boto3
import structlog
from botocore.client import Config
from botocore.exceptions import ClientError

from aran_lake.config import Settings

log = structlog.get_logger(__name__)

# How we name objects under the tenant prefix. Keep this in sync with
# everything that reads keys back — the convention is part of the contract.
_SNAPSHOTS_PREFIX: Final = "snapshots"
_STAGING_PREFIX: Final = "_staging"


@dataclass(frozen=True, slots=True)
class PresignedUpload:
    """A short-lived URL the client uses to PUT bytes directly to Garage."""
    url: str
    object_key: str
    expires_at: datetime
    headers: dict[str, str]


@dataclass(frozen=True, slots=True)
class PresignedDownload:
    url: str
    object_key: str
    expires_at: datetime


def snapshot_object_key(tenant_id: UUID, dataset_id: UUID, content_hash: str) -> str:
    """
    Deterministic key for a snapshot. Content-hashed so identical uploads
    deduplicate naturally at the storage layer.

    Note: extension is always ``.parquet`` for materialized snapshots, but
    direct uploads of CSV/JSON/Excel keep their original bytes under the
    same .parquet suffix — the suffix is convention only; the authoritative
    format is ``snapshot.content_type``. We don't vary the extension by
    format because the content_type is the source of truth and the key
    only needs to be unique + content-addressed.
    """
    return f"{tenant_id}/{_SNAPSHOTS_PREFIX}/{dataset_id}/{content_hash}.bin"


def upload_staging_key(tenant_id: UUID, dataset_id: UUID, upload_id: UUID) -> str:
    """
    Staging key for an in-flight upload. Renamed (server-side copy) to the
    content-addressed final key on completion. We don't know the hash until
    the bytes arrive, so we can't write directly to the final key.
    """
    return f"{tenant_id}/{_STAGING_PREFIX}/{dataset_id}/{upload_id}.bin"


def is_tenant_owned_key(key: str, tenant_id: UUID) -> bool:
    """
    Defense-in-depth: never act on a key that doesn't start with the
    expected tenant prefix. Returns False if the API somehow passed a key
    that wasn't minted for this tenant — a bug, but one we'd rather catch
    than ignore.
    """
    return key.startswith(f"{tenant_id}/")


class ObjectStore:
    """
    Wraps the boto3 S3 client with the lake's tenancy and naming
    conventions. One instance is created per process and shared across
    requests — boto3 clients are thread-safe.
    """

    def __init__(self, settings: Settings) -> None:
        self._bucket = settings.s3_bucket
        self._presign_ttl = settings.presign_ttl_seconds
        self._download_presign_ttl = settings.download_presign_ttl_seconds
        self._multipart_threshold = settings.multipart_threshold_bytes

        # path-style addressing is what Garage and MinIO use; signature v4
        # is the AWS default since 2014. Force both.
        self._client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint_url,
            region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key.get_secret_value(),
            aws_secret_access_key=settings.s3_secret_key.get_secret_value(),
            config=Config(
                signature_version="s3v4",
                s3={"addressing_style": "path"},
                retries={"max_attempts": 3, "mode": "standard"},
            ),
        )

    # --- introspection ---

    async def head(self, object_key: str) -> dict[str, object] | None:
        """Return head metadata or None if the object doesn't exist."""
        def _head() -> dict[str, object] | None:
            try:
                return self._client.head_object(Bucket=self._bucket, Key=object_key)
            except ClientError as e:
                code = e.response.get("Error", {}).get("Code")
                if code in {"404", "NoSuchKey", "NotFound"}:
                    return None
                raise
        return await asyncio.to_thread(_head)

    # --- presigned URLs ---

    async def presign_put(
        self, *, object_key: str, content_type: str, content_length: int | None = None,
    ) -> PresignedUpload:
        """
        Mint a presigned PUT URL. The client uploads directly to Garage
        using this URL — bytes never traverse the lake.
        """
        params: dict[str, object] = {
            "Bucket": self._bucket,
            "Key": object_key,
            "ContentType": content_type,
        }
        if content_length is not None:
            params["ContentLength"] = content_length

        def _sign() -> str:
            return self._client.generate_presigned_url(
                "put_object",
                Params=params,
                ExpiresIn=self._presign_ttl,
                HttpMethod="PUT",
            )

        url = await asyncio.to_thread(_sign)
        expires = datetime.now(UTC) + timedelta(seconds=self._presign_ttl)
        return PresignedUpload(
            url=url,
            object_key=object_key,
            expires_at=expires,
            headers={"Content-Type": content_type},
        )

    async def presign_get(
        self, *, object_key: str, ttl_seconds: int | None = None,
    ) -> PresignedDownload:
        """
        Mint a presigned GET URL for the given object. TTL defaults to the
        configured ``download_presign_ttl_seconds`` (shorter than the
        upload default — a download URL is more sensitive because it
        directly exposes data).
        """
        effective_ttl = ttl_seconds if ttl_seconds is not None else self._download_presign_ttl

        def _sign() -> str:
            return self._client.generate_presigned_url(
                "get_object",
                Params={"Bucket": self._bucket, "Key": object_key},
                ExpiresIn=effective_ttl,
            )

        url = await asyncio.to_thread(_sign)
        expires = datetime.now(UTC) + timedelta(seconds=effective_ttl)
        return PresignedDownload(url=url, object_key=object_key, expires_at=expires)

    # --- mutation ---

    async def copy_object(self, *, source_key: str, dest_key: str) -> None:
        """Server-side copy. No bytes through the lake."""
        def _copy() -> None:
            self._client.copy_object(
                Bucket=self._bucket,
                Key=dest_key,
                CopySource={"Bucket": self._bucket, "Key": source_key},
            )
        await asyncio.to_thread(_copy)

    async def delete_object(self, *, object_key: str) -> None:
        def _delete() -> None:
            self._client.delete_object(Bucket=self._bucket, Key=object_key)
        await asyncio.to_thread(_delete)

    async def upload_file(
        self, *, local_path: str, object_key: str, content_type: str,
    ) -> None:
        """
        Stream a local file to S3 with multipart for large files. Used by
        the materialize handler which writes DuckDB COPY output to /tmp
        first, then ships it to S3.

        Bytes briefly traverse the worker container, which is acceptable
        for internal materialize operations (the worker is the originator
        of the materialization request, not a user-facing path).
        """
        from boto3.s3.transfer import TransferConfig
        cfg = TransferConfig(
            multipart_threshold=self._multipart_threshold,
            multipart_chunksize=self._multipart_threshold,
            use_threads=True,
        )
        def _upload() -> None:
            self._client.upload_file(
                Filename=local_path,
                Bucket=self._bucket,
                Key=object_key,
                ExtraArgs={"ContentType": content_type},
                Config=cfg,
            )
        await asyncio.to_thread(_upload)

    async def sha256_of_object(
        self, *, object_key: str, chunk_size: int = 1024 * 1024,
    ) -> tuple[str, int]:
        """
        Stream an object through SHA-256 and return ``(hexdigest, size_bytes)``.

        Streams rather than reading the body whole because the caller
        (``/complete``) hashes whole uploads: the digest must not cost the
        object's size in resident memory. The S3 ETag is deliberately not used
        for this — it is MD5, and for a multipart upload it is not even the MD5
        of the object.
        """
        def _hash() -> tuple[str, int]:
            resp = self._client.get_object(Bucket=self._bucket, Key=object_key)
            h = hashlib.sha256()
            size = 0
            body = resp["Body"]
            try:
                for chunk in body.iter_chunks(chunk_size=chunk_size):
                    h.update(chunk)
                    size += len(chunk)
            finally:
                body.close()
            return h.hexdigest(), size
        return await asyncio.to_thread(_hash)


def sha256_of_file(path: str, *, chunk_size: int = 1024 * 1024) -> tuple[str, int]:
    """
    Stream a local file through SHA-256 and return ``(hexdigest, size_bytes)``.
    Used by the materialize handler to hash the COPY output before upload.
    """
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size

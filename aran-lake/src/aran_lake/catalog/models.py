"""
SQLAlchemy 2.x ORM models for the lake's catalog.

The model layer is deliberately thin: rows in / rows out, with the
business logic living in :mod:`aran_lake.catalog.repositories`. Models
use SQLAlchemy 2.x's typed declarative API.

All tenant-owned tables carry ``tenant_id`` and every query in the
repositories layer is required to filter on it. The lake does not own
the tenant authority — the tenant id is supplied by the caller (validated
via the bearer token before reaching the repository layer).

v0.2 changes vs v0.1
--------------------

* ``Profile`` table is **removed**. Profiling is no longer a lake concern;
  it lives in Aran now (run-via-SQL, store the output blob wherever Aran
  wants it — e.g. attached to the wrapper row in Aran's catalog).

* ``Dataset.name`` and ``Dataset.description`` are **removed**. The lake's
  ``Dataset`` becomes a bare grouping row — ``id``, ``tenant_id``,
  ``created_at`` — keyed by whatever opaque UUID Aran supplies. Aran owns
  what a dataset *means*; the lake owns where its bytes live.

* ``Snapshot`` and ``SnapshotUpload`` gain a ``format_options`` JSONB
  column for per-format read hints (CSV delimiter, JSON record path, etc).
"""
from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKeyConstraint,
    Index,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    """Shared declarative base. The single source of truth for ``Base.metadata``."""


class Dataset(Base):
    """
    Bare snapshot-grouping row. The lake records that a dataset_id exists
    for a tenant, with no other metadata — name, description, schema,
    semantics all live in Aran's catalog.

    The row is created lazily on first snapshot upload for that dataset_id.

    The key is ``(tenant_id, id)``, not ``id`` alone: dataset_ids are supplied
    by Aran, so two tenants may legitimately present the same one, and each must
    get its own row. The composite key also lets the child tables carry a
    composite FK, which makes a cross-tenant reference unrepresentable.
    """
    __tablename__ = "dataset"

    id:          Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), primary_key=True)
    tenant_id:   Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), primary_key=True)
    created_at:  Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )

    snapshots: Mapped[list[Snapshot]] = relationship(
        back_populates="dataset", cascade="all, delete-orphan", passive_deletes=True
    )

    __table_args__ = (
        Index("idx_dataset_tenant", "tenant_id"),
    )


class Snapshot(Base):
    """
    An immutable bytes-on-disk artifact for a dataset.

    Content-addressed by ``content_hash`` (SHA-256 of the raw bytes), so
    re-uploading the same bytes deduplicates naturally. Format identified
    by ``content_type``; format-specific read hints in ``format_options``.
    """
    __tablename__ = "snapshot"

    id:            Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), primary_key=True)
    tenant_id:     Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), nullable=False)
    dataset_id:    Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), nullable=False)
    object_key:    Mapped[str] = mapped_column(String(1024), nullable=False)
    content_hash:  Mapped[str] = mapped_column(String(128), nullable=False)
    size_bytes:    Mapped[int] = mapped_column(BigInteger, nullable=False)
    row_count:     Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    content_type:  Mapped[str] = mapped_column(
        String(128), nullable=False, default="application/vnd.apache.parquet"
    )
    format_options: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, default=dict,
        doc="Per-format read hints (CSV delimiter/header, JSON record path, etc)",
    )
    # Provenance for derived snapshots — null for direct uploads, populated
    # when the snapshot is a job's output. Lets Aran follow the lineage without
    # re-reading the job table. No live job writes `derived_from_snapshot_id`
    # since `snapshot.materialize` was deleted (2026-08-27) — an import has no
    # source snapshot — so it is history-only until a snapshot-to-snapshot job
    # returns. `derived_via_job_id` below is still written, by imports.
    derived_from_snapshot_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), nullable=True,
        doc="Source snapshot id if this row was derived from another; else NULL",
    )
    derived_via_job_id: Mapped[UUID | None] = mapped_column(
        PgUUID(as_uuid=True), nullable=True,
        doc="Job id that produced this row, if any",
    )
    created_at:    Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    created_by:    Mapped[UUID | None] = mapped_column(PgUUID(as_uuid=True), nullable=True)
    created_by_caller: Mapped[str | None] = mapped_column(
        String(64), nullable=True,
        doc="caller-id (e.g. 'aran', 'freyja') from the bearer auth, for audit",
    )

    dataset: Mapped[Dataset] = relationship(back_populates="snapshots")

    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "dataset_id"], ["dataset.tenant_id", "dataset.id"],
            name="fk_snapshot_dataset", ondelete="CASCADE",
        ),
        UniqueConstraint(
            "tenant_id", "dataset_id", "content_hash",
            name="uq_snapshot_tenant_dataset_hash",
        ),
        Index("idx_snapshot_tenant_dataset", "tenant_id", "dataset_id"),
    )


class SnapshotUpload(Base):
    """
    In-flight upload state. Lives between /snapshots/init and /complete.
    """
    __tablename__ = "snapshot_upload"

    id:                  Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), primary_key=True)
    tenant_id:           Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), nullable=False)
    dataset_id:          Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), nullable=False)
    object_key:          Mapped[str] = mapped_column(String(1024), nullable=False)
    declared_size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    content_type:        Mapped[str] = mapped_column(
        String(128), nullable=False, default="application/vnd.apache.parquet"
    )
    format_options:      Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, default=dict,
    )
    multipart_upload_id: Mapped[str | None] = mapped_column(String(256), nullable=True)
    created_at:          Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    expires_at:          Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    created_by:          Mapped[UUID | None] = mapped_column(PgUUID(as_uuid=True), nullable=True)

    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "dataset_id"], ["dataset.tenant_id", "dataset.id"],
            name="fk_snapshot_upload_dataset", ondelete="CASCADE",
        ),
        Index("idx_snapshot_upload_tenant", "tenant_id"),
        Index("idx_snapshot_upload_expires", "expires_at"),
    )


class Job(Base):
    """
    Generic async-work record. Type-tagged via ``job_type``, payload/result
    are JSONB so each handler can evolve independently.

    Status machine:  ``PENDING → RUNNING → {SUCCEEDED | FAILED}``

    Job types:
      * ``import.from_postgres`` — stream a remote SELECT into a parquet
        snapshot.

    ``snapshot.materialize`` was the other v0.2 type; it was deleted on
    2026-08-27 (see ``jobs/handlers.py``). A PENDING row of that type in an
    older catalog now lands FAILED with "no handler registered for job_type",
    which is the runner's ordinary unknown-type path.
    """
    __tablename__ = "job"

    id:            Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), primary_key=True)
    tenant_id:     Mapped[UUID] = mapped_column(PgUUID(as_uuid=True), nullable=False)
    job_type:      Mapped[str] = mapped_column(String(64), nullable=False)
    status:        Mapped[str] = mapped_column(String(16), nullable=False, default="PENDING")
    payload:       Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    result:        Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at:    Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    started_at:    Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    finished_at:   Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    created_by:    Mapped[UUID | None] = mapped_column(PgUUID(as_uuid=True), nullable=True)
    created_by_caller: Mapped[str | None] = mapped_column(
        String(64), nullable=True,
        doc="caller-id from bearer auth (e.g. 'aran'), for audit",
    )

    __table_args__ = (
        CheckConstraint(
            "status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')",
            name="ck_job_status",
        ),
        Index("idx_job_tenant_status", "tenant_id", "status"),
        Index(
            "idx_job_claim",
            "status", "created_at",
            postgresql_where="status = 'PENDING'",
        ),
    )

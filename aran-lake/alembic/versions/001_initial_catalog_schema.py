"""initial catalog schema (v0.2)

The catalog is the lake's metadata layer. It mirrors the *naming* of Aran's
own tenant/dataset model but is intentionally a separate database — the
lake is operationally independent of Aran, and at-rest reuse would couple
their schemas in ways that hurt evolution.

This is the v0.2 schema, baked in as a single migration. v0.1 callers who
have existing data should drop their database and rerun this migration —
the v0.2 release notes are explicit that v0.1 → v0.2 is a clean cut, not
an in-place migration. (Confirmed in design discussion: no preserved data.)

Tables:

  dataset           — bare grouping row for snapshots under one
                      Aran-supplied dataset_id; **no name, no description**
                      (those live in Aran's catalog, not the lake's).
  snapshot          — an immutable bytes-on-disk artifact, content-addressed.
                      Format-flexible (parquet | csv | json | xlsx | ...).
                      Carries derived_from_snapshot_id / derived_via_job_id
                      for materialize lineage, and created_by_caller for audit.
  snapshot_upload   — in-flight uploads (presigned URLs minted, not yet
                      completed). The "upload exists in Garage but the
                      catalog hasn't seen the completion" state is a real
                      hazard; we model it explicitly so cleanup is possible.
  job               — async work units (materialize, imports).
                      Caller-id recorded for audit.

The v0.1 ``profile`` table is **gone**. Profile is no longer a lake concern;
Aran runs profiling via the generic ``/api/query`` endpoint and stores the
result wherever it stores wrapper-side metadata.

Tenancy: every row carries tenant_id and we never read without filtering
on it. There's no FK to a tenant table because the lake doesn't own the
tenant authority — Aran does. The tenant id is just an opaque UUID here.

Revision ID: 001
Revises:
Create Date: 2026-05-24
"""
from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # ---------- dataset ----------
    # Bare grouping row. No name, no description — Aran owns the logical
    # metadata; the lake only records that snapshots exist under this
    # dataset_id for this tenant.
    op.create_table(
        "dataset",
        sa.Column("id",          postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id",   postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_at",  sa.TIMESTAMP(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
    )
    op.create_index("idx_dataset_tenant", "dataset", ["tenant_id"])

    # ---------- snapshot ----------
    op.create_table(
        "snapshot",
        sa.Column("id",           postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id",    postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("dataset_id",   postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("dataset.id", ondelete="CASCADE"), nullable=False),
        sa.Column("object_key",   sa.String(length=1024), nullable=False),
        sa.Column("content_hash", sa.String(length=128), nullable=False),
        sa.Column("size_bytes",   sa.BigInteger(), nullable=False),
        sa.Column("row_count",    sa.BigInteger(), nullable=True),
        sa.Column("content_type", sa.String(length=128),
                  nullable=False, server_default="application/vnd.apache.parquet"),
        sa.Column("format_options", postgresql.JSONB(),
                  nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("derived_from_snapshot_id", postgresql.UUID(as_uuid=True),
                  nullable=True),
        sa.Column("derived_via_job_id", postgresql.UUID(as_uuid=True),
                  nullable=True),
        sa.Column("created_at",   sa.TIMESTAMP(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
        sa.Column("created_by",   postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("created_by_caller", sa.String(length=64), nullable=True),
        sa.UniqueConstraint("tenant_id", "dataset_id", "content_hash",
                            name="uq_snapshot_tenant_dataset_hash"),
    )
    op.create_index("idx_snapshot_tenant_dataset", "snapshot", ["tenant_id", "dataset_id"])
    # Lineage lookup: "show me everything materialized from snapshot X".
    op.create_index(
        "idx_snapshot_derived_from", "snapshot", ["derived_from_snapshot_id"],
        postgresql_where=sa.text("derived_from_snapshot_id IS NOT NULL"),
    )

    # ---------- snapshot_upload ----------
    op.create_table(
        "snapshot_upload",
        sa.Column("id",          postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id",   postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("dataset_id",  postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("dataset.id", ondelete="CASCADE"), nullable=False),
        sa.Column("object_key",  sa.String(length=1024), nullable=False),
        sa.Column("declared_size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("content_type",  sa.String(length=128),
                  nullable=False, server_default="application/vnd.apache.parquet"),
        sa.Column("format_options", postgresql.JSONB(),
                  nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("multipart_upload_id", sa.String(length=256), nullable=True),
        sa.Column("created_at",  sa.TIMESTAMP(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
        sa.Column("expires_at",  sa.TIMESTAMP(timezone=True), nullable=False),
        sa.Column("created_by",  postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.create_index("idx_snapshot_upload_tenant", "snapshot_upload", ["tenant_id"])
    op.create_index("idx_snapshot_upload_expires", "snapshot_upload", ["expires_at"])

    # ---------- job ----------
    # Async work units. Type-tagged; payload+result are JSONB so we can evolve
    # individual job types without migration. State machine:
    #   PENDING -> RUNNING -> {SUCCEEDED | FAILED}
    op.create_table(
        "job",
        sa.Column("id",            postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("tenant_id",     postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("job_type",      sa.String(length=64), nullable=False),
        sa.Column("status",        sa.String(length=16),
                  nullable=False, server_default="PENDING"),
        sa.Column("payload",       postgresql.JSONB(), nullable=False),
        sa.Column("result",        postgresql.JSONB(), nullable=True),
        sa.Column("error_message", sa.Text(),          nullable=True),
        sa.Column("created_at",    sa.TIMESTAMP(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
        sa.Column("started_at",    sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column("finished_at",   sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column("created_by",    postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("created_by_caller", sa.String(length=64), nullable=True),
        sa.CheckConstraint(
            "status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')",
            name="ck_job_status",
        ),
    )
    op.create_index("idx_job_tenant_status", "job", ["tenant_id", "status"])
    op.create_index(
        "idx_job_claim",
        "job",
        ["status", "created_at"],
        postgresql_where=sa.text("status = 'PENDING'"),
    )


def downgrade() -> None:
    op.drop_index("idx_job_claim", table_name="job")
    op.drop_index("idx_job_tenant_status", table_name="job")
    op.drop_table("job")

    op.drop_index("idx_snapshot_upload_expires", table_name="snapshot_upload")
    op.drop_index("idx_snapshot_upload_tenant", table_name="snapshot_upload")
    op.drop_table("snapshot_upload")

    op.drop_index("idx_snapshot_derived_from", table_name="snapshot")
    op.drop_index("idx_snapshot_tenant_dataset", table_name="snapshot")
    op.drop_table("snapshot")

    op.drop_index("idx_dataset_tenant", table_name="dataset")
    op.drop_table("dataset")

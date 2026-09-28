"""tenant-scoped dataset key

The v0.2 schema keyed ``dataset`` on ``id`` alone. dataset_ids are supplied by
Aran rather than minted by the lake, so two tenants can legitimately present the
same one — and the single-column key made the second tenant's row a primary-key
collision instead of a separate dataset. ``upsert_dataset`` looks the row up
scoped by ``(tenant_id, dataset_id)``, so it would sail past the existence check
and then fail on insert with ``duplicate key value violates unique constraint
"dataset_pkey"``.

This migration moves ``dataset`` onto the composite key ``(tenant_id, id)`` and
re-points the two child FKs at it as composite ``(tenant_id, dataset_id)``
references. Beyond fixing the collision, the composite FK makes a cross-tenant
reference unrepresentable: a snapshot can no longer point at a dataset row
belonging to another tenant, which the single-column FK happily allowed.

Revision ID: 002
Revises: 001
"""
from collections.abc import Sequence

from alembic import op

revision: str = "002"
down_revision: str | None = "001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # The child FKs reference dataset(id); they must go before the key swaps.
    op.drop_constraint("snapshot_dataset_id_fkey", "snapshot", type_="foreignkey")
    op.drop_constraint(
        "snapshot_upload_dataset_id_fkey", "snapshot_upload", type_="foreignkey",
    )

    op.drop_constraint("dataset_pkey", "dataset", type_="primary")
    op.create_primary_key("dataset_pkey", "dataset", ["tenant_id", "id"])

    op.create_foreign_key(
        "fk_snapshot_dataset", "snapshot", "dataset",
        ["tenant_id", "dataset_id"], ["tenant_id", "id"], ondelete="CASCADE",
    )
    op.create_foreign_key(
        "fk_snapshot_upload_dataset", "snapshot_upload", "dataset",
        ["tenant_id", "dataset_id"], ["tenant_id", "id"], ondelete="CASCADE",
    )


def downgrade() -> None:
    # Downgrading is only sound while no two tenants share a dataset_id — the
    # single-column key cannot represent that, so such rows would have to be
    # dropped by hand first.
    op.drop_constraint("fk_snapshot_dataset", "snapshot", type_="foreignkey")
    op.drop_constraint(
        "fk_snapshot_upload_dataset", "snapshot_upload", type_="foreignkey",
    )

    op.drop_constraint("dataset_pkey", "dataset", type_="primary")
    op.create_primary_key("dataset_pkey", "dataset", ["id"])

    op.create_foreign_key(
        "snapshot_dataset_id_fkey", "snapshot", "dataset",
        ["dataset_id"], ["id"], ondelete="CASCADE",
    )
    op.create_foreign_key(
        "snapshot_upload_dataset_id_fkey", "snapshot_upload", "dataset",
        ["dataset_id"], ["id"], ondelete="CASCADE",
    )

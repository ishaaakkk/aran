"""
Example: execute a query, receive Arrow IPC, convert to pandas / polars.
Assumes a snapshot already exists (run upload_csv.py first).
"""
from __future__ import annotations

import sys

from aran_lake_client import LakeClient

if len(sys.argv) < 3:
    print("usage: python query_arrow.py <tenant_id> <snapshot_id> [sql]")
    sys.exit(1)

tenant_id, snapshot_id = sys.argv[1], sys.argv[2]
sql = sys.argv[3] if len(sys.argv) > 3 else "SELECT * FROM snapshot LIMIT 10"

with LakeClient(
    endpoint="http://localhost:8080",
    bearer_token="dev-shared-secret-change-in-prod",
    tenant_id=tenant_id,
) as client:
    table = client.query(snapshot_id, sql)
    print(f"Got {table.num_rows} rows, {table.num_columns} columns")
    print(f"Schema: {table.schema}")
    print("As pandas:")
    print(table.to_pandas())
    # Polars users:
    # import polars as pl; df = pl.from_arrow(table)

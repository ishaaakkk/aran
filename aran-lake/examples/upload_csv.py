"""
End-to-end example: upload a CSV directly (no parquet conversion) and
query it as CSV.

v0.2 lets the lake read CSV natively — you no longer need to convert to
parquet on the client side. Producing a cleaned/typed parquet artifact
from it is Aran's job, not the lake's: Aran's ``materialize-oql`` operator
does that. The lake's own materialize endpoint was deleted on 2026-08-27
(nothing called it, and it bypassed the ``/api/query`` safety screen).

Run from the examples/ directory after ``docker compose up`` is running:

    python upload_csv.py
"""
from __future__ import annotations

from pathlib import Path
from uuid import uuid4

from aran_lake_client import LakeClient

# --- Make a small CSV for demo purposes ---
CSV_TEXT = """id,name,region,score
1,Alice,EU,0.91
2,Bob,US,0.83
3,Charlie,APAC,0.76
4,Daniela,EU,0.95
5,Eitan,US,0.88
"""

TENANT_ID = "00000000-0000-0000-0000-000000000001"
DATASET_ID = str(uuid4())  # in real Aran this comes from the dataset record


def main() -> None:
    # 1) Write the CSV locally
    csv_path = Path("/tmp/aran-lake-demo.csv")
    csv_path.write_text(CSV_TEXT, encoding="utf-8")
    print(f"Wrote {csv_path} ({csv_path.stat().st_size} bytes)")

    with LakeClient(
        endpoint="http://localhost:8080",
        bearer_token="dev-shared-secret-change-in-prod",
        tenant_id=TENANT_ID,
    ) as client:
        # 2) Upload as CSV — no need to convert to parquet first.
        snap = client.upload_snapshot(
            dataset_id=DATASET_ID,
            local_path=csv_path,
            content_type="text/csv",
            format_options={"header": True, "delim": ","},
        )
        print(f"CSV snapshot: id={snap.id} hash={snap.content_hash[:12]}…")

        # 3) Query the CSV directly. Under the hood the lake uses
        #    DuckDB's read_csv_auto with the hints from format_options.
        result = client.query(
            snap.id,
            "SELECT region, count(*) AS n, avg(score) AS avg_score "
            "FROM snapshot GROUP BY region ORDER BY n DESC",
        )
        print("Query against CSV snapshot:")
        print(result.to_pandas())

        # 4) Casting is just SQL — the lake reads the CSV's text columns and
        #    DuckDB casts them per query. Persisting a typed parquet version
        #    of this is Aran's materialize-oql, not a lake call.
        typed = client.query(
            snap.id,
            "SELECT CAST(id AS INTEGER) AS id, name, region, "
            "CAST(score AS DOUBLE) AS score FROM snapshot",
        )
        print("Typed projection:")
        print(typed.to_pandas())


if __name__ == "__main__":
    main()

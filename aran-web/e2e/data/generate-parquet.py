#!/usr/bin/env python
"""Regenerate venues.parquet.

Committed as a binary so the fixtures never depend on the lake venv being
bootstrapped. Run from the repo root with the lake venv:

    aran-lake/.venv/bin/python aran-web/e2e/data/generate-parquet.py
"""
from pathlib import Path

import pandas as pd

HERE = Path(__file__).parent

pd.DataFrame(
    {
        "venue_code": ["V-01", "V-02", "V-03"],
        "venue_name": ["ISWC", "SIGMOD", "VLDB"],
        "rank": ["A", "A*", "A*"],
    }
).to_parquet(HERE / "venues.parquet", index=False)

print(f"wrote {HERE / 'venues.parquet'}")

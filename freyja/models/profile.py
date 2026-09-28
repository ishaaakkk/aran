"""
Profile data model.

The ``ProfileSet`` is FREYJA's typed output. It replaces the
DataFrame-of-CSV-rows + ``.pkl`` pair the old code emitted.

Why dataclasses, not Pydantic models, for the internal type:

* The library API doesn't need request/response validation — the
  external API (``schemas.py``) does, and that's where Pydantic lives.
* Dataclasses are zero-cost; the profiler builds tens of these per
  call and they don't need to do field coercion every time.
* Conversion to Pydantic / JSON / Arrow is a one-liner at the API
  boundary.

The ``metrics`` field is a free-form dict because the metric set
itself is a research artifact — adding a metric should not require a
schema migration on this type. The downstream consumers
(distance computation, alignment) iterate over ``METRICS`` to know
what's in it.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True, slots=True)
class ColumnProfile:
    """One column's full profile."""

    column_name: str
    column_index: int
    source_type: str           # original SQL type, e.g. "VARCHAR"
    metrics: dict[str, Any]    # all metric values, keyed by metric name

    def to_dict(self) -> dict[str, Any]:
        return {
            "column_name": self.column_name,
            "column_index": self.column_index,
            "source_type": self.source_type,
            "metrics": self.metrics,
        }


@dataclass(frozen=True, slots=True)
class ProfileSet:
    """All column profiles for a single snapshot."""

    source_label: str                       # e.g. snapshot UUID or filename
    columns: list[ColumnProfile]
    row_count: int = 0
    errors: dict[str, str] = field(default_factory=dict)  # column_name → error

    def to_dict(self) -> dict[str, Any]:
        return {
            "source_label": self.source_label,
            "row_count": self.row_count,
            "columns": [c.to_dict() for c in self.columns],
            "errors": dict(self.errors),
        }

    def column(self, name: str) -> ColumnProfile | None:
        for c in self.columns:
            if c.column_name == name:
                return c
        return None

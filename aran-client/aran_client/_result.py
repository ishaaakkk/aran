"""The result of an OQL query, before it commits to a DataFrame library.

Why there is a result object at all, rather than ``query()`` returning a
DataFrame directly: the export endpoint reports the true row count in an
``X-Aran-Row-Count`` header, and that number is the one piece of information a
DataFrame cannot carry back if the transfer was truncated. Holding the bytes and
the header together lets :meth:`QueryResult.to_pandas` check them against each
other. ``query()`` still returns a DataFrame — this type is what it converts.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from .errors import AranError

if TYPE_CHECKING:  # pragma: no cover - typing only
    import pyarrow as pa

__all__ = ["QueryResult"]

_MISSING_PANDAS = (
    "pandas is not installed. Install it with `pip install aran-client[pandas]`, "
    "or ask for polars with query(..., into='polars')."
)
_MISSING_POLARS = (
    "polars is not installed. Install it with `pip install aran-client[polars]`, "
    "or ask for pandas with query(..., into='pandas')."
)


class QueryResult:
    """Raw bytes of one query result, plus the metadata the server sent with them."""

    __slots__ = ("_data", "_format", "_reported_rows", "_table")

    def __init__(self, data: bytes, fmt: str, reported_rows: int | None = None):
        self._data = data
        self._format = fmt.upper()
        self._reported_rows = reported_rows
        self._table: pa.Table | None = None

    @property
    def raw(self) -> bytes:
        """The undecoded response body — parquet or CSV, as requested."""
        return self._data

    @property
    def format(self) -> str:
        return self._format

    @property
    def row_count(self) -> int:
        """Rows in the result.

        The server's reported count where it sent one, so this is answerable
        without decoding a large parquet file.
        """
        if self._reported_rows is not None:
            return self._reported_rows
        return self.arrow().num_rows

    def arrow(self) -> pa.Table:
        """Decode to a pyarrow Table. Cached — decoding twice is pure waste."""
        if self._table is None:
            self._table = self._decode()
        return self._table

    def _decode(self) -> pa.Table:
        import pyarrow as pa

        buffer = pa.BufferReader(self._data)
        if self._format == "PARQUET":
            import pyarrow.parquet as pq

            table = pq.read_table(buffer)
        elif self._format == "CSV":
            import pyarrow.csv as pacsv

            table = pacsv.read_csv(buffer)
        else:
            raise AranError(f"cannot decode format {self._format!r}")

        # A truncated transfer decodes cleanly into a short table, which would
        # otherwise reach the caller as a silently incomplete answer.
        if self._reported_rows is not None and table.num_rows != self._reported_rows:
            raise AranError(
                f"incomplete result: server reported {self._reported_rows} rows, "
                f"decoded {table.num_rows}"
            )
        return table

    def to_pandas(self) -> Any:
        try:
            import pandas  # noqa: F401
        except ImportError as exc:  # pragma: no cover - depends on env
            raise AranError(_MISSING_PANDAS) from exc
        return self.arrow().to_pandas()

    def to_polars(self) -> Any:
        try:
            import polars as pl
        except ImportError as exc:  # pragma: no cover - depends on env
            raise AranError(_MISSING_POLARS) from exc
        return pl.from_arrow(self.arrow())

    def into(self, frame: str) -> Any:
        """Convert to ``"pandas"``, ``"polars"`` or ``"arrow"``."""
        choice = frame.lower()
        if choice == "pandas":
            return self.to_pandas()
        if choice == "polars":
            return self.to_polars()
        if choice in ("arrow", "pyarrow"):
            return self.arrow()
        raise ValueError(f"into must be 'pandas', 'polars' or 'arrow', got {frame!r}")

    def __repr__(self) -> str:
        return f"<QueryResult {self._format} rows={self._reported_rows} bytes={len(self._data)}>"

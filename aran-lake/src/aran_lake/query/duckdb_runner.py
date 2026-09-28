"""
DuckDB query runner.

Each query gets a fresh DuckDB in-memory connection configured to read
from our S3-compatible store via the ``httpfs`` extension. We do *not*
share connections across requests — DuckDB connections are cheap to
create, expensive to share safely under concurrency, and each query has
its own resource budget (timeout, row limit).

The runner exposes:

* :func:`run_sql_against_snapshot` — the simple "query this snapshot"
  primitive. Accepts a SQL statement that may refer to ``snapshot`` as
  the view name. Returns a pyarrow Table.

* :func:`bind_snapshot_view` — sets up the format-aware ``snapshot`` view
  for a snapshot, dispatching on its ``content_type`` to use the right
  DuckDB reader (``read_parquet`` / ``read_csv_auto`` / ``read_json``
  / Excel). Applies ``format_options`` as reader parameters.

``/api/query`` is now the only path that executes caller SQL here, and
:func:`_screen_sql` is therefore the only screen — there is no longer a
second, screen-bypassing materializer beside it.

Why pyarrow Tables and not pandas DataFrames? Because Arrow is the
on-the-wire format we serialize to clients (Arrow IPC stream). Going
DuckDB → Arrow → IPC is a zero-copy path; DuckDB → pandas → Arrow
allocates twice and loses dtype precision.
"""
from __future__ import annotations

import asyncio
import re
from typing import Any

import duckdb
import pyarrow as pa
import structlog

from aran_lake.catalog.models import Snapshot
from aran_lake.config import Settings
from aran_lake.query import duckdb_session

log = structlog.get_logger(__name__)


# Forbidden patterns for the `/api/query` SQL screen.
#
# The first group is about statement *type*: nothing here may write, attach or
# reconfigure. The second group is about what a plain SELECT may reach, and it
# exists because "read-only" and "safe" are not the same property — every
# pattern below is read-only, and each one returns something the caller must
# not have:
#
#   * a reader or scanner (``read_csv``, ``postgres_scan``, …) reaches the
#     container's filesystem and the network. Caller SQL never needs one: the
#     snapshot arrives already bound as the view ``snapshot`` by
#     :func:`bind_snapshot_view`, which builds its own reader.
#   * ``duckdb_secrets()`` returns the S3 access key and secret that
#     :func:`_configure_connection` installs on every connection a few lines
#     below. That is the lake's credential to the whole bucket — every tenant's
#     objects, not just the caller's.
#   * a bare string literal in FROM position is a reader with no name in it.
#     DuckDB's replacement scan turns ``SELECT * FROM '/etc/passwd.csv'`` into a
#     ``read_csv``, so a screen that matches function names alone never sees it.
#
# This is cruder than the tokenizer Aran uses for the same job
# (``WrapperSqlValidator.findForbiddenConstruct``), and deliberately so: no
# caller-supplied SQL reaches this endpoint today — Aran is the lake's only
# client and does not forward user SQL here — so this is depth behind that
# topology rather than the load-bearing screen. If that ever changes, port the
# tokenizer; a regex cannot agree with DuckDB about where a literal ends.
_FORBIDDEN_PATTERNS = [
    re.compile(r"\b(ATTACH|DETACH)\b", re.IGNORECASE),
    re.compile(r"\b(COPY)\b", re.IGNORECASE),
    re.compile(r"\b(INSTALL|LOAD)\b", re.IGNORECASE),
    re.compile(r"\b(CREATE|DROP|ALTER|TRUNCATE)\b", re.IGNORECASE),
    re.compile(r"\b(INSERT|UPDATE|DELETE)\b", re.IGNORECASE),
    re.compile(r"\b(EXPORT|IMPORT)\b", re.IGNORECASE),
    re.compile(r"\b(PRAGMA)\b", re.IGNORECASE),
    re.compile(r"\b(read_\w*|\w*_scan|scan_\w*|duckdb_\w*|getenv|glob)\s*\(", re.IGNORECASE),
    # A double quote is deliberately absent: "snapshot" after FROM is a quoted
    # identifier, which is ordinary SQL, not a literal.
    re.compile(r"\b(FROM|JOIN)\s*('|\$|[Ee]'|[Uu]&')", re.IGNORECASE),
]


class QueryRejected(Exception):
    """Raised when a query fails the safety screen before execution."""


class UnsupportedFormat(Exception):
    """Raised when a snapshot's content_type doesn't map to a known reader."""


def _screen_sql(sql: str) -> None:
    """
    Pre-execution safety screen for the user-supplied SQL on /api/query.

    Two checks, and the order matters only for the error message.

    **Statement boundary.** The caller's SQL is executed inside
    :func:`run_sql_against_snapshot`'s ``SELECT * FROM (<sql>) AS _cap LIMIT n``
    wrapper, and a caller who closes that parenthesis themselves —
    ``anything) AS t; <second statement>`` — leaves a statement the wrapper
    never constrained. So a semicolon anywhere but the very end is refused
    outright. This check arrived here on 2026-08-27 from the deleted
    ``materialize_to_local_parquet``, which had it and ``/api/query`` did not:
    it was the one thing in the lake's materialize path worth keeping.

    It is deliberately crude — a semicolon inside a string literal is refused
    too. That is the safe direction, and it matches the regex screen below,
    which cannot agree with DuckDB about where a literal ends either.

    **Forbidden constructs.** Statement types that write/attach/reconfigure,
    and read-only constructs that reach outside the bound snapshot. See the
    ``_FORBIDDEN_PATTERNS`` comment for why each entry is there.
    """
    if ";" in sql.rstrip().rstrip(";"):
        raise QueryRejected("query rejected: SQL must be a single statement")

    for pattern in _FORBIDDEN_PATTERNS:
        if pattern.search(sql):
            raise QueryRejected(
                f"query rejected: forbidden statement type matched /{pattern.pattern}/"
            )


def _configure_connection(con: duckdb.DuckDBPyConnection, settings: Settings) -> None:
    """
    Configure a fresh DuckDB connection to read from our S3 endpoint.

    ``httpfs`` is NOT in the duckdb Python wheel: the image installs it at
    build time (aran-lake/Dockerfile) and DuckDB autoloads it from there; a
    bare environment autoinstalls it from extensions.duckdb.org on first read.
    The ``excel`` extension is loaded on demand (only when an Excel
    snapshot is being read) — see :func:`_ensure_excel_loaded`.
    """
    endpoint = settings.s3_endpoint_url
    endpoint_host = endpoint.removeprefix("http://").removeprefix("https://")
    use_ssl = endpoint.startswith("https://")
    url_style = "path"   # Garage / MinIO require path-style

    def _esc(v: str) -> str:
        return v.replace("'", "''")

    con.execute(
        f"""
        CREATE SECRET s3_secret (
            TYPE S3,
            KEY_ID '{_esc(settings.s3_access_key.get_secret_value())}',
            SECRET '{_esc(settings.s3_secret_key.get_secret_value())}',
            REGION '{_esc(settings.s3_region)}',
            ENDPOINT '{_esc(endpoint_host)}',
            URL_STYLE '{url_style}',
            USE_SSL {"true" if use_ssl else "false"}
        );
        """
    )


_excel_loaded: set[int] = set()


def _ensure_excel_loaded(con: duckdb.DuckDBPyConnection) -> None:
    """
    Install + load the DuckDB ``excel`` extension on demand. Only called
    when we actually need to read an .xlsx snapshot. We track per-connection
    by id() so we don't re-INSTALL on every call within the same connection.
    """
    if id(con) in _excel_loaded:
        return
    con.execute("INSTALL excel; LOAD excel;")
    _excel_loaded.add(id(con))


# ---------- format-aware view binding ----------

# DuckDB read function selection. The values are templates; placeholders
# get filled by _read_call_for_snapshot below.
_PARQUET_TYPES = {
    "application/vnd.apache.parquet",
    "application/x-parquet",
    "application/parquet",
}
_CSV_TYPES = {
    "text/csv",
    "application/csv",
    "text/tab-separated-values",
}
_JSON_TYPES = {
    "application/x-ndjson",
    "application/jsonl",
    "application/json",  # treated as NDJSON; explicit array-of-objects via format_options
}
_EXCEL_TYPES = {
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
}


def _quote_sql_literal(s: str) -> str:
    """Single-quote a string for inline embedding in SQL. Doubles inner quotes."""
    return "'" + s.replace("'", "''") + "'"


def _sql_value(v: Any) -> str:
    """Render a Python value as a DuckDB SQL literal for option values."""
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, str):
        return _quote_sql_literal(v)
    if v is None:
        return "NULL"
    raise ValueError(f"unsupported format_option value type: {type(v).__name__}")


# Reader-argument whitelists, one per format. A `format_options` key that
# isn't in the set for the snapshot's format is dropped rather than spliced
# into the reader call — see V39: `import.from_postgres` used to write
# `format_options={"lineage": "..."}` on every snapshot it produced, and
# because every entry was rendered as a named argument, that string became
# `read_parquet(uri, lineage = '...')` — an invalid parameter DuckDB refused,
# making every imported snapshot unreadable. `lineage` no longer lives in
# format_options (see handlers.py), but this whitelist is the structural
# guard: whatever wanders into format_options next, only a reader argument
# a `read_*` call actually accepts can reach the SQL.
_PARQUET_READER_OPTIONS = frozenset({
    "binary_as_string", "filename", "file_row_number", "hive_partitioning",
    "union_by_name", "compression",
})
_CSV_READER_OPTIONS = frozenset({
    "header", "delim", "sep", "quote", "escape", "encoding", "columns",
    "all_varchar", "dateformat", "timestampformat", "skip", "null_padding",
    "sample_size", "types", "names", "compression",
})
_JSON_READER_OPTIONS = frozenset({
    "format", "records", "columns", "auto_detect", "sample_size",
    "maximum_object_size", "compression", "dateformat", "timestampformat",
})
_EXCEL_READER_OPTIONS = frozenset({"sheet", "range", "header", "all_varchar"})


def _options_to_args(
    options: dict[str, Any], allowed: frozenset[str], *, snapshot_id: Any = None,
) -> str:
    """
    Render ``format_options`` as DuckDB function arguments, restricted to
    ``allowed`` — the known reader arguments for the snapshot's format.

    A key outside ``allowed`` is dropped with a WARN naming the key, never
    spliced into the reader call (V39). Keys with ``None`` values are
    skipped. Keys are quoted as identifiers (lowercased; DuckDB option names
    are case-insensitive).
    """
    parts: list[str] = []
    for k, v in options.items():
        if k.lower() not in allowed:
            log.warning(
                "format_options: dropping key not in the reader whitelist",
                key=k, snapshot_id=snapshot_id,
            )
            continue
        if v is None:
            continue
        parts.append(f"{k.lower()} = {_sql_value(v)}")
    return ", ".join(parts)


def _read_call_for_snapshot(snapshot: Snapshot, uri: str) -> str:
    """
    Build the DuckDB ``read_*('uri', opts...)`` call appropriate for the
    snapshot's content_type. Applies any ``format_options`` as named
    arguments.

    Raises :class:`UnsupportedFormat` if the content_type isn't recognized.
    """
    ct = (snapshot.content_type or "").lower().split(";", 1)[0].strip()
    opts = dict(snapshot.format_options or {})
    uri_lit = _quote_sql_literal(uri)

    if ct in _PARQUET_TYPES:
        args = _options_to_args(opts, _PARQUET_READER_OPTIONS, snapshot_id=snapshot.id)
        return f"read_parquet({uri_lit}{', ' + args if args else ''})"

    if ct in _CSV_TYPES:
        # read_csv_auto handles header sniffing; format_options can override.
        # Common keys: header (bool), delim (str), quote (str), escape (str),
        # encoding (str), columns (struct), all_varchar (bool).
        args = _options_to_args(opts, _CSV_READER_OPTIONS, snapshot_id=snapshot.id)
        return f"read_csv_auto({uri_lit}{', ' + args if args else ''})"

    if ct in _JSON_TYPES:
        # read_json with auto_detect; format_options can set format='array'
        # or 'newline_delimited', records, columns, etc.
        args = _options_to_args(opts, _JSON_READER_OPTIONS, snapshot_id=snapshot.id)
        return f"read_json({uri_lit}{', ' + args if args else ''})"

    if ct in _EXCEL_TYPES:
        # Excel is read via the `excel` extension. Common option: sheet.
        args = _options_to_args(opts, _EXCEL_READER_OPTIONS, snapshot_id=snapshot.id)
        return f"read_xlsx({uri_lit}{', ' + args if args else ''})"

    raise UnsupportedFormat(
        f"snapshot {snapshot.id} has unsupported content_type {snapshot.content_type!r}; "
        f"supported: parquet, csv, json/ndjson, xlsx"
    )


def needs_excel_extension(snapshot: Snapshot) -> bool:
    ct = (snapshot.content_type or "").lower().split(";", 1)[0].strip()
    return ct in _EXCEL_TYPES


def bind_snapshot_view(
    con: duckdb.DuckDBPyConnection,
    snapshot: Snapshot,
    *,
    bucket: str,
    view_name: str = "snapshot",
) -> None:
    """
    Bind the given snapshot to a view of name ``view_name`` on the
    connection. Synchronous (caller wraps in ``asyncio.to_thread`` if
    they're in async context).

    The view is created with ``CREATE OR REPLACE VIEW`` so re-binding
    works on the same connection (used by materialize, which may need
    multiple bindings).
    """
    if needs_excel_extension(snapshot):
        _ensure_excel_loaded(con)
    uri = f"s3://{bucket}/{snapshot.object_key}"
    read_call = _read_call_for_snapshot(snapshot, uri)
    con.execute(f"CREATE OR REPLACE VIEW {view_name} AS SELECT * FROM {read_call}")  # noqa: S608 — internal identifiers, not caller input


async def run_sql_against_snapshot(
    *,
    snapshot: Snapshot,
    sql: str,
    settings: Settings,
    row_limit: int | None = None,
) -> pa.Table:
    """
    Execute ``sql`` against ``snapshot``. The view ``snapshot`` is bound
    using format-aware dispatch before execution.

    ``row_limit`` (default: from settings) is applied as a LIMIT *outside*
    the user's SQL — the stricter of the two caps wins.
    """
    _screen_sql(sql)
    effective_limit = row_limit if row_limit is not None else settings.max_query_rows

    def _run() -> pa.Table:
        con = duckdb_session.connect()
        try:
            _configure_connection(con, settings)
            bind_snapshot_view(con, snapshot, bucket=settings.s3_bucket)
            wrapped = f"SELECT * FROM ({sql}) AS _cap LIMIT {effective_limit + 1}"  # noqa: S608 — running caller SQL is this endpoint's purpose; screened above
            tbl = con.execute(wrapped).fetch_arrow_table()
            if tbl.num_rows > effective_limit:
                tbl = tbl.slice(0, effective_limit)
                meta = dict(tbl.schema.metadata or {})
                meta[b"aran_lake_row_limit_hit"] = b"true"
                tbl = tbl.replace_schema_metadata(meta)
            return tbl
        finally:
            con.close()

    return await asyncio.to_thread(_run)


def arrow_table_to_ipc(table: pa.Table) -> bytes:
    """Serialize an Arrow Table to the Arrow IPC stream format."""
    sink = pa.BufferOutputStream()
    with pa.ipc.new_stream(sink, table.schema) as writer:
        writer.write_table(table)
    buf = sink.getvalue()
    return buf.to_pybytes()


def row_limit_hit(table: pa.Table) -> bool:
    """Did the query hit the row-cap?"""
    meta = table.schema.metadata or {}
    return meta.get(b"aran_lake_row_limit_hit") == b"true"

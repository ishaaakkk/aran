"""
ScanQueryExecutor — federated-source executor.

Runs the profiler's SQL suite on a local, in-process DuckDB connection
whose ``snapshot`` view is bound to a *federated scan expression*
(``iceberg_scan('…')``, an httpfs ``read_parquet('s3://…')``, or a table
of an attached Postgres or MySQL catalog,
``aran_attach_<repo>."schema"."table"``) instead of a lake snapshot. This is
what makes federated profiling follow the exact mechanism federated
queries use: the same scan Aran's query resolver would emit, the same
wrapper-as-view binding, the same SQL suite — only aggregate results ever
cross the wire, and nothing is staged to the lake.

Aran supplies two things it alone can build (they need the repository's
stored credentials and, for Iceberg, a catalog round-trip for the current
metadata location):

* ``scan_expression`` — the scan, exactly as the query engine's snapshot
  resolver emits it;
* ``setup_sql`` — the ordered statements the connection needs first:
  ``INSTALL``/``LOAD`` for scanner extensions, ``CREATE SECRET`` for
  data-plane credentials, and for a relational source a read-only
  ``ATTACH`` (plus, for MySQL, one setting), built by the same single
  authorities the query path uses (``FederatedProfilingBinding`` on the
  Java side). ``MySqlEngine``/``PostgresEngine.federatedScan`` are the one
  spelling. Postgres used to arrive as ``postgres_scan('<dsn>', …)``; that
  shape is no longer admitted, because an expression becomes a view body
  and DuckDB hands every view body back through its catalogue.

Credential hygiene: the setup statements can carry keys and passwords
(``CREATE SECRET``) and, for an attached catalog, the ``ATTACH`` that names
the secret; the scan expression itself carries no credential. Nothing is
logged here or stored beyond this executor's lifetime. DuckDB's own error
text is another matter — a failed connection can echo connection details,
password included, on its first line — so no DuckDB text reaches an
exception unredacted:

* a failing ``CREATE SECRET`` surfaces only its index and keyword;
* every other failure (another setup statement, the view binding, a
  profiling query) surfaces its first line through ``_redact``, which
  replaces the expression's own conninfo, in either spelling, outright and
  then masks any libpq ``password=`` value, quoted or bare;
* no ``ScanQueryError`` chains DuckDB's exception (``from None``), so a
  ``logger.exception`` further up cannot print the raw text from the cause.

Aran relays this text as a 502 body into its logs and job rows, which is
why it matters here and not only on the Java side.

Trust boundary: the sidecar's callers are bearer-authenticated, but this
executor still opens a *local* DuckDB with no lake in front of it, so it
fail-closes on anything but the shapes Aran legitimately sends — see
``_check_scan_expression``/``_check_setup_sql``. Local filesystem access
is additionally disabled after setup (the one DuckDB knob that separates
"read remote data over httpfs/scanners" from "read this host's files"),
so a hostile expression that slipped the screen still cannot read local
paths. ``allow_local_files=True`` skips both the remote-URL requirement
and that lockdown and exists for tests only — production wiring
(``profile_operator``) never sets it.

Concurrency: the profiler fans out per-column queries across threads and
``base.py`` says a DuckDB-backed executor owes the caller its own
serialization — a single connection guarded by one lock. Cheap relative
to the remote reads themselves.
"""
from __future__ import annotations

import re
import threading
from collections.abc import Mapping
from typing import Any

import duckdb

from freyja.executors import duckdb_session
from freyja.executors.binding import (
    reject_colliding_columns,
    resolve_wrapper_sql,
    substitute_wrapper,
)


class FederatedScanRejected(ValueError):
    """
    Raised when the scan expression or a setup statement fails the
    fail-closed shape check. The caller's mistake (or an attempt), never
    the remote source's — ``profile_api`` maps it to 400.
    """


class ScanQueryError(RuntimeError):
    """Raised when a setup statement or a profiling query fails on the scan."""


# The table functions Aran's query resolver can emit for a federated
# dataset — and nothing else. `query()` / `read_csv` over local paths /
# arbitrary SQL in table position are exactly what WrapperSqlValidator
# forbids on the Java side; this is the same fence at this boundary.
_ALLOWED_SCAN_FUNCTIONS = (
    "iceberg_scan",
    "read_parquet",
    "read_csv_auto",
    "read_json_auto",
)

_SCAN_SHAPE = re.compile(
    r"^\s*(" + "|".join(_ALLOWED_SCAN_FUNCTIONS) + r")\s*\(",
    re.IGNORECASE,
)

# read_* functions can address the local filesystem; a federated read is by
# definition remote, so their first argument must be a remote-URL literal.
_REMOTE_READ_SHAPE = re.compile(
    r"^\s*read_\w+\s*\(\s*'(s3://|s3a://|https?://)",
    re.IGNORECASE,
)

# Setup statements are extension loads and S3/HTTP secret bindings — the
# two things a connection needs before a httpfs/iceberg read — plus the
# secret and ATTACH a relational read needs (and MySQL's one setting),
# matched exactly below. Nothing else: a CREATE SECRET of another TYPE, or
# a mysql/postgres one with a SOCKET, an ssl_key or a passfile path, is
# refused, not waved through by the generic form.
_SETUP_SHAPE = re.compile(
    r"^\s*(INSTALL\s+\w+|LOAD\s+\w+"
    r"|CREATE\s+(OR\s+REPLACE\s+)?SECRET\s+\w+\s*\(\s*TYPE\s+(S3|HTTP)\s*,.*\))\s*$",
    re.IGNORECASE | re.DOTALL,
)

# The attached-catalog read, character for character as MySqlEngine and
# PostgresEngine emit it (Java's FederatedScanExpressionTest pins the same
# strings). An ATTACH runs BEFORE the local-filesystem lockdown, so a loose
# ATTACH shape would be a local-file read (`ATTACH '/some/file.duckdb'`):
# hence the empty path, the relational TYPE, READ_ONLY and a secret of that
# same type that this same setup created — never anything looser. HOST and
# DATABASE admit exactly what RelationalDatabaseRepository admits; USER and
# PASSWORD are SQL literals ('' escapes, no backslash escapes — DuckDB's
# own literal grammar), which is why a ';' inside them is harmless and
# allowed here only. The extension quotes each field for its client itself,
# so no connection-string grammar is left for a value to inject into.
_IDENT = r"[A-Za-z_][A-Za-z0-9_]*"
_SQL_LITERAL = r"'(?:[^']|'')*'"
_ATTACH_ALIAS = r"aran_attach_[a-z0-9_]+"
_RELATIONAL_TYPE = r"(?P<type>mysql|postgres)"
_MYSQL_SETTING = "SET mysql_aggregate_pushdown_enabled=false"
_RELATIONAL_SECRET_SHAPE = re.compile(
    r"CREATE OR REPLACE TEMPORARY SECRET (?P<name>" + _IDENT + r") \(TYPE " + _RELATIONAL_TYPE
    + r", HOST '[A-Za-z0-9._:\[\]-]+', PORT \d{1,5}, DATABASE '[A-Za-z0-9._-]+'"
    r", USER " + _SQL_LITERAL + r", PASSWORD " + _SQL_LITERAL + r"\)"
)
_RELATIONAL_ATTACH_SHAPE = re.compile(
    r"ATTACH IF NOT EXISTS '' AS (?P<alias>" + _ATTACH_ALIAS + r")"
    r" \(TYPE " + _RELATIONAL_TYPE + r", SECRET (?P<secret>" + _IDENT + r"), READ_ONLY\)"
)
_ATTACHED_TABLE_SHAPE = re.compile(
    r"\s*(?P<alias>" + _ATTACH_ALIAS + r')\."(?:[^"]|"")+"\."(?:[^"]|"")+"\s*'
)


def _check_scan_expression(
    expression: str, allow_local_files: bool, attached: set[str],
) -> None:
    if ";" in expression:
        raise FederatedScanRejected(
            "scan expression must be a single expression (no semicolons)"
        )
    table = _ATTACHED_TABLE_SHAPE.fullmatch(expression)
    if table is not None:
        if table.group("alias") not in attached:
            raise FederatedScanRejected(
                "scan expression names a catalog the setup did not attach"
            )
        return
    if not _SCAN_SHAPE.match(expression):
        raise FederatedScanRejected(
            "scan expression must be a call to one of: "
            + ", ".join(_ALLOWED_SCAN_FUNCTIONS)
            + ", or a table of a catalog the setup attached"
        )
    if (
        not allow_local_files
        and expression.lstrip().lower().startswith("read_")
        and not _REMOTE_READ_SHAPE.match(expression)
    ):
        raise FederatedScanRejected(
            "a read_* scan expression must read a remote (s3:// or "
            "http(s)://) location"
        )


def _check_setup_sql(setup_sql: list[str]) -> set[str]:
    """
    Screen every setup statement; return the aliases its ATTACHes create,
    which are the only catalogs a scan expression may then name.
    """
    # secret name -> its TYPE, so an ATTACH can only use a secret of its own
    # type that this setup created before it.
    secrets: dict[str, str] = {}
    attached: set[str] = set()
    for i, statement in enumerate(setup_sql):
        secret = _RELATIONAL_SECRET_SHAPE.fullmatch(statement)
        if secret is not None:
            secrets[secret.group("name")] = secret.group("type")
            continue
        attach = _RELATIONAL_ATTACH_SHAPE.fullmatch(statement)
        if attach is not None and secrets.get(attach.group("secret")) == attach.group("type"):
            attached.add(attach.group("alias"))
            continue
        if statement == _MYSQL_SETTING:
            continue
        if ";" in statement or not _SETUP_SHAPE.match(statement):
            # The statement text itself is deliberately NOT in the message:
            # a CREATE SECRET carries credentials.
            raise FederatedScanRejected(
                f"setup statement {i} is not an INSTALL, LOAD, CREATE SECRET "
                "(S3/HTTP) statement or the MySQL/Postgres secret, setting and "
                "ATTACH Aran emits"
            )
    return attached


def _first_line(e: Exception) -> str:
    """DuckDB appends the offending SQL after a newline; keep the verdict only."""
    return str(e).splitlines()[0] if str(e) else type(e).__name__


# libpq keyword=value: a value is bare up to whitespace, or single-quoted with
# backslash escapes (\' and \\) — how PostgresEngine.dsn writes one — and, in
# the SQL literal an error can quote back, with every quote doubled. It must know
# all three: `password=\S+` stops at a quoted value's first space and leaves the
# rest of the password in the message. The same pattern as Aran's
# Credentials.scrub, so the two sides cannot disagree about what a password is.
_CREDENTIAL_TOKEN = re.compile(
    r"(?i)\b(password|passwd|pwd)(\s*=\s*)"
    r"(?:''(?:\\''|\\[^']|[^'\\])*(?:'')?"  # SQL-doubled: ''p w\''d''
    r"|'(?:\\.|[^'\\])*'?"                   # libpq-quoted: 'p w\'d'
    r"|\S+)"                                 # bare: pw
)

# postgres_scan's first argument, a SQL literal ('' escapes a quote).
_POSTGRES_SCAN_CONNINFO = re.compile(
    r"^\s*postgres_scan\s*\(\s*'((?:[^']|'')*)'", re.IGNORECASE,
)

_DSN_REDACTED = "<dsn redacted>"


def _conninfo_spellings(scan_expression: str) -> tuple[str, ...]:
    """
    The expression's postgres conninfo as DuckDB echoes it (quotes undoubled)
    and as a binder error can echo the expression itself (quotes doubled) —
    the second is a spelling no keyword=value scrub recognizes.
    """
    m = _POSTGRES_SCAN_CONNINFO.match(scan_expression)
    if m is None or not m.group(1):
        return ()
    literal = m.group(1)
    return tuple(dict.fromkeys((literal, literal.replace("''", "'"))))


def _redact(message: str, conninfos: tuple[str, ...] = ()) -> str:
    # Longest first, so a shorter spelling cannot pre-empt a longer one it overlaps.
    for conninfo in sorted(conninfos, key=len, reverse=True):
        message = message.replace(conninfo, _DSN_REDACTED)
    return _CREDENTIAL_TOKEN.sub(lambda m: m.group(1) + m.group(2) + "***", message)


def _is_secret_statement(statement: str) -> bool:
    upper = statement.lstrip().upper()
    return upper.startswith("CREATE") and "SECRET" in upper


def _apply_local_lockdown(conn: duckdb.DuckDBPyConnection) -> None:
    """
    Disable local filesystem access and lock the configuration so composed
    SQL cannot re-enable it. Module-level so the test suite can prove the
    knob actually restricts on the pinned duckdb — this is a security
    control whose behaviour is engine-defined, not ours (the same
    discipline as the Java side's ``DuckDbLockdownTest``).
    """
    conn.execute("SET disabled_filesystems='LocalFileSystem'")
    conn.execute("SET lock_configuration=true")


class ScanQueryExecutor:
    """
    Implements ``QueryExecutor`` over one federated scan expression.

    Construction is where the remote source is first touched (extension
    loads, secret binding, and the ``snapshot`` view binding, which may
    read remote metadata) — a wrong credential or unreachable source
    fails here, as ``ScanQueryError``, before any metrics SQL is
    composed.
    """

    def __init__(
        self,
        scan_expression: str,
        *,
        setup_sql: list[str] | None = None,
        wrapper_sql: str | None = None,
        allow_local_files: bool = False,
    ) -> None:
        setup_sql = list(setup_sql or [])
        # All rejections before any resource is allocated, so a refused
        # request never leaks a connection.
        attached = _check_setup_sql(setup_sql)
        _check_scan_expression(scan_expression, allow_local_files, attached)
        self._resolved_wrapper_sql = resolve_wrapper_sql(wrapper_sql)
        self._conninfos = _conninfo_spellings(scan_expression)

        self._lock = threading.Lock()
        self._schema_cache: list[tuple[str, str]] | None = None
        self._conn = duckdb_session.connect()  # in-memory, private to this executor, UTC-pinned
        try:
            for i, statement in enumerate(setup_sql):
                try:
                    self._conn.execute(statement)
                except Exception as e:
                    keyword = statement.split(None, 1)[0].upper() if statement.split() else "?"
                    if _is_secret_statement(statement):
                        # Even the first line can quote the offending token of
                        # a statement that holds a password — and no cause
                        # chain, for the same reason.
                        raise ScanQueryError(
                            f"federated setup statement {i} ({keyword}) failed"
                        ) from None
                    raise ScanQueryError(
                        f"federated setup statement {i} ({keyword}) failed: "
                        f"{self._redacted(e)}"
                    ) from None
            if not allow_local_files:
                # After setup — INSTALL writes the extension to disk and
                # LOAD reads it back — but before any caller-shaped SQL runs.
                _apply_local_lockdown(self._conn)
            try:
                self._conn.execute(
                    "CREATE VIEW snapshot AS SELECT * FROM " + scan_expression  # noqa: S608 — binding the screened scan IS this executor's purpose; _check_scan_expression above
                )
            except Exception as e:
                # The postgres scanner's refusal names the DSN, password and all.
                raise ScanQueryError(
                    f"binding the federated scan failed: {self._redacted(e)}"
                ) from None
        except BaseException:
            self._conn.close()
            raise

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def __enter__(self) -> ScanQueryExecutor:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # -- public API ---------------------------------------------------

    def fetch_row(self, sql: str) -> Mapping[str, Any]:
        table = self._query_arrow(substitute_wrapper(sql, self._resolved_wrapper_sql))
        if table.num_rows == 0:
            return {}
        # Same native-scalar contract as LakeQueryExecutor.fetch_row.
        return {name: col[0] for name, col in table.to_pydict().items()}

    def fetch_table(self, sql: str) -> list[Mapping[str, Any]]:
        table = self._query_arrow(substitute_wrapper(sql, self._resolved_wrapper_sql))
        return table.to_pylist()

    def fetch_columns(self) -> list[tuple[str, str]]:
        # DESCRIBE over the resolved wrapper (its output columns are what
        # get profiled), or over the bound view. Built directly — never
        # through substitute_wrapper, whose docstring says why.
        if self._schema_cache is not None:
            return self._schema_cache
        describe_sql = (
            f"DESCRIBE ({self._resolved_wrapper_sql})"
            if self._resolved_wrapper_sql is not None
            else "DESCRIBE snapshot"
        )
        rows = self._query_arrow(describe_sql).to_pylist()
        columns = [(r["column_name"], r["column_type"]) for r in rows]
        if self._resolved_wrapper_sql is not None:
            reject_colliding_columns(columns)
        self._schema_cache = columns
        return self._schema_cache

    # -- internals ----------------------------------------------------

    def _redacted(self, e: Exception) -> str:
        return _redact(_first_line(e), self._conninfos)

    def _query_arrow(self, sql: str):
        with self._lock:
            try:
                return self._conn.execute(sql).fetch_arrow_table()
            except Exception as e:
                # A query reconnects through the scan, so it can fail the same way.
                raise ScanQueryError(
                    f"profiling query failed on federated scan: {self._redacted(e)}"
                ) from None

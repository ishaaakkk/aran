"""
ScanQueryExecutor — federated profiling over a live scan binding.

These tests run the REAL profiling suite against a REAL DuckDB scan of a
real parquet file — ``read_parquet('<local file>')`` standing in for the
remote scans (``iceberg_scan``/``postgres_scan``) that cannot run offline,
via the test-only ``allow_local_files=True``. What the local stand-in
proves is exactly what transfers: the profiler's composed SQL, the
wrapper-as-view substitution, the DESCRIBE-driven column selection and the
thread-safe fan-out all execute over a *scan-bound* ``snapshot`` relation
rather than a lake view. What it cannot prove — a real remote scan — is
register-gated (V54).
"""
from __future__ import annotations

import duckdb
import pytest
from freyja.executors.binding import WrapperSqlRejected
from freyja.executors.scan import (
    FederatedScanRejected,
    ScanQueryError,
    ScanQueryExecutor,
    _apply_local_lockdown,
    _check_scan_expression,
    _check_setup_sql,
    _conninfo_spellings,
    _redact,
)
from freyja.profiling import metrics
from freyja.profiling.profiler import ProfileOptions, profile_snapshot


@pytest.fixture
def parquet_file(tmp_path):
    path = tmp_path / "people.parquet"
    con = duckdb.connect()
    con.execute(
        "COPY (SELECT * FROM (VALUES "
        "('alice', 'BCN', 34), ('bob', 'BCN', 28), ('carol', 'PAR', 41), "
        "('dave', NULL, 28), ('erin', 'BCN', 55)"
        ") AS t(name, city, age)) TO '" + str(path) + "' (FORMAT PARQUET)"
    )
    con.close()
    return str(path)


def _executor(parquet_file: str, **kwargs) -> ScanQueryExecutor:
    return ScanQueryExecutor(
        f"read_parquet('{parquet_file}')",
        allow_local_files=True,
        **kwargs,
    )


# ---- the load-bearing proof: the suite itself runs over the scan ---- #

def test_profile_snapshot_runs_the_full_suite_over_the_scan(parquet_file):
    with _executor(parquet_file) as ex:
        result = profile_snapshot(
            ex, ProfileOptions(varchar_only=False, source_label="scan"),
        )
    assert result.row_count == 5
    by_name = {c.column_name: c for c in result.columns}
    assert set(by_name) == {"name", "city", "age"}
    assert by_name["name"].metrics["cardinality"] == 5
    assert by_name["name"].metrics["uniqueness"] == 1.0
    assert by_name["city"].metrics["cardinality"] == 2
    assert result.errors == {}


def test_chunked_scalar_metrics_are_the_values_the_inline_builder_produced(
    monkeypatch, tmp_path,
):
    """
    S13-1's semantic guard. The scalar suite is now a CTE chain, split
    across statements above a byte budget; the values below are the ones
    the pre-rewrite builder (one statement, ``_clean_str`` re-inlined per
    aggregate) computed over these exact rows — NULL, empty string,
    embedded newline, repeated inner spaces, mixed case and all.
    """
    path = tmp_path / "gnarly.parquet"
    con = duckdb.connect()
    con.execute(
        "COPY (SELECT * FROM (VALUES "
        "('Alice Smith', '  padded  '), "
        "('bob   jones', ''), "
        "('ALICE SMITH', 'line' || chr(10) || 'break'), "
        "('carol', 'MiXeD Case'), "
        "(NULL, 'x')"
        ") AS t(name, messy)) TO '" + str(path) + "' (FORMAT PARQUET)"
    )
    con.close()

    # Small enough that these two short-named columns land in separate
    # statements: the values must not depend on the chunk they fell in.
    monkeypatch.setattr(metrics, "SCALAR_STATEMENT_BUDGET_BYTES", 1000)

    with _executor(str(path)) as ex:
        recorder = _RecordingExecutor(ex)
        result = profile_snapshot(
            recorder, ProfileOptions(varchar_only=False, source_label="scan"),
        )

    assert len([s for s in recorder.statements if "AS row_count" in s]) == 2
    assert result.row_count == 5

    name = result.column("name").metrics
    assert name["cardinality"] == 3          # case-folded: two ALICE SMITHs collapse
    assert name["incompleteness"] == 0.2     # one NULL of five
    assert name["first_word"] == "alice smith"
    assert name["last_word"] == "carol"
    assert name["len_max_word"] == 5
    assert name["len_min_word"] == 0         # 'bob   jones' splits to empty tokens
    assert name["len_avg_word"] == 4.25
    assert name["number_words"] == 9
    assert name["words_cnt_max"] == 4
    assert name["words_cnt_avg"] == 1.8

    messy = result.column("messy").metrics
    assert messy["cardinality"] == 5
    assert messy["first_word"] == ""         # the empty cell, after trimming
    assert messy["last_word"] == "x"
    assert messy["len_max_word"] == 9        # 'linebreak', chr(10) stripped
    assert messy["words_cnt_avg"] == 1.0


class _RecordingExecutor:
    """Pass-through QueryExecutor that keeps the SQL it was handed."""

    def __init__(self, inner) -> None:
        self._inner = inner
        self.statements: list[str] = []

    def fetch_row(self, sql: str):
        self.statements.append(sql)
        return self._inner.fetch_row(sql)

    def fetch_table(self, sql: str):
        self.statements.append(sql)
        return self._inner.fetch_table(sql)

    def fetch_columns(self):
        return self._inner.fetch_columns()


def test_wrapper_sql_binds_as_a_view_over_the_scan(parquet_file):
    wrapper = (
        "SELECT name, UPPER(city) AS city_code FROM ${SNAPSHOT} "
        "WHERE age > 30"
    )
    with _executor(parquet_file, wrapper_sql=wrapper) as ex:
        assert [c for c, _t in ex.fetch_columns()] == ["name", "city_code"]
        result = profile_snapshot(
            ex, ProfileOptions(varchar_only=False, source_label="scan"),
        )
    # alice, carol, erin pass the wrapper's filter; the profiled relation
    # is the wrapper's OUTPUT, not the raw scan.
    assert result.row_count == 3
    assert {c.column_name for c in result.columns} == {"name", "city_code"}


def test_concurrent_fanout_is_serialized_by_the_executor(parquet_file):
    # base.py: a DuckDB-backed executor owes the caller its own
    # serialization — the profiler fans per-column queries across threads.
    with _executor(parquet_file) as ex:
        result = profile_snapshot(
            ex,
            ProfileOptions(varchar_only=False, max_concurrent_column_queries=4,
                           source_label="scan"),
        )
    assert result.errors == {}
    assert len(result.columns) == 3


# ---- fail-closed shape checks ---- #

def test_expression_must_be_an_allowlisted_table_function():
    with pytest.raises(FederatedScanRejected):
        ScanQueryExecutor("(SELECT * FROM read_parquet('s3://b/k'))")
    with pytest.raises(FederatedScanRejected):
        ScanQueryExecutor("query('SELECT 1')")


def test_expression_semicolon_is_rejected():
    with pytest.raises(FederatedScanRejected):
        ScanQueryExecutor("read_parquet('s3://b/k'); SELECT 1")


def test_local_read_is_rejected_without_the_test_only_flag(tmp_path):
    # The LFI fence: a read_* expression must address a remote URL. The
    # message never echoes the expression.
    with pytest.raises(FederatedScanRejected) as e:
        ScanQueryExecutor("read_parquet('/etc/passwd')")
    assert "/etc/passwd" not in str(e.value)


def test_setup_statements_are_limited_to_install_load_create_secret(parquet_file):
    for bad in (
        "COPY snapshot TO '/tmp/exfil.parquet'",
        "SET enable_external_access=false",
        "INSTALL httpfs; SELECT 1",
    ):
        with pytest.raises(FederatedScanRejected) as e:
            _executor(parquet_file, setup_sql=[bad])
        # A CREATE SECRET carries credentials, so a rejected statement's
        # TEXT must never appear in the error.
        assert "exfil" not in str(e.value)
        assert "SELECT 1" not in str(e.value)


# ---- the MySQL attached-catalog shape: exactly what MySqlEngine emits ---- #
#
# Java's FederatedScanExpressionTest pins these same strings. DuckDB's mysql
# extension has no scan function, so the setup ATTACHes the database — and
# an ATTACH runs before the local-filesystem lockdown, which is why every
# loosening below must be refused, not only the ones that look dangerous.

_ALIAS = "aran_attach_0f1e2d3c4b5a69788796a5b4c3d2e1f0"
_SECRET = "aran_mysql_0f1e2d3c4b5a69788796a5b4c3d2e1f0"
_MYSQL_SECRET = (
    f"CREATE OR REPLACE TEMPORARY SECRET {_SECRET} (TYPE mysql, HOST 'db.example.org', "
    "PORT 5432, DATABASE 'research', USER 'aran', PASSWORD 's3cret')"
)
_MYSQL_ATTACH = (
    f"ATTACH IF NOT EXISTS '' AS {_ALIAS} (TYPE mysql, SECRET {_SECRET}, READ_ONLY)"
)
_MYSQL_SETUP = [
    "INSTALL mysql",
    "LOAD mysql",
    "SET mysql_aggregate_pushdown_enabled=false",
    _MYSQL_SECRET,
    _MYSQL_ATTACH,
]
_MYSQL_EXPRESSION = f'{_ALIAS}."research"."papers"'


def test_the_mysql_binding_aran_emits_passes_the_screen():
    attached = _check_setup_sql(_MYSQL_SETUP)
    assert attached == {_ALIAS}
    _check_scan_expression(_MYSQL_EXPRESSION, False, attached)


def test_a_mysql_password_is_one_literal_whatever_it_holds():
    # '' is the only escape in a DuckDB literal, so ; and " and \ are data.
    secret = _MYSQL_SECRET.replace("'s3cret'", "'p w''d\\x\"q; host=evil'")
    assert _check_setup_sql([*_MYSQL_SETUP[:3], secret, _MYSQL_ATTACH]) == {_ALIAS}


@pytest.mark.parametrize(
    "bad",
    [
        # a secret that is not the exact shape: persistent, extra options,
        # an unscreened host or database, a literal that breaks out
        _MYSQL_SECRET.replace("TEMPORARY", "PERSISTENT"),
        _MYSQL_SECRET.replace("PASSWORD 's3cret')", "PASSWORD 's3cret', SOCKET '/tmp/mysql.sock')"),
        _MYSQL_SECRET.replace("PASSWORD 's3cret')", "PASSWORD 's3cret', SSL_KEY '/etc/shadow')"),
        _MYSQL_SECRET.replace("'db.example.org'", "'db host'"),
        _MYSQL_SECRET.replace("'research'", "'research?allowLoadLocalInfile=true'"),
        _MYSQL_SECRET.replace("'s3cret'", "'x', SOCKET '/tmp/s'"),
        # a secret of any other type
        f"CREATE OR REPLACE SECRET {_SECRET} (TYPE postgres, HOST 'h', PASSWORD 'p')",
        # any other setting
        "SET mysql_aggregate_pushdown_enabled=true",
        "SET mysql_aggregate_pushdown_enabled=false; SET enable_external_access=true",
    ],
)
def test_a_loosened_mysql_setup_statement_is_rejected(bad):
    with pytest.raises(FederatedScanRejected) as e:
        _check_setup_sql(["INSTALL mysql", "LOAD mysql", bad, _MYSQL_ATTACH])
    assert "s3cret" not in str(e.value)


@pytest.mark.parametrize(
    "bad_attach",
    [
        # a local file: the ATTACH would read it before the lockdown lands
        _MYSQL_ATTACH.replace("''", "'/var/lib/aran/other.duckdb'"),
        _MYSQL_ATTACH.replace("TYPE mysql", "TYPE duckdb"),
        _MYSQL_ATTACH.replace("TYPE mysql", "TYPE sqlite"),
        _MYSQL_ATTACH.replace("TYPE mysql", "TYPE postgres"),
        _MYSQL_ATTACH.replace(", READ_ONLY)", ")"),
        _MYSQL_ATTACH.replace(_ALIAS, "src"),
        # a secret the setup never created
        _MYSQL_ATTACH.replace(f"SECRET {_SECRET}", "SECRET someone_elses"),
    ],
)
def test_a_loosened_attach_is_rejected(bad_attach):
    with pytest.raises(FederatedScanRejected):
        _check_setup_sql([*_MYSQL_SETUP[:4], bad_attach])


def test_an_attach_before_its_secret_is_rejected():
    with pytest.raises(FederatedScanRejected):
        _check_setup_sql(["INSTALL mysql", "LOAD mysql", _MYSQL_ATTACH, _MYSQL_SECRET])


@pytest.mark.parametrize(
    "bad_expression",
    [
        # a catalog nobody attached, or not in the reserved namespace
        '"aran_attach_other"."research"."papers"',
        'aran_attach_other."research"."papers"',
        'memory."main"."snapshot"',
        # the attached table, plus anything
        _MYSQL_EXPRESSION + " UNION SELECT * FROM read_csv('/etc/passwd')",
        _MYSQL_EXPRESSION + ", read_csv('/etc/passwd')",
        _ALIAS + '."research"',
    ],
)
def test_the_expression_may_only_name_a_table_of_an_attached_catalog(bad_expression):
    attached = _check_setup_sql(_MYSQL_SETUP)
    with pytest.raises(FederatedScanRejected):
        _check_scan_expression(bad_expression, False, attached)


def test_mysql_scan_is_no_longer_admitted():
    # DuckDB 1.5.5 registers no such function; admitting the name would only
    # widen the fence for whatever a later extension puts behind it.
    with pytest.raises(FederatedScanRejected):
        ScanQueryExecutor("mysql_scan('host=db', 't')")


# ---- the Postgres attached-catalog shape: exactly what PostgresEngine emits ---- #
#
# Java's FederatedScanExpressionTest pins these strings too. Postgres left
# postgres_scan('<dsn>', …) for the MySQL shape because a scan expression
# becomes a view body, and DuckDB hands every view body back through
# duckdb_views(): the DSN, password included, was one catalogue read away.

_PG_SECRET_NAME = "aran_pg_0f1e2d3c4b5a69788796a5b4c3d2e1f0"
_PG_SECRET = (
    f"CREATE OR REPLACE TEMPORARY SECRET {_PG_SECRET_NAME} (TYPE postgres, "
    "HOST 'db.example.org', PORT 5432, DATABASE 'research', USER 'aran', "
    "PASSWORD 's3cret')"
)
_PG_ATTACH = (
    f"ATTACH IF NOT EXISTS '' AS {_ALIAS} (TYPE postgres, SECRET {_PG_SECRET_NAME}, READ_ONLY)"
)
_PG_SETUP = ["INSTALL postgres", "LOAD postgres", _PG_SECRET, _PG_ATTACH]
_PG_EXPRESSION = f'{_ALIAS}."public"."papers"'


def test_the_postgres_binding_aran_emits_passes_the_screen():
    attached = _check_setup_sql(_PG_SETUP)
    assert attached == {_ALIAS}
    _check_scan_expression(_PG_EXPRESSION, False, attached)


def test_a_postgres_password_is_one_literal_whatever_it_holds():
    secret = _PG_SECRET.replace("'s3cret'", "'p w''d\\x\"q; host=evil'")
    assert _check_setup_sql([*_PG_SETUP[:2], secret, _PG_ATTACH]) == {_ALIAS}


@pytest.mark.parametrize(
    "bad",
    [
        _PG_SECRET.replace("TEMPORARY", "PERSISTENT"),
        _PG_SECRET.replace("PASSWORD 's3cret')", "PASSWORD 's3cret', PASSFILE '/etc/shadow')"),
        _PG_SECRET.replace("'db.example.org'", "'db host'"),
        _PG_SECRET.replace("'research'", "'research sslmode=disable'"),
        _PG_SECRET.replace("'s3cret'", "'x', SSLROOTCERT '/tmp/s'"),
        # a postgres ATTACH naming a secret of the other type
        _MYSQL_SECRET,
    ],
)
def test_a_loosened_postgres_setup_statement_is_rejected(bad):
    with pytest.raises(FederatedScanRejected) as e:
        _check_setup_sql(["INSTALL postgres", "LOAD postgres", bad, _PG_ATTACH])
    assert "s3cret" not in str(e.value)


@pytest.mark.parametrize(
    "bad_attach",
    [
        _PG_ATTACH.replace("''", "'/var/lib/aran/other.duckdb'"),
        _PG_ATTACH.replace("''", "'host=evil dbname=x'"),
        _PG_ATTACH.replace(", READ_ONLY)", ")"),
        _PG_ATTACH.replace(_ALIAS, "src"),
        _PG_ATTACH.replace(f"SECRET {_PG_SECRET_NAME}", "SECRET someone_elses"),
    ],
)
def test_a_loosened_postgres_attach_is_rejected(bad_attach):
    with pytest.raises(FederatedScanRejected):
        _check_setup_sql([*_PG_SETUP[:3], bad_attach])


def test_postgres_scan_is_no_longer_admitted():
    # Nothing Aran sends is a postgres_scan any more, and the one it used to
    # send carried a DSN — admitting the shape would only keep that open.
    with pytest.raises(FederatedScanRejected):
        ScanQueryExecutor("postgres_scan('dbname=x password=y', 'public', 't')")


def test_a_failing_create_secret_never_echoes_its_driver_message(parquet_file):
    # Shape-valid (TYPE S3), rejected by DuckDB at execution — whose message
    # can quote the option values it did not like.
    with pytest.raises(ScanQueryError) as e:
        _executor(parquet_file, setup_sql=[
            "CREATE OR REPLACE SECRET s (TYPE S3, NOT_AN_OPTION 'hunter2-sentinel')",
        ])
    assert str(e.value) == "federated setup statement 0 (CREATE) failed"
    assert e.value.__cause__ is None


def test_failing_setup_statement_error_names_index_and_keyword_only(parquet_file):
    # A shape-valid statement that fails at execution: LOAD of an
    # extension that is not installed. The error carries the index and
    # keyword, plus only the first line of DuckDB's message.
    with pytest.raises(ScanQueryError) as e:
        _executor(parquet_file, setup_sql=["LOAD no_such_extension_xyz"])
    assert "statement 0 (LOAD)" in str(e.value)
    assert "\n" not in str(e.value)


def test_wrapper_semicolon_rejected_before_any_connection(parquet_file):
    with pytest.raises(WrapperSqlRejected):
        _executor(parquet_file, wrapper_sql="SELECT * FROM ${SNAPSHOT}; DROP TABLE x")


def test_wrapper_output_column_named_snapshot_is_rejected(parquet_file):
    with _executor(
        parquet_file, wrapper_sql="SELECT name AS snapshot FROM ${SNAPSHOT}",
    ) as ex, pytest.raises(WrapperSqlRejected):
        ex.fetch_columns()


def test_unbindable_scan_fails_at_construction(tmp_path):
    with pytest.raises(ScanQueryError):
        ScanQueryExecutor(
            f"read_parquet('{tmp_path}/absent.parquet')",
            allow_local_files=True,
        )


# ---- the lockdown knob itself, on the pinned duckdb ---- #

def test_local_lockdown_actually_restricts_and_cannot_be_reopened(parquet_file):
    # Engine-defined security control: prove it restricts on THIS duckdb
    # version, the same discipline as the Java side's DuckDbLockdownTest.
    conn = duckdb.connect()
    _apply_local_lockdown(conn)
    with pytest.raises(Exception, match=r"(?i)disabled|permission"):
        conn.execute(f"SELECT * FROM read_parquet('{parquet_file}')")
    with pytest.raises(Exception, match=r"(?i)lock"):
        conn.execute("SET disabled_filesystems=''")
    conn.close()


# ---- credential hygiene: no DuckDB text carries a password out ---- #

# A password that defeats the naive `password=\S+` scrub: a space, then
# libpq's \' escape, then a backslash. PostgresEngine.dsn quotes it exactly
# like this, and Names.sqlLiteral then doubles every single quote.
_SENTINEL = "hunter2 sen\\'ti\\\\nel"


def _postgres_scan(password: str) -> str:
    conninfo = (
        f"dbname='srcdb' user='u' password='{password}' host='127.0.0.1' port=1"
    )
    return "postgres_scan('" + conninfo.replace("'", "''") + "', 'public', 'venue')"


def _postgres_attach_setup(password: str) -> list[str]:
    """The binding Aran emits for a live Postgres source, pointed at a port
    nothing listens on: the ATTACH fails, and its error is what a caller
    would see."""
    literal = password.replace("'", "''")
    secret = (
        f"CREATE OR REPLACE TEMPORARY SECRET {_PG_SECRET_NAME} (TYPE postgres, "
        f"HOST '127.0.0.1', PORT 1, DATABASE 'srcdb', USER 'u', PASSWORD '{literal}')"
    )
    return ["INSTALL postgres", "LOAD postgres", secret, _PG_ATTACH]


_PG_UNREACHABLE_TABLE = f'{_ALIAS}."public"."venue"'


def _postgres_extension_loads() -> bool:
    con = duckdb.connect()
    try:
        con.execute("INSTALL postgres")
        con.execute("LOAD postgres")
        return True
    except Exception:
        return False
    finally:
        con.close()


@pytest.mark.parametrize(
    ("message", "leak"),
    [
        # DuckDB's own first line for a refused postgres_scan.
        (
            'IO Error: Unable to connect to Postgres at "dbname=\'srcdb\' '
            "user='u' password='s3cret' host='10.0.0.5' port=5432\": "
            "connection refused",
            "s3cret",
        ),
        # `leak` is the part a whitespace-bounded scrub leaves behind: the tail
        # after a quoted value's first space, or after an escaped quote.
        ("... password='p w\\'d' host='h' ...", "w\\'d"),
        ("... password='p w\\\\' host='h' ...", "w\\\\"),
        ("... PASSWORD = bare-secret host=h ...", "bare-secret"),
        ("... pwd=bare2 host=h ...", "bare2"),
        # Unterminated: everything after the opening quote may be the value.
        ("... host=h password='unterminated tail", "tail"),
        # The SQL-literal spelling, every quote doubled, with no conninfo to
        # match it against: masked by the pattern alone.
        ("... password=''p w\\''d\\\\'' host=''h'' ...", "d\\\\"),
    ],
)
def test_the_scrubber_redacts_every_libpq_password_spelling(message, leak):
    scrubbed = _redact(message)
    assert leak not in scrubbed
    assert "***" in scrubbed
    assert "host=" in scrubbed, "the host diagnostic is what a user can act on"


def test_the_dsn_is_redacted_whole_even_in_its_sql_literal_spelling():
    # A binder error can echo the expression as written — every quote
    # doubled — where no keyword=value scrub recognizes the value. The
    # exact conninfo, in either spelling, is replaced outright.
    expression = _postgres_scan(_SENTINEL)
    literal = expression[len("postgres_scan('"):expression.index("', 'public'")]
    message = f"Binder Error: no such table in {expression}"
    scrubbed = _redact(message, _conninfo_spellings(expression))
    assert literal not in scrubbed
    assert "hunter2" not in scrubbed
    assert "<dsn redacted>" in scrubbed


def test_a_failed_attach_never_echoes_the_password():
    # The binding Aran emits since the attached-catalog read: the password
    # lives in the secret, the ATTACH is what dials the source, and its
    # error is the one a caller sees.
    if not _postgres_extension_loads():
        pytest.skip("DuckDB's postgres extension is not installable here")
    with pytest.raises(ScanQueryError) as e:
        ScanQueryExecutor(
            _PG_UNREACHABLE_TABLE, setup_sql=_postgres_attach_setup(_SENTINEL),
        )
    message = str(e.value)
    assert "federated setup statement 3 (ATTACH) failed" in message
    assert "hunter2" not in message
    assert "127.0.0.1" in message, "the connection diagnostic survives"
    # No cause chain: a logger.exception up the stack would print DuckDB's
    # unredacted text from it.
    assert e.value.__cause__ is None
    assert e.value.__suppress_context__


def test_the_binding_error_is_scrubbed_whatever_scan_fails(tmp_path):
    # Extension-free twin of the test above: any DuckDB text on the binding
    # path goes through the scrubber, here via a path DuckDB echoes back.
    with pytest.raises(ScanQueryError) as e:
        ScanQueryExecutor(
            f"read_parquet('{tmp_path}/password=hunter2sentinel/x.parquet')",
            allow_local_files=True,
        )
    assert "hunter2sentinel" not in str(e.value)
    assert e.value.__cause__ is None


def test_a_failing_profiling_query_is_scrubbed(parquet_file, tmp_path):
    with _executor(parquet_file) as ex, pytest.raises(ScanQueryError) as e:
        ex.fetch_row(
            f"SELECT * FROM read_parquet('{tmp_path}/password=hunter2sentinel/x.parquet')"
        )
    assert "hunter2sentinel" not in str(e.value)
    assert e.value.__cause__ is None

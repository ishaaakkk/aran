import pytest
from freyja.profiling.metrics import (
    LAKE_STATEMENT_MAX_BYTES,
    METRICS,
    SCALAR_STATEMENT_BUDGET_BYTES,
    ColumnSpec,
    DistributionAndCommonMetrics,
    ScalarMetrics,
)


def _wide_columns(n: int) -> list[ColumnSpec]:
    """
    ``n`` columns named the way real snapshots name them — 20 to 30
    characters, not the 4-character names the rest of this file uses. Name
    length is what decides where the lake's statement cap bites, so a cap
    test on short names would pass while production failed.
    """
    base = "customer_account_reference"
    columns = [
        ColumnSpec(f"{base[: 16 + (i % 11)]}_{i:03d}", i, "VARCHAR")
        for i in range(n)
    ]
    assert all(20 <= len(c.name) <= 30 for c in columns)
    return columns


def test_column_spec():
    c = ColumnSpec("hello_world", 1, "VARCHAR")
    assert c.name == "hello_world"
    assert c.index == 1
    assert c.alias_prefix == "c1"
    assert c.quoted == '"hello_world"'

def test_scalar_metrics_build_query():
    columns = [
        ColumnSpec("col1", 0, "VARCHAR"),
        ColumnSpec('col"2', 1, "INTEGER")
    ]
    query = ScalarMetrics.build_query(columns)

    assert "COUNT(*) AS row_count" in query
    assert "FROM snapshot" in query
    assert '"col1"' in query
    assert '"col""2"' in query
    assert "c0__cardinality" in query
    assert "c1__cardinality" in query

def test_scalar_metrics_parse_result():
    columns = [
        ColumnSpec("col1", 0, "VARCHAR")
    ]
    raw_row = {
        "row_count": 100,
        "c0__non_null": 80,
        "c0__cardinality": 5,
        "c0__entropy": 1.5,
        "c0__first_word": "apple",
        "c0__last_word": "zebra",
        "c0__len_max_word": 5,
        "c0__len_min_word": 5,
        "c0__len_avg_word": 5.0,
        "c0__number_words": 1,
        "c0__words_cnt_max": 1,
        "c0__words_cnt_min": 1,
        "c0__words_cnt_avg": 1.0,
        "c0__words_cnt_sd": 0.0,
    }

    row_count, parsed = ScalarMetrics.parse_result(raw_row, columns)
    assert row_count == 100
    metrics = parsed[0]

    assert metrics["cardinality"] == 5
    assert metrics["entropy"] == 1.5
    assert metrics["uniqueness"] == 5 / 100
    assert metrics["incompleteness"] == 20 / 100
    assert metrics["is_empty"] == 0
    assert metrics["is_binary"] == 0
    assert metrics["_non_null"] == 80

def test_distribution_build_query():
    c = ColumnSpec("my_col", 2, "VARCHAR")
    query = DistributionAndCommonMetrics.build_query(c)
    assert '"my_col"' in query
    assert "AS freq_avg" in query
    assert "AS freq_q" in query

def test_distribution_parse_result():
    raw_row = {
        "freq_avg": 10.0,
        "freq_min": 2,
        "freq_max": 20,
        "freq_sd": 5.0,
        "freq_iqr": 15,
        "freq_skew": 0.1,
        "freq_kurt": 0.2,
        "freq_q": [1, 2, 3, 4, 5, 6, 7],
        "common_values": ["apple", "banana"]
    }
    parsed = DistributionAndCommonMetrics.parse_result(raw_row, 100)

    assert parsed["frequency_avg"] == 10.0
    assert parsed["frequency_min"] == 2.0
    assert parsed["frequency_max"] == 20.0
    assert parsed["val_pct_max"] == 20 / 100
    assert parsed["freq_word_containment"] == ["apple", "banana"]
    assert len(parsed["freq_word_soundex_containment"]) == 2

def test_every_scalar_statement_stays_under_the_lakes_statement_cap():
    """
    S13-1: the whole scalar suite used to be one statement whose size grew
    ~4 KB per column, so from ~16 columns the lake answered 422 and the
    dataset got NO profiles at all (the scalar statement runs before the
    per-column fan-out). 200 columns is the width this must survive.
    """
    columns = _wide_columns(200)

    statements = [sql for sql, _ in ScalarMetrics.build_queries(columns)]

    assert statements
    for sql in statements:
        assert len(sql.encode("utf-8")) <= LAKE_STATEMENT_MAX_BYTES


def test_chunking_covers_every_column_exactly_once_in_order():
    """Chunk boundaries must not drop or duplicate a column: the profiler
    merges the chunks back into one per-column structure, and a duplicate
    would silently overwrite the earlier chunk's metrics."""
    columns = _wide_columns(200)

    chunks = [chunk for _, chunk in ScalarMetrics.build_queries(columns)]

    assert len(chunks) > 1, "200 wide columns cannot fit one statement"
    assert [c for chunk in chunks for c in chunk] == columns


def test_a_narrow_table_still_takes_exactly_one_statement():
    """Chunking must not cost a round-trip on the common case."""
    assert len(ScalarMetrics.build_queries(_wide_columns(6))) == 1


def test_each_chunk_is_a_self_contained_statement():
    """Every chunk carries its own row_count and only its own columns'
    aliases, because ``parse_result`` is applied per chunk."""
    columns = _wide_columns(200)

    for sql, chunk in ScalarMetrics.build_queries(columns):
        assert "COUNT(*) AS row_count" in sql
        assert "FROM snapshot" in sql
        for c in chunk:
            assert f"{c.alias_prefix}{ScalarMetrics.SEP}cardinality" in sql
        absent = [c for c in columns if c not in chunk]
        for c in absent:
            assert f"{c.alias_prefix}{ScalarMetrics.SEP}cardinality" not in sql


def test_the_cleaning_expression_is_emitted_once_per_column():
    """The size fix itself: ``_clean_str`` used to be re-inlined up to ~3x
    per aggregate, 14 aggregates deep. One CTE binding per column is what
    makes the statement an order of magnitude smaller."""
    columns = _wide_columns(8)

    sql, _ = ScalarMetrics.build_queries(columns)[0]

    assert sql.count("chr(10)") == len(columns)


def test_freyja_mirrors_the_lakes_own_statement_cap():
    """Drift guard: the cap freyja chunks under is aran-lake's, and the two
    live in different packages. Skipped when aran_lake is not installed —
    freyja must build the same SQL either way (the federated scan path has
    no lake at all)."""
    query = pytest.importorskip("aran_lake.api.query")

    lake_cap = next(
        m.max_length
        for m in query.QueryRequest.model_fields["sql"].metadata
        if hasattr(m, "max_length")
    )

    assert lake_cap == LAKE_STATEMENT_MAX_BYTES
    assert SCALAR_STATEMENT_BUDGET_BYTES < LAKE_STATEMENT_MAX_BYTES


def test_metrics_registry():
    # Ensure some expected metrics exist
    metric_names = [m.name for m in METRICS]
    assert "cardinality" in metric_names
    assert "entropy" in metric_names

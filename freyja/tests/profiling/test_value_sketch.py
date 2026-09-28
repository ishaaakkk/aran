"""The value-hash sample the ``exact`` join score is estimated from (S13-2 closing review)."""
from __future__ import annotations

import duckdb
import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from freyja.executors.scan import ScanQueryExecutor
from freyja.profiling import value_sketch
from freyja.profiling.metrics import ColumnSpec, DistributionAndCommonMetrics
from freyja.profiling.profiler import ProfileOptions, profile_snapshot
from freyja.profiling.value_sketch import ValueSample, jaccard, multiset_jaccard, sample_of


def _profile(tmp_path, name, values):
    path = tmp_path / f"{name}.parquet"
    pq.write_table(pa.table({"v": pa.array(values, pa.string())}), path)
    with ScanQueryExecutor(f"read_parquet('{path}')", allow_local_files=True) as ex:
        (column,) = profile_snapshot(ex, ProfileOptions()).columns
    return column.metrics


def test_the_python_hash_is_the_one_the_profiler_sql_computes():
    """A persisted top-10 list is hashed in Python (legacy profiles) and compared with
    samples DuckDB hashed, so the two must agree to the byte — unicode included."""
    values = ["1", "r-001", "maría", "北京", "", "a,b"]
    con = duckdb.connect()
    in_sql = [
        con.execute(f"SELECT {value_sketch.sql_value_hash('?')}", [v]).fetchone()[0]
        for v in values
    ]
    assert in_sql == [value_sketch.value_hash(v) for v in values]


def test_the_profiler_samples_the_smallest_hashes_of_the_cleaned_distinct_values(tmp_path):
    values = [f"ID-{i}" for i in range(1, 501)] + ["id-1 ", "ID-2"]  # cleaning folds these
    metrics = _profile(tmp_path, "ids", values)

    every_hash = sorted({value_sketch.value_hash(f"id-{i}") for i in range(1, 501)})
    assert metrics[value_sketch.METRIC] == every_hash[: value_sketch.SAMPLE_SIZE]
    assert metrics["cardinality"] == 500


def test_a_column_smaller_than_k_is_sampled_whole(tmp_path):
    metrics = _profile(tmp_path, "small", ["a", "b", "c", "a"])
    sample = sample_of(metrics)
    assert sample == ValueSample(
        frozenset(value_sketch.value_hash(v) for v in "abc"), complete=True)


def test_complete_samples_give_the_exact_jaccard():
    a = ValueSample(frozenset({"1", "2", "3", "4"}), complete=True)
    b = ValueSample(frozenset({"3", "4", "5"}), complete=True)
    assert jaccard(a, b) == 2 / 5
    assert multiset_jaccard(a, b) == pytest.approx(2 / (4 + 3))


def test_the_estimate_reads_only_the_k_smallest_hashes_of_the_union():
    """Bottom-k: of the union's 4 smallest hashes (0,1,2,3), 0 and 2 are in both samples.
    Hash 9 is shared too but lies outside the union's bottom 4, so it must not count."""
    a = ValueSample(frozenset({"0", "2", "3", "9"}), complete=False)
    b = ValueSample(frozenset({"0", "1", "2", "9"}), complete=False)
    assert jaccard(a, b) == 2 / 4


def test_a_legacy_profile_small_enough_for_its_top_values_to_be_its_value_set_is_scored():
    legacy = {"cardinality": 3, "freq_word_containment": ["r0", "r1", "x"]}
    assert sample_of(legacy) == ValueSample(
        frozenset(value_sketch.value_hash(v) for v in ("r0", "r1", "x")), complete=True)


def test_a_legacy_profile_whose_top_values_are_not_its_value_set_has_no_sample():
    """The top ten of a 1,000-value column are chosen by frequency — the very evidence the
    sample replaces. Without a persisted sample there is nothing honest to score from."""
    legacy = {"cardinality": 1000, "freq_word_containment": [str(i) for i in range(10)]}
    assert sample_of(legacy) is None


def test_the_lakes_sql_screen_admits_the_sampling_statement():
    """A snapshot is profiled through aran-lake's /api/query, which screens every statement.
    Were md5/substr refused there, every column's distribution query would fail, and Aran
    persists no profile for a column freyja reports as failed. Skipped without aran_lake."""
    runner = pytest.importorskip("aran_lake.query.duckdb_runner")
    runner._screen_sql(DistributionAndCommonMetrics.build_query(ColumnSpec("id", 0, "VARCHAR")))

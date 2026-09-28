from unittest.mock import MagicMock

from freyja.profiling.profiler import ProfileOptions, profile_snapshot


def test_profile_snapshot_empty():
    executor = MagicMock()
    executor.fetch_columns.return_value = [("col1", "VARCHAR")]

    # Empty table scalar result
    executor.fetch_row.return_value = {
        "row_count": 0,
        "c0__non_null": 0,
        "c0__cardinality": 0,
        "c0__entropy": 0,
        "c0__first_word": None,
        "c0__last_word": None,
        "c0__len_max_word": 0,
        "c0__len_min_word": 0,
        "c0__len_avg_word": 0,
        "c0__number_words": 0,
        "c0__words_cnt_max": 0,
        "c0__words_cnt_min": 0,
        "c0__words_cnt_avg": 0,
        "c0__words_cnt_sd": 0,
    }

    result = profile_snapshot(executor, ProfileOptions())
    assert result.row_count == 0
    assert len(result.columns) == 0


def test_profile_snapshot_with_data():
    executor = MagicMock()
    executor.fetch_columns.return_value = [("col1", "VARCHAR")]

    def mock_fetch_row(sql):
        if "COUNT(*) AS row_count" in sql:
            return {
                "row_count": 100,
                "c0__non_null": 100,
                "c0__cardinality": 10,
                "c0__entropy": 2.5,
                "c0__first_word": "a",
                "c0__last_word": "z",
                "c0__len_max_word": 5,
                "c0__len_min_word": 1,
                "c0__len_avg_word": 3.0,
                "c0__number_words": 1,
                "c0__words_cnt_max": 1,
                "c0__words_cnt_min": 1,
                "c0__words_cnt_avg": 1.0,
                "c0__words_cnt_sd": 0.0,
            }
        else:
            return {
                "freq_avg": 10.0,
                "freq_min": 1,
                "freq_max": 20,
                "freq_sd": 2.0,
                "freq_iqr": 5,
                "freq_skew": 0.1,
                "freq_kurt": 0.2,
                "freq_q": [1, 2, 3, 4, 5, 6, 7],
                "common_values": ["apple", "banana"]
            }

    executor.fetch_row.side_effect = mock_fetch_row

    result = profile_snapshot(executor, ProfileOptions(max_concurrent_column_queries=1))

    assert result.row_count == 100
    assert len(result.columns) == 1

    col_prof = result.columns[0]
    assert col_prof.column_name == "col1"
    assert col_prof.metrics["cardinality"] == 10
    assert col_prof.metrics["frequency_avg"] == 10.0
    assert col_prof.metrics["val_pct_max"] == 0.2  # 20 / 100

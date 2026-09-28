"""
Refactored metric query builders.

The big change vs ``app/core/profiling/metrics.py``:

* ``ScalarMetrics`` builds **one SELECT for every scalar metric across
  every column of the table**. Used to be 8 queries per column; now
  it's 1 query for the table — or, for a table too wide to state in one
  query the lake will accept, as few as the byte budget allows (see the
  class docstring; the profiler stitches the chunks back together).

* ``DistributionAndCommonMetrics`` builds **one CTE-based query per
  column** that computes distribution stats, the top-10 common values
  *and* the bottom-k value-hash sample from the same value-frequency
  CTE — one DuckDB pass per column, where before there were two.

The metric *registry* (``MetricProperties``, the per-metric ``active``
/ ``normalize`` / ``distance_pattern`` flags) is unchanged in spirit —
the research-side definitions are the right artifact, we just moved
the SQL composition into bulk form.

Cleaning is inlined: every read of a string column uses
``_clean_str("col")`` instead of bare ``"col"``. This is the
``UPDATE``-replacement called out in §1.4 of the revision document.

Column-name handling: the **original** name is the SQL-quoted
identifier; the **alias** is a deterministic safe key built from the
column's position. That avoids both injection risk in aliases and
case-sensitivity headaches.
"""
from __future__ import annotations

import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any

import jellyfish
import numpy as np

from freyja.profiling import value_sketch

# -------------------------------------------------------------------- #
# Statement size limits
# -------------------------------------------------------------------- #

# aran-lake refuses a longer statement with a pydantic 422:
# ``QueryRequest.sql = Field(..., min_length=1, max_length=64 * 1024)`` in
# aran-lake/src/aran_lake/api/query.py. Mirrored here rather than imported
# because freyja composes the same SQL when the lake package is absent (the
# federated scan path never touches the lake); the mirror is pinned against
# the real field by test_metrics.test_freyja_mirrors_the_lakes_own_statement_cap.
LAKE_STATEMENT_MAX_BYTES = 64 * 1024

# Byte budget for ONE composed scalar statement. Deliberately half the cap:
# wrapper-output profiling substitutes the wrapper's own SQL text for the
# statement's single bare ``snapshot`` reference (freyja/executors/binding.py)
# AFTER this builder has run, and the builder cannot see that text — so the
# headroom is the whole defence against a wrapper pushing a legal statement
# over the cap. A wrapper longer than that headroom would still trip it; the
# builder would have to be told the executor's substitution cost to close
# that, which is a change to the QueryExecutor boundary, not to this number.
SCALAR_STATEMENT_BUDGET_BYTES = 32 * 1024


# -------------------------------------------------------------------- #
# Identifier helpers
# -------------------------------------------------------------------- #

def _q(identifier: str) -> str:
    """Double-quote an SQL identifier, escaping internal quotes."""
    return '"' + identifier.replace('"', '""') + '"'


_ALIAS_SAFE = re.compile(r"[^a-zA-Z0-9_]")


def _alias_for(index: int) -> str:
    """
    Stable, injection-safe alias prefix for a column. We do *not* use
    the column's own name because that may contain unicode, spaces,
    quotes, reserved words. Position is canonical.
    """
    return f"c{index}"


def _clean_str(quoted_column: str) -> str:
    """
    String-cleaning expression. Push the same trim/lower/strip-newlines
    that the old ``clean_string_values`` UPDATE did into the SELECT,
    so we don't need to mutate the table.

    Note: the cast to VARCHAR handles non-string source types (BOOLEAN,
    integer-cast-as-varchar, etc.) so callers can apply this expression
    uniformly.
    """
    return (
        f"lower(trim(replace(replace(CAST({quoted_column} AS VARCHAR), "
        f"chr(10), ''), chr(13), '')))"
    )


# -------------------------------------------------------------------- #
# Column descriptor: name + position + alias
# -------------------------------------------------------------------- #

@dataclass(frozen=True, slots=True)
class ColumnSpec:
    name: str       # original column name (as in the snapshot)
    index: int      # position in the profile call's column list
    sql_type: str   # source type (VARCHAR, BOOLEAN, etc.)

    @property
    def quoted(self) -> str:
        return _q(self.name)

    @property
    def alias_prefix(self) -> str:
        return _alias_for(self.index)


# -------------------------------------------------------------------- #
# Scalar metrics — one query for the whole table (chunked when wide)
# -------------------------------------------------------------------- #

_LIST_SEP = ",\n    "
_CLEANED_CTE = "cleaned"
_PER_ROW_CTE = "per_row"


@dataclass(frozen=True, slots=True)
class _ColumnFragments:
    """One column's contribution to each of the three select lists."""

    cleaned: tuple[str, ...]     # bindings in the `cleaned` CTE
    per_row: tuple[str, ...]     # bindings in the `per_row` CTE
    aggregates: tuple[str, ...]  # items in the final SELECT

    @property
    def size(self) -> int:
        """
        Bytes this column adds to a composed statement, separators included
        — the quantity the chunker packs against, so it must count exactly
        what ``ScalarMetrics.build_query`` writes.
        """
        items = (*self.cleaned, *self.per_row, *self.aggregates)
        return sum(len(item.encode("utf-8")) + len(_LIST_SEP) for item in items)


class ScalarMetrics:
    """
    Builds the table-level scalar query.

    For each column, computes the metrics that need only flat
    aggregates (no GROUP BY): cardinality, entropy, length stats,
    word-count stats, lexicographic boundaries, and the non-null
    count. The row count for the whole table is emitted once.

    Output: a single-row result whose keys are
    ``{row_count, c0__cardinality, c0__entropy, ..., c1__cardinality, ...}``.
    The profiler unpacks per-column metrics by alias prefix.

    **Shape, and why it is a CTE chain** (S13-1). Every cleaned-string read
    used to be a fresh inline ``_clean_str`` expansion — up to three per
    aggregate, thirteen aggregates per column, ~4 KB of SQL per column — so
    a snapshot of ~16 ordinary columns composed a statement past the lake's
    64 KB cap. The lake answered 422, and because this statement runs
    *before* the per-column fan-out, the dataset ended up with no profiles
    at all: no row count, no NDV, no null fractions, nothing for key
    detection or join discovery, reported as a single WARN. So each column
    now binds its cleaned text (and the two expressions derived from it)
    once in a CTE, and the aggregates read those short names.

    **And why it is also chunked.** Smaller per column is not a bound: at
    some width any single statement crosses the cap. ``build_queries``
    packs columns into as few statements as fit
    ``SCALAR_STATEMENT_BUDGET_BYTES``; each is self-contained (it carries
    ``row_count`` and its own columns' aliases) and the profiler merges
    them back into one per-column structure, so nothing downstream can tell
    where a chunk boundary fell. A single column whose fragment alone
    exceeds the budget still gets its own statement — over the cap it would
    be refused, but nothing shorter can be composed for it.
    """

    # Tag-format: ``{alias}__{metric_name}``. Keeps it parseable.
    SEP = "__"

    @classmethod
    def build_queries(
        cls,
        columns: Iterable[ColumnSpec],
        *,
        budget_bytes: int | None = None,
    ) -> list[tuple[str, tuple[ColumnSpec, ...]]]:
        """
        Compose the scalar suite as ``(sql, columns_of_that_statement)``
        pairs — one pair for a table narrow enough, more for a wide one.

        ``budget_bytes`` defaults to the module's
        ``SCALAR_STATEMENT_BUDGET_BYTES`` at call time (not at import), so a
        test can lower it to force chunk boundaries on a small fixture.
        """
        budget = (
            SCALAR_STATEMENT_BUDGET_BYTES if budget_bytes is None else budget_bytes
        )
        # The statement skeleton, measured rather than guessed: composing it
        # with no columns is never executed, only weighed.
        overhead = len(cls._compose((), (), ()).encode("utf-8"))

        chunks: list[list[ColumnSpec]] = []
        current: list[ColumnSpec] = []
        current_bytes = overhead
        for c in columns:
            size = cls._fragments(c).size
            # `current and` keeps a column that is on its own too big for the
            # budget from producing an empty statement — it gets a statement
            # to itself instead.
            if current and current_bytes + size > budget:
                chunks.append(current)
                current, current_bytes = [], overhead
            current.append(c)
            current_bytes += size
        if current:
            chunks.append(current)

        return [(cls.build_query(chunk), tuple(chunk)) for chunk in chunks]

    @classmethod
    def build_query(cls, columns: Iterable[ColumnSpec]) -> str:
        """
        One statement over ``columns``. Callers that may be handed a wide
        table want ``build_queries`` instead: this composes whatever it is
        given, cap or no cap.
        """
        fragments = [cls._fragments(c) for c in columns]
        return cls._compose(
            tuple(item for f in fragments for item in f.cleaned),
            tuple(item for f in fragments for item in f.per_row),
            tuple(item for f in fragments for item in f.aggregates),
        )

    @classmethod
    def _fragments(cls, c: ColumnSpec) -> _ColumnFragments:
        q = c.quoted
        p = c.alias_prefix
        # Position-derived, like the alias prefix: never the column's own
        # name, which may carry unicode, spaces, quotes or reserved words.
        raw, txt, tokens, words = f"r{c.index}", f"s{c.index}", f"t{c.index}", f"w{c.index}"

        return _ColumnFragments(
            cleaned=(
                # The raw column is carried through for COUNT() alone:
                # non_null counts raw NULLs, which cleaning would not change
                # but casting could obscure.
                f"{q} AS {raw}",
                f"{_clean_str(q)} AS {txt}",
            ),
            per_row=(
                raw,
                txt,
                # Token lengths, and the per-row word count (spaces + 1, zero
                # for a cell that cleaned to empty). Both were re-inlined into
                # every aggregate that read them; bound once, they cost their
                # readers a two-character name.
                f"list_transform(str_split({txt}, ' '), s -> LENGTH(s)) AS {tokens}",
                f"CASE WHEN {txt} IS NULL OR {txt} = '' THEN 0 "
                f"ELSE LENGTH({txt}) - LENGTH(REPLACE({txt}, ' ', '')) + 1 END AS {words}",
            ),
            aggregates=(
                f"COUNT({raw}) AS {p}{cls.SEP}non_null",
                f"COUNT(DISTINCT {txt}) AS {p}{cls.SEP}cardinality",
                f"COALESCE(ENTROPY({txt}), 0.0) AS {p}{cls.SEP}entropy",
                # lexicographic boundaries — over the cleaned text
                f"MIN({txt}) AS {p}{cls.SEP}first_word",
                f"MAX({txt}) AS {p}{cls.SEP}last_word",
                # word-length stats: max/min/avg over each row's token lengths
                f"MAX(list_aggregate({tokens}, 'max')) AS {p}{cls.SEP}len_max_word",
                f"MIN(list_aggregate({tokens}, 'min')) AS {p}{cls.SEP}len_min_word",
                f"AVG(list_aggregate({tokens}, 'avg')) AS {p}{cls.SEP}len_avg_word",
                f"SUM({words}) AS {p}{cls.SEP}number_words",
                f"MAX({words}) AS {p}{cls.SEP}words_cnt_max",
                f"MIN({words}) AS {p}{cls.SEP}words_cnt_min",
                f"AVG({words}) AS {p}{cls.SEP}words_cnt_avg",
                f"STDDEV_POP({words}) AS {p}{cls.SEP}words_cnt_sd",
            ),
        )

    @staticmethod
    def _compose(
        cleaned: tuple[str, ...],
        per_row: tuple[str, ...],
        aggregates: tuple[str, ...],
    ) -> str:
        # `snapshot` appears exactly once — the executors' wrapper-output
        # substitution rewrites every bare occurrence of that token
        # (freyja/executors/binding.py), so one occurrence means the wrapper
        # text is inserted once and the size headroom stays predictable.
        return (
            f"WITH {_CLEANED_CTE} AS (\n  SELECT\n    "
            + _LIST_SEP.join(cleaned)
            + "\n  FROM snapshot\n),\n"
            f"{_PER_ROW_CTE} AS (\n  SELECT\n    "
            + _LIST_SEP.join(per_row)
            + f"\n  FROM {_CLEANED_CTE}\n)\n"
            "SELECT\n    COUNT(*) AS row_count"
            + ("".join(_LIST_SEP + item for item in aggregates))
            + f"\nFROM {_PER_ROW_CTE}"
        )

    @classmethod
    def parse_result(
        cls,
        row: Mapping[str, Any],
        columns: Iterable[ColumnSpec],
    ) -> tuple[int, dict[int, dict[str, Any]]]:
        """
        Split the one-row result back into per-column metric dicts.

        Returns ``(row_count, {column_index: {metric: value}, ...})``.
        ``is_empty`` / ``is_binary`` / ``uniqueness`` / ``incompleteness``
        are derived here, not queried.
        """
        row_count = int(row["row_count"])

        per_column: dict[int, dict[str, Any]] = {}
        for c in columns:
            p = c.alias_prefix
            sep = cls.SEP

            non_null = int(row[f"{p}{sep}non_null"])
            cardinality = int(row[f"{p}{sep}cardinality"])

            # All metrics, including the derived ones.
            metrics: dict[str, Any] = {
                "cardinality":  cardinality,
                "entropy":      row[f"{p}{sep}entropy"],
                "uniqueness":   (cardinality / row_count) if row_count else 0,
                "incompleteness": ((row_count - non_null) / row_count) if row_count else 0,

                "first_word":   row[f"{p}{sep}first_word"],
                "last_word":    row[f"{p}{sep}last_word"],

                "len_max_word": row[f"{p}{sep}len_max_word"],
                "len_min_word": row[f"{p}{sep}len_min_word"],
                "len_avg_word": row[f"{p}{sep}len_avg_word"],

                "number_words":  row[f"{p}{sep}number_words"],
                "words_cnt_max": row[f"{p}{sep}words_cnt_max"],
                "words_cnt_min": row[f"{p}{sep}words_cnt_min"],
                "words_cnt_avg": row[f"{p}{sep}words_cnt_avg"],
                "words_cnt_sd":  row[f"{p}{sep}words_cnt_sd"],

                # Derived flags — no DB call needed.
                "is_empty":  1 if non_null == 0 else 0,
                "is_binary": 1 if cardinality == 2 else 0,
            }
            # Used downstream to decide whether to emit this column at
            # all (the profiler drops columns with non_null == 0).
            metrics["_non_null"] = non_null
            per_column[c.index] = metrics

        return row_count, per_column


# -------------------------------------------------------------------- #
# Distribution + common values — one query per column
# -------------------------------------------------------------------- #

class DistributionAndCommonMetrics:
    """
    Builds the per-column query that produces:

    * distribution stats over the value-frequency table:
      freq_avg/min/max/sd/iqr/skewness/kurtosis,
      7 quantiles (via the list-form of ``quantile_disc``)
    * the top-10 common values (raw, ordered by count desc then value asc)
    * the value-hash sample: the ``value_sketch.SAMPLE_SIZE`` smallest
      hashes of the distinct values, which the ``exact`` join score
      estimates overlap from — the top-10 cannot serve, being chosen by
      frequency (see ``value_sketch``)

    All from a single CTE — the value-frequency table is materialized
    once per query, read three times.
    """

    @staticmethod
    def build_query(column: ColumnSpec) -> str:
        q = column.quoted
        qc = _clean_str(q)

        # Single quantile_disc call returning all seven cut-points at once.
        # DuckDB supports the list form: quantile_disc(c, [q1, q2, ...])
        # returning a LIST<numeric>.
        seven_quantiles = "[0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875]"
        value_hash = value_sketch.sql_value_hash("v")

        # {q}/{qc} are identifiers already escaped by _q(), not values. Column names reach
        # this from the snapshot schema, and _q doubles any embedded quote, so there is no
        # string-literal context for a value to break out of.
        return f"""
WITH freq AS (
    SELECT {qc} AS v, COUNT(*) AS c
    FROM snapshot
    WHERE {q} IS NOT NULL
    GROUP BY {qc}
)
SELECT
    AVG(c)                              AS freq_avg,
    MIN(c)                              AS freq_min,
    MAX(c)                              AS freq_max,
    STDDEV_POP(c)                       AS freq_sd,
    quantile_disc(c, 0.75) - quantile_disc(c, 0.25)
                                        AS freq_iqr,
    SKEWNESS(c)                         AS freq_skew,
    KURTOSIS(c)                         AS freq_kurt,
    quantile_disc(c, {seven_quantiles}) AS freq_q,
    (
        SELECT list(v ORDER BY c DESC, v ASC)
        FROM (SELECT v, c FROM freq ORDER BY c DESC, v ASC LIMIT 10) top
    )                                   AS common_values,
    (
        SELECT list(h ORDER BY h)
        FROM (
            SELECT DISTINCT {value_hash} AS h FROM freq
            ORDER BY h LIMIT {value_sketch.SAMPLE_SIZE}
        ) bottom
    )                                   AS value_hash_sample
FROM freq
""".strip()

    @staticmethod
    def parse_result(row: Mapping[str, Any], row_count: int) -> dict[str, Any]:
        """Convert raw DB row into the metric dict for one column."""
        def _nz(v: Any) -> float:
            return 0.0 if v is None or (isinstance(v, float) and np.isnan(v)) else float(v)

        freq_min = _nz(row["freq_min"])
        freq_max = _nz(row["freq_max"])
        freq_sd = _nz(row["freq_sd"])

        # quantile_disc returns a list of 7 values. Empty group → None.
        quantiles = row.get("freq_q") or [0] * 7
        if len(quantiles) != 7:
            # defensive — pad/truncate
            quantiles = (list(quantiles) + [0] * 7)[:7]
        freq_qs = [(_nz(v) / freq_max) if freq_max > 0 else 0 for v in quantiles]

        # Top-10 common values.
        common = row.get("common_values") or []
        common_strs = [str(v) for v in common if v is not None]
        common_soundex = [jellyfish.soundex(v) for v in common_strs]

        value_hashes = [str(h) for h in (row.get("value_hash_sample") or []) if h is not None]

        return {
            "frequency_avg":  _nz(row["freq_avg"]),
            "frequency_min":  freq_min,
            "frequency_max":  freq_max,
            "frequency_sd":   freq_sd,
            "frequency_iqr":  (_nz(row["freq_iqr"]) / row_count) if row_count else 0,
            "skewness":       _nz(row["freq_skew"]),
            "kurtosis":       _nz(row["freq_kurt"]),

            "val_pct_min":    (freq_min / row_count) if row_count else 0,
            "val_pct_max":    (freq_max / row_count) if row_count else 0,
            "val_pct_std":    (freq_sd  / row_count) if row_count else 0,
            "constancy":      (freq_max / row_count) if row_count else 0,

            "frequency_1qo":  freq_qs[0],
            "frequency_2qo":  freq_qs[1],
            "frequency_3qo":  freq_qs[2],
            "frequency_4qo":  freq_qs[3],
            "frequency_5qo":  freq_qs[4],
            "frequency_6qo":  freq_qs[5],
            "frequency_7qo":  freq_qs[6],

            "freq_word_containment":          common_strs,
            "freq_word_soundex_containment":  common_soundex,

            value_sketch.METRIC:              value_hashes,
        }


# -------------------------------------------------------------------- #
# MetricProperties — the metric registry (mostly unchanged in spirit)
# -------------------------------------------------------------------- #

# The metric *flags* (which are normalized, which use what distance
# pattern) are unchanged from the original ``metrics.py``. They drive
# the downstream distance and normalization steps. We just centralize
# them as plain data instead of a class hierarchy.

@dataclass(frozen=True, slots=True)
class MetricDef:
    name: str
    normalize: bool             # included in Z-score normalization
    distance_pattern: str       # "substraction" | "containment" | "levenshtein"


METRICS: tuple[MetricDef, ...] = (
    # Cardinality
    MetricDef("cardinality",     True,  "substraction"),
    MetricDef("incompleteness",  False, "substraction"),
    MetricDef("uniqueness",      False, "substraction"),
    MetricDef("entropy",         True,  "substraction"),

    # Distribution
    MetricDef("frequency_avg",   True,  "substraction"),
    MetricDef("frequency_min",   True,  "substraction"),
    MetricDef("frequency_max",   True,  "substraction"),
    MetricDef("frequency_sd",    True,  "substraction"),
    MetricDef("frequency_iqr",   False, "substraction"),
    MetricDef("val_pct_min",     False, "substraction"),
    MetricDef("val_pct_max",     False, "substraction"),
    MetricDef("val_pct_std",     False, "substraction"),
    MetricDef("constancy",       False, "substraction"),
    MetricDef("frequency_1qo",   False, "substraction"),
    MetricDef("frequency_2qo",   False, "substraction"),
    MetricDef("frequency_3qo",   False, "substraction"),
    MetricDef("frequency_4qo",   False, "substraction"),
    MetricDef("frequency_5qo",   False, "substraction"),
    MetricDef("frequency_6qo",   False, "substraction"),
    MetricDef("frequency_7qo",   False, "substraction"),

    # Common values
    MetricDef("freq_word_containment",         False, "containment"),
    MetricDef("freq_word_soundex_containment", False, "containment"),

    # Length
    MetricDef("len_max_word",    True,  "substraction"),
    MetricDef("len_min_word",    True,  "substraction"),
    MetricDef("len_avg_word",    True,  "substraction"),

    # Word counts
    MetricDef("number_words",    True,  "substraction"),
    MetricDef("words_cnt_max",   True,  "substraction"),
    MetricDef("words_cnt_min",   True,  "substraction"),
    MetricDef("words_cnt_avg",   True,  "substraction"),
    MetricDef("words_cnt_sd",    True,  "substraction"),

    # Boundary words (levenshtein-compared)
    MetricDef("first_word",      False, "levenshtein"),
    MetricDef("last_word",       False, "levenshtein"),

    # Flags
    MetricDef("is_empty",        False, "substraction"),
    MetricDef("is_binary",       False, "substraction"),
)


# Lookup helpers — built once on import.

METRICS_TO_NORMALIZE: tuple[str, ...] = tuple(m.name for m in METRICS if m.normalize)
DISTANCE_PATTERNS: Mapping[str, str] = {m.name: m.distance_pattern for m in METRICS}

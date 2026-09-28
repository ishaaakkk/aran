"""
The value sample the ``exact`` join score estimates its multiset Jaccard from.

A column's ``freq_word_containment`` list is its ten MOST FREQUENT values, ties
broken by value. That is not a sample of the value set: a unique column has every
frequency at 1, so its "top 10" is its lexicographic prefix ('1', '10', '100', …),
which any other surrogate key shares, while a foreign key's top 10 is chosen by how
often each id is referenced and rarely meets the key's prefix. Scored off those
lists, an unrelated 1..300 serial column outranked the real foreign key of a 1..1000
primary key (closing review of S13-2).

What two columns of one domain draw alike is a **bottom-k by hash**: the k smallest
hashes of each column's distinct cleaned values. A value in both columns has one
hash, so it lands in both samples whenever it is small enough to land in either,
which is what makes the standard bottom-k Jaccard estimate below unbiased.

The hash is md5 — not DuckDB's ``hash()`` — because the sample is persisted and
compared later, possibly against one computed by a different DuckDB (the lake's,
or freyja's own over a federated scan): ``hash()`` is not specified to be stable
across releases, a digest is. :func:`value_hash` mirrors :func:`sql_value_hash`
byte for byte; ``test_value_sketch`` pins that against a real DuckDB.
"""
from __future__ import annotations

import hashlib
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any

# k. At 64 the standard error of the Jaccard estimate is about sqrt(J(1-J)/64),
# ~0.06 at J = 0.5 — enough to order a foreign key above an unrelated serial key,
# for ~1 KB per column.
SAMPLE_SIZE = 64

# 64 bits of the digest: collisions are negligible below billions of distinct
# values, and a fixed-width lowercase hex string orders exactly as its number, so
# SQL and Python agree on "smallest" by plain string comparison.
HASH_HEX_DIGITS = 16

# The metric key the sample travels under, profile → Aran → /distance.
METRIC = "value_hash_sample"

# The top values (``DistributionAndCommonMetrics`` keeps ten): the whole value set
# only when the column has no more distinct values than the list holds.
_TOP_VALUES_METRIC = "freq_word_containment"


def sql_value_hash(expr: str) -> str:
    """The hash of one value, as SQL."""
    return f"substr(md5({expr}), 1, {HASH_HEX_DIGITS})"


def value_hash(value: str) -> str:
    """The hash of one value, as :func:`sql_value_hash` computes it (md5 of UTF-8)."""
    # A value fingerprint, not a security boundary: md5 is chosen for its stability.
    return hashlib.md5(value.encode("utf-8"), usedforsecurity=False).hexdigest()[:HASH_HEX_DIGITS]


@dataclass(frozen=True, slots=True)
class ValueSample:
    """A column's bottom-k hashes; ``complete`` when they are its whole value set."""

    hashes: frozenset[str]
    complete: bool


def sample_of(metrics: Mapping[str, Any]) -> ValueSample | None:
    """The column's value sample, or None when its profile carries none.

    A profile persisted before the sample existed still carries its top values, and
    where the column has no more distinct values than that list holds, the list IS
    the value set — so it is hashed into a complete sample and those small columns
    (the e2e corpus's) score exactly as before. Anything else has no sample of its
    value set at all, and None is the honest answer: the top values are exactly the
    evidence this module exists to stop scoring from.
    """
    cardinality = _int(metrics.get("cardinality"))

    persisted = _strings(metrics.get(METRIC))
    if persisted:
        hashes = frozenset(persisted)
        return ValueSample(hashes, complete=len(hashes) >= cardinality)

    top = _strings(metrics.get(_TOP_VALUES_METRIC))
    if top and cardinality <= len(set(top)):
        return ValueSample(frozenset(value_hash(v) for v in top), complete=True)
    return None


def jaccard(a: ValueSample, b: ValueSample) -> float:
    """Jaccard, shared / union: exact when both samples are complete, else the bottom-k estimate.

    The k smallest hashes of the columns' union all lie in the union of the two samples
    (a value among the k smallest of the union is among the k smallest of whichever
    column holds it), and such a value is in both columns exactly when it is in both
    samples — so the fraction of those k found in both estimates J. k is the smaller
    incomplete sample's size: a sample shorter than k is not a bottom-k of anything
    larger.
    """
    union = a.hashes | b.hashes
    if not union:
        return 0.0
    if a.complete and b.complete:
        return len(a.hashes & b.hashes) / len(union)
    k = min(len(s.hashes) for s in (a, b) if not s.complete)
    smallest = sorted(union)[:k]
    shared = sum(1 for h in smallest if h in a.hashes and h in b.hashes)
    return shared / len(smallest)


def multiset_jaccard(a: ValueSample, b: ValueSample) -> float:
    """The paper's MJ, |A ∩ B| / (|A| + |B|), which is J / (1 + J) for sets.

    Kept on that scale (identical sets → 0.5) because it is what the truncnorm
    prior in ``rank()`` is calibrated to.
    """
    j = jaccard(a, b)
    return j / (1.0 + j)


def _int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _strings(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, Iterable):
        return [str(v) for v in value if v is not None]
    return []

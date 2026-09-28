"""
Wrapper-over-``snapshot`` binding — the substitution machinery shared by
every executor.

The profiler's SQL builders (``freyja/profiling/metrics.py``) always name
the profiled relation with the bare, unquoted identifier ``snapshot``.
Wrapper-output profiling (Phase 3B, §3.1) redirects that reference to run
against the wrapper's OUTPUT instead, by substituting the resolved wrapper
text, parenthesized as a derived table, for every bare ``snapshot``
reference — without the underlying engine (aran-lake, or a local DuckDB
scan) learning anything new.

Extracted from ``freyja/executors/lake.py`` when the federated scan
executor (``freyja/executors/scan.py``) gained the same need: two copies of
a TOKEN-based substitution plus its collision guard would be exactly the
drift hazard the guard's own docstring warns about — the check and the
substitution are only safe because they share one pattern.

The substitution is TOKEN-based, not AST-based: it cannot tell a bare
relation reference from a quoted *column* reference spelled the same way
(e.g. ``"snapshot"`` in ``COUNT("snapshot")``), because both are just the
letters s-n-a-p-s-h-o-t to a regex. For a wrapper's OUTPUT columns this is
not a theoretical gap — a wrapper is free to alias any column to
``snapshot`` (a snapshot-timestamp column is an entirely ordinary name in
this domain), and if that were allowed through, the substitution would
corrupt the composed metrics SQL into a bind error. That is why executors
re-check the wrapper's resolved output column names against this exact
regex (``reject_colliding_columns``) and refuse to proceed if any collide.
"""
from __future__ import annotations

import re

# Word boundaries keep this from matching inside `snapshot_id` or similar.
SNAPSHOT_REF = re.compile(r"\bsnapshot\b", re.IGNORECASE)

# Alias for the wrapper-output derived table substituted in place of a bare
# `snapshot` reference. Scoped to the single query it's substituted into;
# never referenced by name from outside that query.
WRAPPER_ALIAS = "_wrapper_output"


class WrapperSqlRejected(ValueError):
    """
    Raised when ``wrapper_sql`` fails FREYJA's cheap pre-flight check.

    FREYJA does not parse wrapper SQL — Aran's ``WrapperSqlValidator``
    already did, before this ever reached the sidecar. This only rejects
    the one thing that would
    let a single ``${SNAPSHOT}``-bodied view smuggle in a second
    statement (a semicolon), and — lazily, once the wrapper's resolved
    output schema is known — an output column that would itself match
    ``SNAPSHOT_REF``.
    """


def resolve_wrapper_sql(wrapper_sql: str | None) -> str | None:
    """
    Resolve Aran's ``${SNAPSHOT}`` placeholder to the bare identifier
    ``snapshot`` — the executors' own bound view name — after the
    single-statement check.

    Raises ``WrapperSqlRejected`` on a semicolon. Callers must run this
    BEFORE allocating any resource the rejection would leak.
    """
    if wrapper_sql is None:
        return None
    if ";" in wrapper_sql:
        raise WrapperSqlRejected(
            "wrapper_sql must be a single statement (no semicolons)"
        )
    return wrapper_sql.replace("${SNAPSHOT}", "snapshot")


def substitute_wrapper(sql: str, resolved_wrapper_sql: str | None) -> str:
    """
    Redirect the profiler's ``snapshot`` reference to the bound wrapper's
    output. A no-op when no wrapper is bound — this is what keeps
    behaviour byte-identical for every caller that never sets
    ``wrapper_sql``.

    Never run this over already-resolved wrapper SQL (a ``DESCRIBE`` an
    executor builds itself): the resolved text legitimately contains the
    bare word ``snapshot`` meant to survive untouched, and re-running the
    substitution would double-substitute it.
    """
    if resolved_wrapper_sql is None:
        return sql
    replacement = f"({resolved_wrapper_sql}) AS {WRAPPER_ALIAS}"
    # A function replacement, not a template string: `replacement` is
    # caller-adjacent SQL text (Aran-screened, but not ours), and
    # re.sub treats backslashes in a *string* replacement as group
    # references — a literal backslash in wrapper_sql would corrupt it.
    return SNAPSHOT_REF.sub(lambda _match: replacement, sql)


def reject_colliding_columns(columns: list[tuple[str, str]]) -> None:
    """
    Refuse a wrapper whose OUTPUT includes a column that would itself
    match ``SNAPSHOT_REF`` — e.g. ``SELECT ts AS snapshot FROM
    ${SNAPSHOT}``, an entirely ordinary alias in this domain. Left
    unchecked, ``substitute_wrapper`` would substitute *inside* that
    column's quoted reference in the composed metrics SQL too (the regex
    cannot tell a column reference from a relation reference), corrupting
    it into a query DuckDB can still parse but not bind. Checked once,
    right after the wrapper's real output schema is known and before any
    metrics SQL referencing these columns is ever composed or sent — safe
    specifically because this check and the substitution share one
    pattern, so they can't drift apart.
    """
    for name, _sql_type in columns:
        if SNAPSHOT_REF.search(name):
            raise WrapperSqlRejected(
                f"wrapper output column {name!r} may not be named "
                "'snapshot' — it collides with the profiling relation "
                "binding"
            )

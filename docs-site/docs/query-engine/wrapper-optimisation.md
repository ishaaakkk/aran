---
title: 'Optimizable and opaque wrappers'
sidebar_label: 'Optimizable wrappers'
sidebar_position: 2
description: 'What the badge on the Wrapper tab means, how a wrapper earns Optimizable, why one reads Opaque, and when the verdict is re-checked.'
# Diátaxis mode: explanation
---

A wrapper is the SQL that shapes one source into an integrated class. Aran
must run it. Aran would also like to plan *through* it, so a filter in your
query can travel inside the wrapper instead of waiting above it. Planning
through a wrapper needs proof that Aran's own version of that SQL returns
exactly the same rows. Aran runs that proof and records the verdict. The
**Wrapper** tab shows the verdict as a badge.

## The two verdicts

| Badge | Meaning |
| --- | --- |
| **Optimizable — verified round-trip** | Aran can plan through this wrapper. A filter in your query can travel into it. |
| **Opaque — `<reason>`** | Aran runs this wrapper as written, and cannot see inside it. The reason states why. |
| **Opaque — not yet verified** | Aran has not checked this wrapper yet. Bootstrapping writes a wrapper for every source, and only a save or a rebuild checks one. |

Opaque is never an error. An opaque wrapper returns exactly the same rows as
an optimizable one. Aran simply plans a less efficient path to them.

On the wire, the two verdicts are the `expansionStatus` values `EXPANDED`
(the *Optimizable* badge) and `OPAQUE`.

## How a wrapper earns Optimizable

Aran checks five things, in order. A wrapper must pass all five.

1. **The SQL is allowed.** The same screen every wrapper gets — see
   [Write wrapper SQL](../how-to/write-wrapper-sql.md#what-is-allowed).
2. **The class is in the active schema.** The catalog must hold a source
   view for this dataset and class pair. Without one, the verdict is
   `verify unavailable: no source view found for dataset '<id>' and class
   '<iri>' in the active schema.` Promote the model, then save again.
3. **The SQL plans over the source's columns.** Aran plans your text against
   the wrapper's own `src_` relation. A construct Aran's planner does not
   recognise stops it here.
4. **The shape is a projection.** Only a chain of project, filter, and sort
   over the single `src_` scan is rebuildable. An aggregate, a sub-query, a
   join, or a window function is a different shape, and Aran refuses it —
   even when it would round-trip. The planner would never expand that shape,
   so the badge must not promise it.
5. **The rows match.** Aran runs your SQL and its own unparse of your SQL
   against the real snapshot, and compares a count and an order-independent
   hash. Both fingerprints run this statement:

   ```sql
   SELECT COUNT(*) AS c, COALESCE(SUM(hash("__aran_rt")), 0) AS h
   FROM (<sql>) AS "__aran_rt"
   ```

Step 5 is the one that costs: two full scans of the snapshot. Aran runs it
when you save the wrapper, and again at every **full rebuild** for each
wrapper that is Optimizable, never verified, or Opaque for a retryable
reason. The rebuild logs the cost: `Re-verified {} wrapper verdict(s) at
compile in {} ms`. A wrapper whose Opaque reason is decided by its own text
is skipped — the text has not changed, so the verdict cannot.

You can run the check yourself, before saving, through the `query-engine`
operator's `verify-wrapper` operation. It takes `datasetId`, `classIri`,
and `sql`, and returns the verdict it would persist.

## Why a wrapper reads Opaque

The badge shows the first 80 characters of the reason; hover it for the
full text. Reasons longer than 512 characters are truncated with a trailing
`…` before they are stored. The persisted reasons:

| Reason | What to do |
| --- | --- |
| `forbidden construct: forbidden function call: <name>` | Remove the named function. Reading the snapshot is the engine's job. |
| `forbidden construct: a string literal cannot be used as a table reference (DuckDB would read it as a file)` | Remove the file path. `FROM` must be `${SNAPSHOT}` alone. |
| `wrapper does not plan cleanly over its src_ relation — see the server log for the parse/validate/convert failure or unusable construct.` | The SQL uses something Aran's planner cannot read. Simplify it, or accept Opaque. |
| `not a shape-preserving projection: it plans to a <node>, which the planner will never expand.` | The wrapper aggregates, joins, or nests. Move that work into the query instead. |
| `round-trip mismatch: user SQL fingerprinted to (count=…, hash=…) but the expansion fingerprinted to (count=…, hash=…)` | Your SQL and Aran's version of it return different rows. Aran keeps yours and refuses to plan through it. |
| `verify unavailable: <cause>` | Aran could not run the check — most often because the class is not in the active schema yet. Promote the model, then save the wrapper again. |
| `stale: the snapshot was refreshed after this verdict was computed — it will be re-verified at the next compile` | The dataset's data changed after the verdict. Rebuild the model to re-earn it. |
| `stale: could not re-verify — verify unavailable: <cause>` | A rebuild tried to re-earn the verdict and could not run the check. The wrapper was Optimizable before; it is Opaque until a rebuild's re-verify succeeds. |

## What Optimizable costs

An Optimizable verdict opts the class out of two things:

- **Wrapper-output profiling.** The compile does not profile the wrapper's
  resolved output for an Optimizable class; its snapshot profiles stand in.
- **Constraint discovery.** **Discover constraints** returns nothing for a
  class whose wrapper is Optimizable, and does not fall back to the raw
  snapshot. See [Denial constraints in practice](./denial-constraints.md).

## Optimizable is a verdict, not a promise

The planner checks again, for every query. It declines to plan through an
optimizable wrapper in seven cases:

1. **The class enforces a denial constraint.** The recorded reason:
   *the class enforces N denial constraint(s), which buildDdl applies inside
   the u_ view body and an expansion would read straight past*. Aran applies
   constraints inside each source's own view; planning through the wrapper
   would read past that view.
2. **The class types a column as a decimal or a timestamp.** The recorded
   reason: *the class types column 'X' as `<T>`, which this projection
   cannot reproduce*. Aran's planner reproduces exactly the types `BIGINT`,
   `DOUBLE`, `BOOLEAN`, and `DATE`; `DECIMAL` unparses as `DECIMAL(19,0)`
   where the view writes `DECIMAL(38,9)`, and `TIMESTAMP` as
   `TIMESTAMP(0)`, so neither is safe to expand.
3. **The wrapper does not output a column the mapping promises.** The
   recorded reason: *it outputs no column 'X', which its mapping promises —
   the wrapper text and the mapping disagree*. The neighbouring case — a
   member of a multi-source class that does not supply a column the class
   types as text — is refused too: *it does not supply column 'X', whose
   type the class does not know*.
4. **The wrapper does not plan at plan time.** Aran records *could not be
   expanded at plan time*.
5. **The planned tree contains a node the planner cannot rebuild.** The
   recorded reason: *it plans to a `<node>`, which the planner cannot
   rebuild in its own cluster* — the plan-time form of the projection-shape
   gate in [How a wrapper earns
   Optimizable](#how-a-wrapper-earns-optimizable).
6. **A text column arrives from the wrapper as a different type.** The
   recorded reason: *column 'X' arrives as `<T>` and the class does not know
   its type*.
7. **The class already types a column exactly as the wrapper produces it.**
   The recorded reason: *the wrapper's own plan already carries column 'X'
   as `<T>`, so the TRY_CAST buildDdl writes would be optimized away as
   redundant*. Expanding would drop the conversion the view performs, and a
   dirty cell would then fail the query instead of degrading to NULL.

In every case Aran runs the wrapper as written, and the query returns the
same rows. Aran logs one WARN per `(dataset, reason)` pair, in this form,
and DEBUG thereafter:

```
Wrapper for dataset '<id>' is recorded EXPANDED but <reason>, so class
'<class>' scans it as an opaque view instead: <detail>
```

## When the verdict is re-checked

| Event | Effect on the verdict |
| --- | --- |
| You save a wrapper | Aran checks it now and stores the result. |
| You refresh the dataset | Every **Optimizable** verdict on that dataset drops to Opaque immediately, with a `stale:` reason. |
| You run **Rebuild draft schema** | Aran re-checks every Optimizable, never-checked, and retryable-Opaque wrapper. A verdict it cannot re-check drops to `stale: could not re-verify — verify unavailable: <cause>`. |
| You run **Add without re-reading the others** | Aran re-checks **nothing**. The stored verdict is carried through as it stands. |
| A wrapper is Opaque because of its own text | Aran does not re-check it. The text decides the verdict, and the text has not changed. |

A wrapper can hold a stale badge in one situation: you grow a model with
incremental adds only, and never rebuild it. The badge then reports what was
true at the last save or rebuild. Run **Rebuild draft schema** to bring
every badge up to date.

A stale badge never costs you a wrong row. The planner re-checks the wrapper
at plan time, and falls back to the opaque path whenever the stored verdict
no longer holds.

## Known limitations

- **An incremental add does not re-check any verdict.** A full rebuild does.
- **An upgrade of the engine does not re-check any verdict.** A rebuild is
  the only way to re-earn one after an upgrade.
- **The row comparison is a hash comparison.** Two genuinely different
  results whose row hashes sum to the same total would both pass. The
  probability is negligible, and the planner's own plan-time check stands
  behind it.
- **A decimal or timestamp column blocks planning through the wrapper**, even
  when the badge reads Optimizable. The badge does not check for it; the
  planner does.
- **An enforced denial constraint blocks planning through the wrapper** the
  same way, and the badge does not check for it either.

## Related

- [Write and edit wrapper SQL](../how-to/write-wrapper-sql.md) — writing the SQL this page judges.
- [The two wrapper fields](../deep-dives/wrapper-fields.md) — which copy of the wrapper runs.
- [Typed columns and cost-based plans](./typed-columns-and-costing.md) — where the decimal and timestamp rule comes from.
- [Denial constraints in practice](./denial-constraints.md) — the enforced-constraint gate, and what discovery skips.

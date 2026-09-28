---
title: 'Comment philosophy'
sidebar_label: 'Comment philosophy'
sidebar_position: 3
description: 'Why this repository comments the way it does, and the rule a reviewer will apply to a new comment.'
# Diátaxis mode: explanation
---

Comment density in this repository ranges from about 5% in `Dataset.java` to
about 52% in `AranRules.java`. That spread is not inconsistency to fix. It is
what happens when the rule is about *content*, not *quantity*.

## Explain why, never what

If a comment restates the signature or the next line, delete it. A record
whose components are self-evident gets no per-field javadoc.

```java
// Bad — restates the code
/** The tenant id. */
String tenantId;

// Good — says something the code cannot
// Pinned at open, not later: both lockdown sequences end in
// SET lock_configuration=true, after which no SET is accepted, so a later
// pin would silently do nothing on exactly the paths that run user SQL.
st.execute("SET TimeZone='UTC'");
```

## Comment the non-obvious decision, and say what breaks without it

`AranRules` is 52% comment because a debugging cycle found the reason behind
nearly every rule it adds or excludes. That reason is worth its length.

The test of a good comment here: would removing the code it guards produce a
mystery? Two examples from the codebase:

> Two copies of credential-string assembly is exactly the hazard `Names` warns
> about for view names: a drifted copy here would not produce a compile error, it
> would produce a probe that connects somewhere subtly different from where
> queries go — and then reports no drift, forever.

> The enforced constraints are applied HERE, per member, rather than once above
> the union. Selection distributes over union — σ(A ∪ B) = σ(A) ∪ σ(B) — so a
> union of filtered members is exactly `int_`. Without this, a planner that unions
> the members directly would silently stop enforcing denial constraints and
> surface quarantined rows.

Both name a specific failure the reader would otherwise have to rediscover.
Neither describes what the code does.

**Density is an outcome, not a target.** Do not pad a file toward 52%, and
do not strip a file down toward 5%.

## Prefer a test to a paragraph

A claim that can be asserted should be a test with a `@DisplayName`, not a
comment. Comments do not fail when they go stale. Tests do.

If you find yourself writing "this must always hold because…", stop. Write
the assertion instead.

How this repository scopes security-relevant code follows the same logic.
The query engine's read-only SQL guard is package-private rather than
private, with a comment saying why: *"this is a security boundary and its
attack strings must be exercisable without a live catalog."* A boundary
reachable only through a full invocation is a boundary nobody tests.

## Name the artefact, not the anecdote

"F14 — the hint must be the uploaded filename" is useful. "changed this
because it broke" is not. Reference the finding, the ticket, the paper
section, the rule name — something a reader can look up.

## In review

A reviewer will ask, of any new comment:

1. Does it say something the code does not already say?
2. Would removing the guarded code produce a surprise this comment prevents?
3. Could it be a test instead?
4. Will it still be true after a plausible refactor?

A "no" to the first, or a "yes" to the third, means it should not be there.

## Related

- [Module layout](./module-layout.md) — the invariants most worth commenting.
- [Build and test](./build-and-test.md) — where the test that replaces a paragraph goes.

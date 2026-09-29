---
title: 'Align and version'
sidebar_label: 'Align and version'
sidebar_position: 3
description: 'How Aran proposes joins from profile evidence, how you map source schemas onto one integrated vocabulary, and how every promoted version is kept.'
# Diátaxis mode: explanation
---

This is where separate sources become one model. It is the part of Aran that
replaces per-source transformation code: you propose, accept and map, and Aran
keeps a versioned history of the result.

## Propose joins

Join discovery compares column profiles across two datasets and proposes a
relationship where two value domains overlap. Each proposal carries a join
score and the evidence behind it. You accept or reject it. An accepted join is
recorded as a named relationship between two classes.

## Align schemas

You map source classes and properties onto shared ones. Two sources mapped to
the same integrated class are combined with `UNION ALL`: duplicate rows are
kept, and a property one source lacks is NULL in its rows. A join property
links two different classes. The result is the **integrated schema**, a graph
you can query.

## Compile, promote and roll back

Compiling applies the accepted alignments and writes a draft of the integrated
schema. Promoting the draft makes it the active version, and the query engine
derives one SQL view per integrated class from it. Aran keeps every promoted
version, so you can compare any two and roll back.

When a source changes shape in a way that breaks the active schema, Aran does
not apply the change. It marks the dataset `DRIFTED`, and the active version
keeps answering queries until you repair the mappings or accept the new shape.

## Full and incremental integration

Aran builds the next draft in one of two ways:

| | Full rebuild | Incremental add |
| --- | --- | --- |
| Reads | Every enrolled source | The active schema and one new source |
| Applies | Every accepted alignment | The accepted alignments not yet applied |
| Wrapper verdicts | Re-checks them | Carries the stored ones through |

The incremental add is quicker on a large model. Aran refuses it when no schema
has been promoted yet, when the model's promoted schema is no longer the active
one, or when integrated material changed since the last promote; run a full
rebuild then. Both produce a draft that you promote. See [Integrate incrementally](../how-to/integrate-incrementally.md).

## Constrain

A [denial constraint](../how-to/configure-denial-constraints.md) is a rule that
rows of an integrated class must satisfy. One constraint covers every source
mapped into the class. The engine keeps passing rows in the integrated view and
puts failing rows in a quarantine view. A row whose tested value is NULL passes
neither test and appears in neither view; see
[NULL fails the test](../query-engine/denial-constraints.md#null-fails-the-test).

## Next

- [Run join discovery](../how-to/run-join-discovery.md)
- [Align and integrate schemas](../how-to/align-and-integrate-schemas.md)
- [Integrate incrementally](../how-to/integrate-incrementally.md)
- [Configure denial constraints](../how-to/configure-denial-constraints.md)
- [Core concepts](../overview/core-concepts.md)

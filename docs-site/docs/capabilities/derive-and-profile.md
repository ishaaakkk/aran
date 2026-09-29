---
title: 'Derive and profile'
sidebar_label: 'Derive and profile'
sidebar_position: 2
description: 'How Aran derives a schema and a SQL wrapper from each dataset and computes the column statistics that drive joins and query costs.'
# Diátaxis mode: explanation
---

Once a dataset is in, Aran reads it and works out what it contains. You do not
write a schema by hand. This page explains the three things Aran derives: a
schema, a wrapper and a profile.

## Bootstrapping

The bootstrapping operator reads a snapshot and writes an RDF description of
it to the schema graph. That description has one class per record type and one
property per field. It also records the format and how to iterate the records.

## The wrapper

Each source class gets a **wrapper**: a single `SELECT` over `${SNAPSHOT}` that
says how to read the class. Aran generates it and you can edit it in the
Wrapper tab, for example to rename a column or cast a type. The tab previews
the rows your edited wrapper returns before you save it.

When you save a wrapper, Aran does two things:

1. It screens the SQL. Only one `SELECT` over `${SNAPSHOT}` passes, and any
   function that reads files, the network or another database is refused.
2. It checks whether the query planner can rebuild the wrapper as part of its
   own plan. A wrapper that passes is *optimisable*: the optimiser can push a
   query's filters through it to the data it reads, instead of running the
   whole wrapper first. Any other wrapper is *opaque* and runs as written.

[Wrapper optimisation](../query-engine/wrapper-optimisation.md) explains which
wrappers qualify.

## Profiling

The FREYJA sidecar profiles every column of a snapshot right after
bootstrapping. A profile holds the number of distinct values, uniqueness, the share of missing
values, frequency and length statistics, the most common values and a sample
of value hashes. These numbers do two jobs. They feed join discovery, and they
give the query optimiser measured row counts and selectivities instead of
guesses.

## Next

- [Bootstrap and profile a dataset](../how-to/bootstrap-and-profile-a-dataset.md)
- [Write wrapper SQL](../how-to/write-wrapper-sql.md)
- [Bootstrapping and the schema graph](../deep-dives/bootstrapping-and-the-schema-graph.mdx)
- [Wrapper optimisation](../query-engine/wrapper-optimisation.md)

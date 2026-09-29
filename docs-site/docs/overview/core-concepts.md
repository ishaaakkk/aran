---
title: 'Core concepts'
sidebar_label: 'Core concepts'
sidebar_position: 2
description: 'Repositories, datasets, snapshots, the schema graph, integrated classes, wrappers, and OQL: the vocabulary the rest of the documentation assumes.'
# Diátaxis mode: explanation
---

These are the nouns the rest of the documentation uses without redefining them.
They appear in the order data moves through the platform, and each is easiest
to understand in terms of the one before it.

## Tenant, project and user

A **tenant** is the isolation boundary. Every row the platform owns carries a
tenant id. The platform resolves the active tenant from the caller's own
`membership` row, and never from a header, a parameter or a token claim a
caller could set. A user holds at most one membership, so there is no tenant
switcher. One tenant has exactly one active schema graph.

A **project** is an organisational grouping inside a tenant: a set of
repositories, datasets and integration models that belong together. Projects
partition work but not the schema graph.

A **user** authenticates against Keycloak and holds a role within the tenant
(`OWNER` and below). Users can also mint **API tokens** with a restricted scope
for programmatic access.

## Data repository

A **data repository** is a registered connection to a place where data lives:
a PostgreSQL server, an S3 bucket, a SaaS API reached through a Singer tap, or an
Iceberg catalog. Each has one of five types (`RELATIONAL_DB`, `FILE_SYSTEM`,
`OBJECT_STORAGE`, `SINGER_TAP`, `ICEBERG_REST`) and the connection settings that
type requires.

When you register a repository, the platform checks that it can reach it.
Registering does not put any data into Aran.

## Dataset and snapshot

A **dataset** is one concrete body of records drawn from a repository, such as
a table, an uploaded file or the result of an import. Everything downstream
operates on datasets. A dataset moves through the lifecycle `PENDING` →
`RUNNING` → `READY`, or `FAILED` / `CANCELLED`, plus a distinct `DRIFTED` state
described below.

A **snapshot** is the dataset's contents materialised in Aran's own lake, as
parquet, CSV or JSON. Every later stage (bootstrapping, profiling, query
execution) reads this stable local copy, so the origin system does not take
that load. The lake service (`aran-lake`) owns the snapshots.

**Drift** is what happens when a source changes shape after Aran has already
compiled the dataset into the active schema. `DRIFTED` is not `FAILED`: nothing
went wrong and there is nothing to retry. The previous source graph is still in
place and queries still work. A person must decide whether to repair the
affected mappings or accept the new shape.

## The schema graph

The **schema graph** is an RDF graph stored in Fuseki. It describes everything
the platform knows about a tenant's data. There is one active graph per tenant,
named `urn:aran:schema` inside a dataset named after the tenant. It holds two
kinds of content.

The bootstrapping operator derives **source schemas** automatically: one class
per record type in a dataset, one property per field, and metadata under the
`NextiaDI` namespace. That metadata says which dataset the class came from, its
format, how to iterate it and what SQL reads it. Source classes and properties
describe one source in that source's own vocabulary.

**Integrated schemas** are what you declare. An **integrated class** is a class
in the shared vocabulary, for example `IPaper`, and a **global property** is a
field in it. Both carry an extra `nx:IntegratedResource` type, which marks them
as integrated and not source-derived.

Every change produces a new **schema version**. Aran retains every version, so
you can diff a graph against its predecessor, and it detects drift against a
fingerprint.

## Mappings, alignment and integration

An **alignment** says that a source property means the same thing as a
global property. A **mapping** is the recorded form of that claim.

How you group the mappings has two consequences:

- Aran **unions** two source classes mapped to the same integrated class. A
  paper from a CSV and a paper from a JSON dump become rows of one class, and
  columns that only one source contributes are null for rows from the other.
- A **join property** links two integrated classes on a pair of key columns. It
  carries the two class IRIs and the two global property IRIs that are the join
  keys. The planner uses those directly and does not re-derive keys from source
  metadata.

**Compiling** (or promoting) an integration model writes the declared mappings
back into the schema graph and makes the resulting views live.

## Wrappers

A **wrapper** is the SQL that turns a raw snapshot into the flat relation an
integrated class expects. It selects columns, renames them and flattens nested
structures, and it always reads from the literal placeholder `${SNAPSHOT}`. At
run time the executor substitutes the actual source view for the placeholder.

```sql
SELECT doi AS paper_id,
       title,
       year,
       unnest(authors) AS author_id
FROM ${SNAPSHOT}
```

Aran validates wrapper SQL against an allow-list before it accepts a wrapper:
a single `SELECT` over `${SNAPSHOT}`, nothing else. It rejects `read_csv`,
`read_json`, `read_parquet`, `http_get` and `COPY`, because the executor reads
files and the wrapper does not.

Aran also judges whether it can plan through a wrapper, and shows the verdict
as a badge on the Wrapper tab. See [Optimizable and opaque
wrappers](../query-engine/wrapper-optimisation.md).

:::warning Two fields are called "wrapper"
Aran executes only one of them, and which one you are editing decides whether
the edit changes any query result. See
[The two wrapper fields](../deep-dives/wrapper-fields.md).
:::

## Views: `src_`, `w_`, `u_`, `int_`

Query execution builds five layers of DuckDB views: the four prefixed layers,
plus one source table per wrapper under its own plain name:

| Prefix | One per | What it is |
| --- | --- | --- |
| `src_<dataset>` | dataset | The raw snapshot, read with the function its format needs |
| `w_<dataset>_<class>` | dataset × source class | The wrapper SQL applied to that snapshot |
| `<source name>` (no prefix, e.g. `acm`) | dataset × source class | The wrapper's columns under their own names. SQL mode and the JDBC `sources` schema show this table |
| `u_<dataset>_<class>` | dataset × source class | The same rows projected onto the integrated class's column list, with denial constraints applied |
| `int_<class>` | integrated class | `UNION ALL` over every source feeding that class |

The `u_` layer exists so the planner can see the union. If only `int_` were a
named view, Calcite could scan it as one opaque table, and every per-source row
count would be aggregated away before the cost model saw it. Aran applies
constraints per member, not once above the union. This is sound, because
selection distributes over union, and it is necessary, because a plan that
unions the members directly would otherwise stop enforcing the constraints.

The union is always `UNION ALL`, never `UNION`, because plain `UNION` uses set
semantics and would drop real duplicate tuples that arrive from overlapping
sources.

A single normaliser sanitises names from IRIs, so a class IRI ending in
`IPaper` becomes `int_IPaper`. Columns inside `int_` views are named with the
full global property IRI, not the local name, which matters as soon as you
write SQL against them by hand. See [SQL fallback mode](../oql/sql-fallback.md).

## Profiles

A **column profile** is what the FREYJA sidecar computes for each column:
distinct-value counts, uniqueness, value domains and related statistics.
Profiles do two jobs. Join discovery compares them across datasets to propose
relationships where value domains overlap, and the query optimiser uses them as
its cost model. Without a profile the planner has no row counts and falls back
to Calcite's unknown-statistics behaviour. See [Typed columns and
cost-based plans](../query-engine/typed-columns-and-costing.md).

## Denial constraints

A **denial constraint** is a predicate that rows must satisfy, such as
`year >= 1990` or `author_id IS NOT NULL`. Constraints keep implausible values
out of an integrated view. The constraint-discovery operator can also propose
candidate constraints from the data, but a discovered candidate is display-only:
it cannot be enforced, so you write the equivalent constraint by hand (see the
[status page](./status.md) and
[Configure denial constraints](../how-to/configure-denial-constraints.md)).

## Operators

An **operator** is a platform capability with a stable contract: an id, a set
of named operations, and a parameter schema per operation. Everything the
platform does to data is an operator: `bootstrapping`, `profiling`,
`join-discovery`, `schema-integration`, `constraint-discovery`,
`mapping-catalog`, `query-engine`. Seven ids ship from six Gradle modules,
because `mapping-catalog` lives inside the query-engine module.

Component scanning discovers operators as plain Spring components. Each
operator receives a tenant id explicitly on the request instead of reading
ambient state, and it returns a uniform success/failure result. Aran tracks
long-running invocations as **jobs**, which report stage and percentage
progress.

## OQL

**OQL** is the query language you write against integrated classes. It looks
like SQL with one addition: joins are traversals of a named join property.

```
SELECT IPaper.title, IResearcher.name
FROM IPaper -[hasAuthor]-> IResearcher
WHERE IResearcher.affiliation = 'UPC'
```

The engine parses the query, plans it over the mapping catalog with Apache
Calcite, optimises the plan with a cost model built from the profiles, and
emits DuckDB SQL against the `int_` views. Everything the language accepts is
in the [syntax reference](../oql/syntax-reference.md), and execution is covered
in [the query pipeline](../deep-dives/query-pipeline.mdx).

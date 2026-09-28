---
title: 'Core concepts'
sidebar_label: 'Core concepts'
sidebar_position: 2
description: 'Repositories, datasets, snapshots, the schema graph, integrated classes, wrappers, and OQL: the vocabulary the rest of the documentation assumes.'
# Diátaxis mode: explanation
---

These are the nouns the rest of the documentation uses, without redefining
them. They appear in the order data moves through the platform. Each one is
easiest to understand in terms of the one before it.

## Tenant, project, and user

A **tenant** is the isolation boundary. Every row the platform owns carries a
tenant id. The platform resolves the active tenant from the caller's own
`membership` row. It never reads the tenant from a header, a parameter, or a
token claim a caller could set. A user holds at most one membership, so there is no tenant
switcher. One tenant has exactly one active schema graph.

A **project** is an organisational grouping inside a tenant: a set of
repositories, datasets, and integration models that belong together. Projects
partition work. They do not partition the schema graph.

A **user** authenticates against Keycloak. Each user holds a role within the
tenant (`OWNER` and below). Users can also mint **API tokens** for
programmatic access, with a restricted scope.

## Data repository

A **data repository** is a registered connection to a place where data lives:
a PostgreSQL server, an S3 bucket, a SaaS API reached through a Singer tap, or an
Iceberg catalog. Each has one of five types (`RELATIONAL_DB`, `FILE_SYSTEM`,
`OBJECT_STORAGE`, `SINGER_TAP`, `ICEBERG_REST`). It also has the connection
settings that type requires.

When you register a repository, the platform checks that it can reach it.
Registering does not, by itself, put any data into Aran.

## Dataset and snapshot

A **dataset** is one concrete body of records drawn from a repository, such as
a table, an uploaded file, or the result of an import. It is the unit everything
downstream operates on. It moves through a lifecycle: `PENDING` → `RUNNING` →
`READY`, or `FAILED` / `CANCELLED`, plus a distinct `DRIFTED` state described
below.

A **snapshot** is the dataset's contents materialised in Aran's own lake, as
parquet, CSV, or JSON. Every later stage (bootstrapping, profiling, query
execution) reads this stable local copy. The origin system does not take that
load. The lake service (`aran-lake`) owns the snapshots.

**Drift** is what happens when a source changes shape after Aran has already
compiled the dataset into the active schema. `DRIFTED` is not `FAILED`.
Nothing went wrong. There is nothing to retry. The previous source
graph is still in place. Queries still work. A human must decide whether to
repair the affected mappings or accept the new shape.

## The schema graph

The **schema graph** is an RDF graph stored in Fuseki. It describes
everything the platform knows about a tenant's data. There is one active
graph per tenant, named `urn:aran:schema` inside a dataset named after the
tenant. It holds two kinds of content.

The bootstrapping operator derives **source schemas** automatically: one
class per record type in a dataset, one property per field, and metadata
under the `NextiaDI` namespace. That metadata says which dataset the class
came from, its format, how to iterate it, and what SQL reads it. Source
classes and properties describe one source, in that source's own vocabulary.

**Integrated schemas** are what you declare. An **integrated class** is a
class in the shared vocabulary, for example `IPaper`. A **global property** is a
field in it. Both carry an extra `nx:IntegratedResource` type, which marks them
as integrated, not source-derived.

Every change produces a new **schema version**. Aran retains every version,
so you can diff a graph against its predecessor. Aran also detects drift
against a fingerprint.

## Mappings, alignment, and integration

An **alignment** says that a source property means the same thing as a
global property. A **mapping** is the recorded form of that claim.

Two consequences follow from how you group the mappings:

- Aran **unions** two source classes mapped to the **same integrated class**.
  A paper from a CSV and a paper from a JSON dump become rows of one class.
  Columns that only one source contributes are null for rows from the other.
- A **join property** links two integrated classes on a pair of key columns.
  It carries the two class IRIs and the two global property IRIs that are the
  join keys. The planner uses those directly. It does not re-derive keys from
  source metadata.

**Compiling** (or promoting) an integration model writes the declared
mappings back into the schema graph. It also makes the resulting views live.

## Wrappers

A **wrapper** is the SQL that turns a raw snapshot into the flat relation an
integrated class expects. It selects columns, renames them, and flattens
nested structures. It always reads from the literal placeholder `${SNAPSHOT}`.
The executor substitutes the placeholder with the actual source view, at run
time.

```sql
SELECT doi AS paper_id,
       title,
       year,
       unnest(authors) AS author_id
FROM ${SNAPSHOT}
```

Aran validates wrapper SQL against an allow-list before it accepts a wrapper:
a single `SELECT` over `${SNAPSHOT}`, nothing else. Aran rejects `read_csv`,
`read_json`, `read_parquet`, `http_get`, and `COPY`. The executor reads files;
the wrapper does not.

Aran also judges whether it can plan *through* a wrapper, and shows the
verdict as a badge on the Wrapper tab. See [Optimizable and opaque
wrappers](../query-engine/wrapper-optimisation.md).

:::warning Two fields are called "wrapper"
Aran executes only one of them. Which one you are looking at determines
whether editing it changes any query result. See
[The two wrapper fields](../deep-dives/wrapper-fields.md).
:::

## Views: `src_`, `w_`, `u_`, `int_`

Query execution builds five layers of DuckDB views: the four prefixed layers,
plus one **source table** per wrapper under its own plain name:

| Prefix | One per | What it is |
| --- | --- | --- |
| `src_<dataset>` | dataset | The raw snapshot, read with the function its format needs |
| `w_<dataset>_<class>` | dataset × source class | The wrapper SQL applied to that snapshot |
| `<source name>` (no prefix, e.g. `acm`) | dataset × source class | The wrapper's columns under their own names. SQL mode and the JDBC `sources` schema show this table |
| `u_<dataset>_<class>` | dataset × source class | The same rows projected onto the integrated class's column list, with denial constraints applied |
| `int_<class>` | integrated class | `UNION ALL` over every source feeding that class |

The `u_` layer exists so the planner can see the union. If only `int_` were a
named view, Calcite could scan it as one opaque table. Every per-source row
count would then be aggregated away before the cost model saw it. Aran
applies constraints per member, not once above the union. This is sound,
because selection distributes over union. It is also necessary: a plan that
unions the members directly would otherwise stop enforcing the constraints.

The union is always `UNION ALL`, never `UNION`. Plain `UNION` uses set
semantics, which would drop real duplicate tuples that arrive from
overlapping sources.

A single normaliser sanitises names from IRIs, so a class IRI ending in
`IPaper` becomes `int_IPaper`. Aran names columns inside `int_` views with the
**full global property IRI**, not the local name. This matters the moment you
write SQL against them by hand. See [SQL fallback mode](../oql/sql-fallback.md).

## Profiles

A **column profile** is what the FREYJA sidecar computes for each column:
distinct-value counts, uniqueness, value domains, and related statistics.
Profiles do two jobs. Join discovery compares them across datasets to propose
relationships where value domains overlap. The query optimiser uses them as
its cost model. Without a profile, the planner has no row counts, and it
falls back to Calcite's unknown-statistics behaviour. See [Typed columns and
cost-based plans](../query-engine/typed-columns-and-costing.md).

## Denial constraints

A **denial constraint** is a predicate that rows must satisfy, such as
`year >= 1990` or `author_id IS NOT NULL`. Constraints keep implausible values
out of an integrated view. The constraint-discovery operator can also infer
candidate constraints from the data, so you do not have to write them all.

## Operators

An **operator** is a platform capability with a stable contract: an id, a set
of named operations, and a parameter schema per operation. Everything the
platform *does* to data is an operator: `bootstrapping`, `profiling`,
`join-discovery`, `schema-integration`, `constraint-discovery`,
`mapping-catalog`, `query-engine`. Seven ids ship from six Gradle modules:
`mapping-catalog` lives inside the query-engine module.

Component scanning discovers operators as plain Spring components. Each
operator receives a tenant id explicitly on the request. It does not read
ambient state. It returns a uniform success/failure result. Aran tracks
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

The engine parses the query. It plans the query over the mapping catalog,
with Apache Calcite. It optimises the plan with a cost model built from the
profiles. It emits DuckDB SQL against the `int_` views. Everything the
language accepts is in the [syntax reference](../oql/syntax-reference.md).
How it gets executed is in [the query pipeline](../deep-dives/query-pipeline.mdx).

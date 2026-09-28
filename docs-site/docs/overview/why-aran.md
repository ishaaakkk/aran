---
title: 'Why Aran'
sidebar_label: 'Why Aran'
sidebar_position: 2
description: 'What Aran adds next to a lakehouse or an ETL stack, who it is for, and who it is not for.'
# Diátaxis mode: explanation
---

If your organisation already runs Databricks, Snowflake, or a well-tended dbt
project, a fair question is what a data integration platform adds. This page
answers that question. For some organisations, the answer is "probably
nothing".

## A different problem

A lakehouse assumes the hard part is **compute over data you have already
centralised**. The integration itself is left to whoever writes the
transformation code. That person decides that `author_id` in one export is
`authors[].id` in another, and that two feeds describe the same kind of thing.
That knowledge ends up *in programs*, spread across jobs and notebooks. You can
read it only by reading the code, and check it only by running the code again.

Aran assumes the hard part is **heterogeneity**: many sources, none huge, none
agreeing on format, naming, or shape. So Aran treats the integration itself as
the artifact:

- **Derived from the data.** Registering a dataset
  [bootstraps](../how-to/bootstrap-and-profile-a-dataset.md) a schema from
  the data: classes, properties, nested structure, and the SQL to read the
  snapshot back. You start from a description, not a blank notebook.
- **Joins are proposed from evidence.** Column profiles drive
  [join discovery](../how-to/run-join-discovery.md). Aran suggests which
  columns join, with confidence scores computed from value-domain overlap.
  A lakehouse has no component that proposes your joins.
- **Declared as data.** The mappings you accept form a graph: a vocabulary of
  integrated classes, compiled into executable SQL. Because the integration is
  data, you can query it and diff two
  [versions](../how-to/align-and-integrate-schemas.md) of it. You can promote
  a version, roll it back, and detect when a source drifts out from under it.
- **Governed at the level of meaning.** A
  [denial constraint](../how-to/configure-denial-constraints.md) attaches to
  an integrated class, so one rule governs every source mapped into that
  class. Enforced constraints filter the views every query reads. Rows that
  violate a constraint go to a quarantine view.

In short, a lakehouse integrates infrastructure, and Aran integrates meaning.

## Doesn't AI generate all this now?

Increasingly, yes. Assistants attached to the major platforms can draft a
semantic model or a data pipeline from a natural-language prompt. Typing the
integration is no longer the hard part, for Aran or anyone else.

A generated model is plausible, but it is not verified. The assistant writes it
from names, comments, and examples, and it is right when it happens to be
right. Serious deployments of such assistants still depend on human curation
and benchmarking before anyone trusts the answers. Generation makes wrong
answers cheap too, so the expensive part of integration becomes *checking what
got written*.

Aran's proposals come with evidence you can check:

- A join suggestion is computed from profiled value-domain overlap and carries
  a confidence score.
- A wrapper's "optimizable" badge comes from a real row-count and row-hash
  round-trip against the data.
- An enforced constraint quarantines the actual rows that violate it.

The integration is a declared, versioned model, not generated code. So there
is something to check proposals *against*, whether a person or an AI
assistant wrote them.

## "We don't want another external tool"

You build the integration in Aran's UI. You consume it from tools you already
run:

- **[JDBC](../programmatic-access/jdbc.md).** Aran presents as a database over
  Avatica's wire protocol. Any JDBC client that loads the Avatica remote
  driver (`avatica-core`) and uses plain statements can connect. There is no
  Aran-specific driver. The protocol is **not** the Postgres protocol, and a
  tool that always prepares statements cannot run queries. DBeaver is the
  desktop client it is checked against. BI tools such as Metabase and
  Superset have not been tried.
- **[REST export](../how-to/export-query-results.md)** for scripts and
  pipelines, and a Python client for notebooks.
- **[MCP](../programmatic-access/mcp.md)** for agents. An agent reads curated,
  read-only [data products](../how-to/create-a-data-product.md), each recorded
  against the schema version it was defined on. That gives you a small,
  governed surface to hand an agent, self-hosted next to your data. The rows
  an agent reads still go to its model provider; see
  [where the rows go](../programmatic-access/mcp.md#where-the-rows-go).

Deployment follows the same rule. Aran is Apache-2.0 and self-hosted: one
compose file inside your own infrastructure, with your own identity provider
and your own storage. Aran reads sources in place or snapshots them into a lake
you operate. Adopting Aran moves no data out of the systems you already trust.
Removing it means deleting containers.

## Who it is for

Aran is for organisations whose problem is heterogeneity, not scale:

- research groups and universities
- SMEs without a platform team
- public-sector bodies
- consortia integrating each other's exports under data-sharing agreements
- teams inside larger organisations who need one queryable vocabulary over
  the forty sources that never made it into the warehouse

## Who it is not for

- **If your data already lives in one well-governed warehouse**, and your
  pain is compute, scheduling, or BI, stay there. Aran does not compete on
  any of those.
- **If your problem is petabyte scale**, Aran's engine is the wrong weight
  class. It plans with Calcite and executes on DuckDB, over snapshots or live
  relational scans.
- **If you need turnkey SaaS**, Aran is self-hosted today. A managed
  offering is future work, not a promise.

Aran is also pre-1.0. [What is Aran](./what-is-aran.md#what-is-implemented-today)
states what runs, what only connects, and what has not yet run against a live
system.

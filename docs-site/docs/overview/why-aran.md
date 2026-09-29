---
title: 'Why Aran'
sidebar_label: 'Why Aran'
sidebar_position: 2
description: 'What Aran adds next to a lakehouse or an ETL stack, who it suits, and who should use something else.'
# Diátaxis mode: explanation
---

If your organisation already runs Databricks, Snowflake or a well-kept dbt
project, you may wonder what an integration platform adds. For some
organisations the answer is nothing, and this page says which.

## The problem Aran targets

A lakehouse assumes the hard part is compute over data you have already
centralised. The integration itself falls to whoever writes the transformation
code. That person decides that `author_id` in one export is `authors[].id` in
another, and that two feeds describe the same kind of thing. The knowledge ends
up in jobs and notebooks. You can read it only by reading the code and check it
only by running the code again.

Aran targets heterogeneity: many sources, none huge, none agreeing on format,
naming or shape. It treats the integration as the artefact.

- **Derived from the data.** Registering a dataset
  [bootstraps](../how-to/bootstrap-and-profile-a-dataset.md) its classes,
  properties, nested structure and the SQL to read it back.
- **Joins proposed from evidence.** [Join discovery](../how-to/run-join-discovery.md)
  scores candidate joins by value-domain overlap in the column profiles.
- **Declared as data.** Accepted mappings form a graph that compiles to SQL.
  You can query it, [diff two versions](../how-to/align-and-integrate-schemas.md),
  roll back, and see when a source drifts.
- **Governed per concept.** A [denial constraint](../how-to/configure-denial-constraints.md)
  attaches to an integrated class, so one rule covers every source mapped into
  it. An enforced constraint filters the views every query reads, and the
  violating rows go to a quarantine view.

## AI can draft integrations now

Assistants attached to the major platforms can draft a semantic model or a
pipeline from a prompt. Typing the integration is no longer the hard part, for
Aran or anyone else. Checking it is.

A generated model is plausible and unverified. It comes from names, comments and
examples, so it is right when it happens to be right. Aran's proposals carry
evidence you can check:

- A join suggestion comes from measured value overlap and has a confidence score.
- A wrapper's *optimisable* badge comes from a row-count and row-hash comparison
  against the data.
- An enforced constraint quarantines the actual rows that break it.

Because the integration is a declared, versioned model, there is something to
check any proposal against, whether a person or an assistant wrote it.

## Fitting into your stack

You build the integration in Aran's web interface and read it from tools you already run.

- **[JDBC](../programmatic-access/jdbc.md).** Aran presents as a database over
  Avatica's wire protocol. Any client that loads the Avatica remote driver
  (`avatica-core`) and sends plain statements can connect. There is no Aran
  driver. The protocol is not the Postgres protocol, and a tool that always
  prepares statements cannot run queries. DBeaver is the client Aran is tested
  with. Metabase and Superset have not been tried.
- **[REST export](../how-to/export-query-results.md)** for scripts, and a
  [Python client](../programmatic-access/python-sdk.md) for notebooks.
- **[MCP](../programmatic-access/mcp.md)** for agents. An agent reads curated,
  read-only [data products](../how-to/create-a-data-product.md), each recorded
  against the schema version it was defined on. The rows an agent reads still go
  to its model provider. See [where the rows go](../programmatic-access/mcp.md#where-the-rows-go).

Aran is Apache-2.0 and self-hosted. One Compose file runs it inside your
infrastructure with your identity provider and your storage. It reads sources
in place or snapshots them into a lake you operate, so adopting it moves no data
out of systems you already trust, and removing it means deleting containers.

## Who it suits

Aran suits organisations whose problem is heterogeneity, not scale:

- research groups and universities
- SMEs without a platform team
- public-sector bodies
- consortia integrating each other's exports under data-sharing agreements
- teams in larger organisations who need one vocabulary over the forty sources that never reached the warehouse

## Who should use something else

- **Your data already sits in one governed warehouse** and your pain is compute,
  scheduling or BI. Aran does not compete there.
- **Your data is at petabyte scale.** Aran plans with Calcite and executes on
  DuckDB over snapshots or live relational scans, which is the wrong weight class.
- **You need turnkey SaaS.** Aran is self-hosted. A managed offering is not planned yet.

Aran is pre-1.0. The [status page](./status.md) states what has been run against
real services.

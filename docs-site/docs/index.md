---
title: 'Aran documentation'
sidebar_label: 'Home'
sidebar_position: 0
slug: /
description: 'Documentation for Aran, a platform that derives a schema for each of your data sources, proposes how they join, and lets you query them together.'
# Diátaxis mode: explanation (orientation only: this page routes, it does not teach)
---

# Aran documentation

Aran connects to files, databases, object storage, Iceberg catalogs and Singer
taps. It derives a schema for each source, proposes how they join, and gives you
one versioned vocabulary to query. You can read the result over JDBC, REST,
Iceberg REST and MCP.

## Try it

```bash
git clone https://github.com/dtak-upc/aran
cd aran
docker compose -f compose.standalone.yml up --build
```

Open `http://localhost:8080` and sign in as `alice`. The
[quickstart](./getting-started/quickstart.md) has the details, and the
[walkthrough](./getting-started/walkthrough.md) integrates three datasets in
about 45 minutes.

## The five capabilities

| | Capability | Read |
| --- | --- | --- |
| 1 | **Connect** sources, as snapshots or live | [Connect](./capabilities/connect.md) |
| 2 | **Derive and profile** a schema, wrapper and statistics for each | [Derive and profile](./capabilities/derive-and-profile.md) |
| 3 | **Align and version** the schemas into one vocabulary | [Align and version](./capabilities/align-and-version.md) |
| 4 | **Query** the vocabulary in OQL | [Query](./capabilities/query.md) |
| 5 | **Serve** saved queries as data products over JDBC, Iceberg REST and MCP, and run OQL from scripts and notebooks | [Serve](./capabilities/serve.md) |

## Find what you need

| If you want to | Read |
| --- | --- |
| Understand what the platform is for | [What is Aran](./overview/what-is-aran.md) |
| Understand the vocabulary the docs use | [Core concepts](./overview/core-concepts.md) |
| Know what is tested and what is not | [Status and limitations](./overview/status.md) |
| Decide whether Aran fits | [Why Aran](./overview/why-aran.md) |
| Write a query | [OQL tutorial](./oql/tutorial.md) |
| Understand how a query is planned and priced | [Typed columns and costing](./query-engine/typed-columns-and-costing.md) |
| Connect a SQL client, notebook or agent | [Connect your tools](./programmatic-access/index.md) |
| Deploy an instance | [Run Aran standalone](./administration/standalone.md) |
| Assemble a deployment yourself | [Production build and packaging](./administration/production-build.md) |
| Look up a setting | [Configuration reference](./administration/configuration-reference.md) |
| Change the code | [Contributor guide](./contributing/index.md) |

## How the pages are organised

The site follows [Diátaxis](https://diataxis.fr). Each page has one mode, given
in its front matter:

- **Tutorials** teach with a working example.
- **How-to guides** solve one problem for a reader who knows the system.
- **Reference** describes syntax, endpoints, settings and capabilities.
- **Explanation** says why the system works as it does.

A how-to guide links to an explanation page for background instead of repeating it.

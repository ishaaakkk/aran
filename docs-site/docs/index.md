---
title: 'Aran documentation'
sidebar_label: 'Home'
sidebar_position: 0
slug: /
description: 'Documentation for Aran, a platform for registering heterogeneous data sources, integrating their schemas, and querying across them.'
# Diátaxis mode: explanation (orientation only: this page routes, it does not teach)
---

# Aran documentation

Aran registers different data sources. It derives a schema for each
source. You align these schemas into one shared vocabulary. You then query
across every source through that vocabulary.

This documentation is organised by task.

## Start here

| If you want to… | Read |
| --- | --- |
| Understand what the platform is for | [What is Aran](./overview/what-is-aran.md) |
| Learn the vocabulary the rest of the docs assume | [Core concepts](./overview/core-concepts.md) |
| Get a local instance running | [Quickstart](./getting-started/quickstart.md) |
| Follow a complete integration end to end | [Full walkthrough](./getting-started/walkthrough.md) |
| Query an integrated schema | [OQL](./oql/index.md) |
| Understand how a query is planned and priced | [Typed columns and costing](./query-engine/typed-columns-and-costing.md) |
| Deploy or operate an instance | [Administration & Deployment](./administration/production-build.md) |
| Change the code | [Contributor guide](./contributing/index.md) |

## How these pages are organised

Each page uses exactly one of four modes. The front matter states the mode.

- **Tutorials** teach through a working example. They assume no prior
  knowledge. You can follow them line by line.
- **How-to guides** solve one problem. They assume you already know the
  system and know what you want.
- **Reference** describes the interfaces: syntax, endpoints, configuration
  keys, connector capabilities. Use it to look things up, not to read start
  to finish.
- **Explanation** covers why the system works this way. It contains no steps
  to run.

Pages never mix modes. When a how-to guide needs background, it links to the
explanation page instead of repeating it.

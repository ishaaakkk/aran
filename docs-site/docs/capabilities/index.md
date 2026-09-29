---
title: 'Capabilities'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'The five things Aran does, in the order data moves through it: connect, derive and profile, align and version, query, serve.'
# Diátaxis mode: explanation
---

Aran does five things. Each feeds the next, and each has its own section of this site.

| | Capability | What you get |
| --- | --- | --- |
| 1 | [Connect](./connect.md) | Files, relational databases, object storage, Iceberg catalogs and Singer taps registered as sources. |
| 2 | [Derive and profile](./derive-and-profile.md) | A schema, an editable SQL wrapper and column statistics for every source, generated from the data. |
| 3 | [Align and version](./align-and-version.md) | Join proposals backed by evidence, one integrated vocabulary stored as a graph, and a version history you can diff and roll back. |
| 4 | [Query](./query.md) | OQL over the integrated vocabulary, planned by Calcite and executed by DuckDB on snapshots or on the live source. |
| 5 | [Serve](./serve.md) | Data products readable over JDBC, Iceberg REST and MCP, and query results over REST export and the Python SDK. |

[Federated access](./federated-access.md), reading a source live instead of copying it, belongs to the Connect stage. [Connect your tools](../programmatic-access/index.md) covers reaching Aran from SQL clients, notebooks, agents and Iceberg engines.

Two features cut across these stages. [Denial constraints](../how-to/configure-denial-constraints.md)
attach a rule to an integrated class, and the query engine enforces it for every
source mapped into that class when you query the integrated schema. [Virtual data products](../deep-dives/virtual-data-products.md)
recompute on every read, so they always reflect the current constraints and sources.

Not every combination of source and stage is supported. The [status page](../overview/status.md)
lists what is verified, what is preview, and what is not supported.

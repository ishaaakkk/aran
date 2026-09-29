---
title: 'Connect an MCP client'
sidebar_label: 'MCP'
sidebar_position: 2
description: 'Let Claude Desktop, Claude Code, or another MCP client read your Aran data products.'
# Diátaxis mode: how-to
---

`aran-mcp` serves your Aran data products to an AI agent over the [Model
Context Protocol](https://modelcontextprotocol.io/). It runs on your own
machine, under your own API token. It ships no model, runs no inference and
holds no key of its own. The agent is yours.

## Where the rows go

The sidecar process is local and it calls your Aran server, but what it
returns does not stay local.

- Every row it returns enters the agent's context. A client that uses a
  hosted model (Claude Desktop, Claude Code, Cursor) sends that context to its
  model provider. Do not connect data your organisation may not send to that
  provider.
- The token is yours, not the product's. A `READ` token can read everything
  you can read in your organisation, over every surface that accepts a token,
  including data outside the products the agent lists. `ARAN_PROJECT` narrows
  the listing, not the token.

## What an agent can see

An agent sees only `READY` and `STALE` [data products](../how-to/create-a-data-product.md).
It never sees the live schema, or a product still `DRAFT` or `MATERIALIZING`,
because neither has rows to read yet. A `STALE` product is still served, with
its `materializedAt` date attached, so the agent knows the data may be old.

The agent sees every readable product the token can reach, because there is no
per-product publish switch. Creating a data product is what makes rows
visible to an agent, so you curate by deciding which products exist. Set
`ARAN_PROJECT` to narrow the listing to one project. That limits noise, not
access.

A [virtual product](../deep-dives/virtual-data-products.md) is `READY` from
the moment somebody creates it and needs no refresh. The sidecar reads it like
any other product, and every read recomputes it from the sources the active
schema points at: a live source as it is now, an uploaded file as of its last
upload or refresh.

There is no write tool. An agent cannot re-materialise a product or run
arbitrary OQL against the live schema. It has only the two read tools below.

## Install

You need Python **3.10 or newer** (the MCP SDK's floor) and a **source
checkout of Aran**. The sidecar is not published to PyPI and no wheels are
released yet, so a standalone (compose-only) install has nothing to run it
from until you clone the repository. From the root of that checkout, run:

```bash
make aran-mcp
```

This installs `aran-mcp` and its local sibling `aran-client` into
`aran-mcp/.venv`. A plain `pip install -e .` inside `aran-mcp/` fails,
because its `aran-client` dependency is a local package and not on PyPI.
Without `make`, install both together:

```bash
pip install -e ./aran-client -e "./aran-mcp[dev]"
```

## Create a token

1. Open your avatar at the bottom of the left rail and choose **API Tokens**.
2. Create a token. `READ` scope is sufficient, because every call the sidecar
   makes upstream is a GET.

## Configure your MCP client

Add `aran` to your client's server list. In Claude Desktop this is
`mcpServers` in `claude_desktop_config.json`, and Claude Code reads the same
shape. This is the configuration the app itself generates:

```json
{
  "mcpServers": {
    "aran": {
      "command": "/absolute/path/to/aran/aran-mcp/.venv/bin/aran-mcp",
      "env": {
        "ARAN_URL": "http://localhost:8081",
        "ARAN_TOKEN": "aran_pat_…",
        "ARAN_PROJECT": "<project-uuid>"
      }
    }
  }
}
```

- `command` must be an absolute path. A client launches the server as a
  subprocess and will not expand `~` or use your shell's PATH.
- Put the token in `env`, never in an argument: arguments show up in
  process listings.
- `ARAN_URL` is the app's own port, **8081** by default; the standalone
  compose publishes it on host port **8080**.
- `ARAN_PROJECT` is optional and accepts a project **id or name**.

The server also accepts the CLI flags `--url`, `--token`, `--project` and
`--allow-insecure`, each falling back to its environment variable.
`--allow-insecure` is the only way to reach a remote Aran over plain HTTP.
If `--url` or `--token` is missing, or on a configuration or connection
error, the process exits with code **2** and writes the message to **stderr**,
because stdout is the transport.

The transport is **stdio only** and nothing listens on a port. That is why
this runs locally and not as a remote connector: Claude Desktop's remote
connectors route through Anthropic's cloud and need a publicly reachable
host, which `localhost:8081` is not.

:::note Get this configuration from the app
Open a project's **Products** page and use its **⌁ agents** action. It
fills in `ARAN_URL` from the page origin and says whether a product is
ready for an agent. The suggested first prompt is
*"read the \{product\} product from aran"*.
:::

## What the agent can do

The server offers two tools and one resource. On `initialize`, the
server introduces itself to the agent's model with: *"Aran serves data
products: named, curated query results over an integrated model built from
heterogeneous sources … Each product records the schema version it was
defined against; a refresh re-runs it under the schema active then, so its
rows can change between refreshes. Call list_products first to see what
exists, then read_product to get rows. A product marked stale is readable,
but a newer schema version has been promoted since its snapshot was
built."*

| Kind | Name | Parameters | Returns (JSON) |
| --- | --- | --- | --- |
| Tool | `list_products` | none | `{count, products: [{id, name, description, rowCount, schemaVersionId, stale, materializedAt}]}` |
| Tool | `read_product` | `product` (name or id), `limit` (default 20) | `{product, id, schemaVersionId, stale, materializedAt, columns, columnIris, rowCount, truncated, rows}` |
| Resource | `aran://product/{product}` | `product` = name or id | `{id, name, description, oql, schemaVersionId, status, stale, rowCount, materializedAt}`: the contract, never rows |

- `read_product`'s `limit` is **clamped to [1, 500]** and never rejected: ask
  for 10 000 rows and you get 500. The limit applies to materialised and
  virtual products alike. `truncated` is true when Aran says more rows
  exist; from an older Aran, it is true when the reply hit the limit.
- `columns` are display names; `columnIris` are the full property IRIs.
- `{product}` is resolved as an **id first, then a unique name**.

Errors come back as tool **content** and not as protocol errors, so the agent
can read them and correct itself:

```json
{"error": "no readable data product named 'x'. Available: papers, stale_papers"}
```

```json
{"error": "2 data products are named 'papers' (prod-ready, prod-dup). Ask for it by id instead."}
```

A duplicate name is an error and not a guess, so ask by id.

`read_product` is not a bulk export path. For that, use the
[Python SDK](./python-sdk.md) or [JDBC](./jdbc.md).

A virtual product's read runs its query live, bounded by the limit you give.
The sidecar never returns more rows than you asked for, even from an older
Aran that ignored the limit for a virtual product.

An agent cannot tell a materialised product from a virtual one. Aran's wire
response carries the access mode, but neither `list_products` nor
`read_product` emits it, so advice such as "read a materialised product when
the row count matters" cannot be followed from inside the agent. Check the mode
in the interface or in `products._products.access` over [JDBC](./jdbc.md).

## Related

- [Create and materialise a data product](../how-to/create-a-data-product.md)
- [Materialised and virtual data products](../deep-dives/virtual-data-products.md): what an agent gets from each mode.
- [Connect your tools](./index.md)

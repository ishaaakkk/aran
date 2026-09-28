# Aran seminar demo, continued: ask an agent

## Scenario

This continues the [research-publications scenario](DEMO.md): the ACM CSV and Semantic Scholar
JSON paper sources, integrated under a shared `Paper` class. In DEMO.md you query that
integration yourself in OQL or SQL mode. Here you start after promotion and connect an agent
instead: Claude Desktop, Claude Code, or any other client that speaks MCP.

Aran ships no model and runs no inference. Your agent's own model does the reasoning, and Aran
answers its `list_products` and `read_product` calls with real rows.

> **Status.** Run every command below as written. The protocol logic of
> `aran-mcp/examples/demo.py` has been tested over a real stdio subprocess and a real HTTP round
> trip, against the loopback stub that `aran-mcp/tests/conftest.py` uses. The rows and titles
> shown below are examples.

## Prerequisites

Complete DEMO.md through **Step 1** (register the ACM and Semantic Scholar datasets) and align
them into one class. Either DEMO.md's fuller `IPaper` or the minimal `Paper` used here works:
the MCP side does not depend on which OQL a product is built from. Promote the schema.

## Step 1: Create and materialize a data product

**Products → New product.** Name it `Papers` and give it the simplest useful query over what you
just integrated:

```
SELECT Paper.title FROM Paper
```

*(After you have built DEMO.md's `Int_researchers`/`hasAuthor` join, you can use one of its
aggregate queries instead, for example A4, "most prolific researchers". The steps below are the
same.)*

Click **Materialize**. Products are pull-refreshed: an agent that reads the product later does
not trigger a refresh. It reads the snapshot that already exists.

## Step 2: Mint a token

Key icon (left rail) → **API tokens** → **New token**, scope **READ**. Copy the secret; it is
shown only once. `READ` is enough, because the sidecar only ever sends `GET` requests.

## Step 3: Install and connect the sidecar

```bash
make aran-mcp          # from the repo root; creates aran-mcp/.venv
```

Open the `Papers` product, click **⌁ agents**, and copy the config it shows. It looks like this,
with real values in place of the placeholders:

```json
{
  "mcpServers": {
    "aran": {
      "command": "/absolute/path/to/aran/aran-mcp/.venv/bin/aran-mcp",
      "env": {
        "ARAN_URL": "http://localhost:8081",
        "ARAN_TOKEN": "aran_pat_…",
        "ARAN_PROJECT": "<your project id>"
      }
    }
  }
}
```

Paste it into your client's config (for Claude Desktop, `claude_desktop_config.json`), fill in the
real path and the token from Step 2, and restart the client.

## Step 4: Ask it something

> What data products can you read from Aran?

The agent calls `list_products`. It should name `Papers`, give its row count, and say that it is
fresh.

> Read the Papers product and tell me a few of the titles.

The agent calls `read_product("Papers", limit=…)` and should quote real titles back. Check them
against `acm_papers.csv` and `semantic_papers.json` in this directory. The tool returns only real
rows, so a title that is not in those files did not come from Aran.

## What just happened

Two tool calls, nothing else:

| Your prompt | Tool call | What came back |
|---|---|---|
| "What products…" | `list_products()` | name, id, row count, pinned schema version and staleness of every product your token can read |
| "Read the Papers…" | `read_product("Papers", limit=N)` | columns and rows, capped at 500 |

Nothing is ever written; there is no tool for it. The agent cannot run arbitrary OQL either. It
can only read *this* product, in the shape its OQL and pinned schema version define, so its
answers do not change shape when someone re-integrates a source.

## Try it without an agent client

`aran-mcp/examples/demo.py` runs the same protocol round trip from the command line, with no
Claude Desktop install or config file:

```bash
ARAN_URL=http://localhost:8081 ARAN_TOKEN=aran_pat_… make mcp-demo
```

It prints each tool call and the JSON that comes back: `initialize`, `list_tools`,
`list_products`, `read_product`, and the `aran://product/{name}` resource, in the same order
this document walks through them.

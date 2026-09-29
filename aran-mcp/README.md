# aran-mcp

An [MCP](https://modelcontextprotocol.io) server that exposes Aran data products to an
agent you already run: Claude Desktop, Claude Code, Cursor, or anything else that speaks
the protocol.

Aran ships no model and runs no inference. This is a protocol adapter: your client's
model does the language part, and Aran answers with data. It holds no API key for any model
provider and never contacts one. Every row it returns enters your agent's context, and
a client that uses a hosted model sends that context to its provider. The `READ` token it
runs under can read everything its owner can, including products it does not list.

The server is read-only. Every call it makes to Aran is a `GET`, so a `READ`-scoped token
is enough and it cannot change anything you have built. A test enforces this:
`tests/test_server.py::TestReadOnly`.

## Install

The sidecar installs from a source checkout of Aran. It is not published to PyPI and no
wheels are released yet.

```bash
make aran-mcp          # from the checkout's root; creates aran-mcp/.venv
```

or directly:

```bash
python -m venv aran-mcp/.venv
aran-mcp/.venv/bin/pip install -e ./aran-client -e "./aran-mcp[dev]"
```

Python 3.10+ (the MCP SDK's floor; `aran-client` itself still supports 3.9).

## Get a token

In Aran: your avatar at the bottom of the left rail → **API Tokens** → create one with scope
**READ**.
The secret is shown once. `READ` is sufficient; do not use `FULL` here.

## Configure your MCP client

The server speaks stdio, so the client launches it as a subprocess. For Claude Desktop,
in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "aran": {
      "command": "/absolute/path/to/aran/aran-mcp/.venv/bin/aran-mcp",
      "env": {
        "ARAN_URL": "http://localhost:8081",
        "ARAN_TOKEN": "aran_pat_…"
      }
    }
  }
}
```

Prefer `env` over `--token`: command-line arguments show up in process listings.

| Setting | Env | Flag | Notes |
|---|---|---|---|
| Aran base URL | `ARAN_URL` | `--url` | `http://localhost:8081` for a development stack, `http://localhost:8080` for the standalone one |
| API token | `ARAN_TOKEN` | `--token` | `READ` scope is sufficient |
| Project filter | `ARAN_PROJECT` | `--project` | Optional; id or name |
| Allow plain HTTP to a remote host | (none) | `--allow-insecure` | Only when the wire is already encrypted (SSH tunnel, service mesh). `localhost` never needs it |

### Why stdio and not a URL

Aran usually runs on your machine. Claude Desktop's remote connectors route
through Anthropic's cloud and need the server reachable from Anthropic's public IP ranges,
which a `localhost` address is not. A stdio subprocess works for every local client and opens no
network port. A hosted, multi-user Aran would need an HTTP endpoint instead.

## What it exposes

**Tools**

| Tool | Does |
|---|---|
| `list_products` | Every readable data product: name, id, description, row count, the schema version it was defined against, staleness |
| `read_product(product, limit)` | Rows from one product, by name or id. Default 20, capped at 500 |

**Resource**

`aran://product/{name-or-id}`: a product's contract, meaning the OQL defining it, the
schema version it was defined against, its status and its freshness. It does not return rows.

### What it does not expose

- No write tool. Materializing is a `POST` that needs `FULL` scope, and an agent
  re-materializing a product would be a write nobody asked for.
- No arbitrary OQL. Serving products keeps an agent to named, reviewed queries whose
  definition it cannot change. An OQL tool would remove that limit. (A product's schema
  version is provenance, not a pin: a refresh re-runs its OQL under the schema active at
  that time, so its rows can change between refreshes.)
- No `DRAFT` or `MATERIALIZING` products. They have no snapshot to read.

The server does serve `STALE` products, flagged, with their `materializedAt`, so an
agent reading a month-old snapshot knows it.

## Which products are visible?

Every product the token can already read. There is no per-product "publish" switch,
because this sidecar runs on your machine under your own token: anything it can see, you
can already open in the Aran UI.

This changes if the MCP server is hosted and shared with other people: "which products
may agents see" then becomes a separate question from "which products exist". Until
then, the tenant boundary and the token's scope decide what is visible, and Aran enforces
both.

Use `--project` when a large tenant makes the tool listing noisy. It filters the listing;
it is not access control.

## Tests

```bash
aran-mcp/.venv/bin/python -m pytest aran-mcp/tests -q
```

No network, no Docker, no subprocess. The MCP side runs the real protocol in memory
(`Client(server)`). The Aran side is a stub speaking real HTTP on a loopback port, for the
reason given in `aran-client/tests/conftest.py`: mocking httpx would test the mock, and the
seams that matter are the ones a mock hides.

## Live check

The unit suite covers the protocol and the wire shapes against a loopback stub of Aran.
The browser end-to-end suite (`aran-web/e2e/specs/p1-mcp-live-client.spec.ts`) covers what a stub
cannot: it spawns `python -m aran_mcp` as a real subprocess, drives it with the
official MCP SDK client over stdio, and reads a materialized data product from a running
Aran over HTTP with a READ-scoped token (`aran-mcp/tests/test_live_client.py`).

## More

The [documentation site](https://dtak-upc.github.io/aran/) has the user guides.
[CONTRIBUTING.md](../CONTRIBUTING.md) explains how to build and test changes.

"""V21: aran-mcp against a live Aran, driven by a real client.

test_server.py's 18 tests drive the real MCP protocol (mcp.Client against the real
in-process Server), which is genuine coverage of aran-mcp's own logic — but Aran itself
is a loopback stub in every one of them (see conftest.py). What that leaves unverified is
the other half of the same claim: the actual ``python -m aran_mcp`` process, spawned as a
real subprocess and driven over real stdio by the official SDK's low-level client
(``stdio_client`` + ``ClientSession`` — a different code path through the SDK than
``mcp.Client`` uses, and the one a real MCP host such as Claude Desktop actually drives),
talking to a real, running Aran backend over real HTTP.

Skipped by default — safe under ``./scripts/validate.sh`` (both modes) and a bare
``pytest`` with no stack running. Exercised by ``aran-web/e2e/specs/p1-mcp-live-client.spec.ts``,
which brings up a live Aran via ``make e2e``, materializes a real data product, mints a
real READ-scoped API token, and invokes this file with ``ARAN_URL``/``ARAN_TOKEN`` set.

Deliberately does not hardcode a product name or its rows: everything here is discovered
from whatever the live tenant actually has, the same discipline the browser e2e suite's
join-discovery specs use — a hardcoded guess that drifts from the live fixture is worse
than a test that reads what is actually there.
"""

from __future__ import annotations

import json
import os
import sys

import pytest
from mcp import StdioServerParameters
from mcp.client.session import ClientSession
from mcp.client.stdio import stdio_client

ARAN_URL = os.environ.get("ARAN_URL")
ARAN_TOKEN = os.environ.get("ARAN_TOKEN")
ARAN_PROJECT = os.environ.get("ARAN_PROJECT")

pytestmark = pytest.mark.skipif(
    not ARAN_URL or not ARAN_TOKEN,
    reason="live-client test needs ARAN_URL and ARAN_TOKEN — see p1-mcp-live-client.spec.ts",
)


def _server_params() -> StdioServerParameters:
    args = ["-m", "aran_mcp"]
    env = {"ARAN_URL": ARAN_URL, "ARAN_TOKEN": ARAN_TOKEN}
    if ARAN_PROJECT:
        env["ARAN_PROJECT"] = ARAN_PROJECT
    # sys.executable, not a hardcoded path: whichever venv's pytest is running this IS
    # the venv aran_mcp is installed into (see Makefile's `aran-mcp` target).
    return StdioServerParameters(command=sys.executable, args=args, env=env)


async def test_initialize_and_advertised_tools():
    async with (
        stdio_client(_server_params()) as (read, write),
        ClientSession(read, write) as session,
    ):
        init = await session.initialize()
        assert init.server_info.name == "aran"

        tools = await session.list_tools()
        names = {t.name for t in tools.tools}
        assert {"list_products", "read_product"} <= names


async def test_list_products_returns_a_real_materialized_product():
    async with (
        stdio_client(_server_params()) as (read, write),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        result = await session.call_tool("list_products", {})
        payload = json.loads(result.content[0].text)

        assert payload["count"] >= 1
        product = payload["products"][0]
        assert product["name"]
        assert product["id"]
        assert product["schemaVersionId"]
        # Just materialized by the setup spec — not stale, and really refreshed.
        assert product["stale"] is False
        assert product["materializedAt"]


async def test_read_product_returns_real_rows_and_columns():
    async with (
        stdio_client(_server_params()) as (read, write),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        listed = json.loads((await session.call_tool("list_products", {})).content[0].text)
        product = listed["products"][0]

        result = await session.call_tool(
            "read_product", {"product": product["name"], "limit": 50}
        )
        payload = json.loads(result.content[0].text)

        assert payload["product"] == product["name"]
        assert payload["columns"], "a materialized product must describe real columns"
        assert payload["rowCount"] > 0, "a materialized product must have real rows"
        assert len(payload["rows"]) == payload["rowCount"]
        # Every row has one value per column — the shape a real DuckDB result gives,
        # not a stub's canned pair.
        for row in payload["rows"]:
            assert len(row) == len(payload["columns"])


async def test_read_product_resolves_by_id_too():
    async with (
        stdio_client(_server_params()) as (read, write),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        listed = json.loads((await session.call_tool("list_products", {})).content[0].text)
        product = listed["products"][0]

        result = await session.call_tool(
            "read_product", {"product": product["id"], "limit": 5}
        )
        payload = json.loads(result.content[0].text)
        assert payload["id"] == product["id"]
        assert payload["rowCount"] > 0


async def test_unresolvable_product_is_a_content_error_not_a_protocol_error():
    """§3 / _server.py's own comment: an agent can correct itself from a JSON error in the
    tool result; a protocol-level failure is opaque to it. Assert the deliberate choice
    holds against the real server, not just the stub."""
    async with (
        stdio_client(_server_params()) as (read, write),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        result = await session.call_tool(
            "read_product", {"product": "definitely-not-a-real-product-xyz"}
        )
        assert result.is_error is not True
        payload = json.loads(result.content[0].text)
        assert "error" in payload


async def test_product_resource_returns_the_real_definition():
    async with (
        stdio_client(_server_params()) as (read, write),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        listed = json.loads((await session.call_tool("list_products", {})).content[0].text)
        product = listed["products"][0]

        resource = await session.read_resource(f"aran://product/{product['name']}")
        payload = json.loads(resource.contents[0].text)

        assert payload["id"] == product["id"]
        assert payload["oql"], "the resource must carry the product's real defining OQL"
        assert payload["schemaVersionId"] == product["schemaVersionId"]

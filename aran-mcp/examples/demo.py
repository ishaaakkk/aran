"""A narrated, runnable walk-through of the real MCP protocol against a real Aran.

Same code path a real MCP host (Claude Desktop, Claude Code, Cursor) drives: a real
``python -m aran_mcp`` subprocess, spoken to over real stdio by the official SDK's
low-level client (``stdio_client`` + ``ClientSession`` — see
``aran-mcp/tests/test_live_client.py``, which asserts on this same path). This script
does the same round trip but prints what it sees instead of asserting on it, so a
human — or a prospective user deciding whether to trust the marquee pitch — can watch
it happen without installing an agent client first.

Usage:
    ARAN_URL=http://localhost:8081 ARAN_TOKEN=aran_pat_... \\
        aran-mcp/.venv/bin/python aran-mcp/examples/demo.py [--product NAME] [--limit N]

Or, once both env vars are set: ``make mcp-demo``.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys

from mcp import StdioServerParameters
from mcp.client.session import ClientSession
from mcp.client.stdio import stdio_client


def _banner(text: str) -> None:
    print(f"\n\033[1m── {text} ──\033[0m")


def _print_json(payload: str) -> None:
    print(json.dumps(json.loads(payload), indent=2))


def _server_params(project: str | None) -> StdioServerParameters:
    env = {"ARAN_URL": os.environ["ARAN_URL"], "ARAN_TOKEN": os.environ["ARAN_TOKEN"]}
    if project:
        env["ARAN_PROJECT"] = project
    # sys.executable, not a hardcoded path: whichever venv's python is running this
    # demo IS the venv aran_mcp is installed into (see the Makefile's `aran-mcp` target).
    return StdioServerParameters(command=sys.executable, args=["-m", "aran_mcp"], env=env)


async def run(product_name: str | None, limit: int) -> None:
    project = os.environ.get("ARAN_PROJECT")
    async with (
        stdio_client(_server_params(project)) as (read, write),
        ClientSession(read, write) as session,
    ):
        _banner("initialize — the handshake a real agent does on startup")
        init = await session.initialize()
        print(f"server: {init.server_info.name} v{init.server_info.version}")
        if init.instructions:
            print(f"instructions the agent's model receives:\n{init.instructions}")

        _banner("list_tools — what this server lets an agent do")
        tools = await session.list_tools()
        for tool in tools.tools:
            print(f"  {tool.name}: {tool.description}")

        _banner("call_tool('list_products') — the agent asking 'what can I read?'")
        listed = await session.call_tool("list_products", {})
        listed_text = listed.content[0].text
        _print_json(listed_text)
        products = json.loads(listed_text)["products"]
        if not products:
            print("\nNo readable products. Materialize one first — see demo/seminar/MCP_DEMO.md.")
            return

        target = product_name or products[0]["name"]
        _banner(f"call_tool('read_product', product={target!r}) — the agent reading rows")
        read = await session.call_tool("read_product", {"product": target, "limit": limit})
        _print_json(read.content[0].text)

        _banner(f"read_resource('aran://product/{target}') — the product's contract")
        resource = await session.read_resource(f"aran://product/{target}")
        _print_json(resource.contents[0].text)


def main() -> None:
    if not os.environ.get("ARAN_URL") or not os.environ.get("ARAN_TOKEN"):
        print(
            "aran-mcp demo: set ARAN_URL and ARAN_TOKEN first — see aran-mcp/README.md "
            "'Get a token'.",
            file=sys.stderr,
        )
        raise SystemExit(2)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--product", help="Product name to read (default: the first one listed)")
    parser.add_argument("--limit", type=int, default=10, help="Row limit (default: 10)")
    args = parser.parse_args()
    asyncio.run(run(args.product, args.limit))


if __name__ == "__main__":
    main()

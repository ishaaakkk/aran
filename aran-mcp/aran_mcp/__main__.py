"""Entry point: ``aran-mcp``, or ``python -m aran_mcp``.

Speaks MCP over stdio, which is what an MCP client on the same machine expects and
what lets this work against an Aran on localhost. Nothing here listens on a port.
"""

from __future__ import annotations

import argparse
import os
import sys
from collections.abc import Sequence

from aran_client import Connection
from aran_client.errors import AranConfigurationError, AranError

from ._products import ProductCatalog
from ._server import build_server


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="aran-mcp",
        description="Serve Aran data products over MCP, on stdio.",
        epilog=(
            "Values fall back to the ARAN_URL and ARAN_TOKEN environment variables, "
            "which is usually the better place for the token: an MCP client config "
            "file is not a secret store, and arguments show up in process listings."
        ),
    )
    parser.add_argument("--url", default=os.environ.get("ARAN_URL"),
                        help="Aran base URL, e.g. http://localhost:8081 [$ARAN_URL]")
    parser.add_argument("--token", default=os.environ.get("ARAN_TOKEN"),
                        help="An Aran API token; READ scope is sufficient [$ARAN_TOKEN]")
    parser.add_argument("--project", default=os.environ.get("ARAN_PROJECT"),
                        help=(
                            "Only serve products from this project (id or name). "
                            "Narrows noise, not access — the token's tenant is the "
                            "real boundary and is enforced by Aran [$ARAN_PROJECT]"
                        ))
    parser.add_argument("--allow-insecure", action="store_true",
                        help=(
                            "Send the token over plain HTTP to a non-local host. Only "
                            "when the wire is already encrypted by something else, such "
                            "as an SSH tunnel. localhost never needs this."
                        ))
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)

    if not args.url or not args.token:
        # stderr, not stdout: stdout is the MCP transport, and a help message written
        # there is a protocol violation the client reports as a parse error.
        print(
            "aran-mcp: --url and --token are required (or set ARAN_URL and ARAN_TOKEN).",
            file=sys.stderr,
        )
        return 2

    try:
        connection = Connection(args.url, args.token, allow_insecure=args.allow_insecure)
    except (AranConfigurationError, AranError) as exc:
        print(f"aran-mcp: {exc}", file=sys.stderr)
        return 2

    catalog = ProductCatalog(connection, project_filter=args.project)
    server = build_server(catalog)

    # Blocks until the client disconnects.
    server.run(transport="stdio")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

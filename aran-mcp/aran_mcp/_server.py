"""The MCP surface over Aran data products.

Two tools and one resource template. Small on purpose: every entry here is a
contract an agent may come to depend on, and the cheapest time to not have a
tool is before it exists.

There is deliberately no tool that writes. ``materialize`` is a POST needing
``FULL`` scope, and an agent re-materializing a product is a write nobody asked
for. There is also no arbitrary-OQL tool: serving *products* rather than the
live catalog is what pins the schema version, and an OQL escape hatch would give
that away while looking like a convenience.
"""

from __future__ import annotations

import json

from mcp.server import MCPServer

from ._products import AmbiguousProduct, ProductCatalog, ProductNotFound

#: Bound on rows returned in one call. An agent's context is the scarce resource
#: here, not Aran's ability to serve rows.
MAX_LIMIT = 500
DEFAULT_LIMIT = 20

INSTRUCTIONS = """\
Aran serves *data products*: named, curated query results over an integrated model \
built from heterogeneous sources (relational databases, CSV, JSON).

Each product records the schema version it was defined against; a refresh re-runs it \
under the schema active then, so its rows can change between refreshes. Call \
list_products first to see what exists, then read_product to get rows. A product marked \
stale is readable, but a newer schema version has been promoted since its snapshot was \
built.\
"""


def build_server(catalog: ProductCatalog, *, name: str = "aran") -> MCPServer:
    """Wire the catalog into an MCP server.

    Takes the catalog rather than a connection so tests can drive it against a stub
    without the server knowing anything about how Aran is reached.
    """
    server = MCPServer(
        name=name,
        title="Aran data products",
        version="0.1.0",
        instructions=INSTRUCTIONS,
    )

    @server.tool(
        name="list_products",
        title="List Aran data products",
        description=(
            "List the data products this token can read. Returns each product's name, "
            "id, description, row count, the schema version it was defined against, and "
            "whether its snapshot is stale."
        ),
    )
    def list_products() -> str:
        products = catalog.list_products()
        return json.dumps(
            {
                "count": len(products),
                "products": [
                    {
                        "id": p.id,
                        "name": p.name,
                        "description": p.description,
                        "rowCount": p.row_count,
                        "schemaVersionId": p.schema_version_id,
                        "stale": p.is_stale,
                        "materializedAt": p.materialized_at,
                    }
                    for p in products
                ],
            },
            indent=2,
        )

    @server.tool(
        name="read_product",
        title="Read rows from an Aran data product",
        description=(
            "Fetch rows from a data product, by name or id. Returns column names and "
            f"rows, at most {MAX_LIMIT} at a time."
        ),
    )
    def read_product(product: str, limit: int = DEFAULT_LIMIT) -> str:
        # Clamp rather than reject: an agent asking for 10_000 rows wants as many as it
        # can have, and failing the call teaches it nothing it can act on.
        effective = max(1, min(int(limit), MAX_LIMIT))
        try:
            resolved = catalog.resolve(product)
        except (ProductNotFound, AmbiguousProduct) as exc:
            # Returned as content, not raised: the agent can correct itself from this,
            # whereas a protocol error is opaque to it.
            return json.dumps({"error": str(exc)}, indent=2)

        payload = catalog.preview(resolved, effective)
        served = payload.get("rows", []) or []
        # Bounded here as well as upstream: an Aran that predates V37 gap 3's fix served a
        # VIRTUAL product's whole 1,000-row page whatever `limit` said, and every surplus row
        # lands in the agent's context.
        rows = served[:effective]
        has_more = payload.get("hasMore")
        truncated = has_more if isinstance(has_more, bool) and len(served) <= effective \
            else len(served) >= effective
        return json.dumps(
            {
                "product": resolved.name,
                "id": resolved.id,
                "schemaVersionId": resolved.schema_version_id,
                "stale": resolved.is_stale,
                "materializedAt": resolved.materialized_at,
                "columns": payload.get("headerNames") or payload.get("headers") or [],
                "columnIris": payload.get("headers") or [],
                "rowCount": len(rows),
                "truncated": truncated,
                "rows": rows,
            },
            indent=2,
        )

    @server.resource(
        "aran://product/{product}",
        name="aran-data-product",
        title="Aran data product definition",
        description=(
            "The contract of one data product: the OQL that defines it, the schema "
            "version it was defined against, and its freshness. Not its rows — use read_product."
        ),
        mime_type="application/json",
    )
    def product_definition(product: str) -> str:
        try:
            resolved = catalog.resolve(product)
        except (ProductNotFound, AmbiguousProduct) as exc:
            return json.dumps({"error": str(exc)}, indent=2)
        return json.dumps(
            {
                "id": resolved.id,
                "name": resolved.name,
                "description": resolved.description,
                "oql": resolved.oql,
                "schemaVersionId": resolved.schema_version_id,
                "status": resolved.status,
                "stale": resolved.is_stale,
                "rowCount": resolved.row_count,
                "materializedAt": resolved.materialized_at,
            },
            indent=2,
        )

    return server

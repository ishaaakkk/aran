"""MCP server exposing Aran data products to an agent you already run.

    aran-mcp --url http://localhost:8081 --token aran_pat_…

Aran ships no model and runs no inference. This package is a protocol adapter:
your MCP client's model does the language part, and Aran answers with data. It
holds no API key for any model provider and never talks to one.

It is also read-only, all the way down — every call it makes to Aran is a GET,
which is why an ``ApiTokenScope.READ`` token is enough and why it cannot change
anything you have built.

It is a local sidecar rather than an endpoint inside Aran: a stdio subprocess
works for every local client and opens no network port.
"""

from ._products import (
    READABLE_STATUSES,
    AmbiguousProduct,
    Product,
    ProductCatalog,
    ProductNotFound,
)
from ._server import DEFAULT_LIMIT, MAX_LIMIT, build_server

__version__ = "0.1.0"

__all__ = [
    "DEFAULT_LIMIT",
    "MAX_LIMIT",
    "READABLE_STATUSES",
    "AmbiguousProduct",
    "Product",
    "ProductCatalog",
    "ProductNotFound",
    "build_server",
]

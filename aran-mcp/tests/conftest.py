"""A stub Aran, run for real over HTTP on a loopback port.

The same reasoning as ``aran-client/tests/conftest.py``: mocking httpx would test the
mock, and the seams that matter here — the bearer header actually being sent, a limit
actually reaching the query string, a 404 body becoming the right exception — are
exactly the ones a mock hides.

It also records every request it receives, which is what lets the suite assert that
this package only ever issues GETs. That assertion is the difference between
intending to be read-only and being read-only.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

import pytest
from aran_mcp import ProductCatalog, build_server

from aran_client import Connection

TOKEN = "aran_pat_abcdef123456"

PROJECT_ID = "11111111-1111-1111-1111-111111111111"
OTHER_PROJECT_ID = "22222222-2222-2222-2222-222222222222"

SCHEMA_VERSION_ID = "99999999-9999-9999-9999-999999999999"


def product(
    pid: str,
    name: str,
    status: str = "READY",
    *,
    row_count: int | None = 42,
    materialized_at: str | None = "2026-08-01T10:00:00Z",
    oql: str = "FIND Paper.title, Paper.year",
) -> dict[str, Any]:
    """A DataProductDto as the backend shapes it."""
    return {
        "id": pid,
        "projectId": PROJECT_ID,
        "name": name,
        "description": f"{name} description",
        "oql": oql,
        "schemaVersionId": SCHEMA_VERSION_ID,
        "status": status,
        "snapshotId": None if status in {"DRAFT", "MATERIALIZING"} else "snap-1",
        "rowCount": row_count,
        "materializedAt": materialized_at,
        "lastError": None,
        "createdBy": None,
        "createdAt": "2026-07-01T09:00:00Z",
    }


def preview_payload(rows: int = 3) -> dict[str, Any]:
    """Shaped like a workbench query result: IRI headers plus display names."""
    return {
        "headers": ["http://aran/Paper#title", "http://aran/Paper#year"],
        "headerNames": ["title", "year"],
        "rows": [[f"Paper {i}", str(2020 + i)] for i in range(rows)],
    }


DEFAULT_PRODUCTS: list[dict[str, Any]] = [
    product("prod-ready", "papers"),
    product("prod-stale", "stale_papers", status="STALE",
            materialized_at="2026-01-05T08:00:00Z"),
    product("prod-draft", "draft_papers", status="DRAFT",
            row_count=None, materialized_at=None),
    product("prod-mat", "materializing_papers", status="MATERIALIZING",
            row_count=None, materialized_at=None),
]


class _Handler(BaseHTTPRequestHandler):
    server: StubAran  # type: ignore[assignment]

    def do_GET(self) -> None:
        self._record("GET")
        parsed = urlparse(self.path)
        path, query = parsed.path, parse_qs(parsed.query)

        if path == "/api/projects":
            return self._json(self.server.projects)

        if path.endswith("/products") and path.startswith("/api/projects/"):
            project_id = path.split("/")[3]
            return self._json(self.server.products_by_project.get(project_id, []))

        if path.endswith("/preview"):
            limit = int(query.get("limit", ["0"])[0])
            self.server.observed_limits.append(limit)
            # ignores_limit: an Aran whose VIRTUAL read dropped `limit` and served its whole
            # 1,000-row page (V37 gap 3 — fixed server-side, still possible from an older server).
            served = self.server.available_rows if self.server.ignores_limit \
                else min(limit, self.server.available_rows)
            return self._json(preview_payload(served))

        if path.startswith("/api/projects/") and "/products/" in path:
            pid = path.rsplit("/", 1)[-1]
            for products in self.server.products_by_project.values():
                for raw in products:
                    if raw["id"] == pid:
                        return self._json(raw)
            return self._json({"detail": "not found"}, status=404)

        self._json({"detail": f"unexpected path {path}"}, status=404)

    def _write_denied(self, method: str) -> None:
        """Any non-GET is a test failure, recorded so the assertion can name it."""
        self._record(method)
        self._json({"detail": "this stub is read-only"}, status=405)

    def do_POST(self) -> None:
        self._write_denied("POST")

    def do_PUT(self) -> None:
        self._write_denied("PUT")

    def do_DELETE(self) -> None:
        self._write_denied("DELETE")

    def _record(self, method: str) -> None:
        self.server.requests.append((method, self.path))
        self.server.auth_headers.append(self.headers.get("Authorization"))

    def _json(self, body: Any, status: int = 200) -> None:
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args: Any) -> None:
        """Silence the default stderr logging; the suite is noisy enough."""


class StubAran(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self) -> None:
        super().__init__(("127.0.0.1", 0), _Handler)
        self.requests: list[tuple[str, str]] = []
        self.auth_headers: list[str | None] = []
        self.observed_limits: list[int] = []
        self.available_rows = 100
        self.ignores_limit = False
        self.projects: list[dict[str, Any]] = [
            {"id": PROJECT_ID, "name": "Research", "tenantId": "t", "description": ""},
        ]
        self.products_by_project: dict[str, list[dict[str, Any]]] = {
            PROJECT_ID: list(DEFAULT_PRODUCTS),
        }

    @property
    def base_url(self) -> str:
        host, port = self.server_address[0], self.server_address[1]
        return f"http://{host}:{port}"

    @property
    def methods_used(self) -> set:
        return {method for method, _ in self.requests}


@pytest.fixture
def aran():
    stub = StubAran()
    thread = threading.Thread(target=stub.serve_forever, daemon=True)
    thread.start()
    try:
        yield stub
    finally:
        stub.shutdown()
        stub.server_close()


@pytest.fixture
def catalog(aran):
    return ProductCatalog(Connection(aran.base_url, TOKEN))


@pytest.fixture
def server(catalog):
    return build_server(catalog)

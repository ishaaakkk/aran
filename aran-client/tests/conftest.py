"""A stub Aran server, run for real over HTTP on a loopback port.

Mocking httpx would test the mock. The interesting failures in this client are
in the seams a mock hides: the auth header actually being sent, the row-count
header round-tripping, a problem-detail body decoding into the right exception.
So the tests speak HTTP to a thread.
"""

from __future__ import annotations

import json
import threading
from collections.abc import Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import pyarrow as pa
import pyarrow.parquet as pq
import pytest

EXPORT_PATH = "/api/query/export"


def parquet_bytes(table: pa.Table) -> bytes:
    sink = pa.BufferOutputStream()
    pq.write_table(table, sink)
    return sink.getvalue().to_pybytes()


def sample_table() -> pa.Table:
    """Shaped like an integrated-class result: IRI-ish columns, mixed types."""
    return pa.table(
        {
            "http://aran/Paper#title": ["Bell", "GAV", "Aran"],
            "http://aran/Paper#year": [1964, 1997, 2026],
        }
    )


class _Handler(BaseHTTPRequestHandler):
    server: StubServer  # type: ignore[assignment]

    def do_GET(self) -> None:
        """GET exists for /api/auth/policy, which is readable without authenticating."""
        self._handle(b"{}")

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        self._handle(self.rfile.read(length) if length else b"{}")

    def _handle(self, raw: bytes) -> None:
        try:
            body = json.loads(raw)
        except ValueError:
            body = {}

        self.server.requests.append(
            {
                "path": self.path,
                "body": body,
                "authorization": self.headers.get("Authorization"),
            }
        )

        response = self.server.responder(self.path, body)
        status = response.get("status", 200)
        payload: bytes = response.get("payload", b"")
        headers: dict[str, str] = response.get("headers", {})

        self.send_response(status)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def log_message(self, *args: Any) -> None:
        """Silence — the handler otherwise writes a line per request to stderr."""


class StubServer(ThreadingHTTPServer):
    def __init__(self) -> None:
        super().__init__(("127.0.0.1", 0), _Handler)
        self.requests: list[dict[str, Any]] = []
        self.responder: Callable[[str, dict[str, Any]], dict[str, Any]] = _default_responder

    @property
    def base_url(self) -> str:
        host, port = self.server_address[0], self.server_address[1]
        return f"http://{host}:{port}"

    def reply_with(self, responder: Callable[[str, dict[str, Any]], dict[str, Any]]) -> None:
        self.responder = responder

    def problem(self, status: int, detail: str, title: str | None = None) -> None:
        """Answer every request with an RFC 7807 problem detail, as the server does."""
        body = json.dumps(
            {
                "type": "about:blank",
                "title": title or "Error",
                "status": status,
                "detail": detail,
            }
        ).encode()
        self.reply_with(
            lambda path, req: {
                "status": status,
                "payload": body,
                "headers": {"Content-Type": "application/problem+json"},
            }
        )


def _default_responder(path: str, body: dict[str, Any]) -> dict[str, Any]:
    table = sample_table()
    payload = parquet_bytes(table)
    return {
        "status": 200,
        "payload": payload,
        "headers": {
            "Content-Type": "application/vnd.apache.parquet",
            "X-Aran-Row-Count": str(table.num_rows),
        },
    }


@pytest.fixture
def server():
    stub = StubServer()
    thread = threading.Thread(target=stub.serve_forever, daemon=True)
    thread.start()
    try:
        yield stub
    finally:
        stub.shutdown()
        stub.server_close()

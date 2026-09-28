"""Exceptions raised by the Aran client.

The server answers errors as RFC 7807 problem details (``ApiExceptionHandler``),
so the useful human text is the ``detail`` member, not the HTTP reason phrase.
Every exception here carries the server's own message where there is one: a bad
column name should read the same in a notebook as it does in the workbench.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

__all__ = [
    "AranAuthError",
    "AranConfigurationError",
    "AranError",
    "AranInsecureTransportError",
    "AranQueryError",
    "AranServerError",
]


class AranError(Exception):
    """Base class for every error this package raises."""


class AranConfigurationError(AranError):
    """The client was asked to do something before it knew where or who it was."""


class AranInsecureTransportError(AranConfigurationError):
    """Refused to send an API token over an unencrypted connection to a remote host.

    An Aran token is a bearer credential with a months-long lifetime: anyone who reads it
    off the wire has the caller's access until it is revoked. Over plain HTTP it is sent in
    a header on every request, in the clear.

    Localhost is exempt — there is no network to listen on. Everything else must either use
    ``https://`` or opt in explicitly with ``allow_insecure=True``, which is the right answer
    only when something else already provides the encryption (an SSH tunnel, a service mesh,
    a TLS-terminating sidecar on the same host).
    """


class AranHttpError(AranError):
    """An error response from the Aran API."""

    def __init__(self, status_code: int, detail: str, problem: Mapping[str, Any] | None = None):
        super().__init__(f"[{status_code}] {detail}")
        self.status_code = status_code
        self.detail = detail
        self.problem = dict(problem) if problem else {}


class AranAuthError(AranHttpError):
    """401/403 — the token is unknown, expired, or lacks the scope for this call.

    A READ-scoped token reaching this on a query is worth calling out: the
    server allows ``POST /api/query/export`` for READ tokens by name, so a 403
    here means the token itself is the problem, not the endpoint.
    """


class AranQueryError(AranHttpError):
    """400 — the engine refused the query. ``detail`` is the engine's own message."""


class AranServerError(AranHttpError):
    """5xx, or any status the client has no more specific class for."""

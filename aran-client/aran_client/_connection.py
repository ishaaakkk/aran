"""HTTP connection to an Aran server.

The transport is deliberately boring: ``POST /api/query/export`` with
``{"oql": ..., "format": "PARQUET"}``, streamed back as a file. That endpoint
already runs the same ``materialize-oql`` operation a data product runs — same
planner, same constraint enforcement, **no row cap** — so the notebook gets the
whole answer rather than the workbench's 1,000-row page.

Parquet rather than a bespoke Arrow endpoint: the server writes parquet today,
pyarrow reads it without a copy on the client side, and adding an Arrow-IPC
producer to the Java side buys streaming, not correctness. When that lands this
module gains a format, not a rewrite.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from typing import Any
from urllib.parse import urlparse

import httpx

from ._result import QueryResult
from .errors import (
    AranAuthError,
    AranConfigurationError,
    AranError,
    AranInsecureTransportError,
    AranQueryError,
    AranServerError,
)

__all__ = ["Connection", "connect", "default_connection", "query"]

_TOKEN_PREFIX = "aran_pat_"
_EXPORT_PATH = "/api/query/export"
_POLICY_PATH = "/api/auth/policy"
_ROW_COUNT_HEADER = "X-Aran-Row-Count"

#: How this client presents its credential, in the server's own vocabulary.
_SDK_AUTH_METHOD = "api-token-bearer"

_default: Connection | None = None


class Connection:
    """A configured, reusable connection to one Aran server.

    The tenant is **not** a parameter. It is carried by the token: the server
    resolves the tenant from the token's owner and enforces it per request, so a
    client-supplied tenant could only ever disagree with the authoritative one.
    """

    def __init__(
        self,
        base_url: str,
        token: str,
        *,
        timeout: float = 300.0,
        verify: Any = True,
        allow_insecure: bool = False,
        client: httpx.Client | None = None,
    ):
        if not base_url:
            raise AranConfigurationError("base_url is required (or set ARAN_URL).")
        if not token:
            raise AranConfigurationError("token is required (or set ARAN_TOKEN).")
        _check_transport(base_url, allow_insecure)

        self.base_url = base_url.rstrip("/")
        self._token = token
        self._owns_client = client is None
        self._client = client or httpx.Client(
            base_url=self.base_url,
            timeout=timeout,
            verify=verify,
            headers={"Authorization": f"Bearer {token}"},
        )

    @property
    def token_prefix(self) -> str:
        """The token's public prefix, safe to print — never the whole secret."""
        return self._token[: len(_TOKEN_PREFIX) + 6]

    def execute(self, oql: str, *, format: str = "parquet") -> QueryResult:
        """Run ``oql`` and return the undecoded result.

        A long query is normal here; the server caps nothing.
        """
        if not oql or not oql.strip():
            raise ValueError("OQL query is empty.")
        fmt = format.upper()
        if fmt not in ("PARQUET", "CSV"):
            raise ValueError(f"format must be 'parquet' or 'csv', got {format!r}")

        try:
            response = self._client.post(
                _EXPORT_PATH, json={"oql": oql, "format": fmt}
            )
        except httpx.HTTPError as exc:
            raise AranError(f"could not reach Aran at {self.base_url}: {exc}") from exc

        if response.status_code >= 400:
            error = _error_for(response)
            if isinstance(error, AranAuthError):
                error = self._explain_refusal(error)
            raise error

        reported = _reported_rows(response.headers)
        return QueryResult(response.content, fmt, reported)

    def _explain_refusal(self, error: AranAuthError) -> AranAuthError:
        """Add the server's own policy to an auth failure, when it explains one.

        "The administrator turned off bearer-token access" and "your token is wrong" are the
        same 401 on the wire. Asking the policy endpoint — one extra request, and only on a
        failure — is what separates them. Best-effort throughout: a server that cannot answer
        leaves the original error exactly as it was.
        """
        policy = self.auth_policy()
        if not policy:
            return error
        methods = policy.get("methods")
        if not isinstance(methods, list) or _SDK_AUTH_METHOD in methods:
            return error
        accepted = ", ".join(str(m) for m in methods)
        return AranAuthError(
            error.status_code,
            f"{error.detail} — this deployment does not accept "
            f"{_SDK_AUTH_METHOD!r} authentication (accepted: {accepted}), so this is a "
            "server policy, not a bad token.",
            error.problem,
        )

    def query(self, oql: str, *, into: str = "pandas", format: str = "parquet") -> Any:
        """Run ``oql`` and return a DataFrame.

        ``into`` selects ``"pandas"`` (default), ``"polars"`` or ``"arrow"``.
        """
        return self.execute(oql, format=format).into(into)

    def post_json(self, path: str, body: Mapping[str, Any]) -> Any:
        """POST JSON and decode the response. The write half of the client.

        Separate from :meth:`execute` because that one is the query path — it returns
        bytes, cross-checks a row count and knows about parquet. This is a plain JSON
        call for the model endpoints.
        """
        return self._json_call("POST", path, json=dict(body))

    def post_multipart(self, path: str, *, fields: Mapping[str, Any],
                       files: Mapping[str, Any]) -> Any:
        """POST multipart/form-data — how a dataset's bytes reach Aran.

        The bytes go to Aran, which uploads them to the lake itself. No presigned URL
        ever reaches a client: only Aran talks to the lake.
        """
        return self._json_call("POST", path, files={**fields, **files})

    def get_json(self, path: str) -> Any:
        """GET and decode. Used to poll a job to completion."""
        return self._json_call("GET", path)

    def _json_call(self, method: str, path: str, **kwargs: Any) -> Any:
        try:
            response = self._client.request(method, path, **kwargs)
        except httpx.HTTPError as exc:
            raise AranError(f"could not reach Aran at {self.base_url}: {exc}") from exc

        if response.status_code >= 400:
            error = _error_for(response)
            if isinstance(error, AranAuthError):
                error = self._explain_refusal(error)
            raise error

        if not response.content:
            return None
        try:
            return response.json()
        except ValueError as exc:
            raise AranError(f"Aran returned a non-JSON response to {path}") from exc

    def auth_policy(self) -> Mapping[str, Any] | None:
        """What credentials this server accepts: ``{"methods": [...], "requireTls": bool}``.

        Server-side policy, not a client setting — an operator decides it once and every
        client is bound by it. Readable without authenticating, which is the point: a client
        can tell a user *why* a credential was refused instead of relaying a bare 401.

        Returns ``None`` if the server does not publish one (an Aran older than this
        endpoint), which is a reason to say nothing rather than to guess.
        """
        try:
            response = self._client.get(_POLICY_PATH)
        except httpx.HTTPError:
            return None
        if response.status_code != 200:
            return None
        try:
            body = response.json()
        except ValueError:
            return None
        return body if isinstance(body, dict) else None

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def __enter__(self) -> Connection:
        return self

    def __exit__(self, *exc_info: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"<aran.Connection {self.base_url} token={self.token_prefix}…>"


#: Hosts where plain HTTP carries no risk, because nothing can be listening in between.
_LOCAL_HOSTS = frozenset({"localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0"})


def _check_transport(base_url: str, allow_insecure: bool) -> None:
    """Refuse to put a long-lived credential on an unencrypted wire.

    A warning would be the polite choice and the wrong one: warnings are filtered, swallowed
    by notebook frontends, and read after the token has already been sent. The failure is
    cheap to opt out of and expensive to discover later.
    """
    if allow_insecure:
        return
    parsed = urlparse(base_url)
    if parsed.scheme != "http":
        return
    if (parsed.hostname or "") in _LOCAL_HOSTS:
        return
    raise AranInsecureTransportError(
        f"refusing to send an API token over plain HTTP to {parsed.hostname!r}. "
        "Use https://, or pass allow_insecure=True if the connection is already "
        "encrypted by something else (an SSH tunnel, a service mesh)."
    )


def _reported_rows(headers: Mapping[str, str]) -> int | None:
    raw = headers.get(_ROW_COUNT_HEADER) or headers.get(_ROW_COUNT_HEADER.lower())
    if raw is None:
        return None
    try:
        return int(raw)
    except ValueError:
        # A malformed count is not worth failing a good result over; it only
        # disables the truncation cross-check.
        return None


def _error_for(response: httpx.Response) -> AranError:
    """Map an HTTP error onto this package's exceptions.

    The server answers RFC 7807 problem details, so ``detail`` is the message a
    human should see. Anything else (a proxy's HTML error page, an empty body)
    falls back to the reason phrase.
    """
    detail = response.reason_phrase or "request failed"
    problem: Mapping[str, Any] = {}
    try:
        body = response.json()
        if isinstance(body, dict):
            problem = body
            detail = body.get("detail") or body.get("message") or detail
    except ValueError:
        text = response.text.strip()
        if text and len(text) < 500:
            detail = text

    status = response.status_code
    if status in (401, 403):
        return AranAuthError(status, detail, problem)
    if status == 400:
        return AranQueryError(status, detail, problem)
    return AranServerError(status, detail, problem)


def connect(
    base_url: str | None = None,
    token: str | None = None,
    *,
    timeout: float = 300.0,
    verify: Any = True,
    allow_insecure: bool = False,
    set_default: bool = True,
) -> Connection:
    """Open a connection, and make it the one module-level :func:`query` uses.

    Both arguments fall back to ``ARAN_URL`` and ``ARAN_TOKEN``, so a notebook
    that reads its credentials from the environment calls ``aran.connect()``
    with no arguments and never writes a token into a cell.

    ``allow_insecure`` permits plain HTTP to a remote host; see
    :class:`~aran_client.errors.AranInsecureTransportError` for when that is
    the right answer.
    """
    resolved_url = base_url or os.environ.get("ARAN_URL", "")
    resolved_token = token or os.environ.get("ARAN_TOKEN", "")

    connection = Connection(
        resolved_url, resolved_token, timeout=timeout, verify=verify,
        allow_insecure=allow_insecure
    )
    if set_default:
        global _default
        _default = connection
    return connection


def default_connection() -> Connection:
    if _default is None:
        raise AranConfigurationError(
            "no connection yet — call aran.connect(url, token) first, "
            "or set ARAN_URL and ARAN_TOKEN and call aran.connect()."
        )
    return _default


def query(oql: str, *, into: str = "pandas", format: str = "parquet") -> Any:
    """Run ``oql`` on the connection opened by the last :func:`connect` call."""
    return default_connection().query(oql, into=into, format=format)

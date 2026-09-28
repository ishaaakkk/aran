"""
Per-caller shared-secret bearer-token auth.

v0.2 change vs v0.1: instead of a single shared secret, the lake holds a
map of ``{caller_id: secret}``. The bearer-token check returns the
*caller-id whose secret matched*, so handlers and structured logs can
record "this request came from Aran" vs "from FREYJA" without trusting
any caller-asserted identity header.

Tenant identity is still asserted by the caller via ``X-Tenant-Id``; the
secret is what gives us the right to trust the tenant assertion. The new
piece is that the caller is also identified, and the lake can refuse to
serve certain calls to certain callers if it ever needs to (not enforced
in v0.2 — every authenticated caller can hit every endpoint — but the
caller-id is now in scope for future policy).

The check is timing-safe (``hmac.compare_digest``) so we don't leak
secrets via per-byte timing attacks. Because we may have multiple
configured secrets, we iterate through *all* of them and accumulate the
match in a separate variable — the comparison loop runs in constant time
relative to the number of configured secrets, regardless of which one (or
none) matches.

Upgrade path to mTLS documented in ADR 005.
"""
from __future__ import annotations

import hmac
from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Header, HTTPException, status

from aran_lake.config import Settings, get_settings


@dataclass(frozen=True, slots=True)
class CallerIdentity:
    """
    The authenticated caller. Carried as a dependency-injected value so
    handlers and structured logs can record it without re-parsing the
    bearer header.
    """
    caller_id: str


def _settings_dep() -> Settings:
    """
    Indirection so tests can override the settings dependency via FastAPI's
    ``app.dependency_overrides`` without monkey-patching ``get_settings``.
    """
    return get_settings()


def require_bearer(
    authorization: str | None = Header(default=None),
    settings: Annotated[Settings, Depends(_settings_dep)] = ...,  # type: ignore[assignment]
) -> CallerIdentity:
    """
    FastAPI dependency. Returns the authenticated :class:`CallerIdentity`,
    or raises 401 if the request carries no valid ``Authorization: Bearer
    <secret>`` header.

    The matching secret determines the caller-id. The comparison is
    constant-time across all configured secrets.
    """
    # RFC 7235: the scheme name is case-insensitive. This used to require exactly
    # "Bearer ", which meant a client sending "bearer <secret>" was accepted by
    # freyja and rejected here — the two services disagreeing about the same
    # credential. Matching case-insensitively is what freyja does and what the
    # sidecar contract requires; it accepts a differently-cased scheme carrying the
    # same secret, and weakens nothing.
    scheme, _, presented_raw = (authorization or "").partition(" ")
    if scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    presented = presented_raw.strip().encode("utf-8")
    if not presented:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="empty bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    matched_caller_id: str | None = None
    for caller_id, secret in settings.caller_secret_map().items():
        expected = secret.get_secret_value().encode("utf-8")
        # Run compare_digest for every configured secret, even after a
        # match (no break), so total work doesn't reveal which secret matched.
        if hmac.compare_digest(presented, expected) and matched_caller_id is None:
            matched_caller_id = caller_id

    if matched_caller_id is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return CallerIdentity(caller_id=matched_caller_id)

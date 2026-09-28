"""
Per-caller shared-secret bearer-token auth for the FREYJA sidecar.

THIS MIRRORS ``aran_lake.config`` + ``aran_lake.auth.bearer`` DELIBERATELY. The two
services are Aran's Python data plane, they face the same threat, and they are
expected to behave identically: same environment-variable shape, same resolution
precedence, same failure modes, same wire behaviour. ``tests/sidecar_contract/``
asserts that they do, and a third sidecar must be added to it.

The contract, in four rules — see the contributor guide's "Python sidecar security
contract" page for the long form:

1. **Fail closed at startup, not at request time.** No secret configured, no
   service. A warning goes to a log nobody reads while the port answers everyone.
2. **Refuse dev credentials in prod.** The dev sentinels are committed to this
   repository and are therefore public strings.
3. **Compare in constant time**, across every configured secret, with no early
   break — so neither the secret nor *which* secret matched leaks via timing.
4. **Authenticate on the router**, so an endpoint added later cannot be reachable
   unauthenticated by forgetting a decorator. Health probes sit outside it.

WHY THIS IS WORTH FAILING STARTUP OVER. FREYJA takes ``tenant_id`` from the request
BODY and then asserts it to the lake using the lake's privileged shared secret. It
is, by design, a service that will read any tenant's snapshot data on request. The
bearer check is the only thing between "reachable" and "reads every tenant's data",
so a deployment where it is off is not degraded — it is a cross-tenant read
primitive listening on a port.

Enforcement used to activate only when the secret happened to be set, and merely
logged a warning otherwise. That was a compatibility carve-out for sidecars started
before Aran sent the header; it expired when ``aran.freyja.secret`` gained a
non-empty default, and its only remaining effect was to let a misconfigured
deployment expose every tenant's data behind a log line.
"""
from __future__ import annotations

import hmac
import json
import logging
import os
from dataclasses import dataclass

from fastapi import HTTPException, Request, status

logger = logging.getLogger(__name__)

# Inbound auth. The plural form is canonical; the singular is the legacy shape and
# is promoted to {"default": ...}. Same precedence as the lake's
# ARAN_LAKE_SHARED_SECRETS / ARAN_LAKE_SHARED_SECRET.
SECRETS_ENV = "FREYJA_SHARED_SECRETS"
SECRET_ENV = "FREYJA_SHARED_SECRET"  # noqa: S105
PROFILE_ENV = "FREYJA_PROFILE"

DEFAULT_CALLER_ID = "default"

# The value the Makefile and scripts/startup.sh use locally. It is committed, so it
# is public; rule 2 exists because of that.
DEV_DEFAULT_SECRET = "dev-freyja-secret-change-in-prod"  # noqa: S105 — dev sentinel, refused in prod


class MisconfiguredError(RuntimeError):
    """The service cannot be started safely with the configuration it was given."""


@dataclass(frozen=True, slots=True)
class CallerIdentity:
    """
    The authenticated caller.

    FREYJA has exactly one caller today (Aran), so this looks like ceremony. It is
    the lake's shape, and it is what lets a second caller — or a per-caller policy —
    arrive without redesigning auth or re-reading the bearer header in handlers.
    """

    caller_id: str


def resolve_caller_secrets(env: dict[str, str] | None = None) -> dict[str, str]:
    """
    The active ``{caller_id: secret}`` map, or raise.

    Resolution, identical to the lake's ``Settings.caller_secret_map``:

      1. ``FREYJA_SHARED_SECRETS`` set → parse as a JSON object and use it.
      2. Else ``FREYJA_SHARED_SECRET`` set → promote to ``{"default": secret}``.
      3. Else → raise. There is no unauthenticated mode.

    Call at startup so a misconfigured process dies immediately and visibly rather
    than serving. ``env`` is injectable for tests.
    """
    source = os.environ if env is None else env
    profile = (source.get(PROFILE_ENV) or "dev").strip().lower()

    raw_map = (source.get(SECRETS_ENV) or "").strip()
    if raw_map:
        try:
            parsed = json.loads(raw_map)
        except json.JSONDecodeError as exc:
            raise MisconfiguredError(
                f"{SECRETS_ENV} must be a JSON object of {{caller_id: secret}}; "
                "it failed to parse."
            ) from exc
        if not isinstance(parsed, dict) or not parsed:
            raise MisconfiguredError(
                f"{SECRETS_ENV} must be a non-empty JSON object of {{caller_id: secret}}."
            )
        secrets_map = {str(k): str(v) for k, v in parsed.items()}
    else:
        single = (source.get(SECRET_ENV) or "").strip()
        if not single:
            raise MisconfiguredError(
                f"Neither {SECRETS_ENV} nor {SECRET_ENV} is set. FREYJA reads tenant_id "
                "from the request body and asserts it to the lake with a privileged "
                "secret, so an unauthenticated port here can read any tenant's data. "
                "Set it to the same value as Aran's aran.freyja.secret "
                "(ARAN_FREYJA_SECRET) and restart."
            )
        secrets_map = {DEFAULT_CALLER_ID: single}

    for caller_id, secret in secrets_map.items():
        if not caller_id.strip():
            raise MisconfiguredError(f"caller-id in {SECRETS_ENV} cannot be blank")
        if not secret.strip():
            raise MisconfiguredError(
                f"the secret for caller {caller_id!r} is blank"
            )
        if profile == "prod" and secret == DEV_DEFAULT_SECRET:
            raise MisconfiguredError(
                f"the secret for caller {caller_id!r} is still the development "
                f"default while {PROFILE_ENV}=prod. That value is committed to the "
                "repository and is therefore public. Set a high-entropy value."
            )
        if profile != "prod" and secret == DEV_DEFAULT_SECRET:
            logger.warning(
                "%s is the development default for caller %r. Fine locally; set a real "
                "value and %s=prod before this port is reachable from anywhere but "
                "localhost.",
                SECRET_ENV,
                caller_id,
                PROFILE_ENV,
            )

    return secrets_map


def require_bearer(request: Request) -> CallerIdentity:
    """
    Reject the request unless it carries one of the configured shared secrets.

    Returns the caller whose secret matched, so handlers and structured logs can
    record who called without trusting a caller-asserted identity header.

    Startup has already refused to run without a secret, so reaching the
    misconfigured branch means something bypassed startup validation. Deny rather
    than serve — that is the branch which used to let everything through.
    """
    try:
        secrets_map = resolve_caller_secrets()
    except MisconfiguredError as exc:
        logger.error("Refusing request: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Sidecar is not configured for authenticated access.",
        ) from exc

    header = request.headers.get("Authorization", "")
    scheme, _, presented_raw = header.partition(" ")
    # RFC 7235: the scheme name is case-insensitive.
    if scheme.lower() != "bearer" or not presented_raw.strip():
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    presented = presented_raw.strip().encode("utf-8")
    matched: str | None = None
    for caller_id, secret in secrets_map.items():
        # No early break: total work must not reveal which secret matched, or how
        # many were tried before it did.
        if hmac.compare_digest(presented, secret.encode("utf-8")) and matched is None:
            matched = caller_id

    if matched is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return CallerIdentity(caller_id=matched)

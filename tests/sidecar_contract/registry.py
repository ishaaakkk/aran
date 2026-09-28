"""
The sidecars bound by the security contract, and how to drive each one.

ADDING A PYTHON SERVICE THAT Aran CALLS OVER HTTP? Add it here. The suite in
``test_contract.py`` then holds it to the same four rules as ``aran-lake`` and
``freyja``, and CI goes red if it does not comply.

This registry exists because a documented contract is not an enforced one. The
contributor guide's "Python sidecar security contract" page explains *why* each rule
is there; this file is what makes forgetting one a build failure.

Each entry adapts one service to two common operations:

* ``resolve(env)`` → the ``{caller_id: secret}`` map, or raise. Startup-time.
* ``authenticate(env, authorization)`` → the matched caller id, or raise an HTTP
  401/503. Request-time.

The two services implement these differently — the lake through pydantic settings
and a FastAPI ``Header`` dependency, freyja through a plain resolver and a
``Request`` — which is the point. The contract is about behaviour, not shared code.
"""
from __future__ import annotations

import os
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field

from starlette.requests import Request


@contextmanager
def _only_env(env: dict[str, str]) -> Iterator[None]:
    """
    Run with exactly ``env`` visible for the variables that matter.

    Every contract-relevant variable is cleared first so a value leaking in from the
    developer's shell cannot make a failing case pass.
    """
    prefixes = ("ARAN_LAKE_", "FREYJA_")
    saved = {k: v for k, v in os.environ.items() if k.startswith(prefixes)}
    try:
        for key in saved:
            del os.environ[key]
        os.environ.update(env)
        yield
    finally:
        for key in [k for k in os.environ if k.startswith(prefixes)]:
            del os.environ[key]
        os.environ.update(saved)


@dataclass(frozen=True)
class Sidecar:
    name: str
    #: Canonical plural form: a JSON ``{caller_id: secret}`` object.
    secrets_env: str
    #: Legacy single-secret form, promoted to one entry. ``None`` if unsupported.
    legacy_secret_env: str | None
    profile_env: str
    #: The committed development secret this service must refuse in prod.
    dev_default_secret: str
    #: Other variables the service needs before it will construct at all.
    baseline_env: dict[str, str]
    _resolve: Callable[[], dict[str, str]]
    _authenticate: Callable[[str | None], str]
    #: What a misconfiguration raises. Type differs per service; behaviour must not.
    misconfigured: tuple[type[BaseException], ...] = field(default=(Exception,))

    #: Dotted path of the module implementing the bearer check, for the
    #: structural constant-time assertions.
    auth_module_path: str = ""

    def auth_module(self):
        return __import__(self.auth_module_path, fromlist=["_"])

    def env_with(self, **overrides: str) -> dict[str, str]:
        return {**self.baseline_env, **overrides}

    def resolve(self, env: dict[str, str]) -> dict[str, str]:
        with _only_env(env):
            return self._resolve()

    def authenticate(self, env: dict[str, str], authorization: str | None) -> str:
        with _only_env(env):
            return self._authenticate(authorization)


# ── aran-lake ────────────────────────────────────────────────────────────────


def _lake_resolve() -> dict[str, str]:
    from aran_lake.config import Settings

    settings = Settings()
    return {
        caller: secret.get_secret_value()
        for caller, secret in settings.caller_secret_map().items()
    }


def _lake_authenticate(authorization: str | None) -> str:
    from aran_lake.auth.bearer import require_bearer
    from aran_lake.config import Settings

    return require_bearer(authorization=authorization, settings=Settings()).caller_id


LAKE_BASELINE = {
    "ARAN_LAKE_DATABASE_URL": "postgresql+asyncpg://unused:unused@localhost/unused",
    "ARAN_LAKE_S3_ENDPOINT_URL": "http://localhost:3900",
    "ARAN_LAKE_S3_REGION": "garage",
    "ARAN_LAKE_S3_BUCKET": "aran-lake-test",
    "ARAN_LAKE_S3_ACCESS_KEY": "contract-test-access",
    "ARAN_LAKE_S3_SECRET_KEY": "contract-test-secret",
}


# ── freyja ───────────────────────────────────────────────────────────────────


def _freyja_resolve() -> dict[str, str]:
    from freyja.api.security import resolve_caller_secrets

    return resolve_caller_secrets()


def _freyja_authenticate(authorization: str | None) -> str:
    from freyja.api.security import require_bearer

    headers: list[tuple[bytes, bytes]] = []
    if authorization is not None:
        headers.append((b"authorization", authorization.encode()))
    request = Request({"type": "http", "method": "POST", "path": "/", "headers": headers})
    return require_bearer(request).caller_id


def build_registry() -> list[Sidecar]:
    """Built lazily so an import error names the service rather than the module."""
    from fastapi import HTTPException
    from freyja.api.security import (
        DEV_DEFAULT_SECRET as FREYJA_DEV_SECRET,
    )
    from freyja.api.security import (
        MisconfiguredError,
    )

    from aran_lake.config import _DEV_DEFAULT_SHARED_SECRET as LAKE_DEV_SECRET

    return [
        Sidecar(
            name="aran-lake",
            secrets_env="ARAN_LAKE_SHARED_SECRETS",
            legacy_secret_env="ARAN_LAKE_SHARED_SECRET",
            profile_env="ARAN_LAKE_PROFILE",
            dev_default_secret=LAKE_DEV_SECRET,
            baseline_env=LAKE_BASELINE,
            _resolve=_lake_resolve,
            _authenticate=_lake_authenticate,
            # pydantic wraps validator errors, so a ValidationError is the
            # misconfiguration signal here even though the validator raises ValueError.
            misconfigured=(ValueError,),
            auth_module_path="aran_lake.auth.bearer",
        ),
        Sidecar(
            name="freyja",
            secrets_env="FREYJA_SHARED_SECRETS",
            legacy_secret_env="FREYJA_SHARED_SECRET",
            profile_env="FREYJA_PROFILE",
            dev_default_secret=FREYJA_DEV_SECRET,
            baseline_env={},
            _resolve=_freyja_resolve,
            _authenticate=_freyja_authenticate,
            misconfigured=(MisconfiguredError, HTTPException),
            auth_module_path="freyja.api.security",
        ),
    ]


HTTP_UNAUTHORIZED = 401
HTTP_MISCONFIGURED = 503


def status_of(exc: BaseException) -> int | None:
    """The HTTP status an auth rejection carried, if it carried one."""
    return getattr(exc, "status_code", None)

"""
Per-caller bearer-auth tests (v0.2).

Validates:
  * Multiple configured secrets each authenticate as their own caller-id.
  * A wrong secret is rejected.
  * The legacy single-secret form still works (promoted to caller-id "default").
  * Empty / malformed bearer headers fail.
  * Caller-id is propagated into resources created during a request
    (covered in test_jobs.py for jobs; here we sanity-check at the auth
    layer in isolation).
"""
from __future__ import annotations

import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from aran_lake.auth.bearer import CallerIdentity, require_bearer
from aran_lake.config import Profile, Settings


def _mini_app_with(settings: Settings) -> FastAPI:
    """Build a tiny FastAPI app whose only route requires the bearer."""
    from fastapi import Depends

    from aran_lake.auth.bearer import _settings_dep

    app = FastAPI()
    app.dependency_overrides[_settings_dep] = lambda: settings

    @app.get("/whoami")
    def whoami(caller: CallerIdentity = Depends(require_bearer)) -> dict:  # noqa: B008
        return {"caller_id": caller.caller_id}

    return app


def _make_settings(
    *, secrets: dict[str, str] | None = None, single_secret: str | None = None,
) -> Settings:
    return Settings(
        database_url="postgresql+asyncpg://x:x@localhost/x",
        s3_endpoint_url="http://localhost:3900",
        s3_region="garage",
        s3_bucket="aran-lake-test",
        s3_access_key="test-access",            # type: ignore[arg-type]
        s3_secret_key="test-secret",            # type: ignore[arg-type]
        shared_secrets_json=(json.dumps(secrets) if secrets else None),
        shared_secret=single_secret,            # type: ignore[arg-type]
        profile=Profile.DEV,
    )


def test_multi_secret_each_authenticates_as_its_caller() -> None:
    settings = _make_settings(
        secrets={"aran": "secret-aran", "freyja": "secret-freyja"},
    )
    app = _mini_app_with(settings)
    client = TestClient(app)

    r1 = client.get("/whoami", headers={"Authorization": "Bearer secret-aran"})
    assert r1.status_code == 200
    assert r1.json()["caller_id"] == "aran"

    r2 = client.get("/whoami", headers={"Authorization": "Bearer secret-freyja"})
    assert r2.status_code == 200
    assert r2.json()["caller_id"] == "freyja"


def test_wrong_secret_is_rejected() -> None:
    settings = _make_settings(secrets={"aran": "secret-aran"})
    client = TestClient(_mini_app_with(settings))
    r = client.get("/whoami", headers={"Authorization": "Bearer not-the-right-thing"})
    assert r.status_code == 401


def test_legacy_single_secret_form_promotes_to_default_caller() -> None:
    """v0.1 callers passing ARAN_LAKE_SHARED_SECRET get caller-id 'default'."""
    settings = _make_settings(single_secret="legacy-single-secret")
    client = TestClient(_mini_app_with(settings))
    r = client.get(
        "/whoami", headers={"Authorization": "Bearer legacy-single-secret"},
    )
    assert r.status_code == 200
    assert r.json()["caller_id"] == "default"


def test_missing_authorization_header_is_rejected() -> None:
    settings = _make_settings(secrets={"aran": "secret-aran"})
    client = TestClient(_mini_app_with(settings))
    r = client.get("/whoami")
    assert r.status_code == 401


def test_empty_bearer_value_is_rejected() -> None:
    settings = _make_settings(secrets={"aran": "secret-aran"})
    client = TestClient(_mini_app_with(settings))
    r = client.get("/whoami", headers={"Authorization": "Bearer "})
    assert r.status_code == 401


def test_non_bearer_scheme_is_rejected() -> None:
    settings = _make_settings(secrets={"aran": "secret-aran"})
    client = TestClient(_mini_app_with(settings))
    r = client.get("/whoami", headers={"Authorization": "Basic Zm9vOmJhcg=="})
    assert r.status_code == 401


def test_settings_requires_at_least_one_secret() -> None:
    with pytest.raises(ValueError, match="no shared secret configured"):
        Settings(
            database_url="postgresql+asyncpg://x:x@localhost/x",
            s3_endpoint_url="http://localhost:3900",
            s3_bucket="b",
            s3_access_key="a",   # type: ignore[arg-type]
            s3_secret_key="s",   # type: ignore[arg-type]
            profile=Profile.DEV,
        )


def test_settings_rejects_malformed_secrets_json() -> None:
    # rationale: the ValueError may legitimately come from either Settings
    # validation or the lazy caller_secret_map() parse — both are the contract.
    with pytest.raises(ValueError, match="failed to parse"):  # noqa: PT012
        s = Settings(
            database_url="postgresql+asyncpg://x:x@localhost/x",
            s3_endpoint_url="http://localhost:3900",
            s3_bucket="b",
            s3_access_key="a",   # type: ignore[arg-type]
            s3_secret_key="s",   # type: ignore[arg-type]
            shared_secrets_json="{not valid json",
            profile=Profile.DEV,
        )
        s.caller_secret_map()  # trigger lazy parse


def test_settings_rejects_empty_secrets_object() -> None:
    # rationale: as above — either validation or lazy parse may raise.
    with pytest.raises(ValueError, match="non-empty"):  # noqa: PT012
        s = Settings(
            database_url="postgresql+asyncpg://x:x@localhost/x",
            s3_endpoint_url="http://localhost:3900",
            s3_bucket="b",
            s3_access_key="a",   # type: ignore[arg-type]
            s3_secret_key="s",   # type: ignore[arg-type]
            shared_secrets_json="{}",
            profile=Profile.DEV,
        )
        s.caller_secret_map()

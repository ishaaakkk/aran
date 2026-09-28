"""
Auth on the FREYJA sidecar.

freyja reads ``tenant_id`` from the request body and then asserts it to the lake using
the lake's privileged shared secret, so an unauthenticated caller that can reach this
port can read any tenant's snapshot data. These tests pin the bearer check that stops
that, the startup refusal that stops the service running without one, and the one thing
neither must break: health probes.

HISTORY, because the change these pin is a behaviour break. Enforcement used to be
opt-in — unset secret meant "serve unauthenticated and log a warning" — as a
compatibility carve-out for sidecars started before Aran sent the header. Aran now has
a non-empty default for ``aran.freyja.secret``, so that carve-out protects nobody and
its only remaining effect was to let a misconfigured deployment expose every tenant's
data behind a log line. The service now refuses to start instead.
"""

import os
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from freyja.api.profile_api import app
from freyja.api.security import (
    DEV_DEFAULT_SECRET,
    MisconfiguredError,
    resolve_caller_secrets,
)

SECRET = "test-shared-secret"

# A syntactically valid body; these tests only care whether auth runs before the
# handler, so the request never needs to reach a real lake.
BODY = {
    "tenant_id": "00000000-0000-0000-0000-000000000001",
    "snapshot_id": "snap-1",
}


@pytest.fixture
def client():
    return TestClient(app, raise_server_exceptions=False)


# ── Startup refuses an unsafe configuration ──────────────────────────────────


def test_startup_refuses_when_secret_is_unset():
    """
    The headline guarantee: no secret, no service. Fails at startup rather than
    per request, so the failure is impossible to miss and the port never opens.
    """
    with pytest.raises(MisconfiguredError) as excinfo:
        resolve_caller_secrets(env={})
    assert "FREYJA_SHARED_SECRET" in str(excinfo.value)


def test_startup_refuses_a_blank_secret():
    with pytest.raises(MisconfiguredError):
        resolve_caller_secrets(env={"FREYJA_SHARED_SECRET": "   "})


def test_startup_refuses_the_dev_default_in_prod():
    """The dev sentinel is committed to this repository, so it is a public string."""
    with pytest.raises(MisconfiguredError) as excinfo:
        resolve_caller_secrets(
            env={"FREYJA_SHARED_SECRET": DEV_DEFAULT_SECRET, "FREYJA_PROFILE": "prod"}
        )
    assert "development default" in str(excinfo.value)


def test_dev_default_is_allowed_outside_prod():
    """Local development must keep working without ceremony."""
    assert resolve_caller_secrets(
        env={"FREYJA_SHARED_SECRET": DEV_DEFAULT_SECRET}
    ) == {"default": DEV_DEFAULT_SECRET}


def test_a_real_secret_passes_in_prod():
    assert resolve_caller_secrets(
        env={"FREYJA_SHARED_SECRET": SECRET, "FREYJA_PROFILE": "prod"}
    ) == {"default": SECRET}


# ── Enforcement, when the secret is configured ───────────────────────────────


@patch.dict(os.environ, {"FREYJA_SHARED_SECRET": SECRET})
def test_missing_authorization_header_is_rejected(client):
    resp = client.post("/operators/profile", json=BODY)
    assert resp.status_code == 401
    assert resp.headers.get("WWW-Authenticate") == "Bearer"


@patch.dict(os.environ, {"FREYJA_SHARED_SECRET": SECRET})
def test_wrong_secret_is_rejected(client):
    resp = client.post(
        "/operators/profile", json=BODY, headers={"Authorization": "Bearer wrong"}
    )
    assert resp.status_code == 401


@patch.dict(os.environ, {"FREYJA_SHARED_SECRET": SECRET})
def test_non_bearer_scheme_is_rejected(client):
    resp = client.post(
        "/operators/profile", json=BODY, headers={"Authorization": f"Basic {SECRET}"}
    )
    assert resp.status_code == 401


@patch.dict(os.environ, {"FREYJA_SHARED_SECRET": SECRET})
def test_empty_bearer_is_rejected(client):
    resp = client.post(
        "/operators/profile", json=BODY, headers={"Authorization": "Bearer "}
    )
    assert resp.status_code == 401


@patch.dict(os.environ, {"FREYJA_SHARED_SECRET": SECRET})
@pytest.mark.parametrize("path", ["/operators/profile", "/operators/distance", "/operators/rank"])
def test_every_operator_endpoint_is_protected(client, path):
    """Auth is on the router, so a new endpoint cannot be added unprotected by accident."""
    resp = client.post(path, json=BODY)
    assert resp.status_code == 401, f"{path} was reachable without a token"


@patch.dict(os.environ, {"FREYJA_SHARED_SECRET": SECRET})
def test_correct_secret_passes_auth(client):
    """A valid token must get past auth — proven by NOT getting a 401."""
    resp = client.post(
        "/operators/profile", json=BODY, headers={"Authorization": f"Bearer {SECRET}"}
    )
    # The handler will fail for its own reasons (no lake configured here); the point is
    # that it was reached at all rather than short-circuited by the auth dependency.
    assert resp.status_code != 401


@patch.dict(os.environ, {"FREYJA_SHARED_SECRET": SECRET})
def test_bearer_scheme_is_case_insensitive(client):
    resp = client.post(
        "/operators/profile", json=BODY, headers={"Authorization": f"bearer {SECRET}"}
    )
    assert resp.status_code != 401


# ── Health probes must stay open ─────────────────────────────────────────────


@patch.dict(os.environ, {"FREYJA_SHARED_SECRET": SECRET})
def test_healthz_is_not_behind_auth(client):
    """The probe lives on the app, not the operators router, so it stays reachable."""
    resp = client.get("/healthz")
    # Reachability, not health. This fixture builds TestClient WITHOUT the context manager,
    # so the lifespan never runs and no model is loaded — /healthz then correctly reports 503.
    # Asserting 200 here conflated "not behind auth" with "healthy" and broke the moment the
    # probe started telling the truth about the model.
    assert resp.status_code not in (401, 403)


# ── Request-time behaviour when startup validation was somehow bypassed ──────


def test_unset_secret_denies_rather_than_serving(client, monkeypatch):
    """
    Startup should already have refused, so this branch means something bypassed it.
    It must deny. This is the exact case that previously returned data to anyone who
    asked, and it is the reason this file exists.
    """
    monkeypatch.delenv("FREYJA_SHARED_SECRET", raising=False)
    resp = client.post("/operators/profile", json=BODY)
    assert resp.status_code == 503
    assert resp.status_code != 200


def test_unset_secret_denies_even_with_a_plausible_token(client, monkeypatch):
    """A caller guessing a token must not be able to talk a misconfigured sidecar round."""
    monkeypatch.delenv("FREYJA_SHARED_SECRET", raising=False)
    resp = client.post(
        "/operators/profile", json=BODY, headers={"Authorization": "Bearer anything"}
    )
    assert resp.status_code == 503

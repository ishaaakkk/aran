"""
The security contract every Aran Python sidecar must satisfy.

Each test runs against EVERY registered sidecar. A new service is held to the same
rules the moment it is added to ``registry.py``; a service that stops complying
turns this suite red rather than quietly diverging.

This exists because ``aran-lake`` and ``freyja`` had already drifted apart while
both looked correct in isolation: freyja served unauthenticated when its secret was
unset, and the lake rejected a lowercase ``bearer`` scheme that freyja accepted —
so the same client worked against one service and not the other. Documentation did
not catch either. This does.

The four rules, and the tests that pin them:

1. Fail closed at startup      → ``TestRule1FailClosed``
2. Refuse dev secrets in prod  → ``TestRule2NoDevSecretsInProd``
3. Constant-time comparison    → ``TestRule3ConstantTime`` (structural)
4. Uniform wire behaviour      → ``TestRule4WireBehaviour``
"""
from __future__ import annotations

import ast
import inspect
import json

import pytest

from .registry import HTTP_MISCONFIGURED, HTTP_UNAUTHORIZED, Sidecar, build_registry, status_of

SIDECARS = build_registry()
IDS = [s.name for s in SIDECARS]
REAL_SECRET = "a-real-high-entropy-secret"


@pytest.fixture(params=SIDECARS, ids=IDS)
def sidecar(request: pytest.FixtureRequest) -> Sidecar:
    return request.param


class TestRule1FailClosed:
    """No secret configured means no service. There is no unauthenticated mode."""

    def test_no_secret_at_all_is_refused(self, sidecar: Sidecar):
        with pytest.raises(sidecar.misconfigured):
            sidecar.resolve(sidecar.env_with())

    def test_blank_secret_is_refused(self, sidecar: Sidecar):
        if sidecar.legacy_secret_env is None:
            pytest.skip("no single-secret form")
        with pytest.raises(sidecar.misconfigured):
            sidecar.resolve(sidecar.env_with(**{sidecar.legacy_secret_env: "   "}))

    def test_empty_secrets_map_is_refused(self, sidecar: Sidecar):
        with pytest.raises(sidecar.misconfigured):
            sidecar.resolve(sidecar.env_with(**{sidecar.secrets_env: "{}"}))

    def test_malformed_secrets_map_is_refused(self, sidecar: Sidecar):
        with pytest.raises(sidecar.misconfigured):
            sidecar.resolve(sidecar.env_with(**{sidecar.secrets_env: "not json"}))

    def test_secrets_map_that_is_not_an_object_is_refused(self, sidecar: Sidecar):
        with pytest.raises(sidecar.misconfigured):
            sidecar.resolve(sidecar.env_with(**{sidecar.secrets_env: '["a","b"]'}))


class TestRule2NoDevSecretsInProd:
    """The dev sentinels are committed to this repository, so they are public."""

    def test_dev_default_is_refused_in_prod(self, sidecar: Sidecar):
        env = sidecar.env_with(
            **{
                sidecar.secrets_env: json.dumps({"aran": sidecar.dev_default_secret}),
                sidecar.profile_env: "prod",
            }
        )
        with pytest.raises(sidecar.misconfigured):
            sidecar.resolve(env)

    def test_dev_default_is_allowed_outside_prod(self, sidecar: Sidecar):
        """Local development must keep working without ceremony."""
        env = sidecar.env_with(
            **{sidecar.secrets_env: json.dumps({"aran": sidecar.dev_default_secret})}
        )
        assert sidecar.resolve(env) == {"aran": sidecar.dev_default_secret}

    def test_a_real_secret_is_accepted_in_prod(self, sidecar: Sidecar):
        env = sidecar.env_with(
            **{
                sidecar.secrets_env: json.dumps({"aran": REAL_SECRET}),
                sidecar.profile_env: "prod",
            }
        )
        assert sidecar.resolve(env) == {"aran": REAL_SECRET}


class TestResolutionShape:
    """Both services read the same variables with the same precedence."""

    def test_plural_form_supports_multiple_callers(self, sidecar: Sidecar):
        env = sidecar.env_with(
            **{sidecar.secrets_env: json.dumps({"aran": "s1", "freyja": "s2"})}
        )
        assert sidecar.resolve(env) == {"aran": "s1", "freyja": "s2"}

    def test_singular_form_is_promoted_to_one_caller(self, sidecar: Sidecar):
        if sidecar.legacy_secret_env is None:
            pytest.skip("no single-secret form")
        env = sidecar.env_with(**{sidecar.legacy_secret_env: REAL_SECRET})
        resolved = sidecar.resolve(env)
        assert list(resolved.values()) == [REAL_SECRET]
        assert len(resolved) == 1

    def test_plural_wins_over_singular(self, sidecar: Sidecar):
        if sidecar.legacy_secret_env is None:
            pytest.skip("no single-secret form")
        env = sidecar.env_with(
            **{
                sidecar.secrets_env: json.dumps({"aran": "from-map"}),
                sidecar.legacy_secret_env: "from-single",
            }
        )
        assert sidecar.resolve(env) == {"aran": "from-map"}


class TestRule3ConstantTime:
    """
    Structural, not statistical: a timing assertion in CI is a flaky test.

    What is checkable is that the comparison goes through ``compare_digest`` and
    that the loop over configured secrets has no early ``break`` — an early exit
    would leak which secret matched, and how many were tried.
    """

    def test_compares_with_compare_digest(self, sidecar: Sidecar):
        tree = ast.parse(inspect.getsource(sidecar.auth_module()))
        called = {
            node.func.attr if isinstance(node.func, ast.Attribute) else getattr(node.func, "id", "")
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
        }
        assert "compare_digest" in called, (
            f"{sidecar.name} must compare secrets with compare_digest, not =="
        )

    def test_the_secret_loop_does_not_break_early(self, sidecar: Sidecar):
        """
        Parsed, not grepped — both modules mention "break" in prose explaining why
        they do not do it, and a substring check would pass on that and fail on the
        real thing.
        """
        tree = ast.parse(inspect.getsource(sidecar.auth_module()))
        for loop in [n for n in ast.walk(tree) if isinstance(n, (ast.For, ast.While))]:
            calls_compare = any(
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "compare_digest"
                for node in ast.walk(loop)
            )
            if not calls_compare:
                continue
            assert not [n for n in ast.walk(loop) if isinstance(n, ast.Break)], (
                f"{sidecar.name}'s comparison loop breaks early — the total work "
                "would reveal which secret matched, and how many were tried first"
            )


class TestRule4WireBehaviour:
    """
    Identical HTTP behaviour for identical credentials.

    The lake and freyja disagreed here: the lake required exactly ``Bearer`` while
    freyja accepted any casing, so the same client passed one and 401'd on the
    other. RFC 7235 makes the scheme case-insensitive.
    """

    @pytest.fixture
    def configured(self, sidecar: Sidecar) -> dict[str, str]:
        return sidecar.env_with(
            **{sidecar.secrets_env: json.dumps({"aran": REAL_SECRET})}
        )

    def test_correct_token_authenticates_and_names_the_caller(
        self, sidecar: Sidecar, configured: dict[str, str]
    ):
        assert sidecar.authenticate(configured, f"Bearer {REAL_SECRET}") == "aran"

    def test_scheme_is_case_insensitive(self, sidecar: Sidecar, configured: dict[str, str]):
        assert sidecar.authenticate(configured, f"bearer {REAL_SECRET}") == "aran"
        assert sidecar.authenticate(configured, f"BEARER {REAL_SECRET}") == "aran"

    @pytest.mark.parametrize(
        "header",
        [None, "", "Bearer", "Bearer ", f"Basic {REAL_SECRET}", "Bearer wrong-secret"],
        ids=["absent", "blank", "scheme-only", "empty-token", "wrong-scheme", "wrong-secret"],
    )
    def test_bad_credentials_are_401(
        self, sidecar: Sidecar, configured: dict[str, str], header: str | None
    ):
        with pytest.raises(Exception) as excinfo:  # noqa: PT011 — tested across multiple framework exception types
            sidecar.authenticate(configured, header)
        assert status_of(excinfo.value) == HTTP_UNAUTHORIZED, (
            f"{sidecar.name} returned {status_of(excinfo.value)} for {header!r}"
        )

    def test_surrounding_whitespace_in_the_token_is_tolerated(
        self, sidecar: Sidecar, configured: dict[str, str]
    ):
        assert sidecar.authenticate(configured, f"Bearer  {REAL_SECRET}  ") == "aran"

    def test_the_right_caller_is_named_when_several_are_configured(self, sidecar: Sidecar):
        env = sidecar.env_with(
            **{sidecar.secrets_env: json.dumps({"aran": "secret-a", "freyja": "secret-b"})}
        )
        assert sidecar.authenticate(env, "Bearer secret-a") == "aran"
        assert sidecar.authenticate(env, "Bearer secret-b") == "freyja"

    def test_a_misconfigured_service_denies_rather_than_serving(self, sidecar: Sidecar):
        """
        Startup should already have refused. If something bypassed it, requests must
        still not be served — this is the branch that used to return data to anyone.

        The requirement is denial, not a particular status. The two services fail
        differently by construction: freyja resolves per request and answers 503,
        while the lake builds its settings once at app creation, so a misconfigured
        lake cannot reach a request at all and raises during construction. Demanding
        one status here would mean restructuring the lake for a branch it cannot
        take. What must hold in both is: raises, and returns no caller.
        """
        with pytest.raises(Exception) as excinfo:  # noqa: PT011 — raises framework-specific or misconfig error
            sidecar.authenticate(sidecar.env_with(), f"Bearer {REAL_SECRET}")
        carried = status_of(excinfo.value)
        assert carried is None or carried in (HTTP_UNAUTHORIZED, HTTP_MISCONFIGURED), (
            f"{sidecar.name} answered {carried} for an unconfigured request"
        )

"""
Configuration settings for the aran-lake service.

All configuration is read from environment variables with the prefix
``ARAN_LAKE_``. Settings are validated at startup; a misconfigured service
fails to start rather than silently using bad defaults.

v0.2 introduces **per-caller auth**: ``ARAN_LAKE_SHARED_SECRETS`` is a JSON
map from caller-id to secret (``{"aran": "...", "freyja": "..."}``). The
legacy single-secret form ``ARAN_LAKE_SHARED_SECRET`` is still accepted for
backward compatibility and is internally promoted to ``{"default": "..."}``.

The profile (dev/prod) gates a small set of safety checks — most notably,
no secret may be left at the development default in production.
"""
from __future__ import annotations

import json
from enum import StrEnum
from functools import lru_cache
from typing import Any

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Profile(StrEnum):
    DEV = "dev"
    PROD = "prod"


_DEV_DEFAULT_SHARED_SECRET = "dev-shared-secret-change-in-prod"  # noqa: S105 — dev sentinel; startup refuses it in prod
_DEV_DEFAULT_ACCESS_KEYS = frozenset({
    "GK000000000000000000000000",
})

# Caller-id reserved for the legacy single-secret form. Useful in dev where
# a single secret is convenient; in prod we expect a dict with real names.
_DEFAULT_CALLER_ID = "default"


class Settings(BaseSettings):
    """
    Runtime configuration.

    Field names map to env vars via ``model_config.env_prefix``:
    ``database_url`` → ``ARAN_LAKE_DATABASE_URL`` and so on.
    """

    model_config = SettingsConfigDict(
        env_prefix="ARAN_LAKE_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        populate_by_name=True,
    )

    # --- Catalog ---
    database_url: str = Field(..., description="SQLAlchemy async URL for the catalog DB")

    # --- Object storage ---
    s3_endpoint_url: str = Field(..., description="S3-compatible endpoint (Garage in dev)")
    s3_region: str = Field(default="garage")
    s3_bucket: str = Field(..., description="The single bucket all tenant data lives under")
    s3_access_key: SecretStr = Field(...)
    s3_secret_key: SecretStr = Field(...)

    # --- Auth & identity ---
    #
    # Two accepted forms:
    #
    #   ARAN_LAKE_SHARED_SECRETS='{"aran":"<secret>","freyja":"<secret>"}'
    #     The v0.2 form — one secret per caller. Caller-id is recorded in
    #     audit logs.
    #
    #   ARAN_LAKE_SHARED_SECRET='<secret>'
    #     The v0.1 form. Still supported. Internally promoted to a single
    #     entry under caller-id "default". Useful for dev / single-caller
    #     deployments.
    #
    # If both are set, ``SHARED_SECRETS`` wins. If neither is set, startup fails.
    shared_secret: SecretStr | None = Field(default=None,
        description="v0.1 single-secret form (deprecated; prefer SHARED_SECRETS)")
    shared_secrets_json: str | None = Field(default=None,
        alias="ARAN_LAKE_SHARED_SECRETS",
        description="v0.2 per-caller form: JSON map from caller-id to secret")

    # --- Runtime ---
    profile: Profile = Field(default=Profile.DEV)
    log_level: str = Field(default="INFO")

    # --- Tuning ---
    presign_ttl_seconds: int = Field(default=900, ge=60, le=3600 * 12)
    download_presign_ttl_seconds: int = Field(default=300, ge=30, le=3600,
        description="TTL for presigned GET URLs (shorter than upload TTL by default)")
    query_timeout_seconds: int = Field(default=30, ge=1, le=600)
    max_query_rows: int = Field(default=1_000_000, ge=100)
    multipart_threshold_bytes: int = Field(default=64 * 1024 * 1024)  # 64 MiB
    job_poll_interval_seconds: float = Field(default=0.5, ge=0.1, le=10.0)

    # Job-output limits — cap how big a single job's local parquet can grow
    # before we refuse to upload it. Stops a runaway import from exhausting
    # the worker's disk. Named for the materialize job these were introduced
    # for; that job was deleted 2026-08-27 and ``handle_import_from_postgres``
    # is the remaining consumer of both. Kept under their old names because
    # renaming them would break every deployment's ARAN_LAKE_MATERIALIZE_*
    # environment for no behavioural gain.
    materialize_max_bytes: int = Field(default=10 * 1024 * 1024 * 1024,  # 10 GiB
        description="Hard cap on a job's output size")
    materialize_temp_dir: str = Field(default="/tmp",  # noqa: S108 — operator-overridable scratch dir
        description="Local scratch directory for job outputs staged before upload")

    # The claimed content_hash of a completed upload is both the dedup key and
    # the object key, so /complete re-hashes the staged bytes rather than
    # trusting the claim. That read happens in the request path, which is why
    # it is bounded: above this ceiling the endpoint keeps size-only
    # verification and logs a warning naming the unverified hash.
    hash_verify_max_bytes: int = Field(default=1024 * 1024 * 1024,  # 1 GiB
        ge=1024 * 1024, le=50 * 1024 * 1024 * 1024,
        description="Largest staged upload whose SHA-256 is verified server-side")

    @field_validator("log_level")
    @classmethod
    def _valid_log_level(cls, v: str) -> str:
        upper = v.upper()
        if upper not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
            raise ValueError(f"invalid log level: {v}")
        return upper

    # ----- derived: the canonical caller→secret map -----
    #
    # Lazily computed and cached. We don't expose this as a real Field
    # because pydantic-settings would try to read it from env; instead it's
    # a property over the two input fields.

    def caller_secret_map(self) -> dict[str, SecretStr]:
        """
        Return the active caller-id → SecretStr mapping.

        Resolution rules:
          1. If ``SHARED_SECRETS`` is set, parse it as JSON and use it.
          2. Else if ``SHARED_SECRET`` is set, promote to ``{"default": ...}``.
          3. Else: empty dict (validation will reject this at startup).
        """
        if self.shared_secrets_json:
            try:
                raw: dict[str, Any] = json.loads(self.shared_secrets_json)
            except json.JSONDecodeError as e:
                raise ValueError(
                    "ARAN_LAKE_SHARED_SECRETS must be a JSON object "
                    "({caller_id: secret}); failed to parse"
                ) from e
            if not isinstance(raw, dict) or not raw:
                raise ValueError(
                    "ARAN_LAKE_SHARED_SECRETS must be a non-empty JSON object"
                )
            return {str(k): SecretStr(str(v)) for k, v in raw.items()}

        if self.shared_secret is not None:
            return {_DEFAULT_CALLER_ID: self.shared_secret}

        return {}

    @model_validator(mode="after")
    def _validate_secret_presence(self) -> Settings:
        m = self.caller_secret_map()
        if not m:
            raise ValueError(
                "no shared secret configured: set ARAN_LAKE_SHARED_SECRETS "
                "(JSON map) or ARAN_LAKE_SHARED_SECRET (single secret)"
            )
        # Caller-ids must be non-empty and sane (used in logs).
        for caller_id in m:
            if not caller_id or not caller_id.strip():
                raise ValueError("caller-id in ARAN_LAKE_SHARED_SECRETS cannot be blank")
        # …and so must the secrets. A whitespace-only secret is not a weak secret,
        # it is no secret: it satisfies the "is it set?" check while authenticating
        # anyone who sends the same whitespace. Caught by tests/sidecar_contract,
        # which found freyja rejecting this and the lake accepting it.
        for caller_id, secret in m.items():
            if not secret.get_secret_value().strip():
                raise ValueError(
                    f"the shared secret for caller {caller_id!r} is blank"
                )
        return self

    @model_validator(mode="after")
    def _validate_prod_safety(self) -> Settings:
        """
        In ``prod``, reject any leftover dev defaults that would create a
        security hole. The check runs at startup, never at request time.
        """
        if self.profile is not Profile.PROD:
            return self

        for caller_id, secret in self.caller_secret_map().items():
            if secret.get_secret_value() == _DEV_DEFAULT_SHARED_SECRET:
                raise ValueError(
                    f"shared secret for caller {caller_id!r} is left at the "
                    f"dev default. Set a high-entropy value in production."
                )
        if self.s3_access_key.get_secret_value() in _DEV_DEFAULT_ACCESS_KEYS:
            raise ValueError(
                "ARAN_LAKE_S3_ACCESS_KEY is a known dev default. "
                "Provision real Garage/S3 credentials for production."
            )
        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """
    Return the singleton Settings object. Cached so env reads happen once.
    In tests, call ``get_settings.cache_clear()`` between tests that mutate
    the environment.
    """
    return Settings()  # type: ignore[call-arg]

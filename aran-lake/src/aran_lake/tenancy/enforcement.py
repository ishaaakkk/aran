"""
Tenancy enforcement.

The lake is told *which tenant* a request acts on via the
``X-Tenant-Id`` header. Aran is responsible for not lying about this —
it has verified the JWT, resolved the user's tenant, and now asserts it
to the lake. The bearer-secret auth (``require_bearer``) is what gives
us the right to trust the header at all.

In production, every endpoint depends on both ``require_bearer`` and
``require_tenant`` — the first proves the caller is Aran, the second
tells us which tenant Aran is acting on behalf of.
"""
from __future__ import annotations

from uuid import UUID

from fastapi import Header, HTTPException, status

_TENANT_HEADER = "X-Tenant-Id"


def require_tenant(
    x_tenant_id: str | None = Header(default=None, alias=_TENANT_HEADER),
) -> UUID:
    """
    FastAPI dependency. Returns the UUID for the active tenant, or raises
    400 if the header is missing or malformed.

    Validation is strict — we require a real UUID, not just a non-blank
    string. (One of Aran's own bugs was accepting any string as a tenant
    id and crashing deep in the persistence layer; the lake won't repeat
    that.)
    """
    if not x_tenant_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"missing {_TENANT_HEADER} header",
        )
    try:
        return UUID(x_tenant_id)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"invalid {_TENANT_HEADER} header (must be a UUID)",
        ) from e


def require_actor(
    x_actor_id: str | None = Header(default=None, alias="X-Actor-Id"),
) -> UUID | None:
    """
    Optional: the user id on whose behalf Aran is acting. Used for
    ``created_by`` audit columns. Not required — system jobs have no
    actor — so absence is fine, but a present-but-malformed header is
    a client error.
    """
    if not x_actor_id:
        return None
    try:
        return UUID(x_actor_id)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="invalid X-Actor-Id header (must be a UUID)",
        ) from e

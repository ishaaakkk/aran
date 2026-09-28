"""Reading Aran's data products over its REST API.

Everything here is a GET. That is not incidental: it is what lets the whole
sidecar run on an ``ApiTokenScope.READ`` token, and it is asserted by the tests
rather than left as an intention. If a write ever appears in this module, the
credential this package asks users for stops being sufficient.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from aran_client import Connection

#: Statuses whose snapshot can actually be read. DRAFT and MATERIALIZING have no
#: snapshot behind them, so exposing them would offer an agent something that
#: cannot be fetched.
READABLE_STATUSES = frozenset({"READY", "STALE"})


class ProductNotFound(Exception):
    """No product matched. Carries what was available, so the agent can retry usefully."""

    def __init__(self, name: str, available: Sequence[str]):
        self.name = name
        self.available = list(available)
        known = ", ".join(sorted(self.available)) or "none"
        super().__init__(f"no readable data product named {name!r}. Available: {known}")


class AmbiguousProduct(Exception):
    """Two products share a name.

    Product names are not unique per tenant — they are unique to nothing at all — so
    resolving by name can genuinely tie. Erring is the only honest answer: picking one
    would silently give the agent a different product's rows than the user meant.
    """

    def __init__(self, name: str, ids: Sequence[str]):
        self.name = name
        self.ids = list(ids)
        super().__init__(
            f"{len(self.ids)} data products are named {name!r} ({', '.join(self.ids)}). "
            "Ask for it by id instead."
        )


@dataclass(frozen=True)
class Product:
    """One data product, flattened across the project it lives in."""

    id: str
    project_id: str
    name: str
    description: str | None
    oql: str
    schema_version_id: str
    status: str
    row_count: int | None
    materialized_at: str | None

    @classmethod
    def from_wire(cls, project_id: str, body: Mapping[str, Any]) -> Product:
        return cls(
            id=str(body["id"]),
            project_id=project_id,
            name=str(body["name"]),
            description=body.get("description"),
            oql=str(body.get("oql", "")),
            schema_version_id=str(body["schemaVersionId"]),
            status=str(body["status"]),
            row_count=body.get("rowCount"),
            materialized_at=body.get("materializedAt"),
        )

    @property
    def is_stale(self) -> bool:
        return self.status == "STALE"


class ProductCatalog:
    """The set of data products one token can read.

    Scope is the token's tenant, enforced server-side — this class cannot widen it and
    does not try. ``project_filter`` narrows further, for noise rather than for access
    control: an agent handed fifty products spends its context on a menu.
    """

    def __init__(self, connection: Connection, *, project_filter: str | None = None):
        self._connection = connection
        self._project_filter = project_filter

    def projects(self) -> list[Mapping[str, Any]]:
        projects = self._connection.get_json("/api/projects") or []
        if self._project_filter is None:
            return list(projects)
        wanted = self._project_filter
        return [
            p for p in projects
            if str(p.get("id")) == wanted or str(p.get("name")) == wanted
        ]

    def list_products(self) -> list[Product]:
        """Every readable product across the visible projects.

        STALE products are included and flagged, not filtered out. An agent reading a
        month-old snapshot should be told that, and hiding it makes the omission
        invisible rather than safe.
        """
        found: list[Product] = []
        for project in self.projects():
            project_id = str(project.get("id"))
            body = self._connection.get_json(f"/api/projects/{project_id}/products") or []
            for raw in body:
                if str(raw.get("status")) in READABLE_STATUSES:
                    found.append(Product.from_wire(project_id, raw))
        return found

    def resolve(self, name_or_id: str) -> Product:
        """Find one product by id, or by name when the name is unambiguous."""
        products = self.list_products()

        by_id = [p for p in products if p.id == name_or_id]
        if by_id:
            return by_id[0]

        by_name = [p for p in products if p.name == name_or_id]
        if not by_name:
            raise ProductNotFound(name_or_id, [p.name for p in products])
        if len(by_name) > 1:
            raise AmbiguousProduct(name_or_id, [p.id for p in by_name])
        return by_name[0]

    def preview(self, product: Product, limit: int) -> Mapping[str, Any]:
        """Rows from the materialized snapshot, shaped like a workbench query result.

        ``limit`` is passed through rather than applied here, so the cap costs one
        bounded response instead of a large one the caller then discards.
        """
        return self._connection.get_json(
            f"/api/projects/{product.project_id}/products/{product.id}/preview?limit={limit}"
        ) or {}

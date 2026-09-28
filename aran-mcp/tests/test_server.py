"""The MCP surface, exercised through the real protocol.

The client here is not a test double: ``Client(server)`` runs the actual MCP
implementation in-process, so tool discovery, argument coercion and result shaping
go through the same code an agent would drive. Only the network hop to Aran is
local, and even that speaks real HTTP (see conftest).

Each test pins one claim the server makes about itself. If a claim stops being
true, the test naming it fails.
"""

from __future__ import annotations

import json

from aran_mcp import DEFAULT_LIMIT, MAX_LIMIT
from mcp import Client

from .conftest import OTHER_PROJECT_ID, PROJECT_ID, SCHEMA_VERSION_ID, product


async def call(server, tool: str, **arguments) -> dict:
    """Invoke a tool and decode its JSON payload."""
    async with Client(server) as client:
        result = await client.call_tool(tool, arguments or {})
    return json.loads(result.content[0].text)


class TestToolSurface:
    async def test_advertises_exactly_two_tools_and_neither_writes(self, server):
        """§3 — no write path exists.

        Asserted exactly rather than by absence: adding `materialize` later must break
        this test on purpose instead of slipping in behind a looser check.
        """
        async with Client(server) as client:
            tools = await client.list_tools()
        assert {t.name for t in tools.tools} == {"list_products", "read_product"}

    async def test_tools_have_descriptions_an_agent_can_act_on(self, server):
        async with Client(server) as client:
            tools = await client.list_tools()
        for tool in tools.tools:
            assert tool.description
            assert len(tool.description) > 30

    async def test_no_text_an_agent_reads_calls_the_schema_version_a_pin(self, server):
        """UI audit J6-F1 — a product's schema version is provenance, not an execution pin.

        A refresh re-runs the product's OQL under whatever schema is ACTIVE then
        (DataProduct.java: "a record, not an execution pin"), so telling the agent its
        shape "does not change underneath you" was false. The instructions and every
        tool/resource description are what the agent's model reads, so all are checked.
        """
        from aran_mcp._server import INSTRUCTIONS

        async with Client(server) as client:
            tools = await client.list_tools()
            templates = await client.list_resource_templates()
        texts = [INSTRUCTIONS]
        texts += [t.description or "" for t in tools.tools]
        texts += [t.description or "" for t in templates.resource_templates]
        for text in texts:
            assert "pinned" not in text.lower(), text
            assert "does not change underneath" not in text, text
        assert "defined against" in INSTRUCTIONS

    def test_the_readme_does_not_call_the_schema_version_a_pin_either(self):
        """J6-F1, the integrator's copy: the README's tool and resource tables restate the
        descriptions above, so they drifted back to "pinned" when only the server was fixed."""
        from pathlib import Path

        readme = (Path(__file__).resolve().parents[1] / "README.md").read_text(encoding="utf-8")
        for retired in ("pinned", "pins the schema version", "change shape underneath"):
            assert retired not in readme, retired
        assert "defined against" in readme


class TestListProducts:
    async def test_only_readable_products_are_listed(self, server):
        """§3 — DRAFT and MATERIALIZING are not exposed; they have no snapshot."""
        payload = await call(server, "list_products")
        names = {p["name"] for p in payload["products"]}
        assert names == {"papers", "stale_papers"}
        assert payload["count"] == 2

    async def test_stale_is_reported_not_hidden(self, server):
        """§3 — an agent reading an old snapshot should be told, not protected from it."""
        payload = await call(server, "list_products")
        stale = next(p for p in payload["products"] if p["name"] == "stale_papers")
        assert stale["stale"] is True
        assert stale["materializedAt"] == "2026-01-05T08:00:00Z"

        fresh = next(p for p in payload["products"] if p["name"] == "papers")
        assert fresh["stale"] is False

    async def test_every_product_carries_its_pinned_schema_version(self, server):
        """§2 — the version pin is the entire justification for serving products.

        If a product could reach an agent without one, the design's central claim —
        that the shape cannot change underneath the consumer — is not being kept.
        """
        payload = await call(server, "list_products")
        assert payload["products"]
        for entry in payload["products"]:
            assert entry["schemaVersionId"] == SCHEMA_VERSION_ID

    async def test_spans_projects_because_products_are_project_scoped(self, server, aran):
        """Products live under projects; the token's tenant is the real boundary."""
        aran.projects.append({"id": OTHER_PROJECT_ID, "name": "Teaching",
                              "tenantId": "t", "description": ""})
        aran.products_by_project[OTHER_PROJECT_ID] = [product("prod-other", "courses")]

        payload = await call(server, "list_products")
        assert {p["name"] for p in payload["products"]} == {
            "papers", "stale_papers", "courses",
        }

    async def test_project_filter_narrows_noise(self, aran):
        """--project is ergonomics, not access control. It must still actually filter."""
        from aran_mcp import ProductCatalog, build_server

        from aran_client import Connection

        from .conftest import TOKEN

        aran.projects.append({"id": OTHER_PROJECT_ID, "name": "Teaching",
                              "tenantId": "t", "description": ""})
        aran.products_by_project[OTHER_PROJECT_ID] = [product("prod-other", "courses")]

        scoped = build_server(
            ProductCatalog(Connection(aran.base_url, TOKEN), project_filter="Teaching")
        )
        payload = await call(scoped, "list_products")
        assert {p["name"] for p in payload["products"]} == {"courses"}


class TestReadProduct:
    async def test_returns_columns_and_rows(self, server):
        payload = await call(server, "read_product", product="papers")
        assert payload["product"] == "papers"
        assert payload["columns"] == ["title", "year"]
        assert payload["columnIris"] == [
            "http://aran/Paper#title", "http://aran/Paper#year",
        ]
        assert payload["rows"]

    async def test_limit_is_passed_upstream_not_applied_locally(self, server, aran):
        """§3 — the cap should cost one bounded response, not a large discarded one."""
        await call(server, "read_product", product="papers", limit=5)
        assert aran.observed_limits == [5]

    async def test_default_limit_is_used_when_unspecified(self, server, aran):
        await call(server, "read_product", product="papers")
        assert aran.observed_limits == [DEFAULT_LIMIT]

    async def test_oversized_limit_is_clamped_rather_than_refused(self, server, aran):
        """An agent asking for 10_000 wants as many as it can have; failing teaches it nothing."""
        await call(server, "read_product", product="papers", limit=10_000)
        assert aran.observed_limits == [MAX_LIMIT]

    async def test_never_returns_more_rows_than_asked_even_if_aran_does(self, server, aran):
        """V37 gap 3 / J6-F11: a VIRTUAL product's read used to ignore `limit` and serve
        1,000 rows; an agent asking for 20 put all of them in its context. The server now
        honours the limit, and the sidecar bounds what it hands the agent regardless."""
        aran.ignores_limit = True
        aran.available_rows = 1000
        payload = await call(server, "read_product", product="papers", limit=20)
        assert payload["rowCount"] == 20
        assert len(payload["rows"]) == 20
        assert payload["truncated"] is True

    async def test_resolves_by_id_as_well_as_name(self, server):
        payload = await call(server, "read_product", product="prod-ready")
        assert payload["product"] == "papers"

    async def test_unknown_product_names_what_is_available(self, server):
        """The agent can correct itself from this; a protocol error is opaque to it."""
        payload = await call(server, "read_product", product="nope")
        assert "error" in payload
        assert "papers" in payload["error"]

    async def test_ambiguous_name_errors_rather_than_guessing(self, server, aran):
        """§6 Q4 — picking one would silently return a different product's rows."""
        aran.products_by_project[PROJECT_ID].append(product("prod-dup", "papers"))

        payload = await call(server, "read_product", product="papers")
        assert "error" in payload
        assert "prod-ready" in payload["error"]
        assert "prod-dup" in payload["error"]

    async def test_draft_products_are_not_readable(self, server):
        payload = await call(server, "read_product", product="draft_papers")
        assert "error" in payload


class TestResource:
    async def test_product_definition_is_the_contract_not_the_data(self, server):
        async with Client(server) as client:
            result = await client.read_resource("aran://product/papers")
        body = json.loads(result.contents[0].text)

        assert body["oql"] == "FIND Paper.title, Paper.year"
        assert body["schemaVersionId"] == SCHEMA_VERSION_ID
        assert body["status"] == "READY"
        assert "rows" not in body


class TestReadOnly:
    """The claim that a READ-scoped token is sufficient, made structural."""

    async def test_every_request_to_aran_is_a_get(self, server, aran):
        """§4 — the day someone reaches for a POST, this fails loudly.

        Kept even if every other assertion here is dropped: it is the difference
        between intending to be read-only and being read-only.
        """
        await call(server, "list_products")
        await call(server, "read_product", product="papers", limit=3)
        async with Client(server) as client:
            await client.read_resource("aran://product/papers")

        assert aran.requests, "the stub saw no traffic; the test proves nothing"
        assert aran.methods_used == {"GET"}

    async def test_the_token_actually_reaches_aran(self, server, aran):
        """A seam a mock would hide: the bearer header being sent at all."""
        await call(server, "list_products")
        assert aran.auth_headers
        assert all(h == "Bearer aran_pat_abcdef123456" for h in aran.auth_headers)

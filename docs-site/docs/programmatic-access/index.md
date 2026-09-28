---
title: 'Programmatic access'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'Four ways to reach Aran from code: JDBC, MCP, the Python SDK, and the Iceberg catalog. What each is for.'
# Diátaxis mode: explanation
---

Aran has four ways to reach it from code, not a browser. Each fits a
different job.

| | Best for | Speaks | Needs a data product? |
| --- | --- | --- | --- |
| [JDBC](./jdbc.md) | Any SQL client: DBeaver, a BI tool, a script | OQL on `graph`, plain SQL on `products` and `sources` | No |
| [MCP](./mcp.md) | An AI agent: Claude Desktop, Claude Code, Cursor | The Model Context Protocol | Yes |
| [Python SDK](./python-sdk.md) | A notebook or a scheduled script | OQL, plus schema-alignment calls | No |
| [Iceberg catalog](./iceberg.md) | An Iceberg engine that can send a bearer token on its file reads: DuckDB, iceberg-core's REST client | The Iceberg REST protocol, read-only | Yes — materialised only |

All four connect to the application's own port, **8081** by default. The
standalone compose file publishes it on host port **8080** (container 8081).
Port 8080 inside the dev compose stack is Keycloak, not Aran.

## Token scopes: the split is by route, not by meaning

All four paths authenticate with the same **API token**. A token is
`READ`- or `FULL`-scoped, and the scopes divide by **route**, not by
whether an action reads or writes:

- A `READ` token can send any GET, and exactly three POSTs:
  `/api/query/export`, `/api/operators/query-engine/invoke`, and
  `/api/jdbc`. Every operation of the `query-engine` operator is allowed
  through `/invoke`. Any other POST fails with 403
  *This API token is read-only.*
- A `FULL` token is required for everything else — including calls that
  are semantically reads. `POST /api/projects/{id}/model/suggest` (the
  SDK's `propose_mapping`) proposes and persists nothing, but it is not on
  the READ allowlist. `/api/operators/{operator}/submit` needs FULL for
  every operator, `query-engine` included. Invoking any operation on any
  operator other than `query-engine` — even a `ping` — is refused for a
  READ token: *a READ-scope API token cannot invoke '…' on \<operator\>*.
- No token of either scope reaches the administrative routes
  `/api/tenant/members`, `/api/tenant/admin`, `/api/admin`, `/api/tokens`,
  or a tenant owner's membership routes — `/api/tenants/{id}/members`
  (adding a member) and `/api/tenants/{id}/membership/**` (leaving the
  tenant). Those answer 403 *Administration requires an interactive
  session.* `GET /api/tenants/{id}/license` stays readable with a token,
  so a client can read its own license and usage. Minting a token requires
  an OIDC session, so a leaked token cannot mint its replacement.

So: querying, exporting, browsing products, JDBC and the read-only Iceberg
catalog need only `READ`.
Registering a repository, aligning schemas, bootstrapping, profiling, and
join discovery need `FULL`.

## Manage tokens over REST

The UI path is your avatar at the bottom of the left rail → **API Tokens**. The same surface is
`/api/tokens` — reachable only with an interactive session, never with a
token (see above).

| Method | Path | Body / result |
| --- | --- | --- |
| `GET` | `/api/tokens` | `{id, name, prefix, scope, status, expiresAt, lastUsedAt, revokedAt, createdAt}` per token. `status` is computed: `revoked`, `active`, or `expired`. |
| `POST` | `/api/tokens` | `{name, scope?, expiryDays?}` → **201** with `{token, secret}`. The `secret` is populated in this response only. |
| `DELETE` | `/api/tokens/{tokenId}` | Revoke → **204**. |

- `scope` omitted or blank means `READ`. An unknown value is rejected with
  *scope must be READ or FULL, got: …*
- The secret is `aran_pat_` followed by URL-safe base64 entropy.
- Tokens are per-user, never per-tenant, and these routes only ever reach the
  caller's own. An owner sees every token in the tenant under
  **Administration › Credentials** and can revoke one there
  (`DELETE /api/tenant/admin/tokens/{tokenId}`, owner-only, recorded in
  Activity).
- Removing a user from the tenant revokes all of their tokens. Adding the same
  email again later restores their account with a new temporary password —
  never their tokens, and never the password they had.
- `expiryDays` sets an expiry; `expiresAt` is returned on every listing.

## Rate limits

Every `/api/**` request from an authenticated caller is rate-limited to
**300 requests per minute** with a **burst of 50** on top, keyed per token
— one notebook pulling a large result does not starve another. Anonymous
traffic gets 60 per minute. Over the limit, Aran answers **429** with a
`Retry-After` header and the problem-detail body
`{"detail":"Rate limit exceeded. Retry after the Retry-After interval."}`.
A BI tool's metadata sweep or a tight job-polling loop can hit this; it is
throttling, not an outage.

## Why MCP is different

JDBC and the Python SDK both run **live OQL** against the integrated
schema. A query answers from current data, planned fresh each time.

MCP does not. It serves **data products** — named query results. The agent
sees every `READY` or `STALE` product its token can read; there is no
per-product publish switch.

A product is served in one of two ways. A **materialised** product hands
the agent the rows of its last refresh, however old they are. A
**virtual** product recomputes on every read, so an agent always sees
current rows. See [Materialised and virtual data
products](../deep-dives/virtual-data-products.md).

## The same policy governs all four

A deployment decides which credential presentations it accepts, in one
place:

```yaml
aran:
  security:
    auth:
      methods: oidc,api-token-bearer     # JDBC access is now off
      require-tls: true                  # and every credential must arrive over TLS
```

The three method ids are `oidc`, `api-token-bearer`, and
`api-token-basic`. JDBC presents its token as HTTP Basic, so removing
`api-token-basic` turns JDBC off; the example above does exactly that.

`GET /api/auth/policy` reports the active policy without authenticating.
Check it first if any of the four refuses a token you believe is valid —
the deployment may simply not accept that presentation.

## Related

- [Register a data repository](../how-to/register-a-data-repository.md)
- [Create and materialise a data product](../how-to/create-a-data-product.md) — the surface MCP serves.
- [Export query results](../how-to/export-query-results.md) — the export path JDBC and the Python SDK share.

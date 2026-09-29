---
title: 'Connect your tools'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'How to reach Aran from SQL clients, notebooks, scripts, AI agents and Iceberg engines, what each interface is for, and how API tokens work.'
# Diátaxis mode: explanation
---

This page is for anyone who wants Aran's integrated data outside the browser:
in a SQL client, a notebook, a pipeline, an AI agent or an Iceberg engine. Pick
the row that matches your tool, then follow its first call below.

| | Best for | Speaks | Needs a data product? |
| --- | --- | --- | --- |
| [JDBC](./jdbc.md) | Any SQL client: DBeaver, a BI tool, a script | OQL on `graph`, plain SQL on `products` and `sources` | No |
| [MCP](./mcp.md) | An AI agent: Claude Desktop, Claude Code, Cursor | The Model Context Protocol | Yes |
| [Python SDK](./python-sdk.md) | A notebook or a scheduled script | OQL, plus schema-alignment calls | No |
| [REST export](../how-to/export-query-results.md) | A script or pipeline that wants a file | CSV or Parquet from `POST /api/query/export` | No |
| [Iceberg catalog](./iceberg.md) | An Iceberg engine that can send a bearer token on its file reads: DuckDB, iceberg-core's REST client | The Iceberg REST protocol, read-only | Yes (materialised only) |

All of them connect to the application's own port, **8081** by default. The
standalone compose file publishes it on host port **8080** (container 8081).
Port 8080 inside the dev compose stack is Keycloak, not Aran.

## A first call with each

Every interface needs an API token. Open your avatar at the bottom of the left
rail, choose **API Tokens** and create one with `READ` scope, which is enough
for every call below. The examples assume the application on
`localhost:8081`, the token in `ARAN_TOKEN`, and an integrated class called
`Paper`; replace them with your own.

**JDBC.** Load Avatica's remote driver (`avatica-core` 1.27.0) and connect with:

```
jdbc:avatica:remote:url=http://localhost:8081/api/jdbc;serialization=json;authentication=BASIC;avatica_user=aran;avatica_password=<your-token>
```

Then run `SELECT Paper.title FROM Paper` on a plain `Statement`. See [Connect
a JDBC client](./jdbc.md).

**MCP.** From a source checkout of Aran, install the sidecar and watch it read
your products over the real protocol:

```bash
make aran-mcp
ARAN_URL=http://localhost:8081 make mcp-demo
```

To connect Claude Desktop or another client instead, see [Connect an MCP
client](./mcp.md).

**Python SDK.** From a source checkout, install the SDK and run OQL into a
DataFrame:

```bash
pip install -e "./aran-client[pandas]"
```

```python
import aran_client as aran
aran.connect("http://localhost:8081")      # reads ARAN_TOKEN from the environment
df = aran.query("SELECT Paper.title FROM Paper")
```

See [Use the Python SDK](./python-sdk.md).

**REST export.** Download a whole result, with no row cap, as CSV (the
default) or Parquet:

```bash
curl -X POST http://localhost:8081/api/query/export \
  -H "Authorization: Bearer $ARAN_TOKEN" -H "Content-Type: application/json" \
  -d '{"oql": "SELECT Paper.title FROM Paper", "format": "CSV"}' \
  -o papers.csv
```

See [Export query results](../how-to/export-query-results.md).

**Iceberg catalog.** In DuckDB, give the file reads and the catalog the same
token, then query a materialised product by its table name:

```sql
INSTALL httpfs;  LOAD httpfs;
INSTALL iceberg; LOAD iceberg;
CREATE SECRET aran_files (TYPE HTTP, BEARER_TOKEN 'aran_pat_...');
ATTACH 'aran' AS aran (TYPE ICEBERG, ENDPOINT 'http://localhost:8081/api/iceberg', TOKEN 'aran_pat_...');
SELECT * FROM aran.products.papers_per_year;
```

See [Iceberg catalog](./iceberg.md).

## Token scopes: the split is by route, not by meaning

All of them authenticate with the same **API token**. A token is
`READ`- or `FULL`-scoped. The scopes divide by route, not by whether an action
reads or writes:

- A `READ` token can send any GET, and exactly three POSTs:
  `/api/query/export`, `/api/operators/query-engine/invoke`, and
  `/api/jdbc`. Every operation of the `query-engine` operator is allowed
  through `/invoke`. Any other POST fails with 403
  *This API token is read-only.*
- Everything else needs a `FULL` token, including calls that only read.
  `POST /api/projects/{id}/model/suggest` (the
  SDK's `propose_mapping`) proposes and persists nothing, but it is not on
  the READ allowlist. `/api/operators/{operator}/submit` needs FULL for
  every operator, `query-engine` included. Invoking any operation on any
  operator other than `query-engine`, even a `ping`, is refused for a
  READ token: *a READ-scope API token cannot invoke '…' on \<operator\>*.
- No token of either scope reaches the administrative routes
  `/api/tenant/members`, `/api/tenant/admin`, `/api/admin`, `/api/tokens`,
  or a tenant owner's membership routes: `/api/tenants/{id}/members`
  (adding a member) and `/api/tenants/{id}/membership/**` (leaving the
  tenant). Those answer 403 *Administration requires an interactive
  session.* `GET /api/tenants/{id}/license` stays readable with a token,
  so a client can read its own license and usage. Minting a token requires
  an OIDC session, so a leaked token cannot mint its replacement.

Querying, exporting, browsing products, JDBC and the read-only Iceberg catalog
need only `READ`. Registering a repository, aligning schemas, bootstrapping,
profiling and join discovery need `FULL`.

## Manage tokens over REST

In the interface, open your avatar at the bottom of the left rail, then **API Tokens**.
The same surface is `/api/tokens`, which needs an interactive session and
rejects tokens (see above).

| Method | Path | Body / result |
| --- | --- | --- |
| `GET` | `/api/tokens` | `{id, name, prefix, scope, status, expiresAt, lastUsedAt, revokedAt, createdAt}` per token. `status` is computed: `revoked`, `active`, or `expired`. |
| `POST` | `/api/tokens` | `{name, scope?, expiryDays?}` → **201** with `{token, secret}`. The `secret` is populated in this response only. |
| `DELETE` | `/api/tokens/{tokenId}` | Revoke → **204**. |

- `scope` omitted or blank means `READ`. An unknown value is rejected with
  *scope must be READ or FULL, got: …*
- The secret is `aran_pat_` followed by URL-safe base64 entropy.
- Tokens belong to a user, not a tenant, and these routes only reach the
  caller's own. An owner sees every token in the tenant under
  **Administration › Credentials** and can revoke one there
  (`DELETE /api/tenant/admin/tokens/{tokenId}`, owner-only, recorded in
  Activity).
- Removing a user from the tenant revokes all of their tokens. Adding the same
  email again later restores their account with a new temporary password. It
  does not restore their tokens or their old password.
- `expiryDays` sets an expiry; `expiresAt` is returned on every listing.

## Rate limits

Every `/api/**` request from an authenticated caller is rate-limited to
**300 requests per minute** with a **burst of 50** on top, keyed per token, so
one notebook pulling a large result does not starve another. Anonymous
traffic gets 60 per minute. Over the limit, Aran answers **429** with a
`Retry-After` header and the problem-detail body
`{"detail":"Rate limit exceeded. Retry after the Retry-After interval."}`.
A BI tool's metadata sweep or a tight job-polling loop can hit this. It is
throttling, not an outage.

## Why MCP is different

JDBC and the Python SDK run **live OQL** against the integrated schema, so a
query answers from current data and is planned fresh each time.

MCP serves **data products**, which are named query results. The agent sees
every `READY` or `STALE` product its token can read. There is no per-product
publish switch.

A **materialised** product gives the agent the rows of its last refresh,
however old they are. A **virtual** product recomputes on every read, so the
agent always sees current rows. See [Materialised and virtual data
products](../deep-dives/virtual-data-products.md).

## Which credentials a deployment accepts

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
Check it first if any of them refuses a token you believe is valid: the
deployment may not accept that presentation.

## Related

- [Register a data repository](../how-to/register-a-data-repository.md)
- [Create and materialise a data product](../how-to/create-a-data-product.md): the surface MCP serves.
- [Export query results](../how-to/export-query-results.md): the endpoint the Python SDK calls.

---
title: 'Invoke a capability operator'
sidebar_label: 'Invoke an operator'
sidebar_position: 7
description: 'Call an operator through the REST API, pass its parameters, and follow the resulting job.'
# Diátaxis mode: how-to
---

This page is for calling operators yourself; the UI calls them for you.

## Discover what is available

```http
GET /api/operators
Authorization: Bearer <token>
```

This returns every registered operator as a **manifest**. Each manifest
lists the operator's id, display name, and one entry per operation, with
that operation's parameter schema. Manifests are self-describing, so this
list is authoritative — this documentation is not.

The operators that ship today:

| Operator id | Operations |
| --- | --- |
| `bootstrapping` | `ping`, `bootstrap`, `drift-check`, `drift-probe` |
| `profiling` | `profile` |
| `join-discovery` | `ping`, `discover` |
| `constraint-discovery` | `ping`, `discover` |
| `schema-integration` | `ping`, `compile`, `incremental-integrate`, `suggest-alignments` |
| `mapping-catalog` | `default` — catalog derivation |
| `query-engine` | `catalog`, `explain`, `run`, `rewrite-oql`, `run-oql`, `validate-constraint`, `verify-wrapper`, `materialize-oql`, `preview-parquet`, `materialize-product-sql`, `materialize-source-sql` |

The three least obvious `query-engine` operations:

- **`verify-wrapper`** — checks whether a candidate wrapper SQL round-trips
  and could be planned expanded. All three parameters are required strings:
  `datasetId` (the dataset the wrapper reads from), `classIri` (the
  integrated class IRI the wrapper is a source of), and `sql` (the
  candidate text, referencing `${SNAPSHOT}`). It persists nothing — the
  verdict is returned, not written to the model.

  ```http
  POST /api/operators/query-engine/invoke
  Content-Type: application/json
  Authorization: Bearer aran_pat_…

  {
    "operation": "verify-wrapper",
    "parameters": {
      "datasetId": "5f2c8b41-0000-4000-8000-000000000001",
      "classIri":  "http://aran/int#Paper",
      "sql":       "SELECT * FROM ${SNAPSHOT} WHERE year > 2000"
    }
  }
  ```

- **`materialize-product-sql`** — runs a SQL SELECT over the `products`
  schema (required parameter: `sql`) and returns `data.path` (a
  server-local temp parquet) and `data.rowCount`. This is the operation
  the JDBC `products` route drives.
- **`materialize-source-sql`** — the same contract over the `sources`
  schema; drives the JDBC `sources` route.

## Choose invoke or submit

Two endpoints share the same body.

```http
POST /api/operators/{operatorId}/invoke    # synchronous, result in the response
POST /api/operators/{operatorId}/submit    # durable job, returns a job id
```

Each operation's manifest entry has a `longRunning` flag. **When it is true,
use `/submit`.** The work then runs on the job substrate, so its stages,
result, and error survive the request. Everything else can go through
`/invoke`.

These operations are long-running: `bootstrap`, `profile`,
`constraint-discovery`'s `discover`, `compile`, `incremental-integrate`,
`materialize-oql`, `materialize-product-sql`, and `materialize-source-sql`.

## Token scope decides which endpoint you can reach

A `READ`-scoped token can POST **only** `/api/operators/query-engine/invoke`
— every `query-engine` operation is allowed there, and nothing else is:

- `/submit` on any operator, `query-engine` included, answers 403
  *This API token is read-only.*
- Any operation on any other operator — even a `ping` — is refused with
  *a READ-scope API token cannot invoke '…' on \<operator\>*.
- No token of any scope reaches `/api/tenant/members`, `/api/tenant/admin`,
  `/api/admin`, `/api/tokens`, or a tenant owner's membership routes —
  `/api/tenants/{id}/members` and `/api/tenants/{id}/membership/**`: 403
  *Administration requires an interactive session.*
  `GET /api/tenants/{id}/license` stays readable with a token.

Everything else on this page needs a `FULL` token.

## Invoke synchronously

```http
POST /api/operators/join-discovery/invoke
Content-Type: application/json
Authorization: Bearer <token>

{
  "operation": "discover",
  "parameters": {
    "datasetIdA": "5f2c…",
    "datasetIdB": "9ab1…",
    "threshold": "0.5"
  }
}
```

All parameters are strings, including numbers and JSON. A JSON array
parameter is a string that contains JSON, not a nested object.

| Response | Meaning |
| --- | --- |
| `200` | `success: true`, payload on `data` |
| `422` | The operator returned a failure — read `errorCode` and `errorMessage`. An unknown **operator id** also lands here, as `OPERATOR_NOT_FOUND` |
| `404` | Unknown operator id on `/submit` only |

Aran checks required parameters before the operator runs: a missing or
blank required parameter fails without side effects. That is the whole
check — no type checking, no rejection of unknown parameters, and an
operation name the manifest does not list skips validation entirely.

:::note You cannot set the tenant
Your token supplies the tenant and user, never the body. Aran ignores
anything you put in the body for them.
:::

## Submit as a job

```http
POST /api/operators/bootstrapping/submit
Content-Type: application/json

{
  "operation": "bootstrap",
  "parameters": { "datasetId": "5f2c…", "projectId": "1a2b…" }
}
```

This returns a job id immediately. Include `projectId` when you have one —
Aran files the job under it. An unparseable `projectId` is silently
ignored, not an error, so a typo files the job under no project.

Then either poll:

```http
GET /api/jobs/{jobId}
```

The fields that matter are `status` (`SUCCEEDED`, `FAILED`, `CANCELLED`
are terminal) and `errorMessage`. Or subscribe to the event stream:

```http
GET /api/jobs/{jobId}/events
Accept: text/event-stream
```

The stream carries stage changes as the operator reports them —
"downloading snapshot", "building lattice". When an operator fails, it
fails the job and carries its `errorCode` onto the job row, instead of
burying the code in a log.

## `ping` proves registration, not health

Four operators expose `ping`: `bootstrapping`, `join-discovery`,
`schema-integration`, and `constraint-discovery`. `profiling` and
`query-engine` do not — `ping` on `profiling` returns
`UNSUPPORTED_OPERATION`.

Every `ping` that exists returns a hardcoded constant and contacts
nothing:

```json
{ "success": true,
  "data": { "status": "ok", "operator": "join-discovery",
            "message": "Join Discovery service operational" } }
```

It tells you the operator is registered — never whether FREYJA, the lake,
or anything else is reachable. To check FREYJA before a long job, use the
real health probes instead:

- `GET /actuator/health` — public, no credential; carries a `freyja`
  indicator that performs a real `GET /health` against the sidecar.
- `GET /api/tenant/admin/system` — owner-gated (interactive session only;
  no API token reaches it); returns `{version, commit, status,
  components[{name, status, details}]}` with broken components first.

## Authenticating without a browser

For scripts, mint an **API token** rather than reuse a browser session.
Manage them under your avatar (bottom of the left rail) → **API Tokens**, or over
[the token REST API](../programmatic-access/index.md#manage-tokens-over-rest)
— which itself requires an interactive session. Send the token as a bearer
token, exactly as shown above, and mind the
[scope rules](#token-scope-decides-which-endpoint-you-can-reach).

## Related

- [Write a new operator](./write-a-new-operator.md) — adding one of your own.
- The per-capability guides in this section, which use these endpoints for a
  specific task.

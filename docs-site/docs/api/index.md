---
title: 'REST API reference'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'How the API reference is generated from the service''s own OpenAPI description, and how to regenerate it.'
# Diátaxis mode: reference
---

The REST reference is generated from the running service and not written by
hand. The service describes itself through springdoc, a test captures that
description, and the docs site renders the captured file. Because nothing on
the reference page is authored, it cannot describe an endpoint that does not
exist.

## Where it is

The rendered reference appears in the sidebar as **REST endpoints**, at
`/api/rest-endpoints/`, drawn from the committed snapshot
`docs-site/openapi/aran-api.json`. It is grouped by
controller: springdoc tags each operation with the controller that serves
it, and Redoc groups by tag. The generated page is the list of controllers,
and a hand-maintained copy here would drift.

## Why a committed snapshot

There were three options, and two fail in ways this repository has already
seen.

Hand-written pages drift silently. The frontend's checked-in `openapi.d.ts`
served a single type, went stale and ended up contradicting the backend, and
nothing detected it.

Fetching `/v3/api-docs` at docs-build time would always be current, but it
makes the documentation build depend on a running backend with a database. That
is fragile for a static site, and it means you cannot build a docs preview
from a fork.

A committed snapshot, with CI failing when it drifts, keeps the accuracy of
the first option and the independence of the second. The docs build reads a
file. A CI job regenerates that file from the service and fails the build if
the committed copy no longer matches, so the snapshot cannot go stale unnoticed.

## Regenerating it

```bash
# 1. Capture the spec from the service (needs Docker — the test boots the
#    application against a Testcontainers PostgreSQL)
./gradlew :aran-application:test --tests '*OpenApiGenerationTest'

# 2. Canonicalise it and write it into the docs site
cd docs-site && node scripts/openapi-sync.mjs --write

# 3. Commit docs-site/openapi/aran-api.json
```

To check without writing:

```bash
cd docs-site && node scripts/openapi-sync.mjs --check
```

which is what CI runs. The docs workflow runs it on every pull request that
touches `docs-site/`, the backend's main sources (`aran-application`,
`aran-core`) or the build files that pin Spring Boot and springdoc, and on
pushes to `main` that touch the same paths. The job does not commit anything,
so a pull request that moves a controller or a wire DTO must regenerate and
commit the snapshot itself. A stale snapshot fails with the paths that
appeared or vanished, not a diff of several thousand lines, and the job
uploads the freshly generated spec as an artifact.

The sync script sorts keys first. springdoc makes no promise about property
order, and an order-sensitive comparison would fail on reordering alone,
which would teach everyone to ignore the check.

## What shapes the spec

`OpenApiConfig` in `aran-application` applies two customisations, so the
spec says a little more than springdoc's defaults:

- Non-nullable record components are marked required. Aran's wire DTOs are
  Java records, and a component without `@Nullable` is required. Springdoc
  cannot infer that, so the customiser walks the DTO records and adds the
  `required` entries.
- `*/*` response content is rewritten to `application/json`. Springdoc emits
  the wildcard for handlers that do not declare a media type. All of them
  return JSON, and a wildcard gives a client generator nothing to work with.

## Authentication

Every `/api/**` endpoint except `GET /api/auth/policy` and `GET /api/health`
needs a bearer token, either a Keycloak-issued JWT or an Aran API token.
An API token can also travel inside HTTP Basic, which is how a JDBC driver
sends it, when the deployment accepts `api-token-basic`. The actuator routes
are not on this port: they live on the internal management port, **8082** by
default.

```bash
curl -H "Authorization: Bearer $ARAN_TOKEN" http://localhost:8081/api/projects
```

The tenant comes from the caller's own `membership` row (or
the token's owner) and never from a parameter. For scripting, mint an API
token rather than reuse a browser session. Token scopes gate routes, not
read/write semantics; see
[Connect your tools](../programmatic-access/index.md#token-scopes-the-split-is-by-route-not-by-meaning).

See [Invoke a capability operator](../how-to/invoke-a-capability-operator.md)
for worked request and response examples against the operator endpoints,
which most automation uses.

## If the reference is missing

The snapshot is committed, so a normal checkout always has it. The site
still builds without it, for example on a branch cut before the snapshot was
committed, but then has no REST endpoints page and the build prints a warning.
Generate the snapshot with the steps above.

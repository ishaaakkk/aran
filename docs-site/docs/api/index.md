---
title: 'REST API reference'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'How the API reference is generated from the service''s own OpenAPI description, and how to regenerate it.'
# Diátaxis mode: reference
---

The REST reference is **generated from the running service**, not written by
hand. The service describes itself through springdoc. A test captures that
description. The docs site renders the captured file.

Nothing on the reference page is authored. So it cannot describe an endpoint
that does not exist.

## Where it is

The rendered reference appears in the sidebar as **REST endpoints**, at
`/api/rest-endpoints/`, drawn from the committed snapshot
`docs-site/openapi/aran-api.json`. It is grouped by
controller: springdoc tags each operation with the controller that serves
it, and Redoc groups by tag. The generated page is the list of
controllers — a hand-maintained copy here would drift, which is the
failure this page exists to avoid.

## Why a committed snapshot

Three options were available. Two of them fail in ways this repository has
already experienced.

**Hand-written pages** drift silently. This happened to the frontend's
checked-in `openapi.d.ts`: it served a single
type, went stale, and ended up contradicting the backend, with nothing
detecting it.

**Fetching `/v3/api-docs` at docs-build time** would always be current. But
it makes building the documentation depend on a running backend with a
database. That is a fragile thing to put in the path of a static site, and
it means you cannot build a docs preview from a fork.

**A committed snapshot, with CI failing when it drifts,** keeps the accuracy
of the first option and the independence of the second. The docs build reads
a file. A CI job regenerates that file from the service and fails the build
if what is committed no longer matches. The snapshot cannot go stale without
someone being told.

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
`aran-core`) or the build files that pin Spring Boot and springdoc, so a
change that moves a controller or a wire DTO regenerates and commits the
snapshot in the same pull request. A stale snapshot fails
with the paths that appeared or vanished, rather than a diff of several
thousand lines, and the job uploads the freshly generated spec as an
artifact.

Aran sorts keys on the way in. springdoc makes no promise about property
order, and an order-sensitive comparison would flake on reordering alone.
That would teach everyone to ignore the check.

## What shapes the spec

`OpenApiConfig` in `aran-application` applies two customisations, so the
spec says slightly more than springdoc's defaults:

- **Non-nullable record components are marked required.** Aran's wire DTOs
  are Java records, and a component without `@Nullable` is required.
  Springdoc cannot infer that on its own, so the customiser walks the DTO
  records and adds the `required` entries.
- **`*/*` response content is rewritten to `application/json`.** Springdoc
  emits the wildcard for handlers that do not declare a media type. Every
  one of them in fact returns JSON, and a wildcard tells a client generator
  nothing useful.

## Authentication

Every endpoint except the public actuator routes, `GET /api/auth/policy` and
`GET /api/health` needs a bearer token — a Keycloak-issued JWT or an Aran API
token. The tenant comes from the caller's
own `membership` row (or the token's owner), never from a parameter. For
scripting, mint an API token rather than reuse a browser session. Token
scopes gate routes, not read/write semantics — see
[Programmatic access](../programmatic-access/index.md#token-scopes-the-split-is-by-route-not-by-meaning).

See [Invoke a capability operator](../how-to/invoke-a-capability-operator.md)
for worked request and response examples against the operator endpoints.
Most automation goes through these endpoints.

## If the reference is missing

The snapshot is committed, so a normal checkout always has it. The site
still builds without it — a branch cut before the snapshot was committed,
say — and then simply has no REST endpoints page. The build prints a
warning that says so. Generate the snapshot with the steps above.

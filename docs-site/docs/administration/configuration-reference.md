---
title: 'Configuration reference'
sidebar_label: 'Configuration reference'
sidebar_position: 3
description: 'Every configuration property and environment variable the platform reads, with defaults.'
# Diátaxis mode: reference
---

This page lists the environment variables `aran-application` reads, with
defaults from `application.yml`, plus the variables the Compose files and the
`Dockerfile` read. ⚠ marks a security-critical development default.

## Guarded settings — the startup refusal

Nine settings are security-critical. `InsecureDefaultsGuard` checks each one
at startup, and refuses to start while any still holds its published
development default:

```
Refusing to start: N security-critical setting(s) still hold their development default.
  - <property> — set <VARIABLE>
  ...
Set each variable, or set aran.security.allow-insecure-defaults=true to accept them (dev, e2e and tests do).
```

The nine guarded settings and the values that trigger the refusal:

| Variable | Guarded default |
| --- | --- |
| `ARAN_DB_PASSWORD` | `aran` |
| `ARAN_OIDC_CLIENT_SECRET` | `change-me` |
| `ARAN_KEYCLOAK_ADMIN_CLIENT_SECRET` | `dev-backend-secret-change-in-prod` |
| `ARAN_LAKE_SECRET` | `dev-shared-secret-change-in-prod`, `standalone-secret-change-me` |
| `ARAN_FREYJA_SECRET` | `dev-freyja-secret-change-in-prod` |
| `ARAN_FUSEKI_USERNAME` | `admin` |
| `ARAN_FUSEKI_PASSWORD` | `admin` |
| `ARAN_ALLOWED_ORIGINS` | `*` |
| `ARAN_ENCRYPTION_KEY` | `standalone-encryption-key-change-me`, `aran-development-default-key-32c` |

`aran.security.allow-insecure-defaults` defaults to `false`. The `dev`,
`e2e`, and `standalone` profiles set it `true`; the guard then logs a WARN
naming each defaulted credential instead of refusing. Values are matched
exactly — a real password that merely resembles a default passes.

## Database

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_DB_USER` | `aran` | |
| `ARAN_DB_PASSWORD` | ⚠ `aran` | |
| `ARAN_DB_POOL_SIZE` | `20` | Hikari `maximum-pool-size` |
| `ARAN_DB_CONNECTION_TIMEOUT_MS` | `10000` | Hikari `connection-timeout`, milliseconds |

The JDBC URL itself is not an environment variable in `application.yml`. It
defaults to `jdbc:postgresql://localhost:5432/aran`. Override it with the
standard Spring property `SPRING_DATASOURCE_URL`.

Two settings are fixed rather than configurable, and deliberately:

- `ddl-auto: validate` — Flyway owns the schema. Hibernate checks the schema
  matches and never changes it.
- `open-in-view: false` — no lazy loading in the web layer.

The Hikari connection timeout bounds how long a caller waits to be handed a
pooled connection — under load that is mostly queueing time, not database
reachability. The pool is the only backpressure in front of the 256-way
virtual-thread front end, so the timeout tolerates a queue rather than
treating one as an outage. Startup-time reachability is
`initialization-fail-timeout`'s job, and Hikari fails fast there by default.

## Identity — OIDC

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_OIDC_ISSUER` | `http://localhost:8080/realms/aran` | |
| `ARAN_OIDC_JWK_SET_URI` | `…/protocol/openid-connect/certs` | |
| `ARAN_OIDC_AUTH_URI` | `…/protocol/openid-connect/auth` | |
| `ARAN_OIDC_TOKEN_URI` | `…/protocol/openid-connect/token` | |
| `ARAN_OIDC_USER_INFO_URI` | `…/protocol/openid-connect/userinfo` | |
| `ARAN_OIDC_CLIENT_ID` | `aran-web` | |
| `ARAN_OIDC_CLIENT_SECRET` | ⚠ `change-me` | |

Two things worth knowing:

- **No Keycloak library is on the classpath.** These are plain Spring Security
  OAuth2 properties. The word "keycloak" only ever appears inside a URL. Any
  compliant OIDC provider works.
- **Configure each endpoint explicitly — do not rely on discovery.** Discovery
  would block the context at startup with an extra request. Point all five at
  your provider, not just the issuer.

Aran accepts only `RS256` for JWT signatures. It rejects `alg:none` and the
`HS*` family outright.

**Tenancy needs no identity-provider configuration.** Aran resolves the
tenant from its own `membership` row, not from a claim on the token. Add a
user to a tenant from Aran's own admin screens — a tenant owner manages
membership directly — and nothing needs to change on the Keycloak side. See
[Multi-tenancy](../deep-dives/multi-tenancy.md).

## Identity — Keycloak Admin API

Aran creates and manages users through its own admin UI; this client is how
it reaches the system that stores the credential.

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_IDENTITY_PROVIDER` | `keycloak` | |
| `ARAN_KEYCLOAK_URL` | `http://localhost:8080` | Admin API base URL |
| `ARAN_KEYCLOAK_REALM` | `aran` | |
| `ARAN_KEYCLOAK_ADMIN_CLIENT_ID` | `aran-backend` | Confidential client, service account |
| `ARAN_KEYCLOAK_ADMIN_CLIENT_SECRET` | ⚠ `dev-backend-secret-change-in-prod` | Guarded |

## Security

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_ALLOWED_ORIGINS` | ⚠ `*` | Comma-separated SPA origins. The wildcard is dev-only |
| `ARAN_TRUSTED_PROXIES` | `127\.0\.0\.1\|::1\|0:0:0:0:0:0:0:1` | A **regex**, not a list. Which peers' forwarded headers Aran trusts |
| `ARAN_AUTH_METHODS` | empty = all three | Comma-separated: `oidc`, `api-token-bearer`, `api-token-basic`. Unknown name fails startup |
| `ARAN_AUTH_REQUIRE_TLS` | ⚠ `false` | Refuse credentialled requests not over TLS. Loopback always exempt |
| `ARAN_BLOCK_PRIVATE_NETWORKS` | `false` | Refuse connection tests against RFC1918/loopback targets. Set `true` for hosted multi-tenant |
| `ARAN_ENCRYPTION_KEY` | ⚠ none | Encrypts repository credentials at rest. See below |
| `aran.security.rate-limit.pre-auth-token-per-minute` | `3000` | Property only — no `ARAN_*` variable |
| `aran.security.allow-insecure-defaults` | `false` | Property only. `true` in dev/e2e/standalone |

Notes:

- **`ARAN_AUTH_METHODS`** — the accepted policy is published unauthenticated
  at `GET /api/auth/policy`. Disabling `api-token-basic` disables JDBC access
  by design: a JDBC driver can only present a token as a Basic password.
- **`ARAN_AUTH_REQUIRE_TLS`** — honours `X-Forwarded-Proto` only from peers
  matching `ARAN_TRUSTED_PROXIES`, so a TLS-terminating load balancer works
  and a client claiming TLS does not. Set `true` in any deployed environment.
- **`ARAN_BLOCK_PRIVATE_NETWORKS`** — link-local, wildcard, and multicast
  addresses are always refused regardless of this setting. Leave `false`
  on-prem, where the databases Aran federates live on private subnets.
- **`ARAN_ENCRYPTION_KEY`** — read as a raw environment variable, not a
  Spring property: the JPA converter that uses it runs outside the Spring
  container. Rotating the key makes already-stored repository credentials
  undecryptable. The standalone Compose file defaults it to a guarded
  sentinel; set a real value before the deployment is reachable.

## Network and ports

| Variable | Default | Notes |
| --- | --- | --- |
| — | `8081` | API port, `server.port` |
| `ARAN_MANAGEMENT_PORT` | `8082` | Actuator endpoints |

The management port is separate on purpose. **The API port serves no
`/actuator/**` paths at all.** Compose does not publish the management port to
the host. You can reach it only from inside the container or the Docker
network. Exposed endpoints are `health`, `info`, `metrics`, and `prometheus`.

## Lake, FREYJA, and Fuseki

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_LAKE_URL` | `http://localhost:8085` | |
| `ARAN_LAKE_SECRET` | ⚠ `dev-shared-secret-change-in-prod` | Must match the lake's `ARAN_LAKE_SHARED_SECRET` |
| `ARAN_LAKE_IMPORT_TIMEOUT` | `30m` | Ceiling on one lake-native import. Timing out fails the Aran job but does not cancel the lake's own job, which may still register a snapshot. |
| `ARAN_LAKE_IMPORT_POLL_INTERVAL` | `2s` | How often Aran asks the lake whether an import is done. Up to 5 consecutive failed polls are tolerated before the import fails. |
| `ARAN_FREYJA_URL` | `http://localhost:8086` | |
| `ARAN_FREYJA_SECRET` | ⚠ `dev-freyja-secret-change-in-prod` | Must match the sidecar's `FREYJA_SHARED_SECRET` |
| `ARAN_FUSEKI_URL` | `http://localhost:3030` | |
| `ARAN_FUSEKI_USERNAME` | ⚠ `admin` | Guarded |
| `ARAN_FUSEKI_PASSWORD` | ⚠ `admin` | Guarded |

:::info The FREYJA secret is mandatory
`FREYJA_SHARED_SECRET` must be set on the sidecar and equal Aran's
`ARAN_FREYJA_SECRET`. **The sidecar refuses to start without it**, and
refuses the committed development default when `FREYJA_PROFILE=prod` — it
fails closed rather than serving unauthenticated. See the
[Python sidecar security contract](../contributing/python-sidecar-security.md)
for why.
:::

Set on the **sidecars'** environment, not Aran's:

| Variable | Default | Notes |
| --- | --- | --- |
| `FREYJA_SHARED_SECRETS` | none | Canonical. JSON `{caller_id: secret}`, e.g. `{"aran":"…"}` |
| `FREYJA_SHARED_SECRET` | none | Legacy single-secret form, promoted to `{"default": …}` |
| `FREYJA_PROFILE` | `dev` | Set `prod` to reject the committed development secret |
| `ARAN_LAKE_SHARED_SECRETS` | none | Lake counterpart of `FREYJA_SHARED_SECRETS`, same shape |
| `ARAN_LAKE_SHARED_SECRET` | none | Lake counterpart of `FREYJA_SHARED_SECRET` |
| `ARAN_LAKE_PROFILE` | `dev` | Lake counterpart of `FREYJA_PROFILE` |
| `ARAN_LAKE_BIND` | `127.0.0.1` | Lake Compose bind address. Set `0.0.0.0` if Aran runs on another host |
| `ARAN_LAKE_S3_ACCESS_KEY` | ⚠ `GK0000…` | Required — the lake refuses to start without it |
| `ARAN_LAKE_S3_SECRET_KEY` | ⚠ `0000…` | Required — same |

`tests/sidecar_contract/` enforces that the lake and FREYJA variable families
stay aligned.

## Storage and caching

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_DRIVER_CACHE_DIR` | `/var/aran/drivers` | Verified JDBC driver JARs |
| `ARAN_SNAPSHOT_CACHE_DIR` | empty → `<java.io.tmpdir>/aran-snapshot-cache` | |
| `ARAN_SNAPSHOT_CACHE_MAX_BYTES` | `2147483648` (2 GiB) | LRU budget. `0` disables caching |
| `ARAN_WRAPPER_CACHE_DIR` | empty → `<java.io.tmpdir>/aran-wrapper-cache` | |
| `ARAN_WRAPPER_CACHE_MAX_BYTES` | `1073741824` (1 GiB) | LRU budget. `0` disables caching |
| `ARAN_PLACEMENT_ENABLED` | `true` | Per-query snapshot-vs-live placement for dual-access datasets |
| `ARAN_PLACEMENT_LIVE_LAMBDA` | `25.0` | Per-row cost weight of a live read vs a local scan |
| `ARAN_PLACEMENT_LOCAL_LAMBDA` | `1.0` | Per-row cost weight of a local snapshot scan |
| `ARAN_PLACEMENT_STALE_GRACE_FACTOR` | `2.0` | An interval-refreshed copy may serve until this × its interval has passed since the last refresh |
| `ARAN_PLANNER_PRODUCTION_BUDGET` | `50000` | Rule productions the query planner may make before it answers with its best plan so far (the plan view then says `BEST_SO_FAR`). A count, not a time, so plans are identical across machines. `0` disables the count. |
| `ARAN_PLANNER_WALL_CLOCK_GUARD` | `20s` | Second guard on planning time. When this one trips the plan can differ between machines; the plan view says `WALL_CLOCK`. |

All of these need real volumes in production. A container's tmpdir does not
survive a restart. For the two query caches, that costs you a cold cache, not
correctness — both are keyed on content-addressed inputs (snapshots are
immutable, and the wrapper cache's key binds the snapshot id, the wrapper
text and the sort spec), so entries never go stale and the only policy is
eviction.

The wrapper cache holds each class's wrapper output as Parquet, physically
sorted by the class's join keys, so repeated join-heavy queries scan a
pre-transformed, pruning-friendly file instead of re-running the wrapper.
Live (federated) sources are never cached.

**Size the snapshot cache against your data.** The per-entry ceiling is
`max-bytes / 4`. A budget under four times your largest snapshot admits
nothing, and every query falls back to fetching.

Losing the driver cache costs a re-download per engine. Each re-download
needs egress to Maven Central. See
[Driver resolution](../deep-dives/driver-resolution.md).

## Licensing

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_LICENSING_MODE` | `database` | `database`, `signed-token`, or `unlimited` |
| `ARAN_LICENSE_PUBLIC_KEY_PATH` | `/etc/aran/license-public.pem` | `signed-token` only |
| `ARAN_LICENSE_TOKEN` | empty | `signed-token` only |

`unlimited` is the OSS single-node mode: every tenant gets uncapped
entitlements and no `license` row is consulted. It is what
`compose.standalone.yml` runs. Plan limits are configuration under
`aran.licensing.plans.*`, not environment variables, and are read in
`database` mode only. See
[License configuration](./license-configuration.md).

## Refresh scheduler

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_REFRESH_SCHEDULER_ENABLED` | `true` | `false` takes an instance out of the refresh rotation |
| `ARAN_REFRESH_POLL_INTERVAL` | `60s` | How often Aran looks for due refreshes |
| `ARAN_REFRESH_INITIAL_DELAY` | `60s` | Keeps extracts out of startup |

The poll interval is how often Aran looks, not how often anything refreshes —
each dataset's own policy decides that, with a five-minute floor. Claims go
through the database (`FOR UPDATE SKIP LOCKED`), so each due refresh starts
exactly once however many instances poll.

## Singer taps

| Property | Default | Notes |
| --- | --- | --- |
| `aran.singer.taps` | `[]` = feature **off** | Property only — deployment configuration, never user input |

With no tap listed, the `SINGER_TAP` repository kind cannot be registered.
A complete entry:

```yaml
aran:
  singer:
    taps:
      - id: exchangeratesapi                           # what a user selects
        label: Exchange Rates API                      # what the form shows
        command: [/opt/taps/bin/tap-exchangeratesapi]  # absolute path on THIS host
        config-keys: [base, start_date, api_key]       # what a user may set
        secret-keys: [api_key]                         # which of those are credentials
```

The command is deployment configuration: a user picks an id and supplies
values, and Aran builds the argv. `config-keys` is a whitelist — an
undeclared setting is refused, not dropped. Values go into a JSON file the
tap reads, never onto the command line. `secret-keys` decides which values
the API masks; an unlisted key is visible to anyone who can read the
repository. A malformed entry fails startup. Aran ships no tap binary —
installing and licensing one is the operator's job.

## Shape as cube

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_CUBES_ENABLED` | `false` = feature **off** | The OLAP cube layer: the wizard, the cube list, and `/api/projects/{id}/cubes/**` |

Off because the cube slice is not ready for every deployment. Its API path
first ran against a live stack on 2026-09-24, and that run found defects —
a CSV fact admits only `COUNT` measures, and a star table can carry full-IRI
column names — that wait on a decision before the default changes. The
wizard has not yet run live.

With it off, `CubeController` is not registered at all: every cube route
answers **404**, not 403 — the feature does not exist on this deployment —
and the SPA hides the "shape as cube" button and the Cubes panel, because
`GET /api/me` reports `features.cubes: false`. One frontend bundle serves
every deployment, so the server is what decides this, not a build.

Nothing else changes. A cube's star tables are ordinary MATERIALIZED data
products, so a cube built while this was on keeps serving through the
products tier and the JDBC `products` schema with it off. What is withheld
is the cube *definition* surface: listing, creating, inspecting and deleting
cubes.

Set it `true` to try the feature — that is also the live run that is meant
to retire this flag.

## Standalone topology

Read by `compose.standalone.yml` and the `standalone` Spring profile — see
[Run Aran standalone](./standalone.md).

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_VERSION` | the version `build.gradle` declares | Tag of the three `ghcr.io/dtak-upc/aran*` images the compose file pulls |
| `ARAN_PUBLIC_URL` | `http://localhost:8080` | Pins Keycloak's `KC_HOSTNAME` and the OIDC issuer. **Loopback only in v0.1.0** — see [Reaching it from another machine](./standalone.md#reaching-it-from-another-machine) |
| `ARAN_BIND` | `127.0.0.1` | Host address `aran-app` publishes on. **Loopback only in v0.1.0**, as above |
| `ARAN_ADMIN_PASSWORD` | empty → generated | First-run owner password; standalone profile only |
| `ARAN_LAKE_FREYJA_SECRET` | the value of `ARAN_LAKE_SECRET` | What freyja presents to the lake. Set it to give freyja its own identity there |
| `ARAN_FREYJA_SECRET` | ⚠ `dev-freyja-secret-change-in-prod` | One variable feeds both sides: aran-app's `ARAN_FREYJA_SECRET` and the sidecar's `FREYJA_SHARED_SECRET` |
| `ARAN_FREYJA_URL` | `http://freyja:8086` | Fixed in the compose file — aran-app reaches the sidecar over the compose network |
| `ARAN_COMPOSE_FILE` | `compose.standalone.yml` | Read by `scripts/standalone-smoke.sh` |
| `ARAN_AUTH_PROXY_ENABLED` | unset = off | `true` enables the same-origin `/auth` Keycloak proxy |
| `ARAN_AUTH_PROXY_TARGET` | none | Upstream Keycloak URL, e.g. `http://keycloak:8080` |
| `POSTGRES_PASSWORD` | ⚠ `aran` | Both compose files |
| `KC_ADMIN_USER` | `admin` | Keycloak bootstrap admin |
| `KC_ADMIN_PASSWORD` | ⚠ `admin` | Also read by `./aran admin reset-password` |
| `DEMO_SOURCES_PASSWORD` | ⚠ `demo` | Dev compose `demo` profile only |
| `GIT_COMMIT` | `unknown` | Build arg; the builder stage has no git |
| `WITH_UI` | `false` | Build arg. `true` bakes the SPA into the image |

One name mismatch to know: the dev `docker-compose.yml` passes Fuseki's
password as bare `ADMIN_PASSWORD` (default ⚠ `admin`), while
`compose.standalone.yml` reads `ARAN_FUSEKI_PASSWORD` for the same setting.

## Observability

| Variable | Default | Notes |
| --- | --- | --- |
| `ARAN_OTLP_ENABLED` | `false` | Metrics export |
| `ARAN_OTLP_ENDPOINT` | `http://localhost:4318/v1/metrics` | Also used for `/v1/traces` |
| `ARAN_TRACING_ENABLED` | `false` | |
| `ARAN_TRACING_SAMPLE_RATE` | `0.1` | 10%. Set `1.0` in dev |
| `ARAN_LOG_FORMAT` | empty | Set `ecs` for structured JSON logs |

`ARAN_OTLP_ENDPOINT` is one variable. Aran uses it for both the metrics and
traces URLs, with a different path appended for each.

## Uploads

Multipart limits are fixed at **200 MB** per file and per request.
`aran-application` buffers the whole file on its way to the lake. Chunked
browser-to-S3 upload does not exist yet. So this ceiling is real, not a
formality.

## Runtime

Aran enables virtual threads (`spring.threads.virtual.enabled: true`).
Request handling, the operator orchestrator, and JDBC calls all run on them.
This is one reason for the Java 25 baseline. It is also why tenant context
uses `ScopedValue`, not `ThreadLocal`.

## Other services

`aran-lake` and `freyja` have their own configuration, passed as environment
variables at launch. The `Makefile`'s `ARAN_LAKE_DEV_ENV` block holds the
development values: database URL, S3 endpoint, region, bucket, access and
secret keys, shared secret, profile, and log level. Every one of those is a
development credential.

## Related

- [Run Aran standalone](./standalone.md)
- [Docker Compose services](./docker-compose.md)
- [License configuration](./license-configuration.md)
- [Troubleshooting](./troubleshooting.md)

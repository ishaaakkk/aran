---
title: 'Operational troubleshooting'
sidebar_label: 'Troubleshooting'
sidebar_position: 5
description: 'Symptoms an operator is likely to hit, what causes them, and how to confirm the fix.'
# Diátaxis mode: how-to
---

Each entry starts from a symptom an operator sees, names the cause, and says
how to confirm the fix. Find your symptom under the area where it shows up:
startup, authentication, ingestion, queries, drivers or observability.

## Startup

### "Refusing to start: N security-critical setting(s) still hold their development default"

`InsecureDefaultsGuard` throws this before the port opens:

```
Refusing to start: 3 security-critical setting(s) still hold their development default.
  - spring.datasource.password — set ARAN_DB_PASSWORD
  ...
Set each variable, or set aran.security.allow-insecure-defaults=true to accept them (dev, e2e and tests do).
```

The message lists each offending setting and the variable that overrides it.
Set every listed variable to a real value. `aran.security.allow-insecure-defaults=true`
is for development stacks only, and it turns the refusal into a WARN. The nine
guarded settings are listed in the
[configuration reference](./configuration-reference.md#guarded-settings--the-startup-refusal).

### The application will not start, complaining about the schema

Hibernate runs with `ddl-auto: validate`, so if a database's schema does not
match the entities, the context fails instead of migrating it. Either
Flyway did not run, or it ran against a different database.

Compare the highest `version` in the `flyway_schema_history` table with the
highest `V<n>__…sql` file in
`aran-adapters/aran-adapter-persistence/src/main/resources/db/migration/` for
the release you run. If the table is behind, Flyway did not run against this
database:

```sql
SELECT version, description, success FROM flyway_schema_history
ORDER BY installed_rank DESC LIMIT 1;
```

### An error names `aran.licensing.plans.<something>`

A plan in the `LicensePlan` enum has no configured limits. Startup does not
check this: the error appears the first time Aran resolves a tenant on that
plan, as `no limits configured for plan <PLAN>`. Add the block, as described
in [License configuration](./license-configuration.md).

### It will not start after adding a Singer tap

A malformed `aran.singer.taps` entry fails startup. The message names the
problem: `a tap needs an id`, `tap '<id>' has no command configured`, or
`tap '<id>' has a blank argument`. Fix or remove the entry; an empty list
turns the feature off. See the
[configuration reference](./configuration-reference.md#singer-taps).

### Startup hangs before the port opens

Almost always the database. The Hikari connection timeout is 10 seconds
(`ARAN_DB_CONNECTION_TIMEOUT_MS`), and it bounds the wait for a *pooled*
connection, not database reachability. Startup reachability fails fast
through Hikari's `initialization-fail-timeout`, so a real hang points at DNS
or a dropped connection, not a refused one.

## Authentication

### Every request returns 401

Aran is not accepting the token. In order of likelihood: the issuer or JWK
set URI points somewhere else; the token is signed with something other than
RS256 (Aran accepts only RS256 and rejects `alg:none` and
`HS*`); or the provider is unreachable.

### Authenticated, but every tenant-scoped request fails loudly

The caller holds no `membership` row, so there is no tenant to resolve. Code
that reaches tenant-scoped logic without a bound tenant throws, rather than
defaulting.

Give the identity a membership. Signing in as an identity with none produces
exactly this. The Keycloak admin account is the usual example, since it was
never added to a tenant.

### The browser is blocked by CORS

`ARAN_ALLOWED_ORIGINS` still holds its dev-only `*`, or does not list the
SPA's real origin. Serving the SPA and API from one origin behind a shared
gateway avoids it.

## Ingestion and the pipeline

### A dataset stays `BOOTSTRAPPING`

Usually the job event stream is stuck, not the job itself, and the work
typically finishes in seconds. Reload the page. If the row is still
stuck, check the job through `GET /api/jobs/{id}`.

### `BOOTSTRAP FAILED`

The file did not parse. The common cause is JSON whose root is neither an
object nor an array. Preview the dataset to see what arrived.

### A dataset went `DRIFTED`

This is not a failure. The source changed shape in a way that would break something
already compiled into the active schema, so Aran did not apply the
change. The previous graph is intact, and queries still work. See
[Handle drift](../how-to/bootstrap-and-profile-a-dataset.md#handle-drift).

### FREYJA refuses to start

Two configuration errors are fatal by design, and both name themselves in
the log:

- `Neither FREYJA_SHARED_SECRETS nor FREYJA_SHARED_SECRET is set.` One of
  them is mandatory. Set `FREYJA_SHARED_SECRET` to the same value as Aran's
  `ARAN_FREYJA_SECRET`.
- `… is still the development default while FREYJA_PROFILE=prod`. That
  value is committed to the repository, so it is public. Set a real secret.

A blank secret, or a blank caller id in `FREYJA_SHARED_SECRETS`, is refused
the same way.

The sidecar fails closed rather than serving unauthenticated. It reads
`tenant_id` from the request body and asserts it to the lake with a
privileged secret. See the
[Python sidecar security contract](../contributing/python-sidecar-security.md).

### FREYJA dies at startup with a `KeyError`

It is missing `ARAN_LAKE_URL` or `ARAN_LAKE_FREYJA_SECRET`, the two variables
it needs to reach the lake. `LakeConfig.from_env()` reads both without
defaults, so the sidecar dies during startup, not at the first request.
Check `freyja-sidecar.log`.

If you launch it by hand, verify that the variables reach the process, because
a shell-continuation trap can silently swallow them. See
[the sidecar security contract](../contributing/python-sidecar-security.md)
for the check.

### Profiling never produces anything

FREYJA is unreachable. Bootstrapping's automatic profile is best-effort: it
fails quietly and the job still succeeds, so a dataset can be `GRAPH READY`
with no profiles. A broken sidecar can therefore go unnoticed for a long time.

Ask the sidecar directly. Its health endpoint needs no credential, and it
answers 503 with `"model": "missing"` when the join-discovery model did not
load:

```bash
curl http://localhost:8086/healthz
```

If that works, check that Aran's `ARAN_FREYJA_URL` points at the same
address. The `freyja` component of `/actuator/health` on the management port
probes the same `/healthz` from Aran's side: it is DOWN when Aran cannot reach
FREYJA or when FREYJA answers 503 because its model is missing.

### Join discovery returns nothing

Both datasets must be profiled, because discovery compares profiles, not rows. See
above.

### Uploads over ~200 MB fail

That is the configured multipart ceiling, per file and per request, and the
application buffers the whole file on its way to the lake. Split the file, or
import from PostgreSQL instead.

## Queries

### `NO_SCHEMA`

No integrated schema version has been promoted. Integrate and promote first.

### Queries are correct but slow

Check the datasets are profiled. Without profiles, the optimiser gets a row
count of zero, which becomes Calcite's `Statistics.UNKNOWN`, and it then
has no basis for choosing between plans.

Then check the snapshot cache. If `ARAN_SNAPSHOT_CACHE_MAX_BYTES` is less
than **four times** your largest snapshot, nothing is ever admitted (the
per-entry ceiling is a quarter of the budget) and every query re-fetches.

### Queries got slow after a restart, then recovered

The snapshot cache was cold. If its directory is a container tmpdir, it does
not survive a restart. Mount a volume and set `ARAN_SNAPSHOT_CACHE_DIR`.

### A federated query fails but the connection test passes

The test and the query take different paths. The test opens JDBC from the
application. The query attaches the source database (PostgreSQL, MySQL or
MariaDB) from DuckDB inside the application, which needs its own
extension and its own reachability. A failure names the step that failed,
such as `federated setup statement N (ATTACH) failed:`, followed by the
server's own reason, such as *Access denied* or *Unknown server host*. Also
confirm the engine can be federated at all: only PostgreSQL, MySQL, and
MariaDB can.

## Drivers

### *Engine 'X' is not registered*

Only `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `SQLSERVER`, and `HBASE`
are in the registry. Adding one means adding a registry entry, including the
driver's SHA-256.

### A connection test fails on driver download

The backend needs egress to Maven Central once per engine. The HTTP client
honours the system proxy selector, so a proxied environment works if the JVM
is configured for it.

Air-gapped: pre-populate `ARAN_DRIVER_CACHE_DIR` with the JARs. Aran verifies
them against pinned SHA-256s, so they must be the exact versions the
registry names.

### Driver downloads repeat after every restart

The cache directory is not persisted. Mount a volume.

## Observability

### No metrics or traces

Both are off by default. Set `ARAN_OTLP_ENABLED=true` and
`ARAN_TRACING_ENABLED=true`, and point `ARAN_OTLP_ENDPOINT` at your collector.
Tracing samples at 10% unless `ARAN_TRACING_SAMPLE_RATE` says otherwise.

### `/actuator/health` returns 404

You are asking the wrong port. Actuator is on 8082
(`ARAN_MANAGEMENT_PORT`). The API port serves no actuator paths. In Compose,
that port is not published to the host, by design. The API port's own answer
is `GET /api/health`: anonymous, and only two statuses, the process's
readiness and the database's. It is what the UI's status indicator and
offline screen read.

## Getting more detail

- Structured logs: `ARAN_LOG_FORMAT=ecs` emits ECS JSON.
- Correlation ids: a filter attaches one per request. Include it when
  you report a problem.
- Job failures carry the operator's `errorCode` on the job row, not only
  in a log. The **Jobs** button in the top bar lists running, failed and
  recent jobs with that code and message, and offers Retry where the same
  operation can run again; `GET /api/jobs` (and `GET /api/jobs/{id}`) is the
  same list over the API. Read it before reading logs. A job that was running
  when Aran restarted is failed with `ORPHANED_ON_RESTART`, and a data product
  it was materializing is failed with it.
- Local startup logs are written beside the repository root by
  `scripts/startup.sh`, nine of them: `backend.log`, `frontend.log`,
  `aran-lake.log`, `freyja-sidecar.log`, `docker-infra.log`,
  `docker-lake.log`, `migrations.log`, `venv-bootstrap.log`, and
  `aran-web/npm-install.log`. Start with `backend.log` for the Spring Boot
  backend and `frontend.log` for the Vite dev server.

## Related

- [Configuration reference](./configuration-reference.md)
- [Production build](./production-build.md)

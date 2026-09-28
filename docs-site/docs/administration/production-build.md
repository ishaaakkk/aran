---
title: 'Production build and packaging'
sidebar_label: 'Production build'
sidebar_position: 1
description: 'Build the bootable JAR, the frontend assets, and the container image.'
# Diátaxis mode: how-to
---

## Build the backend

```bash
./gradlew :aran-application:bootJar --no-daemon
```

Produces a fully executable Spring Boot JAR under
`aran-application/build/libs/`. `make build` runs the same thing.

Adapters and operators are `runtimeOnly` dependencies. This puts them inside
the JAR even though the application never compiles against them. You need
nothing extra on the classpath at deploy time.

Two consequences of that packaging worth knowing before you deploy:

- **The signed-token licence adapter ships in every build.** It stays inert
  unless `ARAN_LICENSING_MODE=signed-token`. Drop the `runtimeOnly` line from
  `aran-application/build.gradle` if you want it physically absent.
- **JDBC drivers do not ship**, except PostgreSQL. Aran fetches everything
  else at runtime. So a deployment needs either egress to Maven Central or a
  pre-populated driver cache. See
  [Driver resolution](../deep-dives/driver-resolution.md).

## Build the frontend

```bash
make lint                    # npx tsc -b && npm run lint
cd aran-web && npm run build
```

Static assets land in `aran-web/dist/`. Serve them from any static host, or
from behind the same gateway as the API. Do that, and the SPA and API share
an origin — which the `ARAN_ALLOWED_ORIGINS` default is not suitable for.

## Build the image

```bash
make docker            # tags aran:dev
make docker-push       # pushes to the configured registry
```

Or directly:

```bash
docker build -t aran:dev .
```

This is a four-stage build:

- **gradle-scripts** — `eclipse-temurin:25-jdk`. Collects every module's
  build scripts and nothing else, so the dependency-cache layer is rebuilt
  when a build script changes rather than on every source edit.
- **builder** — `eclipse-temurin:25-jdk`. Builds the bootable JAR.
- **frontend** — `node:22-slim`. Builds the SPA, but only when the build arg
  `WITH_UI=true` is set. The default is `WITH_UI=false`, and then this stage
  produces an empty `dist/`.
- **runtime** — `eclipse-temurin:25-jre`. Non-root `aran` user, exposes 8081,
  defines a `HEALTHCHECK`.

**A local `make docker` ships no UI.** It passes no build arg, so
`aran:dev` is API-only — the cloud deployment serves the SPA
separately. The published `ghcr.io/dtak-upc/aran` image is different: CI
builds it with `WITH_UI=true`, so it carries the SPA, and it is the image
`compose.standalone.yml` pulls (a local build of that compose file passes
`WITH_UI: "true"` too). The `standalone` Spring profile then serves the
baked-in SPA from `/app/static/`. To build a UI-carrying image yourself:

```bash
docker build --build-arg WITH_UI=true -t aran:dev .
```

Pass JVM settings through `JAVA_OPTS`. The entrypoint is
`sh -c "exec java $JAVA_OPTS -jar /app/app.jar"`. Setting `JAVA_OPTS`
**replaces** the image's defaults, so carry forward what you still want:

```
-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0
-Djava.security.egd=file:/dev/./urandom
```

## Deploy

For a single-node install, use the standalone topology instead of assembling
the pieces yourself — one compose file, one command. See
[Run Aran standalone](./standalone.md). The checklist below is for a
hand-assembled deployment.

Before first start, have:

1. **A PostgreSQL** the application can reach. Flyway applies migrations at
   startup. Migrations own the schema. Hibernate only validates it.
2. **A Fuseki** for the schema graphs.
3. **An OIDC provider** issuing access tokens Aran can validate (`RS256`).
   Tenancy itself needs no provider-side configuration — Aran resolves it
   from its own `membership` row.
4. **`aran-lake` and FREYJA** running, if you want ingestion, profiling, or
   join discovery. The backend starts without them, and the UI loads, but
   those capabilities simply do not work.
5. **Volumes** for the driver cache and the snapshot cache. In signed-token
   licensing mode, also mount the public key into `/etc/aran` — the image
   declares that volume for it.
6. **Every ⚠ variable** in the
   [configuration reference](./configuration-reference.md) set to a real
   value — the guard refuses to start otherwise — including
   `ARAN_ENCRYPTION_KEY`, which encrypts repository credentials at rest.
7. **`ARAN_AUTH_REQUIRE_TLS=true`**, with TLS terminated in front of the
   application.

Migrations run automatically on startup. So a rolling deploy of two versions
with incompatible migrations is not safe. Deploy one version at a time.

## Verify

```bash
curl http://<host>:8082/actuator/health
```

**Port 8082, not 8081.** The API port serves no actuator paths at all. In
Compose, that port is not published to the host. So this check is
in-network or in-container by design. The image's own `HEALTHCHECK` polls
the readiness sub-path, `/actuator/health/readiness`, and greps for
`"status":"UP"`.

Then sign in through the UI and create a project. That one action exercises
the token, tenant resolution, the database, and the tenant filter — a health
check does not.

## What is not covered here

- **Backups.** Two datastores hold state you cannot rebuild: the platform
  PostgreSQL and Fuseki. The lake holds snapshots. You can re-derive these
  only if their sources still exist, with the same content.
- **TLS.** Terminate it in front of the application.
- **Horizontal scaling.** Untested. The snapshot cache is node-local, and
  jobs run in-process.

## Related

- [Run Aran standalone](./standalone.md) — the supported single-node topology
- [Docker Compose services](./docker-compose.md)
- [Configuration reference](./configuration-reference.md)
- [Troubleshooting](./troubleshooting.md)

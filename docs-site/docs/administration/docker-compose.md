---
title: 'Docker Compose services'
sidebar_label: 'Docker Compose'
sidebar_position: 2
description: 'Every service in the Compose file, the profiles that select them, and the ports and volumes they claim.'
# Diátaxis mode: reference
---

The root `docker-compose.yml` describes the platform's infrastructure. It
does **not** describe the whole stack. `aran-lake` and `freyja` have their
own Compose file and their own launch path.

## Services

| Service | Image | Profile | Host port |
| --- | --- | --- | --- |
| `postgres` | `postgres:17` | default | 5432 |
| `keycloak` | `quay.io/keycloak/keycloak:26.2` | default | 8080 |
| `fuseki` | `stain/jena-fuseki:5.1.0` | default | 3030 |
| `demo-sources` | `postgres:17` | `demo` | 5434 |
| `otel-collector` | `otel/opentelemetry-collector-contrib:0.103.0` | `observability` | 4318, 4317 |
| `prometheus` | `prom/prometheus:v2.52.0` | `observability` | 9090 |

`demo-sources` is the seminar demo's source database (`demo`/`demo`,
database `demo_sources`). It publishes 5434, not 5433, because the lake's
own PostgreSQL holds 5433 — both run beside the stack they demo.

Every image is pinned to a specific release. None uses `:latest`, in any
environment.

## Profiles

```bash
docker compose up -d                              # postgres, keycloak, fuseki
docker compose --profile demo up -d               # + the seminar source database
docker compose --profile observability up -d      # + collector and prometheus
```

Services with no profile always start.

This file does not run Aran itself. It carried a `full` profile with an
`aran-app` service until 2026-08-27; that profile never worked, because
nothing in this file overrode `ARAN_LAKE_URL`, `ARAN_FREYJA_URL` or
`ARAN_FUSEKI_URL`, so a containerised app dialled `localhost` for each — and
there is no lake service here to reach. For a containerised Aran use
`compose.standalone.yml` — see [Run Aran standalone](./standalone.md), the
supported single-node topology.

## Volumes

| Volume | Holds |
| --- | --- |
| `aran-postgres-data` | The platform database |
| `aran-fuseki-data` | The RDF schema graphs |

Both survive `docker compose down`. No `make` target removes them — `make
clean-all` deletes build outputs and the Python venv, not volumes. To wipe
them:

```bash
docker compose down -v
docker compose -f aran-lake/docker-compose.yml down -v   # the lake's volumes
```

There is no volume for the JDBC driver cache or the snapshot cache. In a
containerised deployment, mount both yourself — see
[Configuration reference](./configuration-reference.md#storage-and-caching).

## What is *not* in this file

`aran-lake` and `freyja`, and the lake's own dependencies. Those live in
`aran-lake/docker-compose.yml` (a Garage S3-compatible store and a second
PostgreSQL on 5433). `scripts/startup.sh` runs the two Python services
directly.

So `docker compose up -d` gives you a backend that starts and serves the UI,
but cannot ingest, profile, or run join discovery. `make all` brings up
everything.

## Health checks

Each infrastructure service defines one. Fuseki, for example, polls
`/$/ping` every 10 seconds, with a 30-second start period. Compose reports
the container unhealthy rather than merely running. `scripts/startup.sh`
waits on that report.

## The application image

`Dockerfile` builds the image in three stages:

- **builder** — `eclipse-temurin:25-jdk`. Builds the bootable JAR.
- **frontend** — `node:22-slim`. Builds the SPA only when the build arg
  `WITH_UI=true` is set; otherwise it produces an empty directory.
- **runtime** — `eclipse-temurin:25-jre`. JRE only, running as a non-root
  `aran` user, exposing 8081, with its own `HEALTHCHECK`.

The default is `WITH_UI=false`, so the image ships no UI unless you opt in.
`compose.standalone.yml` is the one place that sets `WITH_UI: "true"`. See
[Production build](./production-build.md#build-the-image).

The entrypoint is `sh -c "exec java $JAVA_OPTS -jar /app/app.jar"`. Pass heap
settings and system properties through `JAVA_OPTS`. The `exec` matters: it
makes the JVM PID 1, so the JVM receives container stop signals directly.

## Port map

| Port | Service |
| --- | --- |
| 3030 | Fuseki |
| 4317 / 4318 | OTLP gRPC / HTTP |
| 5432 | Platform PostgreSQL |
| 5433 | Lake PostgreSQL (lake's own Compose file) |
| 8080 | Keycloak |
| 8081 | Aran API |
| 8082 | Actuator — **not published to the host** |
| 8085 | `aran-lake` |
| 8086 | FREYJA |
| 9090 | Prometheus |
| 3900 | Garage S3 (lake's own Compose file) |
| 5173 | Vite dev server |

The browser end-to-end suite runs the whole stack again on offset ports. This
lets it run beside a live `make all` — see
[the e2e suite](../contributing/e2e-suite.md).

## Related

- [Run Aran standalone](./standalone.md) — the one compose file that runs Aran
- [Production build and packaging](./production-build.md)
- [Configuration reference](./configuration-reference.md)

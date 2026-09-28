---
title: 'Quickstart'
sidebar_label: 'Quickstart'
sidebar_position: 1
description: 'Bring up the infrastructure, the backend, and the web client, then sign in for the first time.'
# Diátaxis mode: tutorial
---

By the end of this page, the whole Aran stack runs locally and you are signed
in to the workspace. It takes one command. The first run takes a few minutes
more, because the script builds a Python environment.

## Before you start

| You need | Version | Why |
| --- | --- | --- |
| JDK | 25 | The backend needs Java 25 language features. `ScopedValue` is required. |
| Node.js | 20 or newer | The web client and its dev server |
| Docker | 24+ with Compose v2 | PostgreSQL, Keycloak, Fuseki, and the lake's object store |
| Python | 3.12 | `aran-lake` and the FREYJA sidecar |
| `make` | any | The commands below |

Docker must run before you start. On the first run, the stack downloads
container images and builds a Python virtual environment. Budget a few
minutes for this. Later runs take seconds.

## Step 1: Start everything

From the repository root:

```bash
make all
```

This command runs `scripts/startup.sh`. The script starts the pieces in
dependency order:

1. The lake's Docker dependencies (Garage object store, the lake's PostgreSQL)
2. The Python virtual environment, if `aran-lake/.venv` does not exist yet
3. The lake's database migrations (alembic)
4. `aran-lake`, the FastAPI service, on port 8085
5. The FREYJA profiling sidecar on port 8086
6. The Vite dev server on port 5173
7. The platform's infrastructure: PostgreSQL, Keycloak, Fuseki
8. The Spring Boot backend on port 8081

Each step reports when it completes. The script health-checks each service it
starts. When the script finishes, it opens the UI.

:::tip Why not just `docker compose up` and `bootRun`
Those two commands start the backend without `aran-lake` or FREYJA. The
backend starts, and the UI loads. But you cannot ingest, profile, or discover
joins, because the services that do that work are not running. `make all`
starts a complete, working system.
:::

## Step 2: Check what came up

| Service | URL |
| --- | --- |
| Web UI | http://localhost:5173 |
| Backend API | http://localhost:8081/api |
| Lake API | http://localhost:8085 |
| FREYJA sidecar | http://localhost:8086 |
| Keycloak | http://localhost:8080 |
| Fuseki | http://localhost:3030 |

```bash
make health
```

If a service is missing, check its log in the repository root. The startup
script writes nine: `backend.log`, `frontend.log`, `aran-lake.log`,
`freyja-sidecar.log`, `docker-infra.log`, `docker-lake.log`,
`migrations.log`, `venv-bootstrap.log`, and `aran-web/npm-install.log`.
The two you will read most are `backend.log` (the Spring Boot backend) and
`frontend.log` (the Vite dev server).

## Step 3: Sign in

Open http://localhost:5173. Aran shows its own sign-in page; click
**continue with single sign-on**, which takes you to the Keycloak sign-in form.

- **Username:** `alice`
- **Password:** `aran`

Two systems create Alice, both dev-only. The Keycloak realm import
(`keycloak/import/aran-realm.json`, mounted by `docker-compose.yml`) creates
her account and the password `aran`. A Flyway seed script (`db/seed/`, added
to the migration locations only under the `dev` Spring profile) creates the
demo organisation and her membership in it. Alice holds the `OWNER` role, so
she can create projects, register repositories, and invoke operators
immediately. Neither seed reaches a production artifact. The standalone
install generates its owner's password instead. See
[Run Aran standalone](../administration/standalone.md).

Keycloak's own admin console is at http://localhost:8080, with `admin` /
`admin` as its credentials. You do not need it for anything in this
documentation.

## Step 4: Confirm the workspace works

After you sign in, the dashboard opens. Two checks confirm that the whole
stack works end to end:

1. Go to **Projects** and create one. If it appears in the table, the
   backend, its database, and your tenant context all work.
2. Go to **Datasets**. The page loads with a **Register** button and an
   empty table. This view reads across projects and repositories, so it
   exercises more of the API than the dashboard does.

You now have a working instance with nothing in it.

## The `./aran` CLI

The repository ships a small CLI that wraps the same scripts `make` calls:

```bash
./aran start      # = make all (also: ./aran up)
./aran stop       # = make stop (also: ./aran down)
./aran restart    # stop, then start
./aran status     # health dashboard: probes each service, prints ONLINE/OFFLINE
./aran install    # global shell execution and completions
./aran help
```

`./aran status` probes the lake (8085), FREYJA (8086), the Vite dev server
(5173), and the backend's actuator (8082). An unknown command prints the
usage and exits 1. The recovery command `./aran admin reset-password` is
documented on the [standalone page](../administration/standalone.md).

## Stopping

```bash
make stop        # or: ./aran stop
```

This stops the background servers (uvicorn, Vite, the backend) and the Docker
containers. Only the servers `make all` started are stopped: the process
groups it recorded under `.run/`, plus anything still listening on the dev
ports (8081, 8082, 8085, 8086, 5173). An e2e run on its offset ports is left
alone. Data stays in Docker volumes. No `make` target removes volumes. To wipe
the data:

```bash
docker compose down -v
docker compose -f aran-lake/docker-compose.yml down -v
```

To remove build outputs and the Python venv:

```bash
make clean       # build outputs, node_modules, logs
make clean-all   # additionally removes the Python venv (~640 MB)
```

## What next

- [Full walkthrough](./walkthrough.md): integrate three real datasets and
  query across them. Read this page next. It turns the empty instance you
  just built into a working integration.
- [Core concepts](../overview/core-concepts.md): read this first if you want
  the vocabulary before you use it.

## If something did not start

| Symptom | Likely cause |
| --- | --- |
| The script stops at "Checking Python virtual env" | No Python 3.12 on `PATH`. `scripts/startup.sh` prefers `python3.12` and falls back to `python3`. A much older interpreter fails during dependency install. |
| Lake health check fails | Port 8085 in use, or the lake's PostgreSQL (port 5433) did not start. Check `aran-lake.log`. |
| FREYJA health check fails | Its dependencies install into the lake's venv on first run. Check `freyja-sidecar.log`. |
| Signing in ends on "Aran did not accept your sign-in" | Every request 401s: Keycloak was not ready when the backend started, or the address you opened does not match `ARAN_PUBLIC_URL`. `make stop && make all`. |
| Signing in ends on "No organization yet" | The account holds no Aran membership. Sign in as `alice`, not the Keycloak admin. The admin belongs to no organization. |

More in [Operational troubleshooting](../administration/troubleshooting.md).

# Contributing to Aran

This file covers building Aran, the checks a pull request must pass and the rules
the code base enforces. The [documentation site](https://dtak-upc.github.io/aran/)
has the user guides and the contributor guide.

## Getting started

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 25 | IntelliJ can download it via Project Structure → SDK |
| Docker | 24+ with Compose v2 | For the runtime dependencies and the Testcontainers suites |
| Node.js | 20+ | For the React frontend and the documentation site |
| Python | 3.12 | For `aran-lake`, freyja, the Python SDK and the MCP sidecar |

Run `make bootstrap` once after cloning. It creates the Python virtual environments and
`node_modules` that the stack and `./scripts/validate.sh` need, without starting anything.

### Run the whole stack from source

```bash
make all    # lake, freyja, Postgres, Keycloak, Fuseki, backend and Vite; opens http://localhost:5173
make stop   # shut it all down
```

### Run the parts yourself (faster loop)

Each long-running command below stays in the foreground, so give each its own terminal.

```bash
# 1. Postgres, Keycloak and Fuseki
docker compose up -d

# 2. The lake (its Garage and Postgres containers, migrations, then the service on :8085)
make aran-lake

# 3. The freyja profiling sidecar on :8086
make freyja-sidecar

# 4. The backend on :8081. Flyway applies migrations on startup. The dev profile is
#    required: without it the backend refuses to start on the published default secrets.
SPRING_PROFILES_ACTIVE=dev ./gradlew :aran-application:bootRun

# 5. The frontend
cd aran-web && npm install && npm run dev   # http://localhost:5173
```

`make dev` runs steps 1, 3 and 4 in one command, and starts freyja only if
`aran-lake/.venv` exists.

### Run the full stack in Docker

```bash
docker compose -f compose.standalone.yml up -d   # http://localhost:8080
```

`compose.standalone.yml` is the containerised topology: it includes the lake, so the
app can reach every service it needs. Without `--build` it pulls the Aran images and
builds them from this checkout only when the pull fails. The root `docker-compose.yml`
starts dependencies only.

### Demo data

With the dev profile, Flyway seeds a demo organisation and alice's membership on first
startup. Sign in as `alice` / `aran`. These credentials are published in this repository
and are for local development only.

---

## Before you open a pull request

### Sign off your commits (DCO)

Aran uses the [Developer Certificate of Origin](https://developercertificate.org/).
There is no CLA and no form. You certify that you wrote the patch, or have the
right to submit it under the project's licence, by adding a `Signed-off-by` line
to each commit:

```bash
git commit -s -m "your message"
```

which appends:

```
Signed-off-by: Your Name <your.email@example.com>
```

Use your real name and a working email. To sign off a branch you have already
written, `git rebase --signoff main` will add the line to every commit on it.

Contributions are made under [Apache-2.0](LICENSE), the same licence as the rest
of the project. You keep the copyright in what you write.

### Run the gate

```bash
./scripts/validate.sh            # the full gate (needs a Docker daemon)
./scripts/validate.sh --offline  # the Docker-free subset
```

`--offline` skips the Java tests tagged `docker` (the `aran-application` integration
tests and a few Garage- and Postgres-backed operator tests), the `aran-lake` suite and
freyja's Docker-marked tests. The Playwright e2e suite is never part of the default gate;
`RUN_E2E=1` adds it to the full one. If you have no Docker, run the
offline subset and say so in the pull request. Do not claim a green full gate you did
not run.

### If you built something you could not verify

Some work cannot be run where it was written: a JDBC client needs a desktop SQL
tool, and a browser suite needs images a sandbox cannot pull. The pull request
may include it if it says so. State what you built, why you could not check it, how to
check it, and what is most likely to be wrong.

### Code of Conduct

Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Project structure

```
aran/
├── aran-core/                  Domain model, services, ports, operator SPI contracts
├── aran-application/           Deployable Spring Boot app: HTTP, security, wire-up
├── aran-adapters/
│   ├── aran-adapter-persistence/    JPA + Flyway + cloud license
│   ├── aran-adapter-iceberg/        Iceberg REST catalog client
│   └── aran-adapter-license-token/  Self-hosted signed-token license provider
├── aran-operators/             Analytical operators the platform loads dynamically
│   ├── bootstrapping/          Source snapshot → RDF schema + wrappers
│   ├── profiling/              Column profiling, via the freyja sidecar
│   ├── join-discovery/         Join candidates, via the freyja sidecar
│   ├── schema-integration/     Alignments → integrated schema versions
│   ├── constraint-discovery/   Denial-constraint discovery (LIMA)
│   └── query-engine/           OQL parser, Calcite planner, DuckDB executor
├── aran-lake/                  FastAPI lake service (Python, own venv + alembic)
├── freyja/                     Profiling + join-discovery sidecar (Python)
├── aran-client/                Python SDK (versioned separately)
├── aran-mcp/                   MCP sidecar for AI agents (Python)
├── aran-web/                   React 19 + Vite + TypeScript SPA
├── docs-site/                  Public documentation site (Docusaurus)
├── tests/sidecar_contract/     Security contract every Python sidecar must pass
├── docker/                     Observability stack config
├── .github/workflows/          CI/CD pipelines
├── Dockerfile                  Multi-stage backend image
├── compose.standalone.yml      The whole product in one Compose file
└── docker-compose.yml          Development dependencies, plus `demo` and `observability` profiles
```

---

## CI expectations

Every pull request against `main` runs these jobs (see `.github/workflows/ci.yml`):

| Job | What it runs |
|---|---|
| Java | `./scripts/validate.sh --java`: the Gradle build and every Java test, Testcontainers included |
| Web | `./scripts/validate.sh --web`: `npm run build` (tsc + vite), `npm run lint`, `npm test` |
| Python | `./scripts/validate.sh --python`: ruff, then aran-lake, freyja, aran-client, aran-mcp and the sidecar contract |
| Supply Chain | gitleaks over history, picklescan over `freyja/models/model.pkl` |
| Images | On merges to `main` only: builds and pushes `aran`, `aran-lake` and `aran-freyja` to GHCR |

A dependency review also runs on pull requests. It fails on a new dependency with a
vulnerability of high severity or above, or with a GPL-2.0, GPL-3.0 or AGPL-3.0 licence.

The first three jobs are `./scripts/validate.sh`, one part each. Run the script
before you push (run `make bootstrap` once first). Without a Docker daemon, use
the offline subset described in "Before you open a pull request".

There is no OpenAPI generation step. `aran-web/src/services/types.ts` is
hand-written and is the only source for wire shapes. `npm run generate:api` reads
a live backend and is only a manual cross-check. Do not commit a generated
`openapi.d.ts`: an earlier one went stale and contradicted the backend without
any check noticing.

### Writing tests

- Unit tests go in `src/test/java` next to the code they test.
- Integration tests that need a real Postgres use Testcontainers (already a dependency of `aran-application`). CI provides a
  Docker daemon and no database of its own.

---

## Architecture rules

A test enforces the hexagonal boundary. `ArchitectureTest` in
`aran-application` fails the build when the boundary is crossed. It also freezes
the core's remaining Jena/JDBC/Calcite/HTTP dependencies, so they can only shrink.
The frozen store does not update itself. Adding a violation, removing one, or
editing a frozen rule fails the test until you re-freeze with the command in
`ArchitectureTest`'s javadoc and commit the store's diff. The boundaries:

- `aran-core` has zero JPA / Spring Web annotations. Domain records are plain Java records.
- The persistence adapter is `runtimeOnly` in the application module. The core never imports it.
- New ports belong in `aran-core/src/main/java/edu/upc/dtak/aran/core/domain/` or `edu/upc/dtak/aran/core/licensing/`.
- New adapter implementations belong in the relevant adapter module, not in the core.

### Adding a new operator

Steps:

1. Create a new module under `aran-operators/` with a `build.gradle` that depends on `aran-core`.
2. Implement `Operator` from the `edu.upc.dtak.aran.core.operator` package and annotate with `@Component`.
3. Add it to `settings.gradle` and as `runtimeOnly` in `aran-application/build.gradle` to compile and register it dynamically via Spring component scanning.

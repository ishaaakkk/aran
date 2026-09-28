# Contributing to Aran

## Getting started

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 25 | IntelliJ can download it via Project Structure → SDK |
| Docker | 24+ | For the runtime dependencies |
| Node.js | 20+ | For the React frontend |

### Run it in dev mode (fastest loop)

```bash
# 1. Start Postgres + Keycloak
docker compose up -d

# 2. Run the backend (Flyway applies migrations on startup)
./gradlew :aran-application:bootRun

# 3. Run the frontend (in another terminal)
cd aran-web && npm install && npm run dev   # http://localhost:5173
```

### Run the full stack in Docker

```bash
docker compose -f compose.standalone.yml up -d   # http://localhost:8080
```

`compose.standalone.yml` is the containerised topology: it builds the image
itself and includes the lake, so the app can reach every service it needs.
The root `docker-compose.yml` starts dependencies only.

### Demo data

Flyway migrations, the demo organisation and alice's membership are provisioned on
first startup. Sign in as `alice` / `aran`.

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

`--offline` skips the Testcontainers integration tests in `aran-application`, the
`aran-lake` suite, and the Playwright e2e run. If you have no Docker, run the
offline subset and **say so in the PR**. Do not claim a green full gate you did
not run.

### If you built something you could not verify

Some work cannot be run where it was written. A JDBC client needs a desktop SQL
tool; a browser suite needs images the sandbox cannot pull. That is fine if the
pull request says so. State what you built, why you could not check it, how to
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
│   └── aran-adapter-license-token/  Self-hosted signed-token license provider
├── aran-operators/             Platform dynamic analytical capabilities
│   ├── bootstrapping/          Source snapshot → RDF schema + wrappers
│   ├── profiling/              Column profiling, via the freyja sidecar
│   ├── join-discovery/         Join candidates, via the freyja sidecar
│   ├── schema-integration/     Alignments → integrated schema versions
│   ├── constraint-discovery/   Denial-constraint discovery (LIMA)
│   └── query-engine/           OQL parser, Calcite planner, DuckDB executor
├── aran-lake/                  FastAPI lake service (Python, own venv + alembic)
├── freyja/                     Profiling + join-discovery sidecar (Python)
├── aran-web/                   React 19 + Vite + TypeScript SPA
├── docs-site/                  Public documentation site (Docusaurus)
├── tests/sidecar_contract/     Security contract every Python sidecar must pass
├── docker/                     Observability stack config
├── .github/workflows/          CI/CD pipelines
├── Dockerfile                  Multi-stage backend image
└── docker-compose.yml          Full-stack + observability profiles
```

---

## CI expectations

Every pull request against `main` runs these jobs (see `.github/workflows/ci.yml`):

| Job | What it runs |
|---|---|
| **Java** | `./scripts/validate.sh --java`: the Gradle build and every Java test, Testcontainers included |
| **Web** | `./scripts/validate.sh --web`: `npm run build` (tsc + vite), `npm run lint`, `npm test` |
| **Python** | `./scripts/validate.sh --python`: ruff, then aran-lake, freyja, aran-client, aran-mcp and the sidecar contract |
| **Supply Chain** | gitleaks over history, picklescan over `freyja/models/model.pkl` |
| **Images** | On merges to `main` only: builds and pushes `aran`, `aran-lake` and `aran-freyja` to GHCR |

Plus **dependency review** on PRs, which alerts on new high/critical CVEs and
GPL-family licences.

The first three jobs are `./scripts/validate.sh`, one part each. Run the script
before you push (run `make bootstrap` once first). Without a Docker daemon, use
the offline subset described in "Before you open a pull request".

**There is no OpenAPI generation step.** `aran-web/src/services/types.ts` is
hand-written and is the only source for wire shapes. `npm run generate:api` reads
a live backend and is only a manual cross-check. Do not commit a generated
`openapi.d.ts`: an earlier one went stale and contradicted the backend without
any check noticing.

### Writing tests

- Unit tests go in `src/test/java` next to the code they test.
- Integration tests that need a real Postgres should use **Testcontainers**
  (dependency already wired in `aran-application`). CI provides a Docker daemon
  and no database of its own.

---

## Architecture rules

The hexagonal boundary is **enforced by a test**. `ArchitectureTest` in
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

In short:

1. Create a new module under `aran-operators/` with a `build.gradle` that depends on `aran-core`.
2. Implement `Operator` from the `edu.upc.dtak.aran.core.operator` package and annotate with `@Component`.
3. Add it to `settings.gradle` and as `runtimeOnly` in `aran-application/build.gradle` to compile and register it dynamically via Spring component scanning.

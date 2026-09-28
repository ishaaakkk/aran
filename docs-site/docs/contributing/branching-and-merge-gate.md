---
title: 'Branching and the merge gate'
sidebar_label: 'Branching & merge gate'
sidebar_position: 4
description: 'Branch naming, what must be green before a merge, and how to get there.'
# Diátaxis mode: how-to
---

## Branch

Feature branches off `main`:

```bash
git checkout main && git pull
git checkout -b feature/phase0-m11-my-change
```

Phase 0 work uses `feature/phase0-m<N>-<slug>`. Other work follows the same
shape — a type prefix, then a slug.

## Work

Run the offline gate while iterating. It needs no Docker daemon, and it
covers most of the codebase:

```bash
./scripts/validate.sh --offline
```

For a tighter loop, run just the module you are changing:

```bash
./gradlew :aran-operators:query-engine:test
cd aran-web && npm test
```

## The gate

**Merge only after `./scripts/validate.sh` is ALL GREEN.** Not the offline
subset — the full run.

```bash
./scripts/validate.sh
```

It ends with `VALIDATION: ALL GREEN`. Anything else counts as a failure,
including a skipped step.

`--offline` deliberately prints what it did not cover:

- `:aran-application:test` — the `@Tag("docker")` integration tests
- the `aran-lake` pytest suite (Testcontainers)
- the Playwright browser suite

Those three are exactly the things most likely to catch a wiring mistake. So
the offline run is a fast loop, not a substitute.

## The browser suite

Not part of the default gate — it takes about ten minutes, and it owns its
own stack. Opt in:

```bash
RUN_E2E=1 ./scripts/validate.sh
```

Run it whenever you touch the frontend, an HTTP contract, or the
integrate-and-query path. See [the e2e suite](./e2e-suite.md).

## CI

Every pull request against `main` runs five jobs:

- **Backend** — the Gradle build and the full Java test suite, against a
  PostgreSQL service container.
- **Frontend** — the type-check, lint, build, and test suite.
- **Lake (Python)** — `ruff check .` over every Python tree, then the
  pytest suites of `aran-lake`, `freyja` (plus an import smoke test),
  `aran-client`, `aran-mcp`, `tests/sidecar_contract` and `tests/scripts`.
- **Supply chain** — gitleaks over the full git history (development
  sentinels are allowlisted by value, not by path, so a real secret in the
  same file is still caught), and picklescan over `freyja/models/model.pkl`.
- **Docker** — builds the image. It pushes only on merges to `main`.

A separate workflow runs a dependency review on every pull request. It
alerts on new high or critical CVEs and GPL-family licences.

The browser e2e suite has its own workflow. It runs on every push to
`main`, on a nightly schedule, and on manual dispatch — so a regression the
local opt-in missed is still caught, and attributed to a day's merges
rather than guessed at.

Docs changes additionally run the documentation gate: the site build, a link
check, a Mermaid render check in a real browser, and a check that the
committed OpenAPI snapshot still matches the service. Backend changes run it
too, because the snapshot is generated from the backend: a pull request that
moves a controller or a wire DTO regenerates and commits
`docs-site/openapi/aran-api.json` in the same pull request (see
[REST API reference](../api/index.md#regenerating-it)).

## Writing tests

- Unit tests sit in `src/test/java` beside the code they test.
- Integration tests needing a real PostgreSQL use Testcontainers, by
  extending `BaseIntegrationTest`, which carries `@Tag("docker")`. Tags are
  inherited, so `--offline` excludes them automatically — you do not tag
  your subclass.
- A **new Docker-free test in `aran-application` needs no tag** and is
  picked up by the offline run the day it is written. A forgotten tag makes
  a test *run* rather than vanish — the safe direction.

## Before you open the pull request

- [ ] `./scripts/validate.sh` — ALL GREEN
- [ ] Schema changes are Flyway migrations, never `ddl-auto`
- [ ] New wire fields are in `aran-web/src/services/types.ts` by hand
- [ ] No new dependency edge between two adapters or two operators
- [ ] Comments explain *why* — see [Comment philosophy](./comment-philosophy.md)
- [ ] `RUN_E2E=1` if you touched the UI or an HTTP contract

## Related

- [Build and test](./build-and-test.md)
- [Running the end-to-end suite](./e2e-suite.md)

---
title: 'Branching and the merge gate'
sidebar_label: 'Branching & merge gate'
sidebar_position: 4
description: 'Branch naming, what must be green before a merge, and how to get there.'
# Diátaxis mode: how-to
---

How to take a change from a branch to a merge: name the branch, iterate on
the offline gate, pass the full gate, and read what CI runs on the pull
request.

## Branch

Feature branches off `main`:

```bash
git checkout main && git pull
git checkout -b feature/phase0-m11-my-change
```

Phase 0 work uses `feature/phase0-m<N>-<slug>`. Other work follows the same
shape: a type prefix, then a slug.

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

Merge only after `./scripts/validate.sh` is ALL GREEN. The offline subset is
not enough; run the full gate.

```bash
./scripts/validate.sh
```

It ends with `VALIDATION: ALL GREEN`. Anything else counts as a failure,
including a run limited with a part selector (`--java`, `--web`,
`--python`), which ends with `unselected parts were NOT run`. The two
opt-in `SKIPPED:` lines for the perf benchmark and the browser suite are
expected.

`--offline` deliberately prints what it did not cover:

- the `@Tag("docker")` Java tests: the `aran-application` integration tests
  and the Garage- or PostgreSQL-backed operator tests
- the `aran-lake` pytest suite (Testcontainers)
- `freyja`'s `docker`-marked live-scan tests
- the Playwright browser suite

Those are the checks most likely to catch a wiring mistake, so the offline
run is a fast loop and not a substitute.

## The browser suite

The browser suite is not part of the default gate: it takes about ten minutes
and owns its own stack. Opt in:

```bash
RUN_E2E=1 ./scripts/validate.sh
```

Run it whenever you touch the frontend, an HTTP contract, or the
integrate-and-query path. See [the e2e suite](./e2e-suite.md).

## CI

The `CI` workflow runs on every pull request against `main` and on every
push to `main` or `develop`. Its test jobs call `validate.sh` itself, one
part each, so CI and your machine run the same commands:

| Job | What it runs |
| --- | --- |
| Java | `./scripts/validate.sh --java`: the Gradle build and every Java suite, Docker-tagged tests included. The tests start their own PostgreSQL and Garage through Testcontainers |
| Web | `./scripts/validate.sh --web`: build (with type-check), lint and Vitest |
| Python | `./scripts/validate.sh --python`: `ruff check .`, then every Python suite the gate lists |
| Supply chain | gitleaks over the full git history (development sentinels are allowlisted by value, not by path, so a real secret in the same file is still caught), and picklescan over `freyja/models/model.pkl` |
| Images | Only on a push to `main`: builds and pushes the `aran` (with `WITH_UI=true`), `aran-lake` and `aran-freyja` images to `ghcr.io` |

A suite added to `validate.sh` is therefore picked up by CI with no workflow
change.

A separate workflow runs a dependency review on every pull request against
`main`. It fails on new dependencies with high or critical vulnerabilities,
and on GPL-2.0, GPL-3.0 and AGPL-3.0 licences.

The browser e2e suite has its own workflow. It runs on every push to
`main`, on a nightly schedule, and on manual dispatch. A regression the
local opt-in missed is therefore still caught and attributed to a day's merges.

Docs changes additionally run the documentation gate: a type-check of the
site config, the site build, a link check, a Mermaid render check in a real
browser, the prose-pattern check, and a check that the
committed OpenAPI snapshot still matches the service. Backend changes run it
too, because the snapshot is generated from the backend: a pull request that
moves a controller or a wire DTO regenerates and commits
`docs-site/openapi/aran-api.json` in the same pull request (see
[REST API reference](../api/index.md#regenerating-it)).

## Writing tests

- Unit tests sit in `src/test/java` beside the code they test.
- Integration tests needing a real PostgreSQL use Testcontainers, by
  extending `BaseIntegrationTest`, which carries `@Tag("docker")`. Tags are
  inherited, so `--offline` excludes them automatically and you do not tag
  your subclass.
- A new Docker-free test in `aran-application` needs no tag and is
  picked up by the offline run the day it is written. A forgotten tag makes
  a test run instead of vanish, which is the safe direction.

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

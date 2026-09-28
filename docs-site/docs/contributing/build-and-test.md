---
title: 'Build and test commands'
sidebar_label: 'Build & test'
sidebar_position: 2
description: 'The commands for building, testing, and validating each part of the repository, including the Docker-free subset.'
# Diátaxis mode: reference
---

## The gate

```bash
make bootstrap                     # once: every venv and node_modules the gate needs
./scripts/validate.sh              # everything. Needs Docker
./scripts/validate.sh --offline    # the Docker-free subset
./scripts/validate.sh --java       # one part: --java, --web, --python
```

A milestone is not done until the full gate is green. The part selectors
combine (`--java --web`), work in either mode, and selecting none means all
three — CI runs one job per part by calling the script this way. Any other
argument prints the usage line, with the script named as you invoked it —
`usage: ./scripts/validate.sh [--offline] [--java] [--web] [--python]` —
and exits 2. A run limited to some parts ends with
`VALIDATION (parts: …): GREEN — unselected parts were NOT run`, or offline
with `VALIDATION (offline subset; parts: …): GREEN — unselected parts were NOT run`,
rather than the lines below.

The full run ends with exactly:

```
VALIDATION: ALL GREEN
```

The offline run ends with exactly:

```
VALIDATION (offline subset): ALL GREEN
```

followed by a list of what it did not cover. An opted-out step prints a skip
line rather than nothing:

```
SKIPPED: query-engine perf benchmark (set RUN_PERF=1 to include it)
SKIPPED: browser e2e suite (set RUN_E2E=1 to include it)
```

The e2e suite drives every part, so `RUN_E2E=1` beside a part selector
skips it too, and says why:

```
SKIPPED: browser e2e suite — RUN_E2E needs every part selected (drop the selectors)
```

### What the full gate runs

1. `make build` — the bootable JAR, tests skipped
2. `make test` — every Java test, Testcontainers included
3. The query-engine perf benchmark — **only** when `RUN_PERF=1`; it closes
   the Java part, so it runs before the web and Python parts
4. `aran-web`: `npm run build && npm run lint && npm test`
5. `ruff check .` — one repo-root `ruff.toml` covers every Python tree
6. `aran-lake`: `pytest` from its own venv
7. `freyja`: `pytest freyja/tests` from the lake's venv
8. `aran-client`: `pytest` from its own venv
9. `aran-mcp`: `pytest` from its own venv
10. `tests/sidecar_contract`: the sidecar security contract, against every registered sidecar
11. `tests/scripts`: the shell scripts that handle secrets, with `docker` replaced by a recorder
12. The browser e2e suite — **only** when `RUN_E2E=1`

Before running anything, the gate checks every prerequisite of the selected
parts — `aran-web/node_modules`; `aran-lake/.venv` with freyja's
dependencies in it; `aran-client/.venv`; `aran-mcp/.venv` — and, if any is
missing, lists all of them at once, each with the make target that creates
it, and exits 1. `make bootstrap` creates them all. When the lake venv exists
but freyja does not import from it, the check prints the last lines of the
import error instead: that may be a missing package (`make freyja-deps`) or
an error in freyja itself, which no make target fixes. `ruff` also fails
rather than skipping when absent:

```
ruff not found — run 'make aran-lake-venv' (installs the pinned dev extras).
```

### What `--offline` runs

The Java tests that need Docker carry `@Tag("docker")` — the
`aran-application` integration tests, plus a few Garage/Postgres-backed
operator tests — and the offline run excludes that tag. So it covers most of
the codebase, not nothing:

- `aran-core`
- all three adapters
- all six operator modules
- `aran-application`, with `-PexcludeTags=docker`
- the whole frontend suite
- `ruff check .` over every Python tree
- `freyja`'s tests, minus the `docker`-marked live-scan ones (`-m "not docker"`),
  and the sidecar contract suite
- `aran-client`'s tests
- `aran-mcp`'s tests

The prerequisite check applies offline too: a missing venv fails the run
instead of silently dropping its suite. To validate only what is set up,
select the parts you have.

`RUN_PERF=1` works in **both** modes, unlike `RUN_E2E=1`. The browser suite genuinely needs
Docker; the perf benchmark does not, so withholding it from the offline gate would withhold it
from exactly the machines that can still run it.

The Docker-free part of `aran-application` is selected by **tag, not by a
list of class names**. `BaseIntegrationTest` carries `@Tag("docker")`, and
JUnit tags are inherited. So a new Docker-free test in that module is
covered the day it is written. A test that forgets the tag *runs*, rather
than vanishing — the safe direction to fail.

`--offline` is not a substitute for the full gate before merge.

## Java

```bash
./gradlew :aran-application:bootJar --no-daemon      # or: make build
./gradlew test --no-daemon                           # or: make test

# One module
./gradlew :aran-operators:query-engine:test

# One class
./gradlew :aran-operators:query-engine:test \
  --tests 'edu.upc.dtak.aran.operators.queryengine.plan.AranSchemaTest'

# Everything except the Docker-backed tests
./gradlew :aran-application:test -PexcludeTags=docker
```

Use `:aran-operators:query-engine:test` as the quick baseline while working
on the query engine.

## Performance suite

The query engine has an in-process performance suite. It needs **no Docker** — it generates its
own Parquet with DuckDB — and it runs in two tiers.

**The gate tier runs on every build**, as part of `:aran-operators:query-engine:test`. It asserts
only things that do not depend on the machine: how many DDL statements a query builds, how many
snapshots its resolver is asked for, how many views its SQL names, exact row counts over
generated data, and plan shapes pinned to `src/test/resources/perf-goldens/`. **No wall-clock
threshold is ever asserted.** A duration on a shared runner fails for reasons unrelated to the
code.

**The benchmark tier is opt-in** and asserts nothing. It measures each phase of the pipeline —
parse, plan, optimize, unparse, prune, prepare, execute — with warm-up and percentiles, and writes
a report.

```bash
# The benchmark; reports at aran-operators/query-engine/build/perf/report.md and report.json
make perf

# Or directly, with the top setting of every dimension (long; some cells may not finish)
./gradlew :aran-operators:query-engine:test --tests '*PerfBenchmarkTest' \
  -Daran.perf=true -Daran.perf.scale=large

# One dimension only
./gradlew :aran-operators:query-engine:test --tests '*PerfBenchmarkTest' \
  -Daran.perf=true -Daran.perf.only=join-depth

# As part of the gate, in either mode
RUN_PERF=1 ./scripts/validate.sh --offline

# The federated tier: snapshot vs the live attached Postgres read, same rows (needs Docker
# and DuckDB extension downloads). Writes build/perf/federated-report.md with a derived
# live/local cost ratio — the calibration input for aran.query.placement.live-lambda.
make perf-federated
```

The benchmark grid also prices the optimizer's *decisions*, not only its phases: the
`cbo-transpose` and `cbo-join-elimination` dimensions plan the same query over catalogs that
differ in exactly one fact (a verified-unique key; a declared foreign key) and the report's scan
counts show which plan the optimizer chose, next to what choosing cost. The `wrapper-cache`
dimension executes the join workload with the wrapper-output cache off, filling, and warm; the
`placement-decision` dimension times the query-placement post-pass as its own column.

After an intended plan change, regenerate the scaled goldens and read the diff:

```bash
./gradlew :aran-operators:query-engine:test --tests '*PerfGoldenTest' \
  -Daran.perf.goldens.update=true
```

That flag is separate from `-Daran.plan.goldens.update` on purpose: one flag rewrites whatever
corpus it is pointed at, so sharing it would let accepting one plan change silently rewrite the
other corpus in the same commit.

The benchmark tier takes three more tuning flags:

| Flag | Default | Effect |
| --- | --- | --- |
| `-Daran.perf.iterations` | `7` | Measured samples per cell |
| `-Daran.perf.warmup` | `3` | Discarded warm-up runs per cell |
| `-Daran.perf.cellBudgetMs` | `20000` | A cell stops taking samples once it exceeds this budget |

The report records the scale label (`-Daran.perf.scale`, default `default`)
in its environment block, so a saved report says what produced it.

The numbers a run produces describe one machine on one afternoon and are not
comparable with another's. The suite's real output is the defects it finds.
It has already shown that planning cost rises with the product of join-path
depth and union fan-in — deep enough, the planner exhausts the JVM heap —
and that each enforced denial constraint adds planning cost to every query,
whether or not the query touches its class.

## Frontend

```bash
cd aran-web
npm test           # vitest run
npm run lint       # eslint
npm run build      # tsc -b && vite build
```

`make lint` from the root runs `npx tsc -b && npm run lint`. This is what CI
runs.

## Python

Four Python trees ship. `aran-lake` and `freyja` share one virtual
environment, on purpose; `aran-client` and `aran-mcp` each have their own —
both are packages users install into their own environments, so neither may
inherit the lake's pins.

```bash
# Bootstrap the lake venv — Docker-free, returns immediately
make aran-lake-venv

# Install freyja's requirements into it, without starting the sidecar
make freyja-deps

# Bootstrap the other two venvs
make aran-client
make aran-mcp

# The lake's own suite — needs Docker (Testcontainers)
cd aran-lake && .venv/bin/python -m pytest -q

# freyja's suite — the docker-marked live-scan tests need Docker (Testcontainers)
aran-lake/.venv/bin/python -m pytest freyja/tests -q
aran-lake/.venv/bin/python -m pytest freyja/tests -q -m "not docker"   # without Docker

# The client, the MCP sidecar, and the sidecar contract — no Docker
cd aran-client && .venv/bin/python -m pytest -q
cd aran-mcp && .venv/bin/python -m pytest -q
PYTHONPATH=".:aran-lake/src" aran-lake/.venv/bin/python -m pytest tests/sidecar_contract -q
```

`make aran-lake` is **not** the bootstrap command: it starts the lake's
Docker dependencies and then blocks on a `uvicorn --reload` dev server. Use
`make aran-lake-venv` to bootstrap.

Do not create a separate environment for FREYJA. The Makefile and
`validate.sh` both assume the shared one.

## Browser end-to-end

```bash
make e2e                             # ~10 minutes, owns its own stack
RUN_E2E=1 ./scripts/validate.sh      # include it in the gate
```

`make e2e` also bootstraps the `aran-mcp` venv (one spec drives a real MCP
client subprocess), installs `aran-web`'s node modules if absent, and runs
`npx playwright install --with-deps chromium`. See
[the e2e suite](./e2e-suite.md).

## Documentation site

```bash
cd docs-site
npm start            # dev server
npm run validate     # build + link check + diagram check
```

The diagram check drives a real browser, because Mermaid renders
client-side. A broken diagram is invisible to both the build and the link
checker.

## Running the stack

```bash
make all      # everything: infra, lake, freyja, backend, frontend
make dev      # infra + backend only (no lake, no freyja)
make stop
make health
make clean       # stop, plus build outputs
make clean-all   # plus the Python venv (~640 MB)
```

`make dev` is faster, but gives a backend that cannot ingest, profile, or
run join discovery — those need the two Python services.

## When Docker is constrained

Some sandboxes cannot give Testcontainers the bridge networking it needs.
There, start one host-networked PostgreSQL on a fixed port and describe it in a
`.container-info.json` file in `aran-lake/` and `aran-application/`. Both test
suites check for that file before starting a container of their own.

A sandbox that also blocks image pulls cannot run the Docker-backed tests at
all. `--offline` is the answer there.

## Related

- [Branching and the merge gate](./branching-and-merge-gate.md)
- [Module layout](./module-layout.md)

---
title: 'Running the end-to-end suite'
sidebar_label: 'End-to-end suite'
sidebar_position: 5
description: 'Run the Playwright browser suite against an isolated stack, and interpret what it reports.'
# Diátaxis mode: how-to
---

The suite drives the real UI in Chromium, against a stack it starts and
stops itself. It is the only test layer that exercises the whole path from a
click to a query result.

## Run it

```bash
make e2e                                          # from the repo root, ~10 minutes
cd aran-web && npm run test:e2e -- a1              # one spec
cd aran-web && npm run test:e2e -- --headed --workers=1   # watch it
cd aran-web && ARAN_E2E_STRESS=1 npm run test:e2e         # only the @stress tests
```

The default run excludes tests tagged `@stress`. `ARAN_E2E_STRESS=1` runs
those and nothing else, on one worker. Do not use `--grep @stress` instead:
the config's exclusion still applies and the run selects no tests.

Needs Docker. `make e2e` bootstraps most of what it needs itself: the
`aran-mcp` venv (one spec drives a real MCP client subprocess), `aran-web`'s
node modules, and the Chromium build. The one prerequisite it does not
create is the lake venv with FREYJA's dependencies, because the suite's
global setup starts both services from it:

```bash
make aran-lake-venv freyja-deps    # Docker-free; the same command CI runs
```

Do not use `make aran-lake` for this: it starts the lake's Docker
dependencies and then blocks on a dev server.

Include it in the merge gate with:

```bash
RUN_E2E=1 ./scripts/validate.sh
```

It is opt-in because of its runtime, not because it is optional. Run it
whenever you touch the frontend, an HTTP contract, or the integrate-and-query
path.

## It runs beside your dev stack

Every port is offset from the development defaults, so `make e2e` and
`make all` can run at once:

| | dev | e2e |
| --- | --- | --- |
| Vite | 5173 | 5273 |
| Backend | 8081 | 8181 |
| Backend management | 8082 | 8182 |
| Keycloak | 8080 | 8180 |
| Lake | 8085 | 8185 |
| FREYJA | 8086 | 8186 |
| Fuseki | 3030 | 3130 |
| Garage (S3) | 3900 | 33900 |
| PostgreSQL | 5432 | 55432 (aran), 55433 (federated source), 55434 (lake) |

## How the specs stay isolated

The suite does not clean up between tests. Each spec gets its own Keycloak
user, and therefore its own tenant.

That follows from the tenancy model. The schema graph is one ACTIVE graph
per tenant, not per project, and a user holds at most one membership, so
there is no tenant switcher to disable. A per-project or per-user cleanup
strategy could not isolate two specs that both promote a schema. A tenant
each can, and that is what lets the specs run in parallel.

One list generates identities into both the Keycloak realm and the seed. To
add an identity:

1. Append the id to `SPEC_IDS` in `e2e/stack/spec-ids.mjs` and to the
   tuple in `e2e/stack/spec-ids.d.mts`. The runtime list and its type
   declaration are separate files, kept in step by hand.
2. Bump the count literal in `e2e/specs/identities.spec.ts` to the new
   length of `SPEC_IDS`.
3. Regenerate the realm and the seed:

```bash
node docker/e2e/generate-identities.mjs
```

Skipping the `.d.mts` append leaves the new id outside the `SpecId` union,
so the spec that uses it fails the type-check. Skipping step 2 fails
`identities.spec.ts` on the count.

## Fixtures

`e2e/data/` holds seven sources, engineered so their value domains overlap
in the ways join discovery is meant to find (see the comment on `EXPECTED`
in `e2e/data/index.ts`). Edit `EXPECTED` in the same commit as any fixture,
or a spec starts asserting against overlap that no longer exists.

An eighth file, `curated_papers.csv`, sits outside `EXPECTED` on purpose: it
carries no join-discovery signal, and folding it in would make every count
there wrong. It exists for the constraint-discovery spec,
which re-derives the facts it needs from the file rather than writing them
down.

`venues.parquet` is committed as a binary. Regenerate it with:

```bash
aran-lake/.venv/bin/python aran-web/e2e/data/generate-parquet.py
```

## When a spec fails

Stack logs are kept at `build/e2e-run/` on failure. Playwright traces are under
`aran-web/test-results/`:

```bash
cd aran-web && npx playwright show-trace test-results/<dir>/trace.zip
```

A health-gate failure names the service that did not come up. Read that
service's log first.

A run killed part-way (Ctrl-C during `make e2e`) can orphan the compose
stack, which then holds the offset ports and fails the next run's health
gate. Remove it with:

```bash
docker compose -p aran-e2e -f docker/e2e/docker-compose.e2e.yml down -v
```

:::danger Never pass `--reporter=line`
It replaces the config's reporter list, and silently drops the keep-logs
reporter that preserves `build/e2e-run/` after a failure. You lose exactly
the evidence you were about to need.
:::

## Writing a spec

Page objects live in `e2e/pages/`. Use them rather than raw locators, because
they encode what the suite has learned about how this UI behaves.

Three habits the existing suite settled on, each after a real failure:

Assert positively, on the state you want. A dataset badge check that
asserts "not BOOTSTRAPPING" also passes on BOOTSTRAP FAILED — the outcome it
was written to catch. Assert the badge equals `GRAPH READY`.

Count rows instead of checking visibility. Several UI actions no-op
silently: adding a duplicate alignment, and accepting join-discovery candidates (each mapping
saves independently, errors are swallowed, and the modal closes regardless).
A visibility check passes for the wrong reason. A row-count increase does
not.

Retry the click as well as the wait. Some controls bail out early while
their data is still loading, with no dialog, no error and nothing to wait for.
Playwright's actionability retries do not help, because the element is
actionable and simply does nothing. Retry the whole open-and-confirm.

## What the suite covers

Most spec files under `e2e/specs/` drive product scenarios (ingestion,
integration, discovery, drift, querying, the MCP client), each signed in as
its own identity from `SPEC_IDS`. A handful check the suite's own machinery
instead: the identity list, the compose stack, the Playwright config, and
the fixture corpus. `npx playwright test --list` shows the current tests.

The suite has repeatedly found product defects rather than test bugs, so
treat a failure as a signal about the product first.

## Where the performance measurement lives

Two suites measure performance, and they do not overlap.

`s1-query-stress.spec.ts` here covers what only a live stack can: the Parquet snapshot cache
warming across repeated queries, DDL pruning observed through real snapshot downloads, and
concurrent queries arriving over HTTP through separate browser contexts.

The query engine itself is measured in-process, with no Docker, by the perf suite in
`aran-operators/query-engine/src/test/java/.../harness/Perf*.java` (see
[Build and test](./build-and-test.md#performance-suite)). That is where planning cost, per-query
DDL construction, plan stability at scale, and row-materialisation cost are measured, because
none of them needs a stack and all of them are drowned out by network time when measured through
one.

## Related

- [Branching and the merge gate](./branching-and-merge-gate.md)
- [Build and test](./build-and-test.md)

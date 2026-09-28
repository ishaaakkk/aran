---
title: 'Contributor guide'
sidebar_label: 'Overview'
sidebar_position: 0
description: 'What a contributor needs to know before the first pull request, and where each rule is enforced.'
# Diátaxis mode: explanation
---

Most of what this repository asks of a contributor is enforced by the build rather
than by review. This page says what those rules are and where each one bites; the
pages under it go into detail.

## The shortest path to a first change

```bash
make all                              # the full stack; first run takes a few minutes
# …make your change…
./scripts/validate.sh --offline       # the Docker-free subset
```

`make all` starts the infrastructure containers itself — do not run
`docker compose up -d` first.

Then a feature branch off `main`, and `./scripts/validate.sh` green before merge.

## The six things to know

**1. The module boundary is structural.** `aran-core` has no Spring Web and no JPA
annotations. Adapters and operators depend on the core and never on each other.
The application depends on both at **runtime only**, so a controller physically
cannot reference a JPA entity or an operator's internals. Break the rule and it
fails to compile, not review. See [Module layout](./module-layout.md).

**2. `validate.sh` is the gate, and it has an offline mode.** Only one Java test
class needs Docker, so `--offline` covers `aran-core`, both adapters, all six
operators, the Docker-free part of `aran-application`, the whole frontend
suite, `ruff` over every Python tree, and the unit suites of `freyja`,
`aran-client`, `aran-mcp`, and the sidecar security contract. See
[Build and test](./build-and-test.md).

**3. Comments explain why, never what.** Density here ranges from 5% to 52% and
that is fine — the 52% file earned it. A comment that restates the next line gets
deleted in review. See [Comment philosophy](./comment-philosophy.md).

**4. Prefer a test to a paragraph.** A claim that can be asserted should be a test
with a `@DisplayName`. Comments do not fail when they go stale.

**5. The schema is owned by migrations.** Flyway for the platform database,
alembic for the lake. Hibernate runs `ddl-auto: validate` and will never change a
schema for you.

**6. Documentation prose follows Simplified Technical English.** Short
sentences, one idea each, active voice. See [Writing style](./writing-style.md).

## What lives where

| Path | |
| --- | --- |
| `aran-core` | Domain records, ports, the `Operator` contract |
| `aran-application` | The Spring Boot app: HTTP, security, wiring |
| `aran-adapters/*` | Persistence, graph store, lake client, licence providers |
| `aran-operators/*` | The six capabilities |
| `aran-lake` | FastAPI lake service (Python, own venv, own alembic) |
| `freyja` | Profiling and join-discovery sidecar (Python, shares the lake's venv) |
| `aran-client` | Python SDK (own venv — users `pip install` it into their own environment) |
| `aran-mcp` | MCP sidecar (own venv — runs beside a user's MCP client, not beside Aran) |
| `tests/sidecar_contract` | One security-contract suite, run against every registered Python sidecar |
| `aran-web` | React 19 + Vite client — the only frontend |
| `docs-site` | This documentation site |
| `docs` | Internal engineering notes. Not published |

## Adding something new

| You are adding | Read |
| --- | --- |
| A capability | [Write a new operator](../how-to/write-a-new-operator.md) |
| An outbound dependency | A port in `aran-core`, an implementation in an adapter — [Module layout](./module-layout.md) |
| A database column | A Flyway migration. Never `ddl-auto` |
| A frontend wire type | `aran-web/src/services/types.ts`, by hand — see below |
| A browser scenario | [The e2e suite](./e2e-suite.md) |

### The hand-written frontend types

`aran-web/src/services/types.ts` is hand-written and is the only source for wire
shapes. There is deliberately **no checked-in generated `openapi.d.ts`**: the one
that existed served a single type, went stale, and ended up contradicting the
backend with nothing detecting it.

`npm run generate:api` reads the live `/v3/api-docs` and exists for cross-checking
a contract, not for producing a file to commit.

The documentation site's [API reference](../api/index.md) takes the opposite
approach — a committed snapshot **with a CI drift check** — which is what makes a
generated artefact safe to commit.

## Pages in this section

- [Module layout and dependency rules](./module-layout.md)
- [Build and test commands](./build-and-test.md)
- [Comment philosophy](./comment-philosophy.md)
- [Branching and the merge gate](./branching-and-merge-gate.md)
- [Running the end-to-end suite](./e2e-suite.md)
- [Writing style](./writing-style.md)

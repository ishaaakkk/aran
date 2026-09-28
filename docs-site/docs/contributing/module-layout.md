---
title: 'Module layout and dependency rules'
sidebar_label: 'Module layout'
sidebar_position: 1
description: 'Every Gradle and Python module, what belongs in it, and the direction dependencies are allowed to point.'
# Diátaxis mode: reference
---

## The modules

| Module | Kind | Contents |
| --- | --- | --- |
| `aran-core` | Gradle | Domain records, services, outbound ports, the `Operator` SPI, `TenantContextHolder`, `Names`, `WrapperSqlValidator` |
| `aran-application` | Gradle | `AranApplication`, the REST controllers, security filters, job config |
| `aran-adapters/aran-adapter-persistence` | Gradle | JPA repositories, Flyway migrations, `FusekiGraphStoreAdapter`, `HttpLakeClient`, `HttpJdbcDriverDownloader`, database licensing |
| `aran-adapters/aran-adapter-license-token` | Gradle | `SignedTokenLicenseProvider` and its configuration |
| `aran-operators/bootstrapping` | Gradle | CSV, JSON and parquet bootstrap; `WrapperGenerator` |
| `aran-operators/profiling` | Gradle | HTTP client for FREYJA profiling |
| `aran-operators/join-discovery` | Gradle | HTTP client for FREYJA join candidates |
| `aran-operators/schema-integration` | Gradle | Alignment, `SchemaIntegrator` |
| `aran-operators/constraint-discovery` | Gradle | Denial-constraint discovery (LIMA) |
| `aran-operators/query-engine` | Gradle | OQL parser and planner, Calcite rules, DuckDB executor, mapping catalog |
| `aran-lake` | Python | FastAPI lake service — snapshots, imports, materialise, query, jobs |
| `freyja` | Python | FastAPI sidecar — profiling and join discovery |
| `aran-client` | Python | The Python SDK users `pip install` |
| `aran-mcp` | Python | The MCP sidecar; runs beside a user's MCP client |
| `tests/sidecar_contract` | Python | One security-contract suite for every registered sidecar |
| `aran-web` | npm | React 19 + Vite client |
| `docs-site` | npm | This documentation site |

There are ten Gradle modules, all listed in `settings.gradle`.

Note that **four adapter implementations live in two modules**. Fuseki, the
lake client, the driver downloader, and JPA all ship inside
`aran-adapter-persistence`. Only the signed-token licence provider is
packaged separately, because it is the one some deployments want physically
absent.

The persistence adapter's `db/seed/` and `db/seed-e2e/` directories are
excluded from its jar, so demo fixture data cannot reach a production
artefact even if their Flyway locations were configured.

## The dependency rules

Three rules govern the Gradle modules, and the build enforces each one:
`aran-core` depends on nobody in this repository; adapters and operators
depend on the core and never on each other; the application depends on both
at **runtime only**, so a controller physically cannot reference a JPA
entity or an operator's internals. The diagram and the full reasoning live
in [Architecture](../overview/architecture.mdx#the-hexagonal-boundary).

## Where new code goes

| You are adding | Put it in |
| --- | --- |
| A domain record or a port | `aran-core` |
| A port implementation | The relevant adapter module — never the core |
| A capability | A new module under `aran-operators/` — see [Write a new operator](../how-to/write-a-new-operator.md) |
| An HTTP endpoint | `aran-application/.../web` |
| A schema change | A Flyway migration in the persistence adapter |
| A lake endpoint | `aran-lake/src/aran_lake/api/` |
| A Python sidecar | Its own tree, registered in `tests/sidecar_contract/registry.py` — see [the sidecar security contract](./python-sidecar-security.md) |

## Two invariants that are easy to break

### Name sanitisation lives in exactly one place

`edu.upc.dtak.aran.core.util.Names` holds the only name normalisers. There
are two distinct operations:

```java
Names.sqlName(iri)      // IRI → SQL view name: strip namespace, [^a-zA-Z0-9_] → _
Names.iriFragment(s)    // label → IRI-safe fragment: [^A-Za-z0-9._-] → _
```

Never inline a private copy of either regex. A drifted copy does not fail to
compile. It makes the SQL generator emit references to views that do not
exist.

### The join contract is consumed, not re-derived

`MappingCatalog.JoinProperty` carries the join's IRI, both integrated-class
IRIs, and both key columns as full global property IRIs. The planner uses
them directly. Do not re-derive join keys from source metadata.

## The Python side

Four Python trees ship, in three virtual environments:

| Tree | Venv | Why |
| --- | --- | --- |
| `aran-lake` | `aran-lake/.venv` | Its own alembic migrations; `make aran-lake-venv` bootstraps it without Docker |
| `freyja` | `aran-lake/.venv` (shared) | `make freyja-deps` installs its requirements into the lake's venv |
| `aran-client` | `aran-client/.venv` | Users `pip install` it into their own environment, so it must not inherit the lake's pins |
| `aran-mcp` | `aran-mcp/.venv` | Installs beside a user's MCP client and must resolve from its own declared dependencies |

Do not create a second environment for FREYJA. The Makefile and
`validate.sh` both assume the shared one.

## Related

- [Architecture](../overview/architecture.mdx) — the dependency diagram, and why the boundaries are where they are.
- [Write a new operator](../how-to/write-a-new-operator.md)

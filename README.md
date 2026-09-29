<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs-site/static/img/aran-lockup-dark.svg">
    <img src="docs-site/static/img/aran-lockup.svg" alt="Aran" height="48">
  </picture>
</h1>

**Query data sources that were never designed to fit together.**

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Status: research preview](https://img.shields.io/badge/status-research%20preview-orange.svg)

<p align="center">
  <img src="docs-site/static/img/aran-overview.svg" alt="Sources such as files, databases, object storage, Iceberg catalogs and Singer taps flow into Aran, which derives a schema per source, profiles columns and proposes joins, aligns them into one vocabulary and versions it. Results go to a query workbench, JDBC, REST, MCP for AI agents and data products." width="100%">
</p>

Aran reads your files, databases, object storage, Iceberg catalogs and Singer taps.
It derives a schema for each one, proposes how they join, and gives you one versioned
vocabulary to query. A single query can return rows from a CSV file, a JSON file and a
PostgreSQL table without naming any of them.

You stay in control of the integration. Aran stores it as a graph you can query, diff
between versions and constrain, and compiles it into SQL. It runs as one self-hosted
Docker Compose stack.

```
SELECT IPaper.title, IResearcher.name
FROM IPaper -[hasAuthor]-> IResearcher
WHERE IResearcher.affiliation = 'UPC'
```

> **Research preview.** Aran is pre-1.0 research software from the
> [DTAK](https://dtak.upc.edu/en) group at Universitat Politècnica de Catalunya. Run
> it self-hosted, for users you trust, on a network you trust. See
> [Status and limitations](docs-site/docs/overview/status.md) and [Security](#security).

## Quickstart

Requires Docker 24+ with Compose v2.

```bash
docker compose -f compose.standalone.yml up --build
```

Open http://localhost:8080 and sign in as `alice`. The first start prints a one-time
administrator password to the application log:

```bash
docker compose -f compose.standalone.yml logs aran-app | grep -A3 'first-run administrator'
```

Keycloak asks you to change it at first login. Set `ARAN_ADMIN_PASSWORD` before the
first start to choose it yourself. The [quickstart](docs-site/docs/getting-started/quickstart.md)
and [standalone guide](docs-site/docs/administration/standalone.md) cover the rest, and
the [walkthrough](docs-site/docs/getting-started/walkthrough.md) integrates three datasets
in about 45 minutes.

## What it does

| | Capability | What you get |
| --- | --- | --- |
| 1 | [**Connect**](docs-site/docs/capabilities/connect.md) | Files (CSV, JSON, Parquet), PostgreSQL, MySQL, MariaDB, S3-compatible storage, Iceberg REST catalogs and Singer taps. Copy a table or stream into the lake, or read PostgreSQL, MySQL, MariaDB and Iceberg tables live. |
| 2 | [**Derive and profile**](docs-site/docs/capabilities/derive-and-profile.md) | A schema, an editable SQL wrapper and column statistics for every source, generated from the data. |
| 3 | [**Align and version**](docs-site/docs/capabilities/align-and-version.md) | Join proposals backed by evidence, one integrated vocabulary, and a version history you can diff, promote and roll back. Denial constraints quarantine rows that break a rule. |
| 4 | [**Query**](docs-site/docs/capabilities/query.md) | OQL over the integrated vocabulary. Calcite plans it with cost estimates from the profiles, and DuckDB runs it on snapshots or on the live source. |
| 5 | [**Serve**](docs-site/docs/capabilities/serve.md) | Save a query as a named data product, stored as a snapshot or recomputed on every read. SQL clients and BI tools read products over JDBC, Iceberg engines through a read-only REST catalog, and AI agents over MCP with a read-only token. Scripts and notebooks run OQL directly through the REST export and the Python SDK. |

Uploading a file works for every repository type. There is no connector for document
stores, caches or generic web APIs: export that data to a file and upload it. The
[capability table](docs-site/docs/repositories/index.md) lists what each type supports,
and [Status and limitations](docs-site/docs/overview/status.md) lists what has been
tested against real services.

## A look around

Screens from the built-in demo, which integrates three sources about research papers.

<table>
  <tr>
    <td width="50%" valign="top">
      <a href="docs-site/static/img/screenshots/alignments.png"><img src="docs-site/static/img/screenshots/alignments.png" alt="Two derived schemas, from a CSV file and a JSON file, shown side by side for alignment"></a>
      <br><b>Align.</b> Map the schemas derived from a CSV and a JSON file onto shared classes.
    </td>
    <td width="50%" valign="top">
      <a href="docs-site/static/img/screenshots/query-workbench.png"><img src="docs-site/static/img/screenshots/query-workbench.png" alt="The query workbench with an OQL query and rows returned from two sources"></a>
      <br><b>Query.</b> One OQL query returns rows from both sources.
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs-site/static/img/screenshots/versions.png"><img src="docs-site/static/img/screenshots/versions.png" alt="Schema version history with a diff between two versions"></a>
      <br><b>Version.</b> Compare any two promoted versions and roll back.
    </td>
    <td width="50%" valign="top">
      <a href="docs-site/static/img/screenshots/agents.png"><img src="docs-site/static/img/screenshots/agents.png" alt="The dialog that configures an MCP client to read a data product"></a>
      <br><b>Serve.</b> Give Claude Desktop, Claude Code or Cursor a read-only token for a data product.
    </td>
  </tr>
</table>

## Documentation

- [What is Aran](docs-site/docs/overview/what-is-aran.md) and [why Aran](docs-site/docs/overview/why-aran.md)
- [Core concepts](docs-site/docs/overview/core-concepts.md)
- [OQL tutorial](docs-site/docs/oql/tutorial.md)
- [Connect your tools](docs-site/docs/programmatic-access/index.md): JDBC, notebooks, MCP, Iceberg
- [Administration](docs-site/docs/administration/standalone.md)
- [Contributor guide](docs-site/docs/contributing/index.md)

The site is built from [`docs-site`](docs-site) and published at https://dtak-upc.github.io/aran/.

## Repository layout

| Path | Contents |
| --- | --- |
| `aran-core`, `aran-application`, `aran-adapters`, `aran-operators` | The Java platform: domain, Spring Boot app, adapters and the operators that do the work. |
| `aran-web` | The React client. |
| `aran-lake`, `freyja`, `aran-mcp` | Python services for snapshots, profiling and MCP. |
| `aran-client` | The Python SDK, versioned separately with its own changelog. |
| `docs-site`, `demo` | The documentation site and the sample data. |
| `tests/sidecar_contract` | The security contract every Python sidecar must satisfy. |

Dependencies point one way: `aran-core` ← adapters and operators ← application.
`ArchitectureTest` in `aran-application` fails the build if the core imports Spring
Web, Spring Data, Spring Security, JPA, Hibernate, servlet or DuckDB types, or any
adapter, operator or application class. [Module layout](docs-site/docs/contributing/module-layout.md)
explains the rules.

## Develop

Requires JDK 25, Node.js 20+, Docker 24+ with Compose v2 and Python 3.12.

```bash
make all      # the whole stack from source, then opens the UI
make stop     # shut it down
```

`make all` starts the lake's Docker dependencies, the `aran-lake` service, the FREYJA
profiling sidecar, Postgres, Keycloak, Fuseki, the Spring Boot backend and the Vite dev
server. The first run also creates the lake's Python virtual environment, which takes a
few minutes. The `./aran` CLI wraps the same scripts (`./aran start|stop|restart|status`).

| Service | URL |
| --- | --- |
| Web UI | http://localhost:5173 |
| Backend API | http://localhost:8081/api |
| Lake API | http://localhost:8085 |
| FREYJA sidecar | http://localhost:8086 |
| Keycloak | http://localhost:8080 |
| Fuseki | http://localhost:3030 |

The development realm signs you in as `alice` / `aran`, and Keycloak's own console uses
`admin` / `admin`. Both are published in this repository and are for local development only.

To build and test:

```bash
make bootstrap                    # once: the Python venvs and node_modules the gate needs
./scripts/validate.sh             # the full gate (needs Docker for Testcontainers)
./scripts/validate.sh --offline   # everything that does not need a Docker daemon
./scripts/validate.sh --java      # one part: --java, --web or --python (combinable)
```

`RUN_PERF=1` adds the query-engine performance benchmark, and `RUN_E2E=1` adds the
browser end-to-end suite (Playwright) to the full gate. See [CONTRIBUTING.md](CONTRIBUTING.md)
and [Build and test](docs-site/docs/contributing/build-and-test.md).

For a read-only tour over canned data, run `cd aran-web && npm run dev:demo` and sign in
as `demo` / `demo`. It needs no Aran backend, only the development Keycloak
(`docker compose up -d keycloak`). [`demo/seminar/DEMO.md`](demo/seminar/DEMO.md) walks
through a full integration over sample data, and [`demo/seminar/MCP_DEMO.md`](demo/seminar/MCP_DEMO.md)
continues it by connecting an AI agent.

## Security

Deploy Aran on a trusted network, for trusted users.

- The development realm and compose files ship published default credentials
  (`alice` / `aran`, Keycloak `admin` / `admin`). Never expose a development stack.
- The standalone stack generates the first administrator password and listens only on
  loopback by default. Read the [standalone guide](docs-site/docs/administration/standalone.md)
  before making it reachable from another machine.
- Multi-tenant hosting for untrusted users is not supported.

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md), not in a
public issue.

## Project

| | |
| --- | --- |
| **License** | [Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE). |
| **Citing** | [`CITATION.cff`](CITATION.cff). GitHub's "Cite this repository" button reads it. |
| **Changes** | [`CHANGELOG.md`](CHANGELOG.md). Breaking changes land in minor releases until 1.0. |
| **Contributing** | [`CONTRIBUTING.md`](CONTRIBUTING.md). Commits are signed off (DCO). There is no CLA. |
| **Conduct** | [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) |

Aran is developed by the [DTAK research group](https://dtak.upc.edu/en) at the
[Universitat Politècnica de Catalunya (UPC)](https://www.upc.edu).

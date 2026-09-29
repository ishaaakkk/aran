# Demo relational sources

Two schemas in one Postgres container, standing for two independent
institutional systems. With them, the seminar demo integrates relational
databases with files, and not only files with each other.

| Schema | Stands for | Tables | Role in the integration |
|---|---|---|---|
| `registry` | A university CRIS (research information system) | `publication`, `publication_credit` | A third source of papers, folded into `IPaper` |
| `funding` | A national/European funding agency | `project`, `project_member` | A concept in no file source: a new integrated class, joined to `Int_researchers` |

## Start it

```bash
docker compose --profile demo up -d demo-sources
```

It is behind the `demo` profile, so a plain `docker compose up -d` does not start
it. Wait for the healthcheck before registering:

```bash
docker compose ps demo-sources          # expect (healthy)
```

Check the seed loaded:

```bash
docker exec aran-demo-sources psql -U demo -d demo_sources \
  -c 'SELECT count(*) FROM registry.publication;'   # 12
docker exec aran-demo-sources psql -U demo -d demo_sources \
  -c 'SELECT count(*) FROM funding.project;'        # 6
```

## Register in Aran

Register two repositories: same server, different schema. In **Repositories →
Register**, choose Relational Database, then:

| Field | `registry` | `funding` |
|---|---|---|
| Engine | `POSTGRESQL` | `POSTGRESQL` |
| Database | `demo_sources` | `demo_sources` |
| Schema | `registry` | `funding` |
| Username | `aran_reader` | `aran_reader` |
| Password | `aran_reader` | `aran_reader` |

Host and port depend on where Aran itself runs. A wrong value here is the
most common reason the demo stalls:

| How you started Aran | Host | Port |
|---|---|---|
| On your machine (`make all`, or `./gradlew :aran-application:bootRun`) | `localhost` | `5434` |
| `docker compose -f compose.standalone.yml up -d` (Aran in a container) | `demo-sources` | `5432` |

In the second case Aran and Postgres share a compose network, so Aran reaches the
container by service name on the internal port. Inside a container, `localhost`
is the container itself, not your machine.

The host port is `5434` because the stack this demo runs beside already uses
5432 (Aran's own metadata Postgres) and 5433 (the data lake's, in
`aran-lake/docker-compose.yml`).

`aran_reader` is a read-only role created by `init.sql`. Aran only introspects
and reads a registered relational source, so a read-only account is all it
needs. Do not register a source with an owner account.

## Why the column names do not match

They differ from the file sources on purpose:

| File sources | This database |
|---|---|
| `paper_id` / `doi` | `pub_ref` |
| `title` | `pub_title` |
| `year` | `pub_year` |
| `venue` (`ISWC`) | `outlet` (`International Semantic Web Conference`) |
| `author_id` / `researcher_id` | `person_ref` |

If every source already used the same name for the join key, join discovery
would have nothing to find. The venue column is the hardest case: abbreviations
on one side, full conference names on the other, as real sources often have.

## What the data supports

- A three-way union into `IPaper`. Five `registry.publication` rows are the
  same works as entries in `acm_papers.csv` and `semantic_papers.json`, under
  this system's own identifiers, so the union has real duplicates to resolve.
  Seven rows are national-venue and teaching output that neither file source
  indexes, so the integrated class is a superset of all three.
- Two join keys of different quality. `doi_link` is set only for the two
  rows whose DOI appears in `semantic_papers.json` and is NULL elsewhere. Where
  it exists it is a precise key; elsewhere, title and year are the only
  fallback.
- A query no single source can answer. `funding` shares no concept with any
  file source; it links through `person_ref`. Answering "which funded projects
  produced open-access publications, and by whom" needs the integrated model,
  with relational and file sources together.

## Regenerating or extending it

`init.sql` runs only on an empty data volume, the standard behaviour of the
Postgres image's `docker-entrypoint-initdb.d`. After editing it:

```bash
docker compose --profile demo rm -sf demo-sources      # stop and remove only this container
docker volume ls -q --filter name=aran-demo-sources-data | xargs docker volume rm   # only this volume
docker compose --profile demo up -d demo-sources
```

If you add rows, keep `person_ref` values within `R001`–`R020`. Those are the
`researcher_id`s in `researchers.json`, and the join to `Int_researchers`
depends on them.

## More

The [documentation site](https://dtak-upc.github.io/aran/) has the seminar walkthrough.
[CONTRIBUTING.md](../../../CONTRIBUTING.md) explains how to build and test changes.

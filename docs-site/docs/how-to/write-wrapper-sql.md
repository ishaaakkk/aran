---
title: 'Write and edit wrapper SQL'
sidebar_label: 'Write wrapper SQL'
sidebar_position: 4
description: 'Shape a raw snapshot into the flat relation an integrated class expects, and preview the result before compiling.'
# Diátaxis mode: how-to
---

A wrapper is the SQL that shapes one source class's raw snapshot into the
flat relation its integrated class reads. Use this page to filter or reshape a
source before it is integrated, preview the result, and apply it.

## Edit the right field

There are two fields called "wrapper", and only one of them executes. The
one you edit is the **Wrapper tab on the dataset detail panel**. That tab
holds the integration model's copy, which compiling writes back into the
schema graph. See
[The two wrapper fields](../deep-dives/wrapper-fields.md).

## Enrol the dataset first

1. Open the dataset.
2. Go to the **Wrapper** tab.
3. If you see an **Enroll in model** button instead of an editor, click it.

Enrolling adds this source to the project's integration model and
seeds the editor with the wrapper that bootstrapping generated. It is not
integrating, and it needs no second source.

:::warning Let the seed load before typing
The editor fills in asynchronously, just after it appears. If you type
before the seed arrives, the seed overwrites your text without warning. Wait
for SQL to show up, then replace it.
:::

## Write the SQL

Write one `SELECT` over the literal placeholder `${SNAPSHOT}`, and start from
the wrapper bootstrapping generated instead of a blank editor. The generated
wrapper aliases every column to the full IRI of the source property it feeds,
and **that alias is the contract with the integrated views**: they select
those exact column names. Keep every alias. A wrapper that renames or drops
one, such as `doi AS paper_id` or a bare
`SELECT paper_id, title FROM ${SNAPSHOT}`, breaks every query over the
integrated class with a DuckDB binder error naming the missing column. When
Aran can see that, it refuses the save: see
[what a save checks](#what-a-save-checks).

What you change is which rows come back. For the walkthrough's `acm`
dataset (`<id>` is its dataset id), keeping only papers from 1990 on:

```sql
SELECT "paper_id"  AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/acm/paper_id",
       "title"     AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/acm/title",
       "year"      AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/acm/year",
       "venue"     AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/acm/venue",
       "author_id" AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/acm/author_id"
FROM ${SNAPSHOT}
WHERE "year" >= 1990
```

At execution time, Aran substitutes `${SNAPSHOT}` with the view over the
dataset's snapshot. For a federated repository, it substitutes a table of
the source database that DuckDB attaches read-only. Never name a file or a
table yourself: Aran refuses an attached database's `aran_attach_…` name
outright.

### What a save checks

Saving runs your text against the dataset's data, but only once the class is
in the active schema. Before that (the dataset is enrolled but no schema with
its class has been promoted), Aran has nothing to check against: a wrapper
that drops an alias still saves, and the error appears as a binder error on
the first query after you compile and promote. Preview it first.

Once the class is in the active schema, Aran refuses to save a wrapper that
renames or drops an alias (`AS "<property IRI>"`), naming the missing columns:

> the wrapper no longer outputs `<n>` column(s) its class reads: `<iri, …>`
> — keep each one aliased exactly as the generated wrapper does (AS
> "&lt;property IRI&gt;").

A wrapper that does not run at all over the dataset's data, such as one with
a misspelt raw column or a function DuckDB does not have, is refused the same
way, quoting
DuckDB's own error:

> the wrapper does not run over its dataset's data: Binder Error: Referenced
> column "titel" not found in FROM clause!

That check is skipped while a refreshed dataset's rebuild has not been
promoted, since your wrapper may name a column only the new data has. It also
never fires when the data could not be read at all.

A column the source itself has lost since the last refresh is exempt, because
dropping it is how you repair the rebuild refusal [below](#apply-it). Such a save is
badged Opaque with a `stale:` reason. Each rebuild re-checks it, and it earns
its real verdict at the first rebuild after the schema without that column is
promoted.

### Flatten a nested array

Bootstrapping already writes this for a JSON record with one array of plain
values, here the walkthrough's `semantic` dataset:

```sql
SELECT s."doi"            AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/semantic/doi",
       s."title"          AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/semantic/title",
       s."year"           AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/semantic/year",
       _r1                AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/semantic/authors",
       s."citation_count" AS "http://www.essi.upc.edu/DTIM/NextiaDI/source/<id>/semantic/citation_count"
FROM ${SNAPSHOT} s, UNNEST(s."authors") AS u1(_r1)
```

The `UNNEST` lateral turns one row with three authors into three rows with
one author each. That is the shape a flat CSV source already has, which is what
makes the two sources unionable. The unnested value keeps the `authors`
property's alias. Pairing it with ACM's `author_id` is the alignment's job
(one label over both), not a rename in the wrapper.

## Preview it

Click **Validate & Preview**. Your SQL runs against the real snapshot, and
the rows come back. Check the row count as well as the columns: an `unnest`
that silently did nothing shows in the count, not in the headers.

The preview endpoint runs whatever SQL is in the request body, which is the
SQL the editor sends, and reads no stored field. The body passes the
same validation screen below before it runs.

When the preview is right, click **Save Wrapper**. The save runs the checks
in [What a save checks](#what-a-save-checks) and earns the wrapper its
[badge](#read-the-badge).

## What is allowed

A wrapper is a single `SELECT` statement whose `FROM` is `${SNAPSHOT}` and
nothing else, with one addition: `${SNAPSHOT}` can be followed by
comma-joined `UNNEST` laterals. That is how a wrapper reads a JSON array, and
it is the shape Aran generates for a nested source. Saving anything
else fails with one of these messages:

| Rejected | Message |
| --- | --- |
| An empty wrapper | `Wrapper SQL cannot be empty` |
| Not a `SELECT` | `Wrapper SQL must be a SELECT statement` |
| No `FROM` | `Wrapper SQL must have a FROM clause` |
| A `JOIN` of any kind, to anything | `Wrapper SQL must not join — FROM must be ${SNAPSHOT} alone` |
| A comma-joined table that is not `UNNEST`, `GENERATE_SERIES` or `RANGE` | `Wrapper SQL must not join — FROM must be ${SNAPSHOT} alone` |
| A `FROM` naming anything but `${SNAPSHOT}` | `FROM clause must strictly reference ${SNAPSHOT}` |
| A screened function anywhere | `Forbidden construct in wrapper SQL: forbidden function call: <name>` |
| Syntax Calcite cannot parse | `Invalid SQL syntax in wrapper: <parser message>` |

The function screen rejects six **name families** (`READ_*`, `*_SCAN`,
`SCAN_*`, `DUCKDB_*`, `MYSQL_*`, `POSTGRES_*`) and these named functions:

```
HTTP_GET, COPY, GLOB, ATTACH, DETACH, INSTALL, LOAD, PRAGMA, EXPORT,
IMPORT, SNIFF_CSV, PARQUET_METADATA, PARQUET_SCHEMA, SHELL, SYSTEM,
GETENV, QUERY, QUERY_TABLE, JSON_EXECUTE_SERIALIZED_SQL,
PARQUET_FILE_METADATA, PARQUET_FULL_METADATA, PARQUET_KV_METADATA,
PARQUET_BLOOM_PROBE, WHICH_SECRET, COPY_DATABASE, IMPORT_DATABASE
```

Two kinds of identifier are refused anywhere in the text, called or not:
the `aran_attach_…` name Aran gives an attached source database, and DuckDB's
catalogue (`duckdb_*`, `pragma_*`, `information_schema`, `pg_catalog`,
`sqlite_master` and the other `sqlite_*` schema names), which can return a
view's full SQL.

Two rules are structural, because a name screen alone would miss them:

- A string literal in table-reference position is rejected. `FROM
  '/etc/passwd.csv'` is a file read with no function call anywhere, via
  DuckDB's replacement scan. The message: `Forbidden construct in wrapper
  SQL: a string literal cannot be used as a table reference (DuckDB would
  read it as a file)`.
- A function call in table-reference position is rejected, except
  `UNNEST`, `GENERATE_SERIES`, and `RANGE`. This closes DuckDB indirection
  like `query()`.

Reading the snapshot is the snapshot resolver's job: it knows the dataset's
`nx:format`, and the federated path lives there. A wrapper that read its own
file would bypass both.

### Calcite parses your wrapper, not DuckDB

Validation parses the wrapper with **Calcite's** SQL parser. DuckDB-only
syntax therefore fails the save with `Invalid SQL syntax in wrapper: …`,
even though DuckDB itself would run it. `unnest`, `CASE`, casts, string
functions, and arithmetic all parse. `list_transform`, `struct.field`
access, `QUALIFY`, and `SELECT * EXCLUDE(...)` do not. CTEs and window
functions are also out of a wrapper, though they work in [SQL
mode](../oql/sql-fallback.md#what-raw-sql-is-allowed-to-be), which does not
parse with Calcite.

## Read the badge

Saving a wrapper also earns it a verdict, shown as a badge above the editor.
**Optimizable** means Aran can plan through your SQL. **Opaque** means Aran
runs it as written, and the badge states why. Opaque is not an error. See
[Optimizable and opaque wrappers](../query-engine/wrapper-optimisation.md)
for what each verdict changes.

The verdict changes after the save:

- A full rebuild re-checks every Optimizable, never-checked, and
  retryable-Opaque wrapper, and downgrades an Optimizable verdict it
  cannot re-check.
- A dataset refresh downgrades every Optimizable verdict on that
  dataset immediately, with a `stale:` reason. Rebuild to re-earn it.
- An incremental add re-checks nothing.

## Apply it

Wrapper edits do not affect queries until you compile the model.

1. Go to **Alignments**.
2. Click **Rebuild draft schema (N sources)**.
3. Click **Promote v*N* (tenant-wide)…** and confirm.

Compiling writes your wrapper over the source class's `nx:wrapperSql`, the
field the executor reads.

Two behaviours follow when the source changes:

- An edited wrapper can block a rebuild. If your wrapper names a column
  the source has since lost, the compile refuses with:

  > Dataset `<id>`: the edited wrapper for class `<iri>` references
  > column(s) the source no longer has: `<a, b>`. The source's columns are
  > now: `<…>`. Edit the wrapper on the Wrapper tab and compile again; the
  > active schema keeps serving queries until you do.

- An unedited wrapper is re-seeded silently. A wrapper you never
  touched is regenerated from the source's new schema after a refresh, so
  it tracks the source. Editing a wrapper once turns that off permanently:
  from then on Aran keeps your text, and the compile refusal above protects it.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Edits reverted as you typed | The seed effect landed after your text. Retype. |
| `Forbidden construct in wrapper SQL: …` | A screened function, a reserved or catalogue name, a file path in `FROM`, or a function call in table position. See the screen above. |
| `Invalid SQL syntax in wrapper: …` | DuckDB-only syntax. Calcite parses wrappers. |
| `Wrapper SQL must not join — FROM must be ${SNAPSHOT} alone` | A `JOIN`, or a comma-joined table that is not an `UNNEST` lateral. Joining is the query engine's job. Align the two sources into one class, or join in [SQL mode](../oql/sql-fallback.md). |
| A rebuild fails naming your wrapper's columns | The source lost a column your edited wrapper uses. See the compile refusal above. |
| The save fails with `the wrapper no longer outputs … column(s) its class reads` | You renamed or dropped a property-IRI alias. Restore it. |
| The save fails with `the wrapper does not run over its dataset's data: …` | DuckDB could not bind the text, usually because of a misspelt raw column. The quoted error names it. |
| Preview works, queries do not reflect it | Not compiled and promoted yet. |
| A column is null for one source | Expected in a union when only one source contributes it. |
| `unnest` returns one row | The field is not an array in that record. Preview the raw data on the Preview tab. |

## Related

- [The two wrapper fields](../deep-dives/wrapper-fields.md): which copy runs.
- [Optimizable and opaque wrappers](../query-engine/wrapper-optimisation.md): what the badge means.
- [Align and integrate schemas](./align-and-integrate-schemas.md): applying the change.

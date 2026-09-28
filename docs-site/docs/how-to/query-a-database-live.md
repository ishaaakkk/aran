---
title: 'Query a database live'
sidebar_label: 'Query a database live'
description: 'Serve a PostgreSQL table from the live database instead of a snapshot, so every query reads the current rows.'
# Diátaxis mode: how-to
---

Aran normally copies a source into its own lake and queries the copy. A
**federated** repository is served the other way: every query reads the live
database. There is no snapshot, and no refresh to run.

Use this when the rows change often and staleness costs you more than query
latency does. A [Singer tap](../repositories/singer-taps.md) is the opposite
trade: it snapshots by construction and cannot be read live.

:::note PostgreSQL, MySQL and MariaDB
The steps use PostgreSQL. MySQL and MariaDB follow the same path: bootstrapping
loads the vendor's JDBC driver, downloaded on first use. Each query attaches
the database read-only, for all three engines — see
[Federated repositories](../repositories/relational-databases.md#federated-repositories).
:::

## The path is not the obvious one

Two nearby actions do something else, and the repository flag reaches
further than it looks.

- The dataset list's bootstrap action refuses a dataset with no snapshot. A
  federated dataset never has one. Use the **Alignments** view instead.
- The dataset dialog's **Import from table** action **copies** the table into
  the lake. It is the opposite of what this guide does.
- **Federated Execution** applies to **every** dataset in the repository. A
  table you imported earlier is then read live as well, and its lake copy is
  ignored at query time. Set it before you register datasets under the
  repository.

## Steps

1. **Mark the repository federated.**
   Open **Repositories**, register or edit a PostgreSQL repository, and tick
   **Federated Execution**. Fill in **Schema** if the table is not in
   `public` — a blank schema reads `public`.

2. **Create a bare dataset named exactly for the remote table.**
   Click **Datasets → Register dataset**. Pick the project, then this repository.
   Keep **Upload file** selected — do **not** pick **Import from table**.
   Type the remote table's name into **Name**. Attach **no file**. Save.

   The name is not a label here. Aran reads the table of that name, and
   queries scan the table of that name. A typo produces a dataset that
   bootstrapping cannot introspect.

3. **Let it bootstrap.**
   Saving starts the bootstrap as a job, the way an upload does: the row shows
   **Bootstrapping…** and turns **Ready** on its own. Aran connects to the
   database, reads the table's columns from the catalog, and writes a source
   schema. It reads no rows.

   To bootstrap it again later, open **Alignments**, select the project,
   choose the dataset on either side, and click **Run Bootstrapping
   Operator**.

4. **Align, compile, and promote as usual.**
   Follow [Align and integrate
   schemas](./align-and-integrate-schemas.md). Nothing about alignment,
   compiling, or promotion differs for a federated source.

## Confirm it is live

Query the class. Then insert a row straight into the source database. Then
run the same query again, with no refresh and no re-bootstrap:

```
SELECT Int_venue.name, Int_venue.capacity
FROM Int_venue
```

The new row appears. Nothing in the lake could have served it, because this
dataset has no snapshot.

Open the **explain** tab to see the same fact in the plan. Each scan carries
an access chip. A federated source reads `FEDERATED`. A class whose sources
disagree reads `MIXED`.

## What still works, and what does not

| Feature | On a federated dataset |
| --- | --- |
| OQL queries, joins, aggregates | Yes. |
| Enforced denial constraints | Yes. Aran applies them over the live scan, exactly as over a snapshot. |
| [Virtual data products](../deep-dives/virtual-data-products.md) | Yes. A virtual product over a federated class serves live rows. |
| Schema-drift checks | Yes. Aran asks the live database for the table's current shape. |
| Column profiles from FREYJA | Yes, at compile time, while the class's wrapper is Opaque. FREYJA runs its profiling queries over the same live read that queries use. Only the aggregates come back, and nothing is copied into the lake. |
| The dataset tab's **Profile** action | **No.** It profiles a snapshot, and there is none. |
| The Wrapper tab's **Validate & Preview** | **No.** Preview downloads a snapshot. |
| Constraint discovery | **No.** It reads a snapshot too. |

While a federated class's wrapper is
[Opaque](../query-engine/wrapper-optimisation.md), the planner costs it off
those profiles. They are exact for the whole table as of the last compile,
and they age as the table changes. For that reason Aran never treats them
as proof that a column is unique, which is the proof join removal needs.

Once a full rebuild verifies the wrapper as Optimizable, profiling stops.
That is the usual outcome for a plain table. From then on the class costs
off the database's own catalog statistics, which are approximations. Aran
harvests those from PostgreSQL only, so a MySQL or MariaDB class then has no
statistics at all. When a profiling pass fails or FREYJA is not running, an
Opaque class keeps the profiles of the last pass that succeeded; only a class
that has never been profiled falls back to catalog statistics. See
[Referential evidence and join removal](../query-engine/referential-evidence.md).

:::note What profiling costs the database
While the wrapper is Opaque, every compile runs one full profiling pass
against the source table. FREYJA connects to the database itself, with the
repository's credentials, which Aran passes to it for that pass. FREYJA's
host therefore needs network access to the database, as the backend's does.
:::

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| *Dataset has no snapshot — upload data before bootstrapping* | The repository is not marked federated, or its engine has no live scanner. |
| *No suitable driver* | The engine is not PostgreSQL. See the warning above. |
| *Cannot bootstrap: dataset has no bound snapshot* | You used the dataset list's action. Use the Alignments view. |
| The plan says `MATERIALIZED` | The repository is not marked federated, or its engine has no live scanner, or the repository is object storage and this dataset has a lake copy. Aran then serves the dataset from its lake copy. |
| *Federated wrapper-output profiling failed for class …* in the backend log | FREYJA could not run its profiling pass against the database. Check that FREYJA's host can reach the database. Queries are unaffected: the class keeps the profiles of its last successful pass, or, if it has none, falls back to catalog statistics or to none. |
| The **explain** output shows a snapshot read expression | A display defect in `explain` only. The executed query uses the live scan. |

## Related

- [Relational databases](../repositories/relational-databases.md) — every field, and which engines federate.
- [Referential evidence and join removal](../query-engine/referential-evidence.md) — what Aran harvests from the same database.
- [The query pipeline](../deep-dives/query-pipeline.mdx) — where the live scan replaces the snapshot read.

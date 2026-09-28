---
title: 'Bootstrap and profile a dataset'
sidebar_label: 'Bootstrap and profile a dataset'
sidebar_position: 2
description: 'Derive a source schema graph from a dataset and collect the column profiles the planner and join discovery depend on.'
# Diátaxis mode: how-to
---

Bootstrapping turns a snapshot into a schema. Profiling measures its columns.
Registering a dataset normally does both for you. This page is for when it
did not, or when you need to redo one.

Every API call on this page needs a **`FULL`** token: `bootstrapping` and
`profiling` are not the query engine, so a READ token is refused with
*a READ-scope API token cannot invoke '…' on \<operator\>*. See
[Programmatic access](../programmatic-access/index.md#token-scopes-the-split-is-by-route-not-by-meaning).

## What happens automatically

Registering a dataset runs a pipeline: snapshot → bootstrap → **best-effort**
profile. The status badge goes from **Bootstrapping…** to **Ready**. (The
API-level enum behind the badge is `PENDING`, `RUNNING`, `READY`, `FAILED`,
`CANCELLED`, `DRIFTED`.)

"Best-effort" is the key word for profiling. If FREYJA is unreachable, the
profile step fails quietly. The job still succeeds, because the bootstrap
genuinely did succeed — failing the job would be a lie. So a dataset can be
**Ready** with no profiles at all.

## Re-bootstrap a dataset

From the UI, re-run bootstrapping from the dataset's detail panel. Over the
API:

```http
POST /api/operators/bootstrapping/submit
{
  "operation": "bootstrap",
  "parameters": { "datasetId": "<uuid>" }
}
```

| Parameter | Required | Notes |
| --- | --- | --- |
| `datasetId` | Yes | |
| `format` | No | Force `JSON`, `CSV`, or `PARQUET`. Otherwise Aran resolves it from the snapshot's MIME type, falling back to the dataset name's extension. Ignored for a snapshot-less federated source — no bytes to detect. |
| `filenameHint` | No | Helps format detection when the name is unhelpful. |
| `force` | No | Apply the new schema even when it breaks mappings already compiled into the active schema. Defaults to false. |

Re-bootstrapping is idempotent. Aran clears existing wrapper triples first.

:::warning Re-bootstrapping can refuse to apply
If the new schema would break something compiled into the active schema,
Aran writes nothing. The dataset's badge becomes **SCHEMA DRIFT**. Nothing
failed, and nothing is retryable. See [Handle drift](#handle-drift).
:::

## Check what re-bootstrapping would change

```http
POST /api/operators/bootstrapping/invoke
{ "operation": "drift-check", "parameters": { "datasetId": "<uuid>" } }
```

This operation is read-only. It reports the difference and writes nothing.
It also accepts the same optional `format` and `filenameHint` as
`bootstrap`. Run it before a `bootstrap` on a dataset already wired into a
live schema.

A **federated** dataset has no snapshot to re-read. Use this instead:

```http
POST /api/operators/bootstrapping/invoke
{ "operation": "drift-probe", "parameters": { "datasetId": "<uuid>" } }
```

This operation asks the live database for the table's current shape. It
compares that shape against the recorded baseline.

## Handle drift

A **SCHEMA DRIFT** badge means the source changed shape. Aran did **not**
apply the change. The previous graph is still in place, and queries still
work. Choose one:

1. **Repair the mappings** to match the new shape, then re-bootstrap.
2. **Accept the new shape.** Re-bootstrap with `force=true`. The affected
   mappings will break.
3. **Leave it.** Nothing degrades. You keep querying the old shape.

## Run profiling

Bootstrap already profiles every dataset that has a snapshot, right after
it reads the schema, so you rarely need to run profiling yourself. Join
discovery reads those stored profiles.

From the UI:

1. Open the dataset.
2. Go to the **Profile** tab.
3. Click **Run Profiling Operator**.

The button needs a bound snapshot. Without one — the federated case — the
tab says *Cannot profile: this dataset metadata has no bound snapshot ID.*
When profiling finishes, per-column metrics appear, including *Cardinality
count*.

Over the API:

```http
POST /api/operators/profiling/submit
{
  "operation": "profile",
  "parameters": {
    "snapshotId": "<snapshot id>",
    "datasetId": "<dataset uuid>"
  }
}
```

| Parameter | Required | Default | Notes |
| --- | --- | --- | --- |
| `snapshotId` | Yes | | The **snapshot**, not the dataset |
| `datasetId` | No | none | **Pass this.** Without it the profiles are computed and not persisted |
| `varcharOnly` | No | `true` | Profile only varchar columns. Any value other than the literal `false` counts as true, garbage included |
| `maxConcurrentColumnQueries` | No | `4` | Parallelism against the lake |
| `wrapperSql` | No | none | Profile the output of this wrapper SQL instead of the raw snapshot |
| `classIri` | No | none | The source class the wrapper-output profile belongs to |

Note three things. First, the required parameter is `snapshotId`, but the
persistence parameter is `datasetId` — omit `datasetId` and you get a
result in the response and nothing stored. Second, `varcharOnly` defaults
to **true**, so by default Aran does not profile numeric columns. Third,
Aran records what a run measured — the snapshot itself (`SNAPSHOT`), a
wrapper's output when `wrapperSql` is given (`WRAPPER_OUTPUT`), or a live read
(`FEDERATED_LIVE`) — and that subject is derived, never a parameter. It
decides who can use the result: **join discovery reads only
`SNAPSHOT`-subject profiles.** A `WRAPPER_OUTPUT` profile feeds the query
planner's costing and contributes nothing to discovery.

## Why profiles matter

Profiles are not decoration. Two things read them:

- **Join discovery** compares value domains across datasets to propose
  joins. With no `SNAPSHOT` profiles, there are no candidates.
- **The query optimiser** takes its row counts and keys from them. An
  unprofiled dataset reports a row count of zero. Calcite treats this as
  `Statistics.UNKNOWN`. Queries still return correct results, but the
  planner has no basis for choosing between plans.

So if join discovery finds nothing, or a query is unexpectedly slow, check
whether the datasets are profiled first.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| **Ready** but the Profile tab is empty | FREYJA was unreachable during the automatic run. Run it manually. |
| Profiling never finishes | FREYJA down. The `profiling` operator has **no `ping`** — it answers `UNSUPPORTED_OPERATION`. Check `GET /actuator/health` (its `freyja` indicator probes the sidecar) or, as an owner, `GET /api/tenant/admin/system`. |
| Badge stuck on **Bootstrapping…** | The job event stream, not the job. Reload; the work is usually done in seconds. |
| **Failed** | The file did not parse. JSON whose root is neither object nor array is the usual cause. |
| Profiles ran but numeric columns have none | `varcharOnly` defaults to true. |

## Related

- [Bootstrapping and the schema graph](../deep-dives/bootstrapping-and-the-schema-graph.mdx) — what gets written.
- [Run join discovery](./run-join-discovery.md) — the main consumer of profiles.

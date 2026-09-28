---
title: 'Run join discovery'
sidebar_label: 'Run join discovery'
sidebar_position: 5
description: 'Have Aran propose join properties between classes from column-overlap evidence, then accept or reject them.'
# Diátaxis mode: how-to
---

Join discovery compares column profiles across two datasets. It ranks
column pairs by how much their value domains overlap. It answers "which
columns look like they refer to the same things" — from the data, not from
names.

## Before you start

**Both datasets must have `SNAPSHOT` profiles.** Discovery reads profiles,
not rows — and only profiles measured over a snapshot. Two dataset shapes
therefore yield zero candidates by design:

- A **federated** dataset. It has no snapshot: its profiles are taken over
  the live read at compile time, or harvested from the source catalog, and
  both are for the planner.
- A class profiled over its **wrapper output** (`subject=WRAPPER_OUTPUT`,
  written at every compile) — that evidence is for the planner, not for
  discovery.

See [Bootstrap and profile a dataset](./bootstrap-and-profile-a-dataset.md),
including the `subject` parameter.

FREYJA does the scoring. Do not use the operator's `ping` to check it —
that returns a hardcoded success and contacts nothing. Check
`GET /actuator/health` (its `freyja` indicator performs a real probe) or,
as a tenant owner in the UI, `GET /api/tenant/admin/system`.

Calling `join-discovery` over the API needs a **`FULL`** token — a READ
token can invoke only `query-engine` operations.

## From the UI

1. Open **Alignments** and select the project.
2. Set a source on each side.
3. Click **Auto-Infer Join Alignments**.
4. Wait. Aran scores every column pair from the profiles stored for both
   datasets — bootstrap profiled them; discovery does not profile. This can
   take a couple of minutes on wide datasets.
5. Tick the candidates you want.
6. Accept them.

Each accepted candidate becomes a `join` mapping.

:::note Discovery runs only when you ask
Choosing the two sources does not start discovery: the button does, and the
**Run Discovery** control inside the panel re-runs it. If you close the panel
while a run is going, the line under the button says when candidates are
ready; the button reopens the panel with them.
:::

:::note What happens to each candidate you accept
Each ticked candidate is saved on its own. The panel closes only when every
one was saved. If the server refuses one — for example because another
alignment already uses the same label — the panel stays open with the reason
on that row, with its label editable. A refusal about the request itself,
such as a label already in use, would come back the same, so that row is
sent again only once you change its label; a failure that may pass next time,
such as a lost connection, is retried as it is. **Retry** sends only those.
You can also align the pair by hand with the form above. A candidate
whose two columns are already aligned, queued for review or rejected earlier
says so and cannot be ticked.
:::

## From the API

```http
POST /api/operators/join-discovery/invoke
{
  "operation": "discover",
  "parameters": {
    "datasetIdA": "<uuid>",
    "datasetIdB": "<uuid>",
    "threshold": "0.2",
    "method": "exact",
    "strictness": "0.25"
  }
}
```

| Parameter | Required | Default | Meaning |
| --- | --- | --- | --- |
| `datasetIdA` | Yes | | One side |
| `datasetIdB` | Yes | | The other side |
| `threshold` | No | `0.0` | Minimum score to return |
| `method` | No | `exact` | `exact` or `ml` |
| `strictness` | No | `0.25` | Exact-match strictness, `0.0`–`1.0`. Only affects `exact` |

`method` has exactly two values: `ml` (a gradient-boosting model) and
`exact`. `strictness` is ignored for `ml` — the UI disables the slider there.
The default threshold is `0.0`, but a candidate scoring exactly zero is
dropped regardless.

The result says what was compared, not only what was found:

| Field | Meaning |
| --- | --- |
| `candidates` | JSON list, highest score first. Each carries `evidence`: `valueOverlap` (the share of the two columns' distinct values in common — the Jaccard — estimated from their hash samples; `null` when a side has no sample), `containment` (the share of the *smaller* column's distinct values found in the other, `containmentOf` = `A` or `B` naming it — how a foreign key reads; an estimate, `null` with `valueOverlap`), `cardinalityRatio`, `distinctA`/`distinctB`, `uniquenessA`/`uniquenessB`, `nullFractionA`/`nullFractionB`, `typeA`/`typeB` |
| `pairsScored` | Column pairs the scoring service ranked |
| `unsampledPairs` | `exact` only: of those, the pairs ranked **without** a measured overlap, because a column has no value sample (profiled before samples existed). `exact` scores them 0, so they can never be candidates — they were not compared, not rejected |
| `profiledColumnsA` / `profiledColumnsB` | Measured columns on each side. `0` means there was nothing to compare: profile that dataset |
| `datasetNameA` / `datasetNameB` | The two datasets' names |
| `degraded`, `reason`, `failedColumns` | Present **only** when some columns could not be scored — the scoring service was down, timed out or refused. The candidates are then a floor, not the answer |

An empty `candidates` with no `degraded` key and `pairsScored` above zero is
a real answer — no pair reached the threshold — for the pairs that were
measured, `pairsScored` minus `unsampledPairs`. When every pair was
unsampled, nothing was compared at all.

`exact` multiplies two estimates, each passed through a truncated-normal
prior that `strictness` shifts: a multiset Jaccard (MJ) and the ratio of the
two columns' distinct counts. MJ is estimated from a **hash sample** each
profile keeps of its column's distinct values: the 64 values whose hashes
are smallest. A value two columns share has one hash, so two columns of one
domain draw their samples alike. A column with 64 distinct values or fewer
keeps all of them; when both columns of a pair do, their MJ is exact,
otherwise it is the estimate. On the formula's own scale two
identical value sets give MJ = 0.5, so an `exact` score **tops out near
0.5** at the default strictness (0.25), about 0.62 at strictness 0, and
lower as strictness rises — never near 1. Compare `exact` scores with each
other, and pick an `exact` threshold on that scale: `0.5` returns nothing.

A dataset profiled before Aran kept this sample has none. Its columns with
ten distinct values or fewer are still scored, from their common values,
which for them are the whole value set. Any other pair that involves such a
column is left unscored and does not appear. To score it, re-run profiling
on that dataset.

This operation is **not** long-running, so `/invoke` is right. But the
FREYJA round-trip still takes real time on wide datasets.

### The response

`data.candidates` is a **JSON-encoded string** — parse it — holding an
array sorted by descending score:

```json
{
  "success": true,
  "data": {
    "candidates": "[{\"iriA\":\"http://aran/source/5f2c…/acm/author_id\",\"iriB\":\"http://aran/source/9ab1…/researchers/researcher_id\",\"columnA\":\"author_id\",\"columnB\":\"researcher_id\",\"score\":0.49,\"label\":\"join_author_id_researcher_id\"}]"
  }
}
```

The generated `label` is always `join_<columnA>_<columnB>`.

Failures arrive as HTTP 422 with an `errorCode`: `MISSING_PARAMETER`
(*datasetIdA and datasetIdB are required*), `INVALID_PARAMETER` (not valid
UUIDs), `DATASET_NOT_FOUND`, or `UNSUPPORTED_OPERATION`.

## Reading the results

With `exact`, a pair scores highly when the two columns share most of their
distinct values and their distinct counts are close. That is evidence of a
shared value domain, whichever way round a foreign key points. **A high
score is evidence, not proof**, and on large columns the overlap is
estimated from a sample. Two unrelated columns with the same values, both
numbered 1–1000, cannot be told apart by value and score like a key. A key
numbered 1–300 against one numbered 1–1000 scores low, because it covers
under a third of the larger domain. A partial overlap is normal when one
source covers a subset. Aran does not use names: `author_id` ↔
`researcher_id` ranks top in the walkthrough (0.49: every ACM author id is
a researcher id) because the ids agree, not because the names look
similar.

The panel colours a score by what its method can reach. For `exact`, green
means two columns of equal cardinality share at least 80% of their distinct
values at the default strictness (a score of 0.29 or more), and violet at
least 60% (0.14 or more). For `ml`, green is 0.8 or more and violet 0.6 or
more.

Lower `threshold` to see more candidates, at the cost of noise.

## Turn a candidate into a join

Accepting a candidate always creates a **`join`** mapping — never a
`datatype` one — carrying the generated label. The OQL relationship name
is that label plus the suffix `_join`. Accepting the
`author_id ↔ researcher_id` candidate above posts

```json
{ "kind": "join",
  "sourceIri": "http://aran/source/5f2c…/acm/author_id",
  "targetIri": "http://aran/source/9ab1…/researchers/researcher_id",
  "label": "join_author_id_researcher_id" }
```

and, once compiled and promoted, is traversed as:

```sql
SELECT Paper.title, Int_researchers.name
FROM Paper -[join_author_id_researcher_id_join]-> Int_researchers
```

`Int_researchers` is the integrated class Aran creates automatically for a
class reached only through a join — see
[Align and integrate schemas](./align-and-integrate-schemas.md).

Then **Rebuild draft schema (N sources)** and **Promote v*N* (tenant-wide)…**.
Nothing is queryable before that.

## Suggested alignments are a different thing

`schema-integration`'s `suggest-alignments` compares **property similarity**
(names, types), not data overlap. It is a different operator, answering a
different question:

| | Join discovery | Suggest alignments |
| --- | --- | --- |
| Operator | `join-discovery` | `schema-integration` |
| Evidence | Value-domain overlap | Property similarity |
| Good at | Finding keys | Finding same-meaning fields |
| Needs profiles | Yes | No |

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| "Nothing to compare: … has no measured column profile" | No `SNAPSHOT` profiles on that side (`profiledColumns` = 0) — unprofiled, federated (no snapshot to profile), or wrapper-output evidence only. Profile it from its Profile tab. |
| "Discovery could not score these sources" | The scoring service (FREYJA) was unreachable or refused (`degraded`, with its `reason`). Nothing was compared for the failed columns — this is not a "no match". Check `GET /actuator/health` or Admin › System, then Retry. |
| "No column pair of A × B shares values (N pairs compared)" | A real answer at threshold 0: every measured pair scored zero. |
| "Not measured: none of the N column pairs … could be compared" | Every pair lacked a value sample (`unsampledPairs` = `pairsScored`): the datasets were profiled before value samples existed. Re-run profiling on both, then discovery. A partial shortfall is said beside the result: "K of N pairs could not be compared". |
| Candidates look nonsensical | Both columns are dense small integers. Raise `threshold`. |
| Accepted candidates did not appear | A save failed (the panel closes anyway) or the mapping already existed and was skipped. Check the mapping count. |
| The join will not traverse in OQL | The schema has not been rebuilt and promoted, or the relationship name is missing its `_join` suffix. |

## Related

- [Bootstrap and profile a dataset](./bootstrap-and-profile-a-dataset.md) — the prerequisite.
- [Core concepts](../overview/core-concepts.md#profiles) — what a profile holds.

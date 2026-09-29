---
title: 'Add a source without rebuilding the schema'
sidebar_label: 'Integrate incrementally'
sidebar_position: 3.5
description: 'Add one enrolled source to an already-promoted schema without re-reading the others: when it is allowed, what it checks, what it leaves alone, and how to recover when it is refused.'
# Diátaxis mode: how-to
---

An incremental add builds the next draft schema from the active schema plus one
new source. A full rebuild reads every enrolled source again. Use the
incremental add when the model already has a promoted schema and you are
growing it one source at a time, especially when the other sources are large or
their wrappers are slow to check.

For the first integration, or after anything that changes material already
integrated, use **Rebuild draft schema** as described in
[Align and integrate schemas](./align-and-integrate-schemas.md).

## Choose incremental or full

| Situation | Use |
| --- | --- |
| The model has no promoted schema yet | Full rebuild. |
| One newly enrolled source, alignments for it already accepted | Incremental add. |
| You removed a source, deleted a dataset the model used, or rejected or deleted a mapping that was already applied | Full rebuild. Aran flags the model and refuses incremental adds. |
| You saved a wrapper on the Wrapper tab after the first promote, for any source | Full rebuild. Same flag. |
| A drift repair rewrote mappings | Full rebuild. Same flag. |
| A rollback or another project's promote replaced the active schema | Full rebuild, then promote. |
| You want Optimizable badges, and Opaque badges whose check could not run, re-checked | Full rebuild. |

## Before you start

- Bootstrap the new dataset, so it has a source graph, and enrol it in the
  model. See [Write wrapper SQL](./write-wrapper-sql.md#enrol-the-dataset-first)
  for enrolment.
- Keep the generated wrapper. Once the model has a promoted schema, saving a
  wrapper on the Wrapper tab sets the rebuild flag, so a new source that needs a
  custom wrapper takes a full rebuild instead.
- Accept the alignments that connect the new source to the existing classes.
  The add applies accepted alignments that no earlier draft has applied yet,
  plus any it deferred last time.
- Have a promoted schema that this model produced, and that is still the active
  one.

## Add the source in the UI

1. Open **Alignments** and select the project.
2. In the compile panel, under **Add source (incremental)**, choose the
   dataset from **Select an enrolled source…**. Only enrolled sources that are
   not yet in the active schema are listed.
3. Click **Add without re-reading the others**.
4. Wait for the job to finish. Its stages are shown as it runs.
5. Inspect the draft, then promote it as you would after a rebuild.

The picker and button stay disabled, with a line of explanation beneath them,
while the model has no baseline, needs a rebuild, or has a baseline that is no
longer the active schema. **Rebuild draft schema** stays available whenever the
model has an enrolled source and no integration job is running.

## Add the source over the API

Driving this needs a `FULL` token. The project route takes only the dataset id
and works out the base version, the pending alignments and the wrapper
overrides itself:

```http
POST /api/projects/{projectId}/model/incremental-integrate
{ "datasetId": "…" }
```

It answers `202` with `{ "jobId": "…" }`. Poll `GET /api/jobs/{jobId}` until the
status is `SUCCEEDED` or `FAILED`, as described in
[Invoke a capability operator](./invoke-a-capability-operator.md). A succeeded job
carries its output in `result`. A failed one carries `errorCode` and
`errorMessage`, which the troubleshooting table below explains.

The lower-level operator route takes every input explicitly:

```http
POST /api/operators/schema-integration/submit
{
  "operation": "incremental-integrate",
  "parameters": {
    "modelId": "…",
    "baseVersionId": "…",
    "newDatasetId": "…",
    "newMappings": "[]"
  }
}
```

`deferredMappings` defaults to `"[]"` and `classWrappers` to `"{}"`. This route
runs the operator alone. It does not record an integration run against the
model, which the project route does, so prefer the project route unless you are
testing the operator.

## What the add reads and checks

The operator reads two things: the active schema graph and the new dataset's
source graph. It never reads the other datasets' source graphs, and it does not
re-run their wrappers. Before integrating it checks that:

- the base version is still the tenant's active version;
- the active graph carries this model's marker, so a base built by a different
  model is refused;
- the new dataset has a bootstrapped source graph;
- none of the new source's classes already appear in the active schema.

The project route adds checks of its own before it calls the operator: the model
is not flagged for rebuild, it has a promoted baseline, and that baseline is the
active version. It also refuses with `WRAPPER_STALE` when an edited wrapper on the
new source names a column the dataset has since lost. All of these run before
anything is written, so a refused add leaves any earlier draft in place.

The add does not re-check anything about the sources already integrated: not
their shape, not their profiles, not their wrapper verdicts.

## Wrapper badges after an add

A full rebuild re-checks every Optimizable, never-checked and retryable-Opaque
wrapper before it writes the draft. An incremental add does not. It sends only
the new source's wrapper text, together with the verdict already stored for it,
and the draft carries that stored verdict as it stands. The other classes keep
the badges the active schema already had.

A badge can therefore be out of date after a run of incremental adds. It never
produces a wrong row, because the planner re-checks at plan time. Run
**Rebuild draft schema** to refresh every badge. See
[Optimizable and opaque wrappers](../query-engine/wrapper-optimisation.md) for
the full table of events.

## What you get

The job produces a `DRAFT` schema version numbered after the active one. The
job's `result` carries these fields, each as a string (the id lists are JSON
arrays inside the string):

| Field | Meaning |
| --- | --- |
| `draftVersionId`, `draftVersionNumber` | The new draft. |
| `tripleCount` | Size of the draft graph. |
| `appliedMappingIds` | Alignments this add applied. |
| `deferredMappingIds` | Alignments that could not apply yet, retried at the next add. |
| `unusedAlignments` | Alignments that matched nothing in the graph. |
| `turtle` | The draft graph serialised. |
| `integrationRunId` | The run recorded for this add (project route only). |

Nothing changes for queries until you promote. A newer draft replaces an
earlier unpromoted one: both branch from the same active schema, so the earlier
draft is abandoned and its alignments return to pending. Promoting is the same
step as after a rebuild, and it makes the draft the new baseline that the next
incremental add builds on.

## Troubleshooting

| Error code | Cause and fix |
| --- | --- |
| `REBUILD_REQUIRED` | Integrated material changed since the last promote. Run a full rebuild and promote it. |
| `NO_BASELINE` | This model has never promoted a draft. Rebuild, then promote. |
| `STALE_INCREMENTAL_BASE` | The model's baseline is no longer the active schema, or the active schema moved during the run. Rebuild and promote. |
| `INCREMENTAL_BASE_MODEL_MISMATCH` | The active graph belongs to a different integration model. Rebuild from this project, or make the change in the project that owns the graph. |
| `SOURCE_GRAPH_MISSING` | The dataset has no bootstrapped schema. Bootstrap it first. |
| `SOURCE_ALREADY_INTEGRATED` | The source is already in the active schema. Pick another, or rebuild to pick up its changes. |
| `WRAPPER_STALE` | The new source's edited wrapper names columns the source no longer has. The message lists them and the current columns. Edit the wrapper on the Wrapper tab, which sets the rebuild flag, then rebuild. |
| `INVALID_ALIGNMENT` | An accepted alignment does not validate against the graph: an endpoint is not a class or property, a property has no domain or range, or its label would mint an integrated IRI that another alignment already owns. The message names the alignment. Fix it, give it a distinct label, or reject it, then retry. |
| `INCREMENTAL_FAILED` | The operator failed for another reason. Read `errorMessage`. |

The source picker is empty when every enrolled source is already in the active
schema.

## Related

- [Align and integrate schemas](./align-and-integrate-schemas.md): alignments, full rebuild and promote.
- [Optimizable and opaque wrappers](../query-engine/wrapper-optimisation.md): when a wrapper verdict is re-checked.
- [Bootstrapping and the schema graph](../deep-dives/bootstrapping-and-the-schema-graph.mdx): the vocabulary the operator reads and writes.

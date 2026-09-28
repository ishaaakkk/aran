---
title: 'Align and integrate schemas across sources'
sidebar_label: 'Align and integrate schemas'
sidebar_position: 3
description: 'Map source properties onto global properties, group them into integrated classes, and promote the result.'
# Diátaxis mode: how-to
---

## Before you start

Enrol every source you want to integrate in the model first. Use the Wrapper
tab's *Enroll in model* button — see
[Write wrapper SQL](./write-wrapper-sql.md). An un-enrolled dataset does not
appear in the alignment pickers.

Open **Alignments** and select the project.

Driving this over the API needs a **`FULL`** token — none of these routes
is on the READ allowlist.

## How the alignment surface works

The page has two source selectors. Each one has a schema graph beneath it.
**You pick properties by clicking nodes in the graph**, not from a dropdown.
The selected node on each side becomes one half of the alignment. Once both
sides have a node, the kind and label controls become enabled.

Expect two behaviours:

- **Join discovery waits until you ask.** Choosing two sources does not run
  it. Click **Auto-Infer Join Alignments** when you want candidates.
- **A source cannot align with itself.** Choosing a dataset on one side
  disables it on the other. To switch both sides, clear them first.

:::note Two nodes can look identical
A node label is the property IRI's last segment. A JSON source with a
top-level `title` and a nested `conference.title` shows two nodes. Both are
labelled `title`, and nothing tells them apart until you click one. Click a
node and read its IRI in the selected-node panel — the nested property has an
extra path segment.
:::

## Add an alignment

1. Click the property on the **left** graph.
2. Click the property on the **right** graph.
3. Choose the **kind**.
4. Type the **label**. A join needs two more controls: the
   **relationship**, and the **direction** — `A → B (Left to Right)` or
   `B → A (Right to Left)`.
5. Click **Add**. The button stays disabled until both nodes are selected
   and the label is non-blank.
6. Confirm a new row appears in the mapping list.

The direction is not cosmetic: it decides which class is the traversal's
domain and which its range, and which column is the join's left and right
key.

### The four kinds

| Kind | Use it for | The label becomes |
| --- | --- | --- |
| `class` | Declaring two source classes are the same thing | **The integrated class's name** |
| `datatype` | Two properties holding the same value | The global property's name |
| `object` | A reference from one class to another | The property's name |
| `join` | A key relationship you will traverse in OQL | The relationship name in `-[…]->` |

**The class alignment's label is the name you write in OQL.** Aran does not
derive it from any dataset name.

If a class is reached *only* through a join alignment, Aran creates an
integrated class for it automatically, named `Int_<sourceClassLocalName>`,
with any character a query cannot write bare — a `.` or a `-` — written as
`_`.
For example, aligning `author_id` and `researcher_id` as a join gives you
something like `Int_researchers` — not the label you chose. Check the real
names in the workbench catalog. Do not assume.

### Mapping status

A mapping carries a status: `SUGGESTED`, `ACCEPTED`, or `REJECTED`. **Only
`ACCEPTED` mappings count** toward the compiled schema. Mappings you add by
hand are accepted; change a status over
`PATCH /api/projects/{id}/model/mappings/{mappingId}`.

### The packed label, if you call the API directly

The UI packs a join's three fields into one string before sending:
`label|relationship|direction`, where direction is `forward` or
`backward`. Anyone POSTing `/api/projects/{id}/model/mappings` must build
that string themselves:

```json
{ "kind": "join",
  "sourceIri": "…author_id", "targetIri": "…researcher_id",
  "label": "authored|hasAuthor|forward" }
```

A label with no `|` still works — the relationship then defaults to
`<label>_join`. That default is what accepted
[join-discovery](./run-join-discovery.md) candidates get.

## Union two sources into one class

1. Align the two source **classes** with kind `class` and the same label.
2. Align each pair of matching properties with kind `datatype`.

Rows from both sources then come back under that one class.

A property that only one source has needs no alignment. It appears in the
integrated view, and it is null for rows from the other source.

## Define a join

Align the two key properties with kind `join`. This needs a **relationship**
name and a **direction** as well as a label. The relationship is what you
traverse:

```
FROM IPaper -[hasAuthor]-> IResearcher
```

The join property records both class IRIs and both key columns. The planner
uses them directly. You never write the join condition in OQL.

## Compile and promote

1. **Rebuild draft schema (N sources)** — compiles every enrolled source
   into a `DRAFT` schema version. This can take a minute or two.
2. **Promote v*N* (tenant-wide)…** — makes the draft the tenant's active
   schema, after a confirmation that lists the classes it adds, removes and
   changes against the current one. Aran takes you to **Versions**, where the
   schema shows as `ACTIVE`.

The active schema is one per tenant, shared by every project. A promote that
would remove classes another project's integration model put there is
refused. The refusal names the classes, and the project that owns them when
you are a member of it. Keep those classes in your draft by enrolling their
sources in your project too, or make the change from the project that owns
them.

Once two projects have promoted the same class, it belongs to both, and one
of them dropping it is refused as well. It leaves the active schema once the
other project has compiled a draft without it: its newest compile has already
dropped it, so your promote may then remove it.

Only members of a project can promote that project's draft, including from
**Versions**. A rollback on **Versions** is not refused this way: it asks you
to type the version number and lists every class it removes, whichever
project put it there.

A compiled draft that has not been promoted is still offered after a reload,
and a draft promoted from **Versions** counts as the model's own promote, so
the next **Add without re-reading the others** works.

Nothing you declare affects a query until you do this. Compiling also writes
each model source's wrapper SQL over the source class's `nx:wrapperSql`.

## Add a source to an already-promoted schema

Use the incremental picker rather than re-integrating everything.

1. Select the new dataset.
2. Click **Add without re-reading the others**.

Already-integrated sources are not offered.

An incremental add reads only the new source. It does not re-check the
wrapper badges of the sources already in the model — a full rebuild does.
See [Optimizable and opaque
wrappers](../query-engine/wrapper-optimisation.md).

Over the API:

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

The operation also accepts `deferredMappings` (default `"[]"`) and
`classWrappers` (default `"{}"`). Aran checks `baseVersionId` against the
graph's owning model, so an incremental step cannot build on a base that
belongs to a different model.

## Let Aran suggest alignments

Two routes reach the same engine:

```http
POST /api/operators/schema-integration/invoke
{
  "operation": "suggest-alignments",
  "parameters": { "datasetIdA": "…", "datasetIdB": "…", "threshold": "0.3" }
}
```

`0.3` is the manifest default. The UI and the Python SDK use the project
route instead — `POST /api/projects/{id}/model/suggest` with body
`{"leftDatasetId": "…", "rightDatasetId": "…"}` — which takes no
threshold and different field names.

These suggestions are based on property similarity. For join candidates based
on **data overlap** instead, see
[Run join discovery](./run-join-discovery.md) — a different operator that
answers a different question.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| A dataset is missing from a picker | Not enrolled, or selected on the other side (which disables it). |
| The Add button is disabled | Both nodes are not selected yet, or the label is blank. This is the common case; a duplicate alignment is the rarer, silent one. |
| The OQL class name is not my label | It was reached only through a join, so it is `Int_<sourceClass>`. |
| Promote stays disabled | The rebuild has not finished. |
| Accepting discovery candidates looked fine but nothing changed | Each mapping is saved independently and the panel closes even on failure; duplicates are skipped. Check the mapping count went up. |
| A mapping exists but the query ignores it | Its status is not `ACCEPTED`. |

## Related

- [The two wrapper fields](../deep-dives/wrapper-fields.md) — what compiling overwrites.
- [OQL tutorial](../oql/tutorial.md) — querying the result.

---
title: 'Use the Python SDK'
sidebar_label: 'Python SDK'
sidebar_position: 3
description: 'Run OQL from a notebook or a script, get a DataFrame back, and align a new dataset into the schema without leaving Python.'
# Diátaxis mode: how-to
---

`aran-client` runs OQL against an Aran server and returns a pandas or
polars DataFrame. It is a network client only. The schema graph, tenancy and query planner all
stay on the server.

## Install

You need Python 3.9 or newer and a source checkout of Aran: the SDK is not
published to PyPI. From the root of the checkout, run:

```bash
pip install -e "./aran-client[pandas]"     # or [polars], or both
```

`pyarrow` is a required dependency: it turns the parquet Aran streams into a
DataFrame. The extras are `pandas`, `polars`, `notebook` (the review widget)
and `dev`.

## Create a token

1. Open your avatar at the bottom of the left rail and choose **API Tokens**.
2. Create a token. `READ` scope is enough to query and export. The
   schema-writing calls further down (`register_dataframe`,
   `propose_mapping`, `confirm_mapping` and `profile_dataset`) need a
   `FULL` token, because they POST to routes outside the READ allowlist.
   `propose_mapping` needs FULL even though it changes nothing.
   See [Connect your tools](./index.md#token-scopes-the-split-is-by-route-not-by-meaning).

## Connect and query

```python
import aran_client as aran

aran.connect("http://localhost:8081", token="aran_pat_…")
df = aran.query("SELECT Paper.title, Paper.year FROM Paper")
```

The app's default port is 8081; the standalone compose publishes host
port 8080. `aran.connect()` also reads `ARAN_URL` and `ARAN_TOKEN` from
the environment, so you can set both and call `aran.connect()` with no
arguments. That keeps the token out of the notebook cell.

| Call | Returns |
| --- | --- |
| `aran.connect(url, token)` | A `Connection`, also stored as the default. |
| `aran.connect(..., set_default=False)` | A second connection, without clobbering the default. |
| `aran.query(oql, into="pandas")` | A DataFrame, from the default connection. |
| `conn.query(oql, into=…)` | `"pandas"` (default), `"polars"`, or `"arrow"`. |
| `conn.execute(oql, format=…)` | A `QueryResult`. `format` is `"parquet"` (default) or `"csv"`. |

`QueryResult` carries `.row_count`, `.format`, `.raw`, `.arrow()`,
`.to_pandas()`, `.to_polars()` and `.into(frame)`. Use `execute()` when you
need the row count but not the data: it reads the count from the
`X-Aran-Row-Count` response header without decoding the body.

A query here has no row cap. It runs the same path a data product runs, with
the same planner and constraints, so the workbench's 1,000-row limit does not
apply.

`connect()` takes no `tenant` parameter. Aran resolves the tenant from the
token's owner and enforces it on every request.

:::warning Alias your projections, then check `df.columns`
An unquoted `Paper.title` projection does not reliably come back labelled
`title`. Case-folding is the parser's call, and a column can arrive keyed by
its full property IRI, for example `df["http://aran/Paper#year"]`.
Alias explicitly (`SELECT Paper.title AS title …`) and check `df.columns`
before indexing by name.
:::

### The `Connection` object

```python
conn = aran.Connection(base_url, token,
                       timeout=300.0, verify=True,
                       allow_insecure=False, client=None)
```

- `verify` takes anything httpx accepts: `False`, or a CA-bundle path.
- `client` injects your own `httpx.Client`.
- `Connection` is a context manager and has `.close()`.
- `conn.token_prefix` is the safe-to-print head of the token
  (`aran_pat_` plus six characters).
- `aran.default_connection()` returns the module default, or raises
  `AranConfigurationError` telling you to call `aran.connect` first.

For an endpoint the SDK has no wrapper for, use `conn.get_json(path)`,
`conn.post_json(path, body)` or `conn.post_multipart(path, fields=…, files=…)`. All three decode RFC 7807
problem details into the exceptions below.

### Errors

Every error inherits `AranError`. The HTTP family inherits
`AranHttpError`, which carries `.status_code`, `.detail` and `.problem`
(the full problem-detail body). Catch it when you want one handler for
everything the server refused.

- `AranQueryError` (400) carries the query engine's own message, so a bad
  column name reads the same here as in the workbench.
- `AranAuthError` (401 or 403) means the token is at fault, not the query.
- `AranServerError` is any 5xx, and any other error status the client has
  no more specific class for (a 404, 409 or 429, for example).
- `AranConfigurationError` is a client-side misconfiguration, raised before
  any request is sent.

A deployment can also refuse a whole class of credential. Check the active
policy directly:

```python
conn.auth_policy()   # {"methods": ["oidc", "api-token-bearer"], "requireTls": True}
```

It returns `None` when the server has no policy endpoint. When a deployment
refuses a token because it does not accept that kind of credential, the error
says so instead of implying the token is wrong.

## Extend the schema from a DataFrame

These calls need a `FULL` token.

```python
ds = aran.register_dataframe(conn, df, project_id=…, repository_id=…, name="papers")
for c in aran.propose_mapping(conn, project_id=…, left_dataset_id=ds, right_dataset_id=other):
    print(c, c.name_score, c.value_score)      # a proposal, not a decision
aran.confirm_mapping(conn, chosen, project_id=…,
                     target="…#corrected", kind="datatype")
```

`register_dataframe` accepts a pandas or polars frame or a
`pyarrow.Table`; anything else raises `TypeError`. It uploads, bootstraps
and profiles by default, and blocks until the bootstrap and profile jobs
finish (`timeout=300.0` covers both, `poll_interval=1.0`). Pass
`profile=False` to skip profiling and accept weaker proposals: without column
statistics, only name similarity contributes to the alignment score.

`propose_mapping` calls the same alignment engine the Alignments view uses
and returns `MappingCandidate` objects with `.source`, `.target`, `.label`,
`.kind`, `.score`, `.name_score`, `.value_score` and `.metrics`. `.metrics`
holds every numeric evidence value the engine emits, so a new metric appears
without a client release. Nothing changes in the schema until you call
`confirm_mapping`.

`confirm_mapping` sends the candidate's own `kind` unless you pass one.
The valid kinds are `class`, `datatype`, `object` and `join`; a candidate
with no kind and no `kind=` argument raises `ValueError` before anything
is sent.

Two things to know. Aran uploads and runs this on the server, so there is no
in-process query mode. And a proposal compares two datasets, not one dataset
against a target class, because that is the shape the alignment engine works
with.

### Jobs and profiling, directly

```python
aran.profile_dataset(conn, dataset_id=…, snapshot_id=…)   # submits and blocks; FULL
aran.wait_for_job(conn, job_id, timeout=300.0, poll_interval=1.0)
```

`wait_for_job` polls `GET /api/jobs/{id}` until `SUCCEEDED`, `FAILED` or
`CANCELLED`, and raises on the last two with the job's `errorMessage`.

### Review proposals in a notebook widget

```python
w = aran.review_mappings(aran.propose_mapping(conn, project_id=…,
                                              left_dataset_id=ds, right_dataset_id=other))
w                                    # renders in the cell: accept, correct, or skip each row
w.accepted                           # empty until you press Confirm
w.apply(conn, project_id=…)          # writes only what you accepted
```

Install the `notebook` extra with `pip install -e "./aran-client[notebook]"`. The widget runs in
JupyterLab, VS Code and Google Colab. Colab blocks the extension mechanism an
ordinary notebook widget needs, so this widget does not use it.

Editing a proposed target is your override, and there is no separate control
to click. An unreviewed widget has accepted nothing, and `apply()` refuses to
write it, since that would write mappings nobody agreed to. `apply()` writes
each accepted proposal with the kind the engine proposed for it.

## Transport

A token is a long-lived credential, sent on every request. `connect()`
refuses to send one over plain HTTP to a remote host:

```python
aran.connect("http://aran.example.org", token=…)   # refused: AranInsecureTransportError
aran.connect("http://localhost:8081",   token=…)   # fine: nothing in between
aran.connect("https://aran.example.org", token=…)  # fine
aran.connect("http://aran.example.org", token=…, allow_insecure=True)  # explicit opt-in
```

This client-side check is a second layer. The deployment's own
`aran.security.auth.require-tls` setting is what refuses a plaintext
credential from every client, whether the SDK, JDBC or anything else.

## Related

- [OQL syntax reference](../oql/syntax-reference.md)
- [Align and integrate schemas](../how-to/align-and-integrate-schemas.md): the interface equivalent of `propose_mapping`/`confirm_mapping`.
- [Connect your tools](./index.md)

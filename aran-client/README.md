# aran-client: Aran for Python notebooks

A Python SDK that runs OQL against an Aran server and returns a pandas or polars
DataFrame.

```python
import aran_client as aran

aran.connect("http://localhost:8080", token="aran_pat_…")
df = aran.query("SELECT Paper.title, Paper.year FROM Paper")
```

`ARAN_URL` and `ARAN_TOKEN` are read from the environment, so `aran.connect()`
with no arguments works and no token has to be typed into a cell.

## Install

From the root of an Aran checkout, into the environment your notebook uses. The package
is not published to PyPI yet.

```bash
pip install -e "aran-client[pandas]"     # or [polars], or both; [notebook] adds the review widget
```

Python 3.9 or later. `pyarrow` is a hard dependency: it turns the parquet the server
streams into a DataFrame. pandas and polars are optional; pick one.

The URL is the Aran backend: `http://localhost:8080` for the standalone stack,
`http://localhost:8081` for a development one. Create the token in Aran under your
avatar → **API Tokens**; `READ` scope is enough to query.

## API

| Call | Returns |
|---|---|
| `aran.connect(url, token)` | a `Connection`, also stored as the default |
| `aran.query(oql, into="pandas")` | DataFrame from the default connection |
| `conn.query(oql, into=…)` | `"pandas"` (default), `"polars"`, `"arrow"` |
| `conn.execute(oql)` | `QueryResult`: `.row_count`, `.raw`, `.arrow()`, `.to_pandas()` |

Use `execute()` when you need the row count more than the data. It reads the
server's `X-Aran-Row-Count` header without decoding the body.

### Errors

All inherit `AranError`. `AranQueryError` (400) carries the engine's own
message, so a bad column name reads the same here as in the workbench;
`AranAuthError` (401/403) means the token, not the query.

## Behaviour

There is no row cap. Queries go through `POST /api/query/export`, which runs the same
`materialize-oql` operation a data product runs, with the same planner and the same
constraint enforcement. The workbench's 1,000-row limit does not apply.

The tenant comes from the token. `connect()` takes no `tenant` argument: the
server resolves the tenant from the token's owner and enforces it on every
request.

A `READ`-scoped token is enough to query, because `/api/query/export` is one of the
POST routes the server explicitly allows for read-only tokens.

## Server authentication policy

```python
conn.auth_policy()   # {"methods": ["oidc", "api-token-bearer"], "requireTls": True}
```

The deployment configures the accepted credentials once (`aran.security.auth.*`)
and enforces them for every client, including this SDK and JDBC. The policy is readable without authenticating, so when a
token is refused because an operator disabled bearer-token access, the error says
so instead of blaming the token:

```
AranAuthError: [401] Unauthorized — this deployment does not accept
'api-token-bearer' authentication (accepted: oidc), so this is a server policy,
not a bad token.
```

## Extending the graph from a DataFrame

```python
ds = aran.register_dataframe(conn, df, project_id=…, repository_id=…, name="papers")
for c in aran.propose_mapping(conn, project_id=…, left_dataset_id=ds, right_dataset_id=other):
    print(c, c.name_score, c.value_score)      # a proposal, not a decision
aran.confirm_mapping(conn, chosen, project_id=…, target="…#corrected")
```

`propose_mapping` only proposes. It calls the alignment engine behind the UI's Alignments
view and returns candidates with a confidence breakdown. Nothing changes until you call
`confirm_mapping`, and you can override either side of a candidate when you do.

The frame is uploaded: Aran runs server-side and has no in-process mode. Proposals are
between two datasets, not onto a target class, because that is the engine's entry
point. Profile a freshly registered frame before asking for proposals; without column
statistics, only name similarity contributes.

### Clicking through the proposals

```python
w = aran.review_mappings(aran.propose_mapping(conn, project_id=…,
                                              left_dataset_id=ds, right_dataset_id=other))
w                                    # renders in the cell: accept, correct or skip each row
w.accepted                           # empty until you press Confirm
w.apply(conn, project_id=…)          # writes only what you accepted
```

Install the `notebook` extra (`pip install -e "aran-client[notebook]"`). The widget is an anywidget, so the same code works
in JupyterLab, VS Code and Google Colab. Colab blocks the extension mechanism a
conventional ipywidget needs.

Editing a target is the override; there is no separate control. An unreviewed widget has
accepted nothing, and `apply()` refuses to run on it, so no mapping is written that nobody
agreed to.

## Transport

A token is a bearer credential with a months-long life, sent on every request, so
`connect()` refuses plain HTTP to a remote host:

```python
aran.connect("http://aran.example.org", token=…)   # AranInsecureTransportError
aran.connect("http://localhost:8080",   token=…)   # fine: nothing in between
aran.connect("https://aran.example.org", token=…)  # fine
aran.connect("http://aran.example.org", token=…, allow_insecure=True)  # explicit opt-in
```

It refuses instead of warning because warnings can be filtered, notebook frontends
swallow them, and a reader sees them after the token has already gone out.

This check applies only to this client. The authoritative control is the server's
`aran.security.auth.require-tls`, which refuses plaintext credentials from every
client, including clients with no guard of their own. `allow_insecure=True` relaxes
this client's check, never the server's.

TLS verification is on by default; `verify=` takes anything httpx accepts (a CA
bundle path, for instance) if you terminate TLS with a private CA.

## Tests

From `aran-client/` (or `make aran-client` from the repository root, which creates the same venv):

```bash
python -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/python -m pytest -q
```

They run against a stub Aran on a loopback port, not a mocked `httpx`. The
failures that matter here (the auth header, the row-count round-trip, a
problem-detail body becoming the right exception) sit in the seam a mock
would hide.

## More

The [documentation site](https://dtak-upc.github.io/aran/) has the user guides.
[CONTRIBUTING.md](../CONTRIBUTING.md) explains how to build and test changes.

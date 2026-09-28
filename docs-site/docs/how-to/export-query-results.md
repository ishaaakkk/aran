---
title: 'Export query results'
sidebar_label: 'Export query results'
sidebar_position: 8
description: 'Download the result of a query in the supported export formats.'
# Diátaxis mode: how-to
---

Query results in the workbench are capped at 1000 rows. This cap suits a
grid on screen, but not a result you want to keep. Export bypasses the cap.

## Export from the workbench

1. Run a query.
2. Use the export control.

Two scopes exist: the page you are looking at, and the whole result, as CSV
or Parquet. Both export the query that produced the result on screen, even if
you have edited the editor since. After a whole-result export the workbench
shows how many rows the file holds.

Export honours the query's own `LIMIT`. The visual builder adds one, so a
builder query exports at most that many rows: the export menu says so, and
so does the message after the download. Remove the `LIMIT` to export every
row.

## Export over the API

A `READ`-scoped token is enough here — `/api/query/export` is on the READ
allowlist.

```http
POST /api/query/export
Content-Type: application/json
Authorization: Bearer <token>

{
  "oql": "SELECT IPaper.title, IPaper.year FROM IPaper",
  "format": "CSV"
}
```

The response streams the file with a deterministic filename —
`aran-export-<YYYY-MM-DD>.csv` or `.parquet` — plus `Content-Length` and
the header **`X-Aran-Row-Count`**: the true row count, which the capped
workbench result cannot report. Read the header when you need the count
without decoding the body; the Python SDK's `QueryResult.row_count` does
exactly that.

| Format | Content type | Use it for |
| --- | --- | --- |
| `CSV` | `text/csv` | Spreadsheets, anything text-based. The default when format is blank |
| `PARQUET` | `application/vnd.apache.parquet` | Large results, and anything going back into a data tool — types survive |

Aran rejects anything else with *format must be CSV or PARQUET*.

:::note Export takes OQL, not SQL
The body field is `oql`, and Aran parses it as OQL. There is no SQL export
path. If your query needs [SQL mode](../oql/sql-fallback.md), page
through `run` with the `offset` parameter and assemble the result yourself.
:::

## Materialise instead

To keep a result in the platform instead of downloading it:

```http
POST /api/operators/query-engine/submit
{
  "operation": "materialize-oql",
  "parameters": {
    "oql": "SELECT IPaper.* FROM IPaper",
    "format": "PARQUET"
  }
}
```

This operation runs the query uncapped. It writes the full result to a file
in the lake and returns the file's snapshot id, not the bytes. The
operation is long-running, so use `/submit` — which requires a **`FULL`**
token. A READ token can only POST `/invoke`; sending it to `/submit` fails
with 403 *This API token is read-only.* With only a READ token, run
`materialize-oql` through `/invoke` and accept that the result does not
survive as a job.

Read rows back from it with:

```http
POST /api/operators/query-engine/invoke
{
  "operation": "preview-parquet",
  "parameters": { "snapshotId": "<id>", "limit": "100" }
}
```

`limit` is capped at 1000.

Materialising a query over a **federated** dataset reads the live source,
not a snapshot. The result is a point-in-time copy of the source as it was
when the query ran.

## Which one to use

| You want | Use |
| --- | --- |
| A file on your machine | `POST /api/query/export` |
| A result kept in the platform for later | `materialize-oql` |
| Just to look at more rows | `run-oql` with `offset` |

`run-oql` pages at a fixed size of 1000 rows. Its response carries
`pageSize` and a `hasMore` flag; keep advancing `offset` while `hasMore`
is true.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| *format must be CSV or PARQUET* | Anything else, including lowercase variants of other names. Case is normalised, but the set of formats is not extended. |
| The export is empty | The query returned nothing. Run it in the workbench first. |
| `NO_SCHEMA` | No integrated schema has been promoted. |
| A parse error | The body is OQL, not SQL. |
| Numbers arrive as text in CSV | CSV has no types. Use parquet. |
| HTTP 402 | The tenant's monthly egress ceiling is reached. Export checks the licence limit before running; the problem-detail body carries `limit`, `currentUsage`, and `maxAllowed`. See [Licensing modes](../deep-dives/licensing-modes.md). |
| HTTP 403 *This API token is read-only.* | A READ token on `/submit` or another non-allowlisted POST. Use a FULL token. |

## Related

- [OQL cookbook](../oql/cookbook.md) — getting the query right first.
- [SQL mode](../oql/sql-fallback.md) — paging when export cannot help.

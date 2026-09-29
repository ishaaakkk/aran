---
title: 'Export query results'
sidebar_label: 'Export query results'
sidebar_position: 8
description: 'Download the result of a query in the supported export formats.'
# Diátaxis mode: how-to
---

The workbench caps query results at 1000 rows, which suits a grid on screen
but not a result you want to keep. Export bypasses the cap.

## Export from the workbench

1. Run an OQL query.
2. Click **⤓ export** in the result toolbar.
3. Choose a scope and format:

| Menu item | What you get |
| --- | --- |
| **this page (CSV)** | The rows on screen only |
| **every row (CSV)** | The whole result, uncapped |
| **every row (Parquet)** | The whole result, uncapped, with column types kept |

Every choice exports the query that produced the result on screen, even if
you have edited the editor since. After a whole-result export the workbench
shows how many rows the file holds. The **every row** items appear only for an
OQL result: a result from [SQL mode](../oql/sql-fallback.md) offers the page
export alone.

Export honours the query's own `LIMIT`. The visual builder adds one, so a
builder query exports at most that many rows: the export menu says so, and
so does the message after the download. Remove the `LIMIT` to export every
row.

## Export over the API

A `READ`-scoped token is enough, because `/api/query/export` is on the READ
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

The response streams the file with a deterministic filename,
`aran-export-<YYYY-MM-DD>.csv` or `.parquet`, plus `Content-Length` and
the header **`X-Aran-Row-Count`**. The header holds the true
row count, which the capped workbench result cannot report. Read it when you need the count
without decoding the body; the Python SDK's `QueryResult.row_count` does
exactly that.

| Format | Content type | Use it for |
| --- | --- | --- |
| `CSV` | `text/csv` | Spreadsheets, anything text-based. The default when format is blank |
| `PARQUET` | `application/vnd.apache.parquet` | Large results, and anything going back into a data tool, since types survive |

Aran rejects anything else with `400` and *format must be CSV or PARQUET,
got: `<format>`*.

:::note Export takes OQL, not SQL
The body field is `oql`, and Aran parses it as OQL. There is no SQL export
path. If your query needs [SQL mode](../oql/sql-fallback.md), page
through `run` with the `offset` parameter and assemble the result yourself.
:::

## Keep the result in the platform instead

To keep a result inside Aran rather than download it, save the query as a
materialised [data product](./create-a-data-product.md). Its refresh runs the
query uncapped, stores the full result as a parquet snapshot in the lake, and
serves it over JDBC, MCP and the Iceberg catalog. A query over a **federated**
dataset reads the live source when the product is refreshed, so the snapshot
is a point-in-time copy of the source as it was then.

Read the first rows of a materialised product back with
`GET /api/projects/{projectId}/products/{productId}/preview?limit=100`, or
with the `query-engine` operation `preview-parquet` and the product's
`snapshotId`. Both return 100 rows by default and at most 1000.

:::note `materialize-oql` is internal
The `query-engine` operator lists a `materialize-oql` operation. It is the
step a product refresh and this page's export use internally: called directly,
it writes the result to a temporary file on the Aran server and returns that
file's path, not the rows and not a lake snapshot. Use export or a data
product instead.
:::

## Which one to use

| You want | Use |
| --- | --- |
| A file on your machine | `POST /api/query/export` |
| A result kept in the platform for later | A materialised data product |
| Just to look at more rows | `run-oql` with `offset` |

`run-oql` returns pages of at most 1000 rows; `limit` sets a smaller page
size, and the default is 1000. Its response carries `pageSize` and a
`hasMore` flag; keep advancing `offset` while `hasMore` is true.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| *format must be CSV or PARQUET* | Anything else, including lowercase variants of other names. Case is normalised, but the set of formats is not extended. |
| The export is empty | The query returned nothing. Run it in the workbench first. |
| `NO_SCHEMA` | No integrated schema has been promoted. |
| A parse error | The body is OQL, not SQL. |
| Numbers arrive as text in CSV | CSV has no types. Use parquet. |
| HTTP 402 | The tenant's monthly egress ceiling is reached. Export checks the licence limit before running; the problem-detail body carries `limit`, `currentUsage`, and `maxAllowed`. See [Licensing modes](../deep-dives/licensing-modes.md). |
| HTTP 403 *This API token is read-only.* | A READ token on a POST route outside the READ allowlist, such as an operator's `/submit`. Use a FULL token. |

## Related

- [OQL cookbook](../oql/cookbook.md): getting the query right first.
- [Create a data product](./create-a-data-product.md): keeping a result in the platform.
- [SQL mode](../oql/sql-fallback.md): paging when export cannot help.

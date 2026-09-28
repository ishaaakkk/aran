---
title: 'Dates and timestamps over JDBC'
sidebar_label: 'Dates and timestamps'
sidebar_position: 4
description: 'What a JDBC client gets back for a date, a timestamp, a time zone and a time of day, on each of the three schemas.'
# Diátaxis mode: reference
---

A JDBC client can read a date or a timestamp from Aran on all three schemas.
This page says exactly what comes back, and which temporal types Aran does
not support.

Everything below is for the Avatica remote driver, version 1.27.0. See
[Connect a JDBC client](./jdbc.md) for the connection itself.

## Dates and timestamps

| Column type | `getObject` returns | `getString` returns | Typed getter |
| --- | --- | --- | --- |
| A date | `java.sql.Date` | `2024-03-15` | `getDate` |
| A date and time | `java.sql.Timestamp` | `2024-03-15 10:30:00` | `getTimestamp` |

All three accessors work, on `graph`, on `products`, and on `sources`.

The value you read is the value that was stored. It does not move with your
client's time zone, or with the server's.

:::note Timestamp text has no fractional seconds
`getString` on a timestamp always renders whole seconds. A stored value of
`10:30:00.512` reads back as `10:30:00`. Use `getTimestamp` when you need
the value itself; the text form cannot carry sub-second precision today.
:::

## Read the type off the result set, not off `getColumns`

`DatabaseMetaData.getColumns` reports every column of the `graph` and
`sources` schemas as `VARCHAR`. That is not what a query returns. Those two
schemas do not record column types — Aran learns a column's real type when
the query runs.

`ResultSetMetaData` on an actual result is correct on every schema. A date
column reports `DATE` there, and gives you a date.

`products` is the exception: its `getColumns` types are real. Aran records a
materialised product's columns when it writes the snapshot, and a virtual
product's columns when somebody creates it.

A tool that plans its work from `getColumns` — a BI tool typically does —
therefore treats a `graph` date as text. Run the query and read its metadata
instead.

## Time zones

A source column that carries a time zone, such as PostgreSQL's
`timestamptz`, behaves differently on each schema.

- On **`sources`**, it comes back as **text**. Aran does not convert it, and
  the offset is in the string.
- On **`graph`** and **`products`**, it is a plain timestamp with no zone.
  Aran drops the zone when it reads the source's schema. Which wall-clock
  value you then see is the conversion the engine applies, and Aran does not
  define it.

Store a time zone in its own column if it matters to you.

## Time of day is not supported

A column holding a time with no date is the one temporal type Aran does not
carry end to end.

- On **`graph`** and **`products`**, such a column is never a time. Aran
  reads it as text.
- On **`sources`**, a time column from an imported table can appear as a
  time. No real client has ever read one back, so treat it as unverified
  rather than as working.

Store a time of day as text, or as a full date and time, if a client has to
read it.

## `products._products.last_refreshed`

The product registry's `last_refreshed` column is a timestamp, and a client
reads it like any other.

It is `NULL` for a [virtual
product](../deep-dives/virtual-data-products.md), as is `row_count`. That
is correct rather than missing: a virtual product recomputes on every
read, so there is no refresh to report.

Its text form is whole seconds, as above. Do not use it to compare two
refreshes within the same second.

## Known limitations

- **A timestamp's text form loses sub-second precision.** The stored value
  keeps it; `getString` does not show it.
- **`getColumns` types are text on `graph` and `sources`.** Only the result
  set knows the real type.
- **A time-of-day column is unverified**, and is text on two of the three
  schemas.
- **A time zone does not survive into `graph` or `products`.**

## Related

- [Connect a JDBC client](./jdbc.md) — connecting, and the three schemas.
- [Typed columns and cost-based plans](../query-engine/typed-columns-and-costing.md) — where an integrated column's type comes from.
- [Materialised and virtual data products](../deep-dives/virtual-data-products.md) — why `last_refreshed` is empty for some products.

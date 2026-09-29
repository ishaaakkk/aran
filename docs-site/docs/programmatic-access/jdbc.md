---
title: 'Connect a JDBC client'
sidebar_label: 'JDBC'
sidebar_position: 1
description: 'Connect DBeaver or any JDBC tool to Aran, and query both the live schema (OQL) and materialised data products (SQL) over one connection.'
# Diátaxis mode: how-to
---

Aran speaks [Avatica](https://calcite.apache.org/avatica/)'s wire protocol.
There is no Aran-specific driver to install: the client is Avatica's own
remote driver, which Calcite ships, tests and versions.

## Get the driver

Download `avatica-core` **1.27.0**. Add it to your client's driver path.

```
Driver class: org.apache.calcite.avatica.remote.Driver
```

## Create a token

1. Open your avatar at the bottom of the left rail and choose **API Tokens**.
2. Create a token. `READ` scope is enough, because the JDBC endpoint has no
   write path.

## Connect

```
jdbc:avatica:remote:url=http://<host>:8081/api/jdbc;serialization=json;authentication=BASIC;avatica_user=aran;avatica_password=<your-token>
```

| Part | Meaning |
| --- | --- |
| `url=…/api/jdbc` | The endpoint, on the app's normal port, **8081** by default. The standalone compose publishes it on host port **8080**. |
| `serialization=json` | Required. The server does not enable protobuf. |
| `authentication=BASIC` | Tells the driver to send the token as a credential. |
| `avatica_password` | Your API token (`aran_pat_…`). |
| `avatica_user` | Ignored. The token identifies you; any value works here. |

If a tool makes it easier, you can put the token in the user field instead.
Aran recognises it either way by its `aran_pat_` prefix.

:::warning Use `https://` for anything but localhost
HTTP Basic sends the token on every request, base64-encoded. That is
encoding, not encryption. Use TLS for any connection that leaves your own
machine.
:::

A deployment can refuse this credential presentation entirely. If the
connection fails with 401, check `GET /api/auth/policy` before assuming the
token is wrong (see [Connect your tools](./index.md#which-credentials-a-deployment-accepts)).

## No prepared statements

Use a plain `Statement`. `prepareStatement` fails with:

> Aran's JDBC endpoint does not support prepared statements: OQL has no
> parameter markers. Use a plain Statement.

A tool that always prepares, such as some ORMs and BI connectors, cannot run
queries here. This is the most common first-contact failure.

## Three schemas: `graph`, `products` and `sources`

There is one catalog, `aran`, with three schemas under it. They are three
different query paths, not three views of the same thing.

| | `graph` | `products` | `sources` |
| --- | --- | --- | --- |
| A table is | An integrated class of the active schema version. | A data product. | One source, under its own name. |
| You write | OQL. | Plain SQL. | Plain SQL, with your own joins. |
| Aran | Rewrites the query and plans it fresh. | Reads the product's parquet snapshot directly, or recomputes it. | Runs your SQL over the source's view, unchanged. |
| Freshness | Live, at query time. | As of the product's last refresh, or live. | Live, at query time. |

Whoever created a product chose how it is served. A **materialised** product
answers from its stored snapshot, and your SQL runs over that. A **virtual**
product has no snapshot, so Aran runs the product's own query first and then
your SQL over the result. See [Materialised and virtual
data products](../deep-dives/virtual-data-products.md).

```sql
-- graph: OQL, and a path join
SELECT Paper.title, Researcher.name FROM Paper -[author]-> Researcher

-- products: plain SQL, joins and all
SELECT r.label, s.total FROM sales s JOIN regions r ON s.region = r.code ORDER BY s.total DESC

-- sources: plain SQL over per-source tables; you write the join
SELECT a.title, r.name FROM sources.acm a JOIN sources.researchers r ON a.author_id = r.id
```

Most work belongs on `graph` or `products`, where Aran does the join for
you. Use `sources` to check what one source contributed, to debug a wrapper,
or to write a join Aran has not learned yet.

The default schema is `graph`. A statement that names `products.<table>`
or `sources.<table>` always takes that path, whatever schema the
connection is on. A statement that names two schemas is rejected: the paths
are different engines, and one statement cannot join across them.

That match is on the text, so OQL over a graph class that is itself called
`products` or `sources` would be read as the other schema. Qualify every
mention of such a class with `graph.`, as in `SELECT graph.products.price FROM
graph.products`, and the statement takes the `graph` path, with the
qualifier removed before the OQL is parsed.

`sources` shows rows as the wrapper emits them. Denial constraints filter
the `graph` tier, not this one.

### Where the table names come from

Table names are derived, so do not guess one from a product or dataset name.
Read it from `getTables` or from `products._products.product`, or open the
product on the **Data products** screen: its **Connect** block shows the table
name, this deployment's JDBC URL and the driver, each with a copy button.

- A **product's** table name is its free-text name with every character
  outside `[A-Za-z0-9_]` replaced by `_`. A name starting with a digit, or
  matching the reserved prefixes `int_`, `u_`, `src_`, `w_`, is prefixed
  `p_`. A name of only punctuation becomes `product`. A cross-project
  collision gets an 8-hex-character suffix from the product id. So a
  product called `Sales: 2024` is the table `Sales__2024`.
- A **sources** table name is derived from the source class IRI, so a
  nested JSON class carries its path: `researchers.affiliations` becomes
  `researchers_affiliations`. A name starting with a digit gets `s_`, and a
  collision gets a dataset-id suffix.

## A complete example

```java
import java.sql.*;

public class AranJdbc {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:avatica:remote:url=http://localhost:8081/api/jdbc"
                   + ";serialization=json;authentication=BASIC"
                   + ";avatica_user=aran;avatica_password=" + System.getenv("ARAN_TOKEN");

        Class.forName("org.apache.calcite.avatica.remote.Driver");

        try (Connection conn = DriverManager.getConnection(url)) {

            // Browse. Catalog is "aran"; schemas are graph / products / sources.
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet tables = meta.getTables("aran", "graph", null, null)) {
                while (tables.next()) {
                    System.out.println(tables.getString("TABLE_NAME")
                                     + "  " + tables.getString("REMARKS"));
                }
            }

            // graph: OQL over a plain Statement — prepareStatement is unsupported.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT People.name, People.joined_on FROM People")) {
                while (rs.next()) {
                    // Read the type off the ResultSet, not off getColumns():
                    // graph/sources columns are always VARCHAR in metadata.
                    System.out.println(rs.getString(1) + " " + rs.getDate(2).toLocalDate());
                }
            }

            // products: plain SQL. setSchema, NOT ";schema=products" in the URL.
            conn.setSchema("products");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM PeopleExport")) {
                while (rs.next()) System.out.println(rs.getString(2));
            }

            // The product registry, reachable from any schema because it is qualified.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT product, status, access, last_refreshed, row_count, oql "
                   + "FROM products._products")) {
                while (rs.next()) {
                    System.out.printf("%s %s %s %s%n",
                        rs.getString("product"), rs.getString("status"),
                        rs.getString("access"), rs.getTimestamp("last_refreshed"));
                }
            }

            // sources: per-source tables, and the only schema with FK metadata.
            conn.setSchema("sources");
            try (ResultSet fks = meta.getImportedKeys("aran", "sources", "acm")) {
                while (fks.next()) {
                    System.out.printf("%s.%s  <-  %s.%s   (%s)%n",
                        fks.getString("PKTABLE_NAME"), fks.getString("PKCOLUMN_NAME"),
                        fks.getString("FKTABLE_NAME"), fks.getString("FKCOLUMN_NAME"),
                        fks.getString("FK_NAME"));   // FK_NAME is the join's name
                }
            }
        }
    }
}
```

## Switch schema with `setSchema`, not the connection string

Call `Connection.setSchema("products")` after connecting, the same way you
would switch schema in any JDBC tool.

Do not put `schema=products` in the connection string. It does not reach
Aran with the real Avatica driver, which negotiates schema only through
`setSchema` and not through a free-form connection property. SQL clients such
as DBeaver call `setSchema` when you pick a schema from their browser, so
normal use is unaffected. Only the connection-string shortcut that some tools
document does not work here.

## Browse the schema

In `graph`, tables are integrated classes and their columns are always
`VARCHAR` in the metadata, because a class's real types are only known once
a query runs. In `products`, tables are typed from the product's own recorded
columns. In `sources`, tables carry the source's own column names, with the
source property IRI in each column's remarks, and their columns are also
`VARCHAR` in the metadata, for the same reason as `graph`.

A result set always reports real types, on every schema. See [Dates and
timestamps over JDBC](./temporal-types.md) before you read a temporal column.

`sources` is the only schema that reports foreign keys. Every join a person
accepted appears in `getImportedKeys`/`getExportedKeys`, so a BI tool can see
which columns join before you write the join by hand. `FK_NAME` carries the
join property's name and `PK_NAME` is empty. `getPrimaryKeys` is always empty,
because an accepted join is a hypothesis and not a uniqueness guarantee.

## The product registry: `products._products`

Every product this token can read, including ones never materialised:

```sql
SELECT * FROM products._products
```

| Column | Type | Holds |
| --- | --- | --- |
| `product` | `VARCHAR` | The table name, after normalisation |
| `status` | `VARCHAR` | Product status |
| `access` | `VARCHAR` | `MATERIALIZED` or `VIRTUAL` |
| `last_refreshed` | `TIMESTAMP` | Last materialisation; `NULL` for virtual |
| `row_count` | `BIGINT` | Rows at last refresh; `NULL` for virtual |
| `project_id` | `VARCHAR` | Owning project |
| `oql` | `VARCHAR` | The product's defining query |

`last_refreshed` and `row_count` are both `NULL` for a virtual product,
which recomputes on every read and so has nothing that can be stale.

## Operational notes

- Statements are stateful per node. An executed statement holds an open
  cursor over a server-side temp file, so a fetch must reach the instance
  that ran the statement. Behind a load balancer, use sticky sessions.
- Aran evicts an abandoned statement after 15 minutes without a fetch, so
  fetch or close within that window.
- The rate limit is 300 requests per minute per token, plus a burst of 50.
  Over it, the endpoint answers 429 with `Retry-After`. A BI tool's metadata
  discovery can hit this.
- Every statement is checked against the tenant's egress ceiling before it
  runs, and can fail with a licence-limit error (HTTP 402 on the wire). See
  [Licensing modes](../deep-dives/licensing-modes.md).
- The connection is read-only. Batch writes throw
  *Aran's JDBC endpoint is read-only: it runs OQL queries…*, and
  `commit`/`rollback` do nothing.

## Related

- [Create and materialise a data product](../how-to/create-a-data-product.md)
- [Materialised and virtual data products](../deep-dives/virtual-data-products.md): the two ways a product is served.
- [Dates and timestamps over JDBC](./temporal-types.md): reading a temporal column from a client.
- [OQL syntax reference](../oql/syntax-reference.md)
- [Connect your tools](./index.md)

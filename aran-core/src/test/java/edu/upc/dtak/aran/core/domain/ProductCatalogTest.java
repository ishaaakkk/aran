package edu.upc.dtak.aran.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The naming contract behind the JDBC {@code products} schema.
 *
 * <p>Two modules read it — the JDBC metadata names the tables, the query engine creates the views
 * a client's SQL then reads — so what is asserted here is the thing that keeps a name in a schema
 * browser executable.
 */
class ProductCatalogTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static DataProduct product(String name, Instant createdAt) {
        return DataProduct.draft(UUID.randomUUID(), TENANT, UUID.randomUUID(), name, null,
                "SELECT Paper.title FROM Paper", UUID.randomUUID(), null, createdAt);
    }

    private static DataProduct materialized(String name, Instant createdAt) {
        return product(name, createdAt).materialized("snap-" + name, 3L, null, createdAt);
    }

    /** A product with a fixed id, so a collision's suffix is predictable. */
    private static DataProduct withId(String name, UUID id, Instant createdAt) {
        return new DataProduct(id, TENANT, UUID.randomUUID(), name, null,
                "SELECT Paper.title FROM Paper", UUID.randomUUID(), ProductAccessMode.MATERIALIZED,
                DataProductStatus.READY, "snap", 1L, null, createdAt, null, null, createdAt);
    }

    @Nested
    @DisplayName("naming")
    class Naming {

        @Test
        @DisplayName("a free-text product name becomes a SQL identifier")
        void normalisesNames() {
            ProductCatalog catalog = ProductCatalog.of(List.of(
                    materialized("Papers per year (2024)", T0)));

            assertThat(catalog.tables()).extracting(ProductTable::sqlName)
                    .containsExactly("Papers_per_year__2024_");
        }

        @Test
        @DisplayName("the whole name survives — this is a title, not an IRI")
        void keepsThePrefix() {
            // Names.sqlName would answer "2024" here, having stripped everything before the last
            // colon. That is right for an IRI and wrong for a product's title.
            ProductCatalog catalog = ProductCatalog.of(List.of(materialized("Sales: 2024", T0)));

            assertThat(catalog.tables().get(0).sqlName()).isEqualTo("Sales__2024");
        }

        @Test
        @DisplayName("a name that would start with a digit is prefixed")
        void prefixesLeadingDigits() {
            ProductCatalog catalog = ProductCatalog.of(List.of(materialized("2024 sales", T0)));

            assertThat(catalog.tables().get(0).sqlName()).isEqualTo("p_2024_sales");
        }

        @Test
        @DisplayName("a name that would shadow a graph-tier view prefix is prefixed, not left to "
                + "collide")
        void prefixesGraphViewNames() {
            // A VIRTUAL product's expanded body references int_/u_/src_/w_ views unqualified in the
            // products schema, so a product named like one of them would shadow it and feed the
            // wrong rows. The p_ prefix breaks that structurally. (q_ is not guarded — no plan reads
            // a quarantine view — so it is left as-is, which this also pins.)
            ProductCatalog catalog = ProductCatalog.of(List.of(
                    materialized("int_Paper", T0),
                    materialized("u_ds_Paper", T0.plusSeconds(1)),
                    materialized("src_data", T0.plusSeconds(2)),
                    materialized("w_ds_Paper", T0.plusSeconds(3)),
                    // Not a graph prefix — "u" is only special immediately before "_", so these
                    // ordinary names must survive untouched.
                    materialized("url_stats", T0.plusSeconds(4)),
                    materialized("width", T0.plusSeconds(5)),
                    materialized("q_rejects", T0.plusSeconds(6))));

            assertThat(catalog.tables()).extracting(ProductTable::sqlName)
                    .containsExactly("p_int_Paper", "p_u_ds_Paper", "p_src_data", "p_w_ds_Paper",
                            "url_stats", "width", "q_rejects");
        }

        @Test
        @DisplayName("a name with nothing identifier-like left in it still gets a table")
        void punctuationOnlyName() {
            ProductCatalog catalog = ProductCatalog.of(List.of(materialized("!!!", T0)));

            assertThat(catalog.tables().get(0).sqlName()).isEqualTo("product");
        }

        @Test
        @DisplayName("two products that normalise alike get distinct names, oldest keeping the base")
        void disambiguatesCollisions() {
            UUID second = UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789");
            ProductCatalog catalog = ProductCatalog.of(List.of(
                    withId("Sales 2024", second, T0.plusSeconds(60)),
                    withId("Sales/2024", UUID.randomUUID(), T0)));

            // Uniqueness is per tenant while `data_product` is unique per project, so this is a
            // collision the database itself permits.
            assertThat(catalog.tables()).extracting(ProductTable::sqlName)
                    .containsExactly("Sales_2024", "Sales_2024_abcdef01");
        }

        @Test
        @DisplayName("adding a product never renames an existing table")
        void namingIsStable() {
            DataProduct first = withId("Sales 2024", UUID.randomUUID(), T0);
            DataProduct second = withId("Sales-2024", UUID.randomUUID(), T0.plusSeconds(60));

            String before = ProductCatalog.of(List.of(first)).tables().get(0).sqlName();
            String after = ProductCatalog.of(List.of(second, first)).tables().get(0).sqlName();

            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("lookup is case-insensitive, as SQL identifiers are here")
        void lookupIgnoresCase() {
            ProductCatalog catalog = ProductCatalog.of(List.of(materialized("Sales", T0)));

            assertThat(catalog.byName("SALES")).isPresent();
            assertThat(catalog.byName("nothing")).isEmpty();
        }
    }

    @Nested
    @DisplayName("which products are tables")
    class Membership {

        @Test
        @DisplayName("only a materialized product is a table; the rest stay in the registry")
        void onlyMaterializedProductsAreTables() {
            ProductCatalog catalog = ProductCatalog.of(List.of(
                    materialized("ready", T0),
                    product("never_run", T0.plusSeconds(1)),
                    product("failed", T0.plusSeconds(2)).failed("boom")));

            assertThat(catalog.tables()).extracting(ProductTable::sqlName).containsExactly("ready");
            assertThat(catalog.all()).extracting(ProductTable::sqlName)
                    .containsExactly("ready", "never_run", "failed");
        }

        @Test
        @DisplayName("a STALE product is still a table — it keeps serving its pinned snapshot")
        void staleProductsAreStillQueryable() {
            ProductCatalog catalog = ProductCatalog.of(List.of(
                    materialized("sales", T0).withStatus(DataProductStatus.STALE)));

            assertThat(catalog.tables()).hasSize(1);
        }

        @Test
        @DisplayName("asking an unmaterialized product for its snapshot says why there is none")
        void snapshotOfAnUnmaterializedProduct() {
            ProductTable table = ProductCatalog.of(List.of(product("never_run", T0))).all().get(0);

            assertThatThrownBy(table::snapshotId)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("never been materialized");
        }
    }

    @Nested
    @DisplayName("recorded columns")
    class Columns {

        @Test
        @DisplayName("the registry's column JSON round-trips")
        void roundTrip() {
            List<ProductColumn> columns = List.of(
                    new ProductColumn("title", "VARCHAR", Types.VARCHAR),
                    new ProductColumn("year", "INTEGER", Types.INTEGER));

            assertThat(ProductColumn.fromJson(ProductColumn.toJson(columns)))
                    .isEqualTo(columns);
        }

        @Test
        @DisplayName("a product with no recorded schema is a table with no columns, not a failure")
        void missingSchemaIsEmpty() {
            // The pre-V29 case: materialized before the registry recorded output schemas. The
            // table must still list, because SELECT * reads the parquet's own header.
            ProductCatalog catalog = ProductCatalog.of(List.of(materialized("sales", T0)));

            assertThat(catalog.tables().get(0).columns()).isEmpty();
        }

        @Test
        @DisplayName("malformed column JSON degrades to no columns rather than breaking the tree")
        void malformedSchemaIsEmpty() {
            assertThat(ProductColumn.fromJson("{not json")).isEmpty();
            assertThat(ProductColumn.fromJson("[{\"typeName\":\"VARCHAR\"}]")).isEmpty();
        }
    }
}

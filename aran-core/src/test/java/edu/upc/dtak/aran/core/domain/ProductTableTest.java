package edu.upc.dtak.aran.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which doors serve a product table. The Iceberg catalog filters on {@link ProductTable#servedOverIceberg()}
 * and the products API reports it, so the products screen's Connect block names only a door that opens
 * (J6-F4) — a product built before V29 recorded columns is JDBC-queryable but has no Iceberg table.
 */
@DisplayName("ProductTable — which doors serve a product")
class ProductTableTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<ProductColumn> COLUMNS = List.of(new ProductColumn("year", "INTEGER", Types.INTEGER));

    private static DataProduct draft(ProductAccessMode mode) {
        return DataProduct.draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "papers", null,
                "SELECT Paper.year FROM Paper", UUID.randomUUID(), null, T0, mode);
    }

    @Test
    @DisplayName("a built MATERIALIZED product with recorded columns is queryable and served over Iceberg")
    void builtWithColumns() {
        DataProduct built = draft(ProductAccessMode.MATERIALIZED)
                .materialized("snap-1", 3L, ProductColumn.toJson(COLUMNS), T0);
        ProductTable table = new ProductTable("papers", built, COLUMNS);
        assertThat(table.queryable()).isTrue();
        assertThat(table.servedOverIceberg()).isTrue();
    }

    @Test
    @DisplayName("a product built before V29 recorded columns is JDBC-queryable but not an Iceberg table")
    void builtWithoutRecordedColumns() {
        DataProduct built = draft(ProductAccessMode.MATERIALIZED).materialized("snap-1", 3L, null, T0);
        ProductTable table = new ProductTable("papers", built, List.of());
        assertThat(table.queryable()).isTrue();
        assertThat(table.servedOverIceberg()).isFalse();
    }

    @Test
    @DisplayName("a never-built product is neither")
    void neverBuilt() {
        ProductTable table = new ProductTable("papers", draft(ProductAccessMode.MATERIALIZED), List.of());
        assertThat(table.queryable()).isFalse();
        assertThat(table.servedOverIceberg()).isFalse();
    }

    @Test
    @DisplayName("a VIRTUAL product is JDBC-queryable and never an Iceberg table — it has no files")
    void virtual() {
        DataProduct ready = draft(ProductAccessMode.VIRTUAL).virtualReady(ProductColumn.toJson(COLUMNS));
        ProductTable table = new ProductTable("papers", ready, COLUMNS);
        assertThat(table.queryable()).isTrue();
        assertThat(table.servedOverIceberg()).isFalse();
    }
}

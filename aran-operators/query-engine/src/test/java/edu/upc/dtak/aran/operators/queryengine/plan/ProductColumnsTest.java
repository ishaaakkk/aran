package edu.upc.dtak.aran.operators.queryengine.plan;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.ProductColumn;
import java.sql.Types;
import java.util.List;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ProductColumns}: the mapper Task 2 needed and that did not exist before it — a virtual
 * product's {@code output_schema} is derived from this and nothing else (no execution, no file).
 */
@DisplayName("ProductColumns")
class ProductColumnsTest {

    private static RelDataType rowType(RelDataTypeFactory.Builder builder) {
        return builder.build();
    }

    @Test
    @DisplayName("field name and order come straight off the row type")
    void namesAndOrderPreserved() {
        RelDataTypeFactory tf = new JavaTypeFactoryImpl();
        RelDataTypeFactory.Builder builder = tf.builder();
        builder.add("http://example.com/year", SqlTypeName.BIGINT);
        builder.add("http://example.com/title", SqlTypeName.VARCHAR);

        List<ProductColumn> columns = ProductColumns.of(rowType(builder));

        assertThat(columns).extracting(ProductColumn::name)
                .containsExactly("http://example.com/year", "http://example.com/title");
    }

    @Test
    @DisplayName("jdbcType is Calcite's own SqlTypeName.getJdbcOrdinal(), which is java.sql.Types")
    void jdbcTypeMatchesJavaSqlTypes() {
        RelDataTypeFactory tf = new JavaTypeFactoryImpl();
        RelDataTypeFactory.Builder builder = tf.builder();
        builder.add("s", SqlTypeName.VARCHAR);
        builder.add("i", SqlTypeName.BIGINT);
        builder.add("d", SqlTypeName.DOUBLE);
        builder.add("dec", SqlTypeName.DECIMAL);
        builder.add("dt", SqlTypeName.DATE);
        builder.add("ts", SqlTypeName.TIMESTAMP);
        builder.add("b", SqlTypeName.BOOLEAN);
        builder.add("int4", SqlTypeName.INTEGER);

        List<ProductColumn> columns = ProductColumns.of(rowType(builder));

        assertThat(columns).extracting(ProductColumn::jdbcType).containsExactly(
                Types.VARCHAR, Types.BIGINT, Types.DOUBLE, Types.DECIMAL,
                Types.DATE, Types.TIMESTAMP, Types.BOOLEAN, Types.INTEGER);
    }

    @Test
    @DisplayName("typeName covers the set XsdTypes actually widens columns to")
    void typeNameCoversTheWidenedSet() {
        RelDataTypeFactory tf = new JavaTypeFactoryImpl();
        RelDataTypeFactory.Builder builder = tf.builder();
        builder.add("s", SqlTypeName.VARCHAR);
        builder.add("i", SqlTypeName.BIGINT);
        builder.add("d", SqlTypeName.DOUBLE);
        builder.add("dec", SqlTypeName.DECIMAL);
        builder.add("dt", SqlTypeName.DATE);
        builder.add("ts", SqlTypeName.TIMESTAMP);
        builder.add("b", SqlTypeName.BOOLEAN);

        List<ProductColumn> columns = ProductColumns.of(rowType(builder));

        assertThat(columns).extracting(ProductColumn::typeName).containsExactly(
                "VARCHAR", "BIGINT", "DOUBLE", "DECIMAL", "DATE", "TIMESTAMP", "BOOLEAN");
    }

    @Test
    @DisplayName("an unrecognised type never fails — it degrades to VARCHAR, same rule as XsdTypes")
    void unrecognisedTypeDegradesToVarcharRatherThanThrowing() {
        RelDataTypeFactory tf = new JavaTypeFactoryImpl();
        RelDataTypeFactory.Builder builder = tf.builder();
        builder.add("x", SqlTypeName.SYMBOL);

        List<ProductColumn> columns = ProductColumns.of(rowType(builder));

        assertThat(columns).singleElement()
                .extracting(ProductColumn::typeName).isEqualTo("VARCHAR");
    }

    @Test
    @DisplayName("an empty row type yields an empty column list, not an error")
    void emptyRowTypeYieldsEmptyList() {
        RelDataTypeFactory tf = new JavaTypeFactoryImpl();
        RelDataTypeFactory.Builder builder = tf.builder();
        // No columns added — degenerate but legal.

        List<ProductColumn> columns = ProductColumns.of(builder.build());

        assertThat(columns).isEmpty();
    }
}

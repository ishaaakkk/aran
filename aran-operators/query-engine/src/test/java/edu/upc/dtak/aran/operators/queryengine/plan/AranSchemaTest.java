package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 acceptance: the Calcite schema must expose int_ tables that mirror
 * the DuckDB views the executor materialises.
 */
class AranSchemaTest {

    private static final String NS  = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";

    private static final String ARTWORKS  = NS + "integration/Artworks";
    private static final String IARTIST   = NS + "integration/IArtist";
    private static final String P_TITLE   = NS + "integration/title";
    private static final String P_NAME    = NS + "integration/name";
    private static final String P_AID     = SRC + "ds1/CMOA/artist_id";
    private static final String P_ID      = SRC + "ds2/Artist/id";
    private static final String HAS_ARTIST = NS + "integration/hasArtist";

    private MappingCatalog catalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("ds1", "SELECT 1",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"))));
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("ds2", "SELECT 1",
                        Map.of(P_ID, "id", P_NAME, "name"))));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        return new MappingCatalog(
                Map.of(ARTWORKS, artworks, IARTIST, iartist),
                List.of(join), List.of(), "v1");
    }

    @Test
    void schemaContainsBothIntegratedClassTables() {
        SchemaPlus schema = AranSchema.create(catalog());
        assertThat(schema.getTableNames()).contains("int_Artworks", "int_IArtist");
    }

    @Test
    void artworksTableHasExpectedColumnsAllVarchar() {
        SchemaPlus schema = AranSchema.create(catalog());
        Table table = schema.getTable("int_Artworks");
        assertThat(table).isNotNull();

        var typeFactory = new JavaTypeFactoryImpl();
        RelDataType rowType = table.getRowType(typeFactory);

        List<String> names = rowType.getFieldNames();
        assertThat(names).contains(P_TITLE, P_AID);
        rowType.getFieldList().forEach(f ->
                assertThat(f.getType().getSqlTypeName())
                        .as("column %s should be VARCHAR", f.getName())
                        .isEqualTo(SqlTypeName.VARCHAR));
    }

    @Test
    void iartistTableHasExpectedColumns() {
        SchemaPlus schema = AranSchema.create(catalog());
        Table table = schema.getTable("int_IArtist");
        assertThat(table).isNotNull();

        var typeFactory = new JavaTypeFactoryImpl();
        RelDataType rowType = table.getRowType(typeFactory);

        assertThat(rowType.getFieldNames()).contains(P_ID, P_NAME);
    }

    @Test
    void tableNamesMatchDuckDbExecutorSanitize() {
        // Verify the sanitize logic is consistent: int_Artworks, not int_integration_Artworks.
        assertThat(Names.sqlName(ARTWORKS)).isEqualTo("Artworks");
        assertThat(Names.sqlName(IARTIST)).isEqualTo("IArtist");
    }

    // ── XSD-typed row types (Phase 2 Task 3) ──────────────────────────────────

    private static final String XSD    = "http://www.w3.org/2001/XMLSchema#";
    private static final String TYPED  = NS + "integration/Measurement";
    private static final String P_YEAR = NS + "integration/year";
    private static final String P_LABEL = NS + "integration/label";

    private MappingCatalog typedSingleSourceCatalog() {
        var measurement = new MappingCatalog.IntegratedClass(TYPED, List.of(
                new MappingCatalog.SourceView("dsT", "SELECT 1",
                        Map.of(P_YEAR, "year", P_LABEL, "label"),
                        0L, List.of(), "", Map.of(P_YEAR, XSD + "integer"))));
        return new MappingCatalog(Map.of(TYPED, measurement), List.of(), List.of(), "v1");
    }

    @Test
    void typedSourcePropertyYieldsWidenedRowType() {
        MappingCatalog catalog = typedSingleSourceCatalog();
        SchemaPlus schema = AranSchema.create(catalog);
        var typeFactory = new JavaTypeFactoryImpl();

        Table intTable = schema.getTable("int_Measurement");
        assertThat(intTable).isNotNull();
        RelDataType rowType = intTable.getRowType(typeFactory);
        assertThat(fieldType(rowType, P_YEAR).getSqlTypeName()).isEqualTo(SqlTypeName.BIGINT);
        assertThat(fieldType(rowType, P_YEAR).isNullable()).isTrue();
        assertThat(fieldType(rowType, P_LABEL).getSqlTypeName()).isEqualTo(SqlTypeName.VARCHAR);
        assertThat(fieldType(rowType, P_LABEL).isNullable()).isTrue();

        // The union member (WrapperTable) must agree with the class: both getRowType
        // implementations fold through the same XsdTypes.widenedType(cls, iri), which is what
        // keeps every UNION ALL branch type-aligned.
        MappingCatalog.IntegratedClass measurementClass = catalog.classes().get(TYPED);
        MappingCatalog.SourceView source = measurementClass.sources().get(0);
        Table memberTable = schema.getTable(WrapperTable.viewName(source, measurementClass));
        assertThat(memberTable).isNotNull();
        assertThat(fieldType(memberTable.getRowType(typeFactory), P_YEAR).getSqlTypeName())
                .isEqualTo(SqlTypeName.BIGINT);
    }

    private MappingCatalog disagreeingSourcesCatalog() {
        String cls = NS + "integration/Reading";
        String amount = NS + "integration/amount";
        var reading = new MappingCatalog.IntegratedClass(cls, List.of(
                new MappingCatalog.SourceView("dsA", "SELECT 1", Map.of(amount, "amount"),
                        0L, List.of(), "", Map.of(amount, XSD + "integer")),
                new MappingCatalog.SourceView("dsB", "SELECT 1", Map.of(amount, "amount"),
                        0L, List.of(), "", Map.of(amount, XSD + "string"))));
        return new MappingCatalog(Map.of(cls, reading), List.of(), List.of(), "v1");
    }

    @Test
    void twoSourceTypeDisagreementFallsBackToVarcharRowType() {
        SchemaPlus schema = AranSchema.create(disagreeingSourcesCatalog());
        Table table = schema.getTable("int_Reading");
        assertThat(table).isNotNull();

        var typeFactory = new JavaTypeFactoryImpl();
        RelDataType rowType = table.getRowType(typeFactory);
        rowType.getFieldList().forEach(f ->
                assertThat(f.getType().getSqlTypeName())
                        .as("column %s should fall back to VARCHAR on source disagreement", f.getName())
                        .isEqualTo(SqlTypeName.VARCHAR));
    }

    private static RelDataType fieldType(RelDataType rowType, String fieldName) {
        return rowType.getFieldList().stream()
                .filter(f -> f.getName().equals(fieldName))
                .findFirst().orElseThrow(() -> new AssertionError("no field " + fieldName))
                .getType();
    }
}

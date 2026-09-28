package edu.upc.dtak.aran.adapter.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CubeDefinitionEntity} against the migration that creates its table (V41).
 *
 * <p>Hibernate's {@code ddl-auto: validate} compares tables and columns and <b>not</b> unique
 * constraints, so an entity whose {@link UniqueConstraint} names different
 * columns — or the same columns under a different constraint name — from the migration passes
 * startup validation and diverges silently. The consequence is not academic: the constraint is
 * what makes two cubes called {@code sales} in one project impossible, and each of them would
 * create a data product called {@code sales_fact}.
 *
 * <p>This reads the migration as text rather than executing it, so it runs with no Docker daemon.
 * That is the trade: it proves the entity and the migration <em>say</em> the same thing, not that
 * Postgres accepted either. Execution is {@code validate.sh}'s Postgres-backed run.
 */
@DisplayName("CubeDefinitionEntity — declared schema matches V41")
class CubeDefinitionSchemaAlignmentTest {

    private static final String MIGRATION = "/db/migration/V41__create_cube_definition.sql";

    /** A column line: the name, then its type. Constraint clauses are excluded by the caller. */
    private static final Pattern COLUMN = Pattern.compile("^\\s*([a-z_]+)\\s+[A-Z]");

    private static String migrationSql() {
        try (InputStream in = CubeDefinitionSchemaAlignmentTest.class
                .getResourceAsStream(MIGRATION)) {
            if (in == null) {
                throw new IllegalStateException(MIGRATION + " is not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The body of {@code CREATE TABLE cube_definition ( … );}, comments and all. */
    private static String createTableBody() {
        String sql = migrationSql();
        int open = sql.indexOf("CREATE TABLE cube_definition (");
        assertThat(open).describedAs("V41 must create cube_definition").isNotNegative();
        int close = sql.indexOf(");", open);
        return sql.substring(open + "CREATE TABLE cube_definition (".length(), close);
    }

    private static Set<String> migrationColumns() {
        Set<String> columns = new LinkedHashSet<>();
        for (String line : createTableBody().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")
                    || trimmed.toUpperCase(Locale.ROOT).startsWith("CONSTRAINT")) {
                continue;
            }
            Matcher matcher = COLUMN.matcher(trimmed);
            if (matcher.find()) {
                columns.add(matcher.group(1));
            }
        }
        return columns;
    }

    /**
     * The entity's columns. Every field either names its column explicitly or is a single
     * lowercase word, for which Spring's physical naming strategy is the identity — so no
     * camelCase-to-snake_case conversion has to be reimplemented here to read this.
     */
    private static Set<String> entityColumns() {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : CubeDefinitionEntity.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            String name = column != null && !column.name().isBlank()
                    ? column.name() : field.getName();
            assertThat(name).describedAs("field '%s' derives a column name that is not already "
                    + "snake_case — name it explicitly", field.getName()).isLowerCase();
            columns.add(name);
        }
        return columns;
    }

    @Test
    @DisplayName("the entity declares exactly the columns V41 creates — no more, no fewer")
    void columnsMatch() {
        assertThat(entityColumns()).containsExactlyInAnyOrderElementsOf(migrationColumns());
    }

    @Test
    @DisplayName("the unique constraint carries V41's name and V41's columns, in V41's order")
    void uniqueConstraintMatches() {
        Matcher declared = Pattern
                .compile("CONSTRAINT\\s+(\\w+)\\s+UNIQUE\\s*\\(([^)]*)\\)")
                .matcher(createTableBody());
        assertThat(declared.find()).describedAs("V41 must declare a named unique constraint")
                .isTrue();
        List<String> sqlColumns = new ArrayList<>();
        for (String column : declared.group(2).split(",")) {
            sqlColumns.add(column.trim());
        }

        UniqueConstraint[] constraints =
                CubeDefinitionEntity.class.getAnnotation(Table.class).uniqueConstraints();
        assertThat(constraints).hasSize(1);
        // The name matters as much as the columns: a mismatch is a constraint Flyway created and
        // Hibernate believes it declared, which nothing then keeps in step.
        assertThat(constraints[0].name()).isEqualTo(declared.group(1));
        assertThat(constraints[0].columnNames()).containsExactlyElementsOf(sqlColumns);
    }

    @Test
    @DisplayName("description and created_by are nullable — the domain leaves both unset")
    void optionalColumnsAreNullable() {
        assertThat(createTableBody()).contains("description TEXT,").contains("created_by TEXT,");
        for (String optional : List.of("description", "createdBy")) {
            assertThat(nullable(optional))
                    .describedAs("%s must be nullable", optional).isTrue();
        }
    }

    private static boolean nullable(String fieldName) {
        try {
            Column column = CubeDefinitionEntity.class.getDeclaredField(fieldName)
                    .getAnnotation(Column.class);
            return column == null || column.nullable();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("CubeDefinitionEntity has no field " + fieldName, e);
        }
    }
}

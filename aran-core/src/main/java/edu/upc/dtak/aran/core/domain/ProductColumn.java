package edu.upc.dtak.aran.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * One column of a data product's materialized output, as recorded in the registry.
 *
 * <p>Both the type name and the JDBC type code are stored, because both are read back by clients
 * that never see the parquet: {@code TYPE_NAME} is what a schema browser prints, {@code DATA_TYPE}
 * is what a driver switches on. Deriving the second from the first later would mean maintaining a
 * DuckDB-name → {@link java.sql.Types} table that the writer already had in hand.
 *
 * <p>The JSON form is the {@code data_product.output_schema} column (V29). It is defined here — in
 * core — rather than at either end, because the writer (the query engine, on materialization) and
 * the reader (the JDBC {@code products} schema) are in different modules and must not each own
 * half of a shape.
 */
@NullMarked
public record ProductColumn(String name, String typeName, int jdbcType) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ProductColumn {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
    }

    /** The registry JSON for {@code columns}: an array of {name, typeName, jdbcType}. */
    public static String toJson(List<ProductColumn> columns) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ProductColumn column : columns) {
            ObjectNode node = array.addObject();
            node.put("name", column.name());
            node.put("typeName", column.typeName());
            node.put("jdbcType", column.jdbcType());
        }
        return array.toString();
    }

    /**
     * The columns recorded in {@code json}, or an empty list when there are none to read.
     *
     * <p>Empty rather than an exception for a null, blank or malformed value: a product
     * materialized before V29 has no recorded schema, and its table must still list and still be
     * queryable — {@code SELECT *} reads the parquet's own header. A schema browser showing a
     * table with no columns is a gap the next materialization closes.
     */
    public static List<ProductColumn> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            List<ProductColumn> columns = new ArrayList<>();
            for (JsonNode node : root) {
                String name = node.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                columns.add(new ProductColumn(name,
                        node.path("typeName").asText("VARCHAR"),
                        node.path("jdbcType").asInt(java.sql.Types.VARCHAR)));
            }
            return List.copyOf(columns);
        } catch (Exception e) {
            return List.of();
        }
    }
}

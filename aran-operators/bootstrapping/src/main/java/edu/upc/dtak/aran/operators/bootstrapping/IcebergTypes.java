package edu.upc.dtak.aran.operators.bootstrapping;

import java.util.Locale;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.XSD;
import org.jspecify.annotations.NullMarked;

/**
 * Maps an Iceberg schema type (as its JSON string form: {@code "long"}, {@code "decimal(38,9)"},
 * {@code "timestamptz"}, …) to the XSD range a source property carries — the Iceberg twin of
 * {@link ParquetBootstrap#xsdRangeFor}, kept to the same target set so an Iceberg column and a
 * parquet column of the same shape type identically downstream ({@code XsdTypes.widenedType}
 * consumes both).
 *
 * <p>Nested types ({@code struct}/{@code list}/{@code map}) degrade to {@code xsd:string}
 * deliberately: DuckDB's {@code iceberg_scan} surfaces them as nested values whose only lossless
 * uniform rendering is text, and the graph tier's TRY_CAST to VARCHAR is exactly that. A 1NF
 * flattening of nested Iceberg fields is future work, not silently promised here.
 */
@NullMarked
final class IcebergTypes {

    private IcebergTypes() {
    }

    static Resource xsdRangeFor(String icebergType) {
        String t = icebergType == null ? "" : icebergType.toLowerCase(Locale.ROOT).trim();
        return switch (t) {
            case "boolean" -> XSD.xboolean;
            case "int", "long" -> XSD.integer;
            case "float", "double" -> XSD.xdouble;
            case "date" -> XSD.date;
            // Both with and without zone: xsd:dateTime is the widest the engine types to
            // (query-processing.md §6), and TIMESTAMP is what DuckDB reads either as.
            case "timestamp", "timestamptz", "timestamp_ns", "timestamptz_ns" -> XSD.dateTime;
            default -> t.startsWith("decimal") ? XSD.decimal : XSD.xstring;
            // string, uuid, time, binary, fixed(n), struct, list, map → xsd:string.
        };
    }
}

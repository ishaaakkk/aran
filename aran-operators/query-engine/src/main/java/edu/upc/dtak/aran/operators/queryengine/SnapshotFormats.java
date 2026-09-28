package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.util.Names;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Which DuckDB reader function reads a snapshot of a given format.
 *
 * <p>One place, because there were three and they had already drifted:
 * {@code SnapshotCache.transcodeToParquet} sent {@code TSV} to {@code read_csv_auto} while both
 * resolvers in {@code QueryEngineOperator} sent it to {@code read_json_auto}. A TSV snapshot was
 * therefore parsed one way when the cache filled and another way when it did not — the same
 * dataset, two schemas, decided by whether a cache entry happened to exist.
 *
 * <p>Adding a format now means editing this file and nothing else. That is the whole point; the
 * bug above is what the alternative costs.
 */
@NullMarked
public final class SnapshotFormats {

    private SnapshotFormats() {
    }

    /**
     * The DuckDB table function for {@code format}.
     *
     * <p>JSON is the fallback rather than an error on purpose: the lake records a snapshot's format
     * from the upload's content type, which is absent often enough that failing here would break
     * datasets that read perfectly well. {@code read_json_auto} is the most forgiving reader, so an
     * unrecognised format degrades to a parse attempt rather than to a hard failure.
     */
    public static String readerFor(@Nullable String format) {
        return switch (format == null ? "" : format.toUpperCase(Locale.ROOT)) {
            case "CSV", "TSV" -> "read_csv_auto";
            case "PARQUET" -> "read_parquet";
            default -> "read_json_auto";
        };
    }

    /**
     * The temp-file extension to download a snapshot of this format into.
     *
     * <p>Lives beside {@link #readerFor} because the two are one decision seen twice: a file
     * written with a {@code .json} suffix and then read with {@code read_csv_auto} is a bug, and
     * keeping them apart is how that bug gets written.
     */
    public static String fileSuffixFor(@Nullable String format) {
        return switch (format == null ? "" : format.toUpperCase(Locale.ROOT)) {
            case "CSV" -> ".csv";
            case "TSV" -> ".tsv";
            case "PARQUET" -> ".parquet";
            default -> ".json";
        };
    }

    /**
     * A complete read expression over {@code location}, with the single quotes DuckDB needs.
     *
     * @param location a filesystem path or a URI; escaped by {@link Names#sqlLiteral}
     */
    public static String readExpression(@Nullable String format, String location) {
        return readerFor(format) + "('" + Names.sqlLiteral(location) + "')";
    }
}

package edu.upc.dtak.aran.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * The one credential scrubber for text on its way out of the backend — an error a caller reads, a
 * log line, a job row.
 *
 * <p>{@code PostgresEngine} writes the repository password inside the federated scan expression
 * ({@code postgres_scan('dbname=… password=… host=…', …)}), and DuckDB's postgres scanner echoes
 * that whole DSN on the first line of a refused connection — in the query engine's DuckDB (audit
 * QUERY_EXECUTION-1) and in freyja's, whose 502 body {@code ProfilingOperator} relays. The DSN is
 * libpq {@code keyword=value}: a value is bare up to whitespace, or single-quoted with {@code \'}
 * and {@code \\} escapes, which is how {@code PostgresEngine} quotes one — and inside the scan
 * expression's SQL literal, which an error can quote back, every one of those quotes is doubled.
 * The pattern knows all three spellings on purpose: {@code password=\S+} stops at a quoted value's
 * first space and leaves the rest of the password in the text, and a single-quote reading of a
 * doubled value ends at its second character. An empty quoted value reads as the doubled
 * spelling and masks the rest of the line, which errs toward saying too little. freyja's
 * {@code _redact} applies the same pattern.
 */
@NullMarked
public final class Credentials {

    private static final Pattern PASSWORD_TOKEN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd)(\\s*=\\s*)"
                    + "(?:''(?:\\\\''|\\\\[^']|[^'\\\\])*(?:'')?" // SQL-doubled: ''p w\''d''
                    + "|'(?:\\\\.|[^'\\\\])*'?"                  // libpq-quoted: 'p w\'d'
                    + "|\\S+)");                                // bare: pw

    private Credentials() {
    }

    /** {@code text} with every {@code password=}/{@code passwd=}/{@code pwd=} value masked. */
    public static String scrub(String text) {
        return PASSWORD_TOKEN.matcher(text).replaceAll(
                m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + "***"));
    }
}

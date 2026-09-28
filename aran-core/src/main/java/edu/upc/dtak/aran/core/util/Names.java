package edu.upc.dtak.aran.core.util;

/**
 * Canonical name normalisers for the GAV pipeline. Two <em>different</em>
 * operations that historically existed as private copies in six classes:
 *
 * <ul>
 *   <li>{@link #sqlName(String)} — IRI → DuckDB view/table identifier.
 *       Strips the namespace, then replaces anything outside
 *       {@code [a-zA-Z0-9_]}. Produces the {@code int_}/{@code src_}/{@code w_}
 *       view names; the Calcite schema, the executor, and the operator MUST
 *       agree on it byte-for-byte or RelToSql emits SQL for views that don't
 *       exist.</li>
 *   <li>{@link #iriFragment(String)} — free-form label → IRI-safe fragment.
 *       Keeps dots and dashes, does NOT strip a namespace. Used when minting
 *       IRIs from dataset/column names during bootstrap and join discovery.</li>
 * </ul>
 *
 * Do not inline private copies of these regexes; change them only here.
 */
public final class Names {

    private Names() {
    }

    /** IRI → SQL identifier: strip namespace, replace non {@code [a-zA-Z0-9_]}. */
    public static String sqlName(String iri) {
        return localName(iri).replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * IRI → its local name: everything after the last {@code /}, {@code #} or {@code :}.
     *
     * <p>The namespace-stripping half of {@link #sqlName(String)}, exposed because it was
     * copy-pasted into four classes with <em>two different behaviours</em> (audit finding M-7).
     * {@code QueryEngineOperator} split on {@code [/#:]}; {@code SourceGraphSerializer},
     * {@code SourceGraphController} and {@code SchemaIntegrationOperator} used
     * {@code lastIndexOf} over {@code /} and {@code #} only. For a {@code urn:} IRI the two
     * disagree — {@code urn:aran:acm} became {@code acm} in one payload and {@code
     * urn:aran:acm} in another, for the same property.
     *
     * <p>The colon-aware behaviour wins because {@code sqlName} already had it, and view names
     * are what must not drift.
     *
     * @return the local name, or {@code ""} for a null IRI
     */
    public static String localName(String iri) {
        if (iri == null) return "";
        return iri.replaceAll("^.*[/#:]", "");
    }

    /** Label → IRI-safe fragment: replace non {@code [A-Za-z0-9._-]}, keep namespace. */
    public static String iriFragment(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Escapes a value for embedding inside a single-quoted SQL literal. Returns the inner text
     * only — the caller supplies the surrounding quotes.
     *
     * <p>Every DuckDB read path in this repo builds a literal path by hand, because
     * {@code read_parquet}/{@code read_csv_auto} take a filename as a string literal rather
     * than a bindable parameter. Before this method existed the doubling was open-coded at
     * eleven sites across four modules, with one lone named helper private to {@code
     * AranCursor} — the exact copy-paste this class's contract forbids, and the one where a
     * missed copy is a SQL injection rather than a cosmetic drift. A snapshot directory
     * containing an apostrophe is enough to reach it.
     *
     * <p>This escapes a <em>value</em>, not an identifier: {@link #sqlName(String)} is what
     * makes a table or column name safe. Doubling a quote is correct for a literal and wrong
     * for an identifier, so the two are deliberately separate methods.
     */
    public static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    /**
     * A name as a double-quoted SQL identifier, embedded quotes doubled — surrounding quotes
     * included, unlike {@link #sqlLiteral(String)}.
     *
     * <p>{@link #sqlName(String)} makes a name <em>safe</em>, not <em>free</em>: a source table
     * called {@code order} or {@code group} passes through it unchanged and is still a reserved
     * word, so {@code CREATE VIEW order} is a parse error (S7-8). Quoting is what lets such a name
     * be a view, and it changes nothing else — DuckDB matches identifiers case-insensitively
     * whether quoted or not.
     */
    public static String sqlIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}

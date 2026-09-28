package edu.upc.dtak.aran.core.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/**
 * The tenant's data products as SQL tables — the registry view behind the JDBC {@code products}
 * schema.
 *
 * <p>This is the whole of the naming contract, in one place, because two modules read it: the JDBC
 * metadata (aran-application) lists these tables, and the query engine (aran-operators) registers
 * a DuckDB view per table before running a client's SQL. A drifted second copy would produce a
 * schema browser offering a name that nothing can execute.
 *
 * <h2>Names</h2>
 *
 * <p>A product name is free text ({@code "Papers per year (2024)"}); a SQL table name is not. The
 * name is therefore normalised to {@code [A-Za-z0-9_]}, prefixed if it would otherwise start with
 * a digit, and — because {@code data_product} is unique per <em>project</em> while this catalog is
 * per <em>tenant</em> — disambiguated with a suffix from the product id when two products in
 * different projects normalise to the same thing. Order is by creation, so the mapping is stable:
 * adding a product never renames an existing table.
 *
 * <p>{@link Names#sqlName} is deliberately <em>not</em> reused: it strips everything before the
 * last {@code / # :}, which is right for an IRI and wrong for a title — {@code "Sales: 2024"}
 * would become {@code 2024}.
 *
 * <h2>Which products are tables</h2>
 *
 * <p>Only those with a materialized snapshot ({@link #tables()}). A DRAFT or FAILED product is a
 * query with no data behind it; listing it as a table would mean every schema browser shows names
 * that cannot be selected from. They stay visible in {@link #all()}, which backs the registry
 * table {@code products._products}, so the reason a name is missing is itself queryable.
 */
@NullMarked
public final class ProductCatalog {

    /** How much of the product id disambiguates a collision. Enough to be unique, short enough to type. */
    private static final int SUFFIX_LENGTH = 8;

    /**
     * The graph-tier view-name prefixes a product name must not shadow. A VIRTUAL product's
     * expanded body references these views ({@code int_}/{@code u_}/{@code src_}/{@code w_})
     * <em>unqualified</em> and is created in the {@code products} schema, which the connection
     * searches first — so a product named, say, {@code int_Paper} would bind the body's
     * {@code FROM int_Paper} to the product view instead of the graph view and feed the query the
     * wrong rows (see {@code ProductSqlExecutor.ensureGraphContext}). Prefixing such a name breaks
     * the collision structurally rather than relying on graph names being improbably long. The
     * quarantine {@code q_} views are deliberately not listed: no query plan ever references one,
     * so a {@code q_}-named product cannot shadow anything a body reads.
     */
    private static final java.util.regex.Pattern GRAPH_VIEW_PREFIX =
            java.util.regex.Pattern.compile("^(int|u|src|w)_");

    private final List<ProductTable> all;
    private final Map<String, ProductTable> byName;

    private ProductCatalog(List<ProductTable> all) {
        this.all = List.copyOf(all);
        Map<String, ProductTable> index = new LinkedHashMap<>();
        for (ProductTable table : all) {
            index.put(table.sqlName().toLowerCase(Locale.ROOT), table);
        }
        this.byName = Map.copyOf(index);
    }

    public static ProductCatalog of(Collection<DataProduct> products) {
        List<DataProduct> ordered = new ArrayList<>(products);
        ordered.sort(Comparator.comparing(DataProduct::createdAt).thenComparing(DataProduct::id));

        Map<String, DataProduct> claimed = new LinkedHashMap<>();
        List<ProductTable> tables = new ArrayList<>();
        for (DataProduct product : ordered) {
            String name = uniqueName(product, claimed);
            claimed.put(name.toLowerCase(Locale.ROOT), product);
            tables.add(new ProductTable(name, product, ProductColumn.fromJson(product.outputSchema())));
        }
        return new ProductCatalog(tables);
    }

    /** Every product, materialized or not, in creation order. */
    public List<ProductTable> all() {
        return all;
    }

    /**
     * The products a client can select from: a MATERIALIZED product with a snapshot to scan, or a
     * VIRTUAL product (Phase 5), which is selectable with no snapshot because it serves by expanding
     * its OQL at read time. See {@link ProductTable#queryable()}.
     */
    public List<ProductTable> tables() {
        return all.stream().filter(ProductTable::queryable).toList();
    }

    /** Lookup by SQL table name, case-insensitively — SQL identifiers are not case-sensitive here. */
    public Optional<ProductTable> byName(String sqlName) {
        return Optional.ofNullable(byName.get(sqlName.toLowerCase(Locale.ROOT)));
    }

    private static String uniqueName(DataProduct product, Map<String, DataProduct> claimed) {
        String base = normalise(product.name());
        if (!claimed.containsKey(base.toLowerCase(Locale.ROOT))) {
            return base;
        }
        String id = product.id().toString().replace("-", "");
        for (int length = SUFFIX_LENGTH; length <= id.length(); length++) {
            String candidate = base + "_" + id.substring(0, length);
            if (!claimed.containsKey(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not derive a unique table name for product " + product.id());
    }

    /**
     * The table name a product called {@code productName} is served under when no earlier product
     * in the tenant claims it — the name before any collision suffix. A caller that promises the
     * user the name they typed <em>is</em> the table name (a cube's star, see {@code CubeNames})
     * compares the two, and checks {@link #byName} for an earlier claimant.
     */
    public static String baseTableName(String productName) {
        return normalise(productName);
    }

    /** Free-text product name → SQL identifier. Package-visible shape; see the class comment. */
    static String normalise(String productName) {
        String cleaned = productName.trim().replaceAll("[^A-Za-z0-9_]", "_");
        // A name of only punctuation collapses to underscores, which is a legal identifier but an
        // unusable one; a name starting with a digit is not legal unquoted anywhere.
        if (cleaned.isBlank() || cleaned.chars().allMatch(c -> c == '_')) {
            return "product";
        }
        // Two names are unusable as a bare product view and take the same p_ prefix: one starting
        // with a digit (not a legal unquoted identifier), and one that would collide with a
        // graph-tier view prefix (would shadow the views a VIRTUAL product's body reads — see
        // GRAPH_VIEW_PREFIX). p_ is not itself a graph prefix, so the result never collides.
        if (Character.isDigit(cleaned.charAt(0)) || GRAPH_VIEW_PREFIX.matcher(cleaned).find()) {
            return "p_" + cleaned;
        }
        return cleaned;
    }
}

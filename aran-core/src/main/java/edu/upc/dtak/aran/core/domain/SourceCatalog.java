package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.util.Names;
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
 * The tenant's per-source wrapper views as SQL tables — the naming contract behind the
 * {@code sources} tier.
 *
 * <p>Like {@link ProductCatalog}, this exists as one class because two modules must agree on it:
 * the JDBC metadata lists these tables, and the query engine emits a DuckDB view per table. A
 * drifted second copy offers a name in the schema browser that nothing can execute.
 *
 * <h2>Why a friendly name is needed at all</h2>
 *
 * <p>The views these name are unusable by hand. A wrapper view is {@code
 * w_3fa85f64_5717_4562_b3fc_2c963f66afa6_acm} — the dataset UUID, sanitised — and its columns are
 * full source property IRIs. Handing a SQL author that is not an escape hatch, it is a dare.
 *
 * <h2>Where the name comes from</h2>
 *
 * <p>From the <em>source class IRI</em>, and nothing else. Bootstrap mints those as {@code
 * …/source/<datasetId>/<datasetName>} (see {@code BootstrapEngine.sourceClassIri}), so the dataset's
 * human name is already inside the IRI: a dataset named {@code acm} yields the table {@code acm}.
 * Nested JSON classes carry their path, so {@code researchers.affiliations} yields {@code
 * researchers_affiliations}.
 *
 * <p>{@link Names#sqlName} does the derivation, which is the point — it is the same normaliser that
 * produces the {@code w_}/{@code int_} view names, so these names cannot drift from those.
 *
 * <h2>Why this is not persisted</h2>
 *
 * <p>A stored name table was considered and rejected. Derivation from the source class IRI is
 * already stable across every event that matters: restarts, schema recompiles, promotions, and
 * <em>dataset renames</em> — the IRI is minted once at bootstrap and a later rename does not touch
 * it. The only thing that changes a name is re-bootstrapping after a rename, which changes the
 * source class IRI itself, i.e. the identity the mapping is keyed on has moved regardless. A
 * persistence table would add a migration, a port and an adapter to defend a case where the thing
 * being defended has already changed underneath it.
 *
 * <h2>Collisions</h2>
 *
 * <p>Two datasets may carry the same name, so two source class IRIs may share a local name while
 * remaining distinct IRIs. The first claim wins and later ones take a suffix from the dataset id,
 * as products do. Order is by source class IRI then dataset id: a total order over values that are
 * themselves stable, so the outcome does not depend on the order the caller supplies.
 */
@NullMarked
public final class SourceCatalog {

    /** How much of the dataset id disambiguates a collision. Matches {@link ProductCatalog}. */
    private static final int SUFFIX_LENGTH = 8;

    /** A source class as the catalog needs it: which dataset, and which class within it. */
    public record SourceRef(String datasetId, String sourceClassIri) {}

    /** One queryable source table: its SQL name and the view it stands for. */
    public record SourceTable(String sqlName, String datasetId, String sourceClassIri) {}

    private final List<SourceTable> tables;
    private final Map<String, SourceTable> byName;

    private SourceCatalog(List<SourceTable> tables) {
        this.tables = List.copyOf(tables);
        Map<String, SourceTable> index = new LinkedHashMap<>();
        for (SourceTable table : tables) {
            index.put(table.sqlName().toLowerCase(Locale.ROOT), table);
        }
        this.byName = Map.copyOf(index);
    }

    public static SourceCatalog of(Collection<SourceRef> refs) {
        List<SourceRef> ordered = new ArrayList<>(refs);
        ordered.sort(Comparator.comparing(SourceRef::sourceClassIri).thenComparing(SourceRef::datasetId));

        Map<String, SourceRef> claimed = new LinkedHashMap<>();
        List<SourceTable> tables = new ArrayList<>();
        for (SourceRef ref : ordered) {
            // The same (dataset, class) pair arriving twice is one table, not two names for it:
            // a class feeding several integrated classes is still one wrapper view.
            if (tables.stream().anyMatch(t -> t.datasetId().equals(ref.datasetId())
                    && t.sourceClassIri().equals(ref.sourceClassIri()))) {
                continue;
            }
            String name = uniqueName(ref, claimed);
            claimed.put(name.toLowerCase(Locale.ROOT), ref);
            tables.add(new SourceTable(name, ref.datasetId(), ref.sourceClassIri()));
        }
        return new SourceCatalog(tables);
    }

    /** Every source table, in the catalog's stable order. */
    public List<SourceTable> tables() {
        return tables;
    }

    /** Lookup by SQL table name, case-insensitively — SQL identifiers are not case-sensitive here. */
    public Optional<SourceTable> byName(String sqlName) {
        return Optional.ofNullable(byName.get(sqlName.toLowerCase(Locale.ROOT)));
    }

    private static String uniqueName(SourceRef ref, Map<String, SourceRef> claimed) {
        String base = normalise(ref.sourceClassIri());
        if (!claimed.containsKey(base.toLowerCase(Locale.ROOT))) {
            return base;
        }
        String id = ref.datasetId().replace("-", "");
        for (int length = SUFFIX_LENGTH; length <= id.length(); length++) {
            String candidate = base + "_" + id.substring(0, length);
            if (!claimed.containsKey(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        // Same dataset id and same class IRI would be the duplicate skipped above, so reaching here
        // means two genuinely different datasets share an id — a corrupt catalog, not a name clash.
        throw new IllegalStateException(
                "could not derive a unique table name for source class " + ref.sourceClassIri());
    }

    /** Source class IRI → SQL identifier, via the one normaliser that also names the views. */
    static String normalise(String sourceClassIri) {
        String cleaned = Names.sqlName(sourceClassIri);
        if (cleaned.isBlank() || cleaned.chars().allMatch(c -> c == '_')) {
            return "source";
        }
        return Character.isDigit(cleaned.charAt(0)) ? "s_" + cleaned : cleaned;
    }
}

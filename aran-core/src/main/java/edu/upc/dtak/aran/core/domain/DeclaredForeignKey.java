package edu.upc.dtak.aran.core.domain;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * A foreign key a source relational catalog itself declares, harvested live (Phase 4B,
 * {@code Harvest.ForeignKey}) and persisted so a catalog build can resolve it into a {@code
 * MappingCatalog.ReferentialEdge} tagged {@code Tier.DECLARED} without a live JDBC round trip.
 *
 * <p><b>Endpoints are Aran dataset ids, not table-name strings.</b> {@code Harvest.ForeignKey}
 * names its endpoints by physical table name, which is only unique <em>within one repository's
 * catalog</em> — two different repositories in the same tenant could each have a table called
 * {@code orders}. Resolving {@code fromTable}/{@code toTable} to their {@link Dataset} ids at
 * harvest-persist time (where the concrete {@code RelationalDatabaseRepository} being harvested is
 * known, so the table-name ambiguity cannot arise) means every later reader — a catalog build
 * across every repository in the tenant — never has to disambiguate by name again. {@code
 * fromCols}/{@code toCols} stay physical column names: unlike the table, a column name is resolved
 * into a source/integrated property IRI relative to a specific source class (via {@code
 * nx:sourceRef}), and that resolution depends on the active schema graph, which changes over time
 * — so it happens fresh at every catalog build, not once at persist time. See {@code
 * DeclaredEdgeResolver} (aran-operators/query-engine).
 *
 * @param repositoryId the relational repository this edge was harvested from — carried so {@link
 *                      DeclaredForeignKeyRepository#replaceForRepository} can scope a fresh
 *                      harvest's replacement to exactly the rows an earlier harvest of the same
 *                      repository produced, never another repository's
 * @param fromCols      ordinal-paired with {@code toCols}, exactly as harvested
 *                      ({@code Harvest.ForeignKey}'s own contract) — position {@code i} of
 *                      {@code fromCols} references position {@code i} of {@code toCols}
 */
@NullMarked
public record DeclaredForeignKey(
        UUID id, UUID tenantId, UUID repositoryId,
        UUID fromDatasetId, List<String> fromCols,
        UUID toDatasetId, List<String> toCols) {

    public DeclaredForeignKey {
        fromCols = List.copyOf(fromCols);
        toCols = List.copyOf(toCols);
        if (fromCols.isEmpty() || fromCols.size() != toCols.size()) {
            throw new IllegalArgumentException(
                    "fromCols and toCols must be non-empty and the same size: " + fromCols + " vs " + toCols);
        }
    }
}

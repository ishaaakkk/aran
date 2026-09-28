package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One source class enrolled in an {@link IntegrationModel}.
 *
 * <p>A dataset contributes one row per {@code rdfs:Class} in its bootstrapped
 * source graph. {@code wrapperSql} is seeded from the graph's
 * {@code nx:wrapperSql} triple and is the single editable truth for that class's
 * wrapper from M6 onward; {@code null} means "use whatever the compile step
 * carries from the source graph".
 *
 * <p>{@code expansionStatus}/{@code expansionReason} are Phase 3A's compile-time
 * verify-wrapper verdict for {@code wrapperSql}, computed and persisted together with it by
 * {@code IntegrationModelService.setWrapper} — never written separately, so the two can never
 * drift apart. Both are {@code null} together exactly when the wrapper has never been verified
 * (a source seeded by {@code addSource} but never edited) or was just cleared; {@code null}
 * means OPAQUE downstream, the same default {@code MappingCatalogOperator}'s catalog read
 * applies to a source class with no {@code nx:wrapperExpansion} triple.
 *
 * <p>{@code wrapperEdited} (V47, Flyway V38) says <em>who wrote {@code wrapperSql}</em>, which is
 * the fact the wrapper loop was missing. False — the default, and what {@code addSource} seeds —
 * means the string is a copy of what bootstrap produced for this class, so a later bootstrap that
 * regenerates it (a refresh over a changed source schema) may re-seed this row and lose nothing.
 * True means a person saved it through {@code PUT /model/sources/{datasetId}/wrappers}: it is
 * never overwritten, and a compile validates it against the current source schema instead, failing
 * with a legible error rather than promoting a class that dies on a DuckDB binder error at query
 * time. Without this flag the two cases are the same string and the stale enrolment seed silently
 * outranks the bootstrap's own correct answer.
 */
@NullMarked
public record ModelSource(
        UUID id,
        UUID tenantId,
        UUID modelId,
        UUID datasetId,
        String classIri,
        @Nullable String wrapperSql,
        @Nullable String expansionStatus,
        @Nullable String expansionReason,
        boolean wrapperEdited
) {
}

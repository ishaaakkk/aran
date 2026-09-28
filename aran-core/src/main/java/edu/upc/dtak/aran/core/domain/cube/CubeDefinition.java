package edu.upc.dtak.aran.core.domain.cube;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A dimensional model over the integrated schema: one fact class, its measures, the dimensions
 * reached from it, and an optional generated calendar.
 *
 * <p>A cube is an <em>annotation layer</em>, not a second metadata store: every IRI in here names
 * something the schema graph already holds, and the cube's own artefacts — the QB4OLAP triples in
 * {@code urn:aran:cubes:<id>}, the {@code cube.yaml} projection, the star's data products — are
 * derived from this one description.
 *
 * <p>v1 has <b>no update path</b>, mirroring data products: create, read, delete. That is what
 * makes {@code schemaVersionId} meaningful — the definition was validated against the evidence of
 * exactly that ACTIVE version, and nothing since has re-validated it.
 *
 * @param productIds      the star's member products in creation order: the fact product first,
 *                        then one per dimension in {@link #dimensions} order. Position is the only
 *                        link back, so the order is part of the record, not an accident of it
 * @param schemaVersionId the ACTIVE graph version the definition was validated against
 * @param createdBy       the user who created it; {@code null} for a system-run creation, the same
 *                        "unattributed rather than attributed to a service account" choice
 *                        {@code DataProduct} makes
 */
@NullMarked
public record CubeDefinition(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String name,
        @Nullable String description,
        String factClassIri,
        List<CubeMeasure> measures,
        List<CubeDimension> dimensions,
        @Nullable CubeCalendar calendar,
        List<UUID> productIds,
        UUID schemaVersionId,
        @Nullable String createdBy,
        Instant createdAt
) {

    public CubeDefinition {
        CubeNames.requireStarName("cube", name);
        if (factClassIri == null || factClassIri.isBlank()) {
            throw new IllegalArgumentException("cube '" + name + "': factClassIri must not be blank");
        }
        measures = measures == null ? List.of() : List.copyOf(measures);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        // Names are not free-form labels here: they become YAML keys, export identifiers and —
        // for a dimension — a data product's name. Two dimensions called `customer` would ask for
        // one product name twice, so the collision is a rejection rather than a suffix.
        requireDistinct("measure", measures.stream().map(CubeMeasure::name).toList());
        requireDistinct("dimension", dimensions.stream().map(CubeDimension::name).toList());
        // The generated calendar claims the QB4OLAP resource ':dimension:calendar'; a user
        // dimension with that name would silently merge with it into one incoherent node.
        if (calendar != null && dimensions.stream().anyMatch(d -> "calendar".equals(d.name()))) {
            throw new IllegalArgumentException(
                    "a dimension cannot be named 'calendar' when the cube has a calendar");
        }
        // data_product.name is VARCHAR(120); two individually-legal 63-char names joined by '_'
        // exceed it, and the overflow would surface as an opaque 500 inside product creation.
        for (CubeDimension dimension : dimensions) {
            String productName = name + "_" + dimension.name();
            if (productName.length() > 120) {
                throw new IllegalArgumentException("dimension '" + dimension.name()
                        + "': the star product name '" + productName
                        + "' exceeds 120 characters — shorten the cube or dimension name");
            }
        }
    }

    private static void requireDistinct(String kind, List<String> names) {
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException(kind + " name '" + name + "' is used twice");
            }
        }
    }

    public String factProductName() {
        return name + "_fact";
    }

    public String dimensionProductName(CubeDimension dimension) {
        return name + "_" + dimension.name();
    }

    /** This definition with its star's products recorded, in the order they were created. */
    public CubeDefinition withProductIds(List<UUID> newProductIds) {
        return new CubeDefinition(id, tenantId, projectId, name, description, factClassIri,
                measures, dimensions, calendar, newProductIds, schemaVersionId, createdBy,
                createdAt);
    }
}

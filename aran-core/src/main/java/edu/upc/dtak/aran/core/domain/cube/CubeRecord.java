package edu.upc.dtak.aran.core.domain.cube;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The persisted form of a {@link CubeDefinition}: the columns a query filters or lists on, plus
 * the rest of the definition as JSON.
 *
 * <p>The split is deliberate. {@code name}, {@code projectId} and {@code factClassIri} are what a
 * listing screen and a "cubes over this class" lookup need, so they are columns; measures,
 * dimensions and the calendar are only ever read as a whole, so storing them shredded across
 * three more tables would buy nothing and cost a migration per shape change — v1 has no update
 * path at all. The adapter stores strings and never interprets them: {@code CubeService} writes
 * both JSON columns and reads the definition, so the wire shape has exactly one author. The one
 * read that lives here, {@link #memberProductIds()}, is here because {@code DataProductService}
 * needs it as well and cannot reach {@code CubeService}, which depends on it.
 *
 * @param definitionJson measures, dimensions and calendar — everything not already a column
 * @param productIdsJson the star's member product ids, in creation order
 */
@NullMarked
public record CubeRecord(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String name,
        @Nullable String description,
        String factClassIri,
        String definitionJson,
        String productIdsJson,
        UUID schemaVersionId,
        @Nullable String createdBy,
        Instant createdAt
) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The star's member product ids, in creation order: the fact product first, then one per
     * dimension.
     *
     * @throws IllegalStateException when the stored JSON is unreadable — a bug or a hand-edited
     *         row, not user input, so it must not surface as a 400
     */
    public List<UUID> memberProductIds() {
        try {
            return List.copyOf(JSON.readValue(productIdsJson, new TypeReference<List<UUID>>() {}));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Cube " + id + " has unreadable member product ids", e);
        }
    }
}

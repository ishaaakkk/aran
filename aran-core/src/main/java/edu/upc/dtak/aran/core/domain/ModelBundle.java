package edu.upc.dtak.aran.core.domain;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * The full contents of an {@link IntegrationModel} loaded in one shot — the model
 * row plus all of its children. This is what {@code GET /api/projects/{id}/model}
 * returns and what {@code rebuildToDraft} reads.
 */
@NullMarked
public record ModelBundle(
        IntegrationModel model,
        List<ModelSource> sources,
        List<ModelMapping> mappings,
        List<ModelConstraint> constraints
) {
}

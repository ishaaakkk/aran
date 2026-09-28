package edu.upc.dtak.aran.app.web.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public final class GraphDtos {
    private GraphDtos() {}

    public record GraphNodeDto(
            String id,
            @Nullable String label,
            String iri,
            String type
    ) {}

    public record GraphEdgeDto(
            String source,
            String target,
            String label
    ) {}

    public record SourceGraphResponseDto(
            String datasetId,
            String graphUri,
            long tripleCount,
            boolean empty,
            List<GraphNodeDto> nodes,
            List<GraphEdgeDto> edges
    ) {}

    public record SchemaGraphResponseDto(
            String versionId,
            String graphUri,
            long tripleCount,
            boolean empty,
            List<GraphNodeDto> nodes,
            List<GraphEdgeDto> edges
    ) {}
}

package edu.upc.dtak.aran.core.source;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * Registry of every {@link SourceConnector} on the classpath. Spring injects all of them;
 * resolution picks the one connector for a descriptor. Adding a source kind is a new
 * {@code @Component} — this class never changes.
 */
@NullMarked
@Component
public class SourceConnectors {

    private final List<SourceConnector> connectors;
    private final Map<String, SourceConnector> byKind;

    public SourceConnectors(List<SourceConnector> connectors) {
        this.connectors = List.copyOf(connectors);
        // Fail fast on duplicate source kinds — two connectors claiming the same key is a bug.
        this.byKind = connectors.stream().collect(Collectors.toUnmodifiableMap(
                c -> c.sourceKind().toUpperCase(Locale.ROOT),
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                            "Duplicate SourceConnector kind '" + a.sourceKind() + "': "
                                    + a.getClass().getName() + " and " + b.getClass().getName());
                }));
    }

    /**
     * Resolve the connector for a descriptor. A caller-forced {@link SourceDescriptor#explicitKind()}
     * wins; otherwise the sole connector whose {@link SourceConnector#supports} matches is used.
     *
     * @throws UnsupportedSourceException if no connector matches.
     * @throws IllegalStateException      if the descriptor is ambiguous (more than one match) or the
     *                                    explicit kind names no connector.
     */
    public SourceConnector forSource(SourceDescriptor source) {
        String explicit = source.explicitKind();
        if (explicit != null && !explicit.isBlank()) {
            SourceConnector c = byKind.get(explicit.toUpperCase(Locale.ROOT));
            if (c == null) {
                throw new IllegalStateException("No source connector for explicit kind '" + explicit + "'");
            }
            return c;
        }

        List<SourceConnector> matches = connectors.stream()
                .filter(c -> c.supports(source))
                .toList();
        if (matches.isEmpty()) {
            throw new UnsupportedSourceException(source);
        }
        if (matches.size() > 1) {
            String kinds = matches.stream().map(SourceConnector::sourceKind).collect(Collectors.joining(", "));
            throw new IllegalStateException("Ambiguous source: connectors " + kinds
                    + " all claim dataset '" + source.datasetName() + "'");
        }
        return matches.get(0);
    }
}

package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.source.SourceSchema;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * JSON source connector. Delegates schema extraction to {@link JsonBootstrap}; its data is read
 * from the lake file. Detection mirrors the former
 * {@code BootstrapEngine.detectFormat} JSON branch.
 */
@NullMarked
@Component
public final class JsonConnector implements SourceConnector {

    private final JsonBootstrap json = new JsonBootstrap();

    @Override
    public String sourceKind() {
        return "JSON";
    }

    @Override
    public boolean supports(SourceDescriptor source) {
        String ct = source.contentType() == null ? "" : source.contentType().toLowerCase(Locale.ROOT);
        if (ct.contains("json")) {
            return true;
        }
        String n = source.filenameHint() == null ? "" : source.filenameHint().toLowerCase(Locale.ROOT);
        return n.endsWith(".json");
    }

    @Override
    public SourceSchema introspect(SourceDescriptor source) throws IOException {
        byte[] bytes = Objects.requireNonNull(source.bytes(), "JSON connector requires file bytes");
        String snapshotId = Objects.requireNonNull(source.snapshotId(), "JSON connector requires a snapshotId");
        return new SourceSchema(
                json.bootstrap(source.datasetId(), source.datasetName(), snapshotId, bytes));
    }
}

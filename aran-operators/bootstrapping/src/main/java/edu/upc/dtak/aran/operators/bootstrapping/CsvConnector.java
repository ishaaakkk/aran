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
 * CSV/TSV source connector. Delegates schema extraction to {@link CsvBootstrap}; its data is
 * read from the lake file. The detection rules mirror the former {@code BootstrapEngine.detectFormat} CSV branch (including TSV, which
 * {@link CsvBootstrap} auto-detects by delimiter).
 */
@NullMarked
@Component
public final class CsvConnector implements SourceConnector {

    private final CsvBootstrap csv = new CsvBootstrap();

    @Override
    public String sourceKind() {
        return "CSV";
    }

    @Override
    public boolean supports(SourceDescriptor source) {
        String ct = source.contentType() == null ? "" : source.contentType().toLowerCase(Locale.ROOT);
        if (ct.contains("csv") || ct.contains("comma-separated")
                || ct.contains("tab-separated") || ct.contains("tsv")) {
            return true;
        }
        String n = source.filenameHint() == null ? "" : source.filenameHint().toLowerCase(Locale.ROOT);
        return n.endsWith(".csv") || n.endsWith(".tsv");
    }

    @Override
    public SourceSchema introspect(SourceDescriptor source) throws IOException {
        byte[] bytes = Objects.requireNonNull(source.bytes(), "CSV connector requires file bytes");
        String snapshotId = Objects.requireNonNull(source.snapshotId(), "CSV connector requires a snapshotId");
        return new SourceSchema(
                csv.bootstrap(source.datasetId(), source.datasetName(), snapshotId, bytes));
    }
}

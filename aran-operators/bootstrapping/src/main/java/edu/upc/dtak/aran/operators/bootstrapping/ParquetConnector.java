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
 * Parquet source connector. Delegates schema extraction to {@link ParquetBootstrap} (which
 * reads the real column schema via DuckDB {@code DESCRIBE read_parquet}); its data is read from
 * the lake file. Detection mirrors the former
 * {@code BootstrapEngine.detectFormat} Parquet branch.
 */
@NullMarked
@Component
public final class ParquetConnector implements SourceConnector {

    private final ParquetBootstrap parquet = new ParquetBootstrap();

    @Override
    public String sourceKind() {
        return "PARQUET";
    }

    @Override
    public boolean supports(SourceDescriptor source) {
        String ct = source.contentType() == null ? "" : source.contentType().toLowerCase(Locale.ROOT);
        if (ct.contains("parquet")) {
            return true;
        }
        String n = source.filenameHint() == null ? "" : source.filenameHint().toLowerCase(Locale.ROOT);
        return n.endsWith(".parquet");
    }

    @Override
    public SourceSchema introspect(SourceDescriptor source) throws IOException {
        byte[] bytes = Objects.requireNonNull(source.bytes(), "Parquet connector requires file bytes");
        String snapshotId = Objects.requireNonNull(source.snapshotId(), "Parquet connector requires a snapshotId");
        return new SourceSchema(
                parquet.bootstrap(source.datasetId(), source.datasetName(), snapshotId, bytes));
    }
}

package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.IcebergTableExtractor;
import edu.upc.dtak.aran.core.source.DuckDbHttpSecret;
import edu.upc.dtak.aran.core.source.DuckDbS3Config;
import edu.upc.dtak.aran.core.source.IcebergScanExpression;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The {@link IcebergTableExtractor} port, over DuckDB's {@code iceberg} extension: asks the REST
 * catalog for the table's current metadata location (control plane, over the
 * {@link IcebergCatalogClient} port), then copies the table into a local parquet file
 * ({@code COPY (SELECT * FROM iceberg_scan('…')) TO …}) and hands back its bytes for the same
 * upload tail every other materialize path takes.
 *
 * <p>The Iceberg twin of {@link DuckDbObjectStorageExtractor}, and the same credential hygiene: the
 * {@code CREATE SECRET} statement carries the data-plane secret, is executed on the scratch
 * connection and discarded, and a bind failure is re-raised naming only the scope. For an
 * {@code s3://} metadata location that S3 secret is all DuckDB holds and the catalog token
 * authenticates only the client's own HTTP call; for an {@code http(s)} location the catalog
 * token is also bound on the scratch connection as a DuckDB HTTP bearer secret, exactly as the
 * live read binds it — the file server behind such a catalog answers only to that bearer.
 */
@NullMarked
@Component
public final class DuckDbIcebergTableExtractor implements IcebergTableExtractor {

    private final @Nullable IcebergCatalogClient catalog;

    public DuckDbIcebergTableExtractor(Optional<IcebergCatalogClient> catalog) {
        this.catalog = catalog.orElse(null);
    }

    @Override
    public Extracted extract(IcebergRestRepository repository, String tableIdentifier, long maxBytes) {
        if (catalog == null) {
            throw new IllegalStateException(
                    "this build has no Iceberg catalog client on its classpath");
        }
        String metadataLocation = catalog.loadTable(repository, tableIdentifier).metadataLocation();
        String scanExpr = IcebergScanExpression.forMetadataLocation(metadataLocation);
        DuckDbS3Config s3 = new DuckDbS3Config(repository.provider(), repository.endpoint(),
                repository.accessKey(), repository.secretKey(), repository.region());

        Path parquet = null;
        try {
            parquet = Files.createTempFile("aran-iceberg-extract-", ".parquet");
            String targetLiteral = Names.sqlLiteral(parquet.toAbsolutePath().toString());

            try (Connection conn = DuckDbSession.open();
                 Statement stmt = conn.createStatement()) {

                for (String ext : s3.installAndLoadHttpfs()) {
                    stmt.execute(ext);
                }
                stmt.execute("INSTALL iceberg");
                stmt.execute("LOAD iceberg");
                try {
                    stmt.execute(s3.createSecretSql());
                } catch (SQLException e) {
                    // The statement holds the secret and a DuckDB secret error can echo option
                    // values, so neither the SQL nor e's message is surfaced.
                    throw new IllegalStateException(
                            "failed to bind S3 credentials for Iceberg table '" + tableIdentifier + "'");
                }
                // A catalog serving its files over HTTP (another Aran's produce surface does)
                // authenticates file reads with the catalog's own bearer — the live path's rule
                // (QueryEngineOperator.icebergReadExpression). Without it every read was a 401.
                if (metadataLocation.startsWith("http")
                        && repository.token() != null && !repository.token().isBlank()) {
                    try {
                        stmt.execute(DuckDbHttpSecret.createSecretSql(
                                "aran_ice_extract_http", repository.token(), null));
                    } catch (SQLException e) {
                        throw new IllegalStateException("failed to bind the catalog's bearer"
                                + " token for Iceberg table '" + tableIdentifier + "'");
                    }
                }

                Files.deleteIfExists(parquet);
                try {
                    stmt.execute("COPY (SELECT * FROM " + scanExpr + ") TO '" + targetLiteral
                            + "' (FORMAT PARQUET)");
                } catch (SQLException e) {
                    // scanExpr names the metadata location only — no credential — so echoing the
                    // message is safe and is the most useful thing when a location or key is wrong.
                    throw new IllegalStateException(
                            "failed to copy Iceberg table '" + tableIdentifier + "': "
                            + e.getMessage(), e);
                }

                long sizeBytes = Files.size(parquet);
                if (sizeBytes > maxBytes) {
                    throw new IllegalStateException("table '" + tableIdentifier + "' produced "
                            + sizeBytes + " bytes of parquet, over the " + maxBytes
                            + "-byte import ceiling");
                }

                long rowCount = countRows(stmt, targetLiteral);
                return new Extracted(Files.readAllBytes(parquet), rowCount);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB error copying Iceberg table: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("failed to stage the Iceberg copy of '"
                    + tableIdentifier + "': " + e.getMessage(), e);
        } finally {
            if (parquet != null) {
                try {
                    Files.deleteIfExists(parquet);
                } catch (IOException ignored) {
                    // The bytes are already read; a leftover temp file is not worth failing an
                    // otherwise successful copy.
                }
            }
        }
    }

    private static long countRows(Statement stmt, String targetLiteral) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT count(*) FROM read_parquet('" + targetLiteral + "')")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}

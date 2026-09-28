package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.ObjectStorageExtractor;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.source.DuckDbS3Config;
import edu.upc.dtak.aran.core.util.DuckDbSession;
import edu.upc.dtak.aran.core.util.Names;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The {@link ObjectStorageExtractor} port, over DuckDB {@code httpfs}: copies one {@code s3://} object
 * into a local parquet file and hands back its bytes, so {@code DatasetService} can upload it as a
 * lake snapshot through the same {@code initUpload}/{@code uploadToS3}/{@code completeUpload} tail an
 * uploaded file or a JDBC extract takes.
 *
 * <p>The object-store twin of {@link JdbcTableExtractor}: same shape (wire up the read, copy to a temp
 * parquet, read the bytes, delete the temp), a different authority for the connection. Where the JDBC
 * extractor opens a driver connection, this opens a scratch DuckDB, loads {@code httpfs} and binds an
 * S3 secret from {@link DuckDbS3Config} — the identical setup {@link ObjectStorageBootstrap} uses to
 * introspect the same object in place. The copy itself is
 * {@code COPY (SELECT * FROM read_parquet('s3://…')) TO '<tmp>' (FORMAT PARQUET)}: a whole-object read
 * (unlike the bootstrap's metadata-only {@code DESCRIBE … LIMIT 0}), because the point of MATERIALIZED
 * is to take a copy.
 *
 * <h2>Credential hygiene</h2>
 *
 * <p>The {@code CREATE SECRET} statement carries the secret key; it is executed on the scratch
 * connection and discarded, never logged, never placed in an exception. A bind failure is re-raised
 * naming only the bucket — never the statement, never the driver's message (a DuckDB secret error can
 * echo option values). The {@code COPY} statement and its errors carry only the {@code s3://} URL (no
 * credential), so surfacing them is safe and is the most useful thing when a key or region is wrong.
 * The COPY SQL is built here around the object key the enrolment recorded — never free-form user text
 * — and every location is passed through {@link Names#sqlLiteral}.
 */
@NullMarked
@Component
public final class DuckDbObjectStorageExtractor implements ObjectStorageExtractor {

    @Override
    public Extracted extract(ObjectStorageRepository repository, String objectKey, String format,
                             long maxBytes) {
        String normalizedFormat = ObjectStorageBootstrap.normalizeFormat(format);
        String s3Url = "s3://" + repository.bucketName() + "/" + objectKey;
        String readExpr = ObjectStorageBootstrap.readExpression(normalizedFormat, s3Url);
        DuckDbS3Config s3 = DuckDbS3Config.from(repository);

        Path parquet = null;
        try {
            parquet = Files.createTempFile("aran-obj-extract-", ".parquet");
            String targetLiteral = Names.sqlLiteral(parquet.toAbsolutePath().toString());

            try (Connection conn = DuckDbSession.open();
                 Statement stmt = conn.createStatement()) {

                for (String ext : s3.installAndLoadHttpfs()) {
                    stmt.execute(ext);
                }
                try {
                    stmt.execute(s3.createSecretSql());
                } catch (SQLException e) {
                    // The statement holds the secret and a DuckDB secret error can echo option
                    // values, so neither the SQL nor e's message is surfaced — only the bucket name.
                    throw new IllegalStateException(
                            "failed to bind S3 credentials for bucket '" + repository.bucketName() + "'");
                }

                // COPY TO overwrites, but the temp file already exists (createTempFile made it) and
                // DuckDB's COPY refuses to clobber unless told; delete it first so COPY writes fresh.
                Files.deleteIfExists(parquet);
                try {
                    stmt.execute("COPY (SELECT * FROM " + readExpr + ") TO '" + targetLiteral
                            + "' (FORMAT PARQUET)");
                } catch (SQLException e) {
                    // readExpr names read_parquet('s3://bucket/key') only — no secret — so echoing
                    // the message is safe and is the most useful thing when a key/region/object is
                    // wrong.
                    throw new IllegalStateException(
                            "failed to copy object '" + s3Url + "': " + e.getMessage(), e);
                }

                long sizeBytes = Files.size(parquet);
                if (sizeBytes > maxBytes) {
                    throw new IllegalStateException("object '" + s3Url + "' produced " + sizeBytes
                            + " bytes of parquet, over the " + maxBytes + "-byte import ceiling");
                }

                long rowCount = countRows(stmt, targetLiteral);
                return new Extracted(Files.readAllBytes(parquet), rowCount);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB error copying object storage: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("failed to stage the object-storage copy of '"
                    + s3Url + "': " + e.getMessage(), e);
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

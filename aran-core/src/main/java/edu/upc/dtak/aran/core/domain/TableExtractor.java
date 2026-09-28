package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * Copies one relation out of a source database and hands back parquet bytes.
 *
 * <p>A port, because the implementation needs DuckDB and lives in
 * {@code aran-operators/bootstrapping}, while the caller ({@code DatasetService}) is in the core —
 * which depends on no operator by construction.
 *
 * <p>Bytes rather than a {@code Path}: the caller's next move is
 * {@code lakeClient.uploadToS3}, which already takes a byte array, and returning a file would push
 * temp-file lifetime across a module boundary for no gain.
 */
@NullMarked
public interface TableExtractor {

    /** Parquet bytes and how many rows are in them. */
    record Extracted(byte[] parquet, long rowCount) {}

    /**
     * Extract {@code table} from {@code repository}.
     *
     * @param maxBytes ceiling on the parquet produced; over it, the extract is abandoned
     * @throws UnsupportedOperationException if the repository's engine has no generic extract —
     *         callers should have checked {@code RelationalEngine.bulkExtract().isImplemented()}
     *         first, and reaching here without doing so is a programming error rather than a
     *         user's mistake
     */
    Extracted extract(RelationalDatabaseRepository repository, String table, long maxBytes);
}

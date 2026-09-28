package edu.upc.dtak.aran.core.domain;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Runs one of a Singer tap's streams and hands back parquet bytes (T6).
 *
 * <p>A port for the same reason {@link TableExtractor} is one: the implementation needs the tap
 * subprocess and DuckDB and so lives in {@code aran-operators/bootstrapping}, while the callers
 * ({@code DatasetService}, {@code DataRepositoryService}) are in the core, which depends on no
 * operator by construction. Bytes rather than a {@code Path}, again for the same reason — the
 * caller's next move is {@code lakeClient.uploadToS3}.
 *
 * <p><b>{@link #discoverStreams} lives here too</b>, which is where this port stops mirroring
 * {@code TableExtractor} exactly. The relational equivalent — "what can I import from this
 * source?" — is a JDBC catalogue read {@code DataRepositoryService} performs itself; a tap has no
 * catalogue, only a discovery <em>run</em> of the same executable. Putting it behind a second port
 * would give two ports the same single implementation and the same single reason to be absent.
 */
@NullMarked
public interface SingerTapExtractor {

    /** Parquet bytes and how many records are in them. */
    record Extracted(byte[] parquet, long rowCount) {}

    /**
     * The streams this tap offers, from its own discovery output.
     *
     * <p>Bounded: it runs a program. The implementation owns the deadline, because the caller has
     * no way to pick a sensible one for a tap it cannot see.
     *
     * @throws edu.upc.dtak.aran.core.source.SingerTapCatalog.TapNotAllowedException if the
     *         repository names a tap this deployment does not declare
     */
    List<String> discoverStreams(SingerTapRepository repository);

    /**
     * Run {@code stream} and return its records as parquet.
     *
     * @param maxBytes ceiling on the parquet produced; over it, the extract is abandoned
     */
    Extracted extract(SingerTapRepository repository, String stream, long maxBytes);
}

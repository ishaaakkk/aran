package edu.upc.dtak.aran.app.iceberg;

import edu.upc.dtak.aran.adapter.iceberg.IcebergProductTable;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.ProductTable;
import edu.upc.dtak.aran.core.domain.UsageKind;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.licensing.LicenseService;
import edu.upc.dtak.aran.core.service.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the files the produce-side Iceberg metadata points at — metadata JSON, Avro manifests,
 * and the parquet data file itself — from Aran, over the same authenticated surface as the catalog
 * (Gap 2). This controller is what makes the "only Aran talks to the lake" invariant hold for
 * external Iceberg engines: they read every byte from here, never from the lake.
 *
 * <p>Tenancy: the path names a product id, but authority comes from the caller — the product must
 * be one of the <em>caller's tenant's</em> servable tables and the path's snapshot must be its
 * <em>current</em> snapshot, or the answer is 404. A refresh therefore invalidates old locations
 * at the moment it lands, and a stale client re-loads the table through the catalog.
 *
 * <p>httpfs note: DuckDB reads ranges with HTTP {@code Range} requests. {@link FileSystemResource}
 * under Spring MVC answers those with 206 and honours {@code Content-Length}, which is exactly
 * what a parquet reader needs to fetch footers without the whole file.
 *
 * <p>Egress: the data file is gated by the tenant's egress ceiling and metered as
 * {@link UsageKind#ICEBERG}, the way a JDBC statement and an export are — it is the third bulk
 * door, and the only one with no row cap at all. The metadata endpoints are deliberately outside
 * both: a metadata.json is protocol traffic a client must fetch before it can decide to read
 * nothing, the same call the JDBC surface leaves unmetered for its catalog.
 */
@RestController
@RequestMapping("/api/iceberg/files")
@NullMarked
public class IcebergFileController {

    private static final MediaType AVRO = MediaType.parseMediaType("application/avro");
    private static final MediaType PARQUET =
            MediaType.parseMediaType("application/vnd.apache.parquet");

    private final IcebergProductService service;
    private final CurrentUserResolver currentUser;
    private final IcebergFilesBaseUrl filesBaseUrl;
    private final LicenseService licenses;
    private final UsageService usage;

    public IcebergFileController(IcebergProductService service, CurrentUserResolver currentUser,
                                 IcebergFilesBaseUrl filesBaseUrl, LicenseService licenses,
                                 UsageService usage) {
        this.service = service;
        this.currentUser = currentUser;
        this.filesBaseUrl = filesBaseUrl;
        this.licenses = licenses;
        this.usage = usage;
    }

    @GetMapping("/{productId}/{snapshotId}/metadata/{file:.+}")
    public ResponseEntity<byte[]> metadataFile(@PathVariable UUID productId,
                                               @PathVariable String snapshotId,
                                               @PathVariable String file,
                                               HttpServletRequest request) {
        AuthenticatedUser caller = currentUser.require();
        Optional<ProductTable> table = currentTable(caller, productId, snapshotId);
        if (table.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        IcebergProductTable.Artifacts artifacts =
                service.artifactsFor(caller, table.get(), filesBaseUrl.forRequest(request));
        byte[] bytes = artifacts.files().get("metadata/" + file);
        if (bytes == null) {
            // A manifest name from a metadata.json generated before an artifact-cache eviction;
            // the regenerated set has fresh names. 404 sends the client back through loadTable.
            return ResponseEntity.notFound().build();
        }
        MediaType type = file.endsWith(".json") ? MediaType.APPLICATION_JSON : AVRO;
        return ResponseEntity.ok().contentType(type).body(bytes);
    }

    @GetMapping("/{productId}/{snapshotId}/data/" + IcebergProductTable.DATA_FILE)
    public ResponseEntity<Resource> dataFile(@PathVariable UUID productId,
                                             @PathVariable String snapshotId) {
        AuthenticatedUser caller = currentUser.require();
        // This is the one bulk door with no row cap — it hands out a whole product — so it takes
        // the same gate the other two do (AranMeta.materialize, QueryExportService.export).
        // Before staging: refusing after a lake download has already run spends exactly the
        // bytes the ceiling exists to bound.
        licenses.requireWithinEgressLimit(caller);
        if (currentTable(caller, productId, snapshotId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path staged = service.stagedDataFile(caller, snapshotId);
        try {
            return ResponseEntity.ok()
                    .contentType(PARQUET)
                    .contentLength(Files.size(staged))
                    .body(new MeteredResource(staged, caller));
        } catch (IOException e) {
            throw new IllegalStateException("staged parquet unreadable: " + e.getMessage(), e);
        }
    }

    /** The caller's servable table with this product id whose CURRENT snapshot is the path's. */
    private Optional<ProductTable> currentTable(AuthenticatedUser caller, UUID productId,
                                                String snapshotId) {
        return service.icebergTables(caller).stream()
                .filter(t -> t.product().id().equals(productId))
                .filter(t -> snapshotId.equals(t.product().snapshotId()))
                .findFirst();
    }

    /**
     * The staged parquet, which reports the bytes a response actually took out of it.
     *
     * <p>Metering happens here rather than in {@link #dataFile} because the controller returns
     * before a single byte is written: what an engine reads is a footer, then a column chunk,
     * then another, as separate {@code Range} requests. Recording {@code Files.size} per request
     * would bill a 4 KB footer read as a whole-file download and make the egress figures worse
     * than not having them.
     *
     * <p>Deliberately extends {@link AbstractResource} rather than {@link FileSystemResource}:
     * every content path {@code AbstractResource} leaves to the interface's defaults —
     * {@code getContentAsByteArray}, {@code readableChannel} — funnels through
     * {@link #getInputStream()}, so no reader can slip past the count. The metadata methods
     * delegate, and {@code contentLength} is one of them precisely because the default would
     * drain the stream to measure it.
     *
     * <p>The row is therefore written while the response streams, after the handler returned.
     * That is safe by {@link UsageService}'s own contract: it takes its own transaction and
     * swallows its failures, so metering can neither join the request's transaction nor fail the
     * download it is measuring.
     */
    private final class MeteredResource extends AbstractResource {

        private final FileSystemResource delegate;
        private final AuthenticatedUser caller;
        private final long started = System.currentTimeMillis();

        MeteredResource(Path file, AuthenticatedUser caller) {
            this.delegate = new FileSystemResource(file);
            this.caller = caller;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FilterInputStream(delegate.getInputStream()) {
                private long served;
                private boolean recorded;

                @Override public int read() throws IOException {
                    int b = super.read();
                    if (b != -1) {
                        served++;
                    }
                    return b;
                }

                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = super.read(buffer, offset, length);
                    if (n > 0) {
                        served += n;
                    }
                    return n;
                }

                @Override public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        // A stream closed twice is one response, and a stream that read nothing
                        // (a probe, an aborted client) is not egress worth a row.
                        if (!recorded && served > 0) {
                            recorded = true;
                            usage.record(caller, UsageKind.ICEBERG,
                                    System.currentTimeMillis() - started, null, served);
                        }
                    }
                }
            };
        }

        @Override public boolean exists() {
            return delegate.exists();
        }

        @Override public boolean isReadable() {
            return delegate.isReadable();
        }

        @Override public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override public long lastModified() throws IOException {
            return delegate.lastModified();
        }

        @Override public @Nullable String getFilename() {
            return delegate.getFilename();
        }

        @Override public URI getURI() throws IOException {
            return delegate.getURI();
        }

        @Override public String getDescription() {
            return "metered " + delegate.getDescription();
        }
    }

}

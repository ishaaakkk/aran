package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.QueryExportService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Ad-hoc export of a query result, uncapped.
 *
 * <p>Separate from the operator invoke endpoint because that one answers with
 * {@code Map<String,String>} inside a JSON envelope, which is the wrong shape for a file that may
 * be hundreds of megabytes. This streams instead.
 */
@RestController
@RequestMapping("/api/query")
public class QueryExportController {

    /** Body of {@code POST /api/query/export}. */
    public record ExportRequest(String oql, String format) {}

    private final QueryExportService exportService;
    private final CurrentUserResolver currentUser;

    public QueryExportController(QueryExportService exportService, CurrentUserResolver currentUser) {
        this.exportService = exportService;
        this.currentUser = currentUser;
    }

    /**
     * Run {@code oql} with no row cap and stream the whole result back as a download.
     *
     * <p>POST rather than GET because an OQL query is unbounded in length and has no business in a
     * URL or an access log.
     *
     * <p>The temp file is deleted when the response stream closes, not in a {@code finally} here:
     * the body is streamed <em>after</em> this method returns, so deleting eagerly would race the
     * transfer and hand the client a truncated file.
     */
    @PostMapping("/export")
    public ResponseEntity<InputStreamResource> export(@RequestBody ExportRequest body)
            throws IOException {
        AuthenticatedUser caller = currentUser.require();
        QueryExportService.Export export = exportService.export(
                new TenantContext(caller.tenantId().toString()),
                caller.userId(),
                body.oql(),
                body.format());

        InputStream stream;
        try {
            stream = deleteOnClose(export);
        } catch (IOException e) {
            export.delete();
            throw e;
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + export.filename() + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(Files.size(export.path())))
                // The true row count, which the capped workbench result cannot report.
                .header("X-Aran-Row-Count", Long.toString(export.rowCount()))
                .body(new InputStreamResource(stream));
    }

    /** A stream over the export's file that removes it once the client has finished reading. */
    private static InputStream deleteOnClose(QueryExportService.Export export) throws IOException {
        return new FilterInputStream(Files.newInputStream(export.path())) {
            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    export.delete();
                }
            }
        };
    }
}

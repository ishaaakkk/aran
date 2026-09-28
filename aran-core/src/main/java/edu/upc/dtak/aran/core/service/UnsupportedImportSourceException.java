package edu.upc.dtak.aran.core.service;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown when a table import is requested against a repository whose engine has no
 * bulk-extract mechanism — see {@link edu.upc.dtak.aran.core.source.RelationalEngine.BulkExtract}.
 *
 * <p>Mapped to RFC-7807 with {@code errorCode=UNSUPPORTED_IMPORT_SOURCE} by
 * {@code ApiExceptionHandler}.
 */
@NullMarked
public class UnsupportedImportSourceException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** The stable machine-readable code carried on the RFC-7807 body. */
    public static final String ERROR_CODE = "UNSUPPORTED_IMPORT_SOURCE";

    public UnsupportedImportSourceException(String message) {
        super(message);
    }
}

package edu.upc.dtak.aran.core.service;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown when a dataset preview cannot be produced — the driver refused the
 * connection, the file is unreadable, the snapshot is gone, the wrapper SQL is
 * invalid.
 *
 * <p>A preview that fails must fail visibly. The previous behaviour — returning
 * plausible-looking rows invented from the dataset's name — meant a user could
 * not distinguish a working source from a broken one, which is the one thing a
 * data-integration product may never get wrong.
 *
 * <p>Mapped to RFC-7807 with {@code errorCode=PREVIEW_FAILED} by
 * {@code ApiExceptionHandler}; {@link #getMessage()} carries the underlying
 * driver/parser message and becomes the problem {@code detail}.
 */
@NullMarked
public class PreviewFailedException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** The stable machine-readable code carried on the RFC-7807 body. */
    public static final String ERROR_CODE = "PREVIEW_FAILED";

    public PreviewFailedException(String message) {
        super(message);
    }

    public PreviewFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

package edu.upc.dtak.aran.core.service;

import org.jspecify.annotations.NullMarked;

/**
 * A job task failing with a caller-meaningful code.
 *
 * <p>{@link JobService} otherwise records the exception's class simple name as
 * the job's {@code error_code}, which is fine for bugs but loses information
 * when the underlying failure already <em>had</em> a code — an
 * {@code OperatorResult.failure(errorCode, ...)}, say. Throwing this preserves
 * that code onto the job row.
 */
@NullMarked
public class JobFailureException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public JobFailureException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

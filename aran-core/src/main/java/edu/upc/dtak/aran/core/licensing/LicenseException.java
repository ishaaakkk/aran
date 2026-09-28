package edu.upc.dtak.aran.core.licensing;

import org.jspecify.annotations.NullMarked;

/**
 * Base class for licensing-related rejections.
 *
 * <p>All three subclasses map to HTTP 402 (Payment Required) — distinct from
 * 403 (authentication / authorization) because the cause is commercial, not
 * permission-based. Splitting them at the type level lets the API exception
 * handler return different problem-detail titles and the frontend show
 * different UI ("upgrade your plan" vs. "license expired — contact admin"
 * vs. "no license on file — contact admin").
 */
@NullMarked
public abstract class LicenseException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    protected LicenseException(String message) {
        super(message);
    }
}

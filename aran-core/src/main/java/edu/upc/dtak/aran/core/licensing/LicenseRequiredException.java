package edu.upc.dtak.aran.core.licensing;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown when an operation requires a license but none is on file for the
 * tenant. Distinct from {@link LicenseExpiredException} (license existed but
 * lapsed) and {@link LicenseLimitExceededException} (license exists but the
 * action would exceed a quota).
 *
 * <p>Maps to HTTP 402.
 */
@NullMarked
public class LicenseRequiredException extends LicenseException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public LicenseRequiredException(String message) {
        super(message);
    }
}

package edu.upc.dtak.aran.core.licensing;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown when a mutating operation is attempted on a tenant whose license is
 * expired or suspended. Reads continue to work — this is the read-only mode
 * triggered by {@code LicenseEntitlements.allowsMutations() == false}.
 *
 * <p>Maps to HTTP 402.
 */
@NullMarked
public class LicenseExpiredException extends LicenseException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public LicenseExpiredException(String message) {
        super(message);
    }
}

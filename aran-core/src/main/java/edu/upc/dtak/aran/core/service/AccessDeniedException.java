package edu.upc.dtak.aran.core.service;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown when the caller is authenticated and the target resource exists, but
 * the caller is not permitted to perform the requested action on it — for
 * example a {@code MEMBER} attempting a tenant-administration operation.
 *
 * <p>Distinct from {@link ResourceNotFoundException}: this is used only where
 * revealing "you are not allowed" leaks nothing the caller could not already
 * infer. For visibility checks on individual resources, prefer
 * {@code ResourceNotFoundException}. The application layer maps this to
 * HTTP 403.
 */
@NullMarked
public class AccessDeniedException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public AccessDeniedException(String message) {
        super(message);
    }
}

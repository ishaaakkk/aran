package edu.upc.dtak.aran.core.service;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown when a requested domain object does not exist — or, deliberately,
 * when it exists but is not visible to the caller.
 *
 * <p>Conflating "not found" and "not allowed to see" is intentional: it
 * prevents an information leak where a caller could probe which project ids
 * exist in a tenant by distinguishing 403 from 404. The application layer maps
 * this to HTTP 404.
 */
@NullMarked
public class ResourceNotFoundException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(message);
    }
}

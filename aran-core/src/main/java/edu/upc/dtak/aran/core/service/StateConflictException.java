package edu.upc.dtak.aran.core.service;

import org.jspecify.annotations.NullMarked;

/**
 * A request that is well formed but refused because of the current state of what it names —
 * answered as a 409 carrying {@link #errorCode()}, so a client can tell one refusal from another
 * without parsing the sentence.
 *
 * <p>An {@link IllegalStateException} so the callers that already caught the plain one for these
 * cases keep working.
 */
@NullMarked
public class StateConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public StateConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

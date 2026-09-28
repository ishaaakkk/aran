package edu.upc.dtak.aran.core.service;

import org.jspecify.annotations.NullMarked;

/**
 * The request is well formed and allowed, but conflicts with the current state of what it names:
 * a name already taken, a version that is no longer a draft, a dataset with no stored snapshot to
 * read. Mapped to <b>409</b> at the web boundary, with {@link #errorCode()} as the stable key a
 * client branches on and the message as the sentence a user reads — so the message is written
 * for that user, never for the log.
 *
 * <p>Deliberately distinct from the two exceptions it replaces at its throw sites.
 * {@link IllegalArgumentException} is a 400: the request is wrong whatever the state, and
 * resending it can never succeed. {@link IllegalStateException} stays unmapped (a generic 500) on
 * purpose, because it also means "the server reached a state it never should", which a caller
 * cannot act on. A 409 says the opposite: look at the current state, and the action may make
 * sense after a change — the reload after a promote that timed out in the browser but landed on
 * the server is the case that motivated it (UI audit J4-F11).
 */
@NullMarked
public class ConflictException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** A name that must be unique in its scope is already used there. */
    public static final String NAME_TAKEN = "NAME_TAKEN";
    /** The dataset has no stored snapshot — it is read live, or its upload has not landed. */
    public static final String NO_SNAPSHOT = "NO_SNAPSHOT";
    /** The version or run is no longer a draft, typically because it was already promoted. */
    public static final String NOT_A_DRAFT = "NOT_A_DRAFT";
    /** The email already belongs to a member of the caller's own organization. */
    public static final String ALREADY_A_MEMBER = "ALREADY_A_MEMBER";

    private final String errorCode;

    public ConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

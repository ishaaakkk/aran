package edu.upc.dtak.aran.core.service;

import org.jspecify.annotations.NullMarked;

/**
 * An owner asked to add someone the tenant cannot take: already a member, a member of another
 * organization (V23 — one tenant per user), or an identity-provider account Aran has never seen.
 *
 * <p>Its own type so the web layer answers 409 with this message. Before it existed, re-adding a
 * removed member reached the identity provider's own "already exists" refusal, which nothing
 * mapped, and every retry answered 500 (audit J7-F2).
 */
@NullMarked
public class MemberConflictException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public MemberConflictException(String message) {
        super(message);
    }
}

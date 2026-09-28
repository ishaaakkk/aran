package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

/**
 * The account behind a {@link User}, in whatever system actually authenticates people.
 *
 * <p><b>Deliberately narrow.</b> It creates accounts, sets passwords, and disables or deletes
 * them. It says nothing about tenants, roles or memberships, because those are Aran's own and live
 * in Aran's database — see {@code IdentityService.soleMembership}. That narrowness is a direct
 * consequence of moving tenancy out of the identity provider: what was going to be an integration
 * that wrote user attributes and mapped realm roles is now four calls.
 *
 * <p>Implemented by the Keycloak Admin API adapter. It exists as a port so the identity provider
 * stays swappable and so the admin UI can be tested without one.
 *
 * <h2>Failures are not recoverable here</h2>
 *
 * <p>Every method throws {@link IdentityProviderException} on failure rather than returning a
 * status. User creation is transactional: the caller provisions the account first and commits
 * Aran's row second, so a failure here must abort the whole operation rather than leave a user who
 * exists in one system and not the other. A user who cannot authenticate has not been created in
 * any useful sense.
 */
@NullMarked
public interface IdentityProviderPort {

    /**
     * Create an account and return its subject — the stable identifier Aran stores on
     * {@link User#subject()} and later authenticates by.
     *
     * @throws IdentityProviderException if the account exists, or the provider is unreachable
     */
    String createUser(String username, String email, String displayName);

    /**
     * The subject of an existing account, or empty if there is none.
     *
     * <p>Exists for the bootstrap, which must be idempotent across two systems: on a dev machine
     * the seeded admin is already in the realm import, on a fresh deployment it is not, and the
     * bootstrap cannot tell which without asking. Creating blindly would fail with "already
     * exists" on every restart of a dev stack.
     */
    java.util.Optional<String> findSubjectByUsername(String username);

    /**
     * The subject of the account registered under this email address, or empty if there is none.
     *
     * <p>Separate from {@link #findSubjectByUsername} because the provider refuses a second account
     * for an address it already holds whatever the username, and a realm-seeded account's
     * username is not its email. Re-adding a removed member (UI-OD9) is decided on this answer:
     * the account exists, so it is reinstated rather than created a second time.
     */
    java.util.Optional<String> findSubjectByEmail(String email);

    /**
     * Set a password the user must change at next login.
     *
     * <p>Temporary is the only mode offered on purpose. This is called when an admin creates an
     * account and when an admin resets a forgotten one, and in both cases somebody other than the
     * account's owner has just chosen the password — so it must not survive the first login.
     */
    void setTemporaryPassword(String subject, String password);

    /**
     * Disable an account without deleting it.
     *
     * <p>Hygiene rather than enforcement: access ends when the Aran membership is removed, which
     * is authoritative. This stops the credential working as well.
     */
    void disableUser(String subject);

    /**
     * Re-enable an account {@link #disableUser} switched off.
     *
     * <p>The reinstatement half of removal (UI-OD9): an owner who removes a member by mistake, or
     * re-hires one, re-adds the same email, and the account comes back with its own credential.
     * Idempotent: enabling an enabled account is not an error.
     */
    void enableUser(String subject);

    /** Remove the account entirely. Idempotent: deleting an unknown subject is not an error. */
    void deleteUser(String subject);

    /** Raised when the identity provider refuses or cannot be reached. */
    class IdentityProviderException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public IdentityProviderException(String message) {
            super(message);
        }

        public IdentityProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * {@link #createUser} was refused because the provider already holds an account with that
     * username or email — most often someone removed from a tenant earlier, whose account is
     * disabled rather than deleted, or a person who belongs to another tenant (V23). A subtype
     * so existing catch sites keep working, and so the web boundary can answer 409 with a
     * sentence instead of a generic 500 (UI audit J7-F2).
     */
    class AccountAlreadyExistsException extends IdentityProviderException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public AccountAlreadyExistsException(String message) {
            super(message);
        }
    }
}

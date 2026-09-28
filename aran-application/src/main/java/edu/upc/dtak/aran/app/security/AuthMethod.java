package edu.upc.dtak.aran.app.security;

import edu.upc.dtak.aran.core.service.ApiTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

import java.util.Locale;

/**
 * How a caller presented a credential — the unit an operator turns on and off.
 *
 * <p>Not "which credential is valid": that is the authentication filters' job and does not change
 * per deployment. This is the <em>presentation</em>, and it is a deployment policy question because
 * the same secret carried two ways has two different risk profiles. An API token in an
 * {@code Authorization: Bearer} header is sent by code. The same token in HTTP Basic is typed into
 * a SQL client's password box, where it lands in that tool's connection profile on disk, its
 * exported settings, and its logs.
 *
 * <p>A site that considers the second unacceptable must be able to refuse it without also losing
 * the first, and without every client having to agree to behave.
 */
@NullMarked
public enum AuthMethod {

    /** A Keycloak-issued JWT. What the browser session uses. */
    OIDC("oidc"),

    /** An {@code aran_pat_…} token in an {@code Authorization: Bearer} header. */
    API_TOKEN_BEARER("api-token-bearer"),

    /**
     * An {@code aran_pat_…} token inside HTTP Basic.
     *
     * <p>The only presentation a JDBC or Postgres-wire client can make: those drivers offer a user
     * and a password, never a header.
     */
    API_TOKEN_BASIC("api-token-basic");

    private static final String BEARER = "Bearer ";
    private static final String BASIC = "Basic ";

    private final String id;

    AuthMethod(String id) {
        this.id = id;
    }

    /** The name used in configuration and on the wire. */
    public String id() {
        return id;
    }

    public static @Nullable AuthMethod fromId(String raw) {
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        for (AuthMethod method : values()) {
            if (method.id.equals(normalised)) {
                return method;
            }
        }
        return null;
    }

    /**
     * How this request presents a credential, or {@code null} if it presents none.
     *
     * <p>Classified from the header's shape alone — no validation happens here, and none should:
     * the policy decision must be made before a credential is checked, or a presentation an
     * operator has forbidden would still reach the verifier that honours it.
     */
    public static @Nullable AuthMethod of(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return null;
        }
        if (header.startsWith(BASIC)) {
            return API_TOKEN_BASIC;
        }
        if (header.startsWith(BEARER)) {
            String value = header.substring(BEARER.length()).trim();
            return value.startsWith(ApiTokenService.TOKEN_PREFIX) ? API_TOKEN_BEARER : OIDC;
        }
        // An unknown scheme is nobody's credential. Left for the chain's entry point to answer,
        // exactly as an absent header is.
        return null;
    }
}

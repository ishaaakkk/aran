package edu.upc.dtak.aran.app.security;

import org.jspecify.annotations.NullMarked;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which credential presentations this deployment accepts, and whether it insists on TLS.
 *
 * <p><b>Server-side policy, enforced once.</b> Every client — the Python SDK, the JDBC endpoint,
 * the Postgres-wire shim when it lands, a generated MCP server, anything a user writes — reaches
 * Aran through the same filter chain, so this is the only place the question is asked and no
 * client can widen it by asking differently.
 *
 * <p>The alternative, which this replaces, was each client bringing its own rules: the Python
 * client refused plain HTTP and the JDBC path did not, because one had guard code and the other
 * used a third-party driver. Policy that depends on which client you happen to be holding is not
 * policy.
 *
 * @param methods    accepted presentations; a request presenting anything else is refused before
 *                   its credential is looked at
 * @param requireTls refuse a credentialled request that did not arrive over TLS. Loopback is
 *                   always exempt — there is no network to intercept — so turning this on does
 *                   not break local development
 */
@NullMarked
public record AuthPolicy(Set<AuthMethod> methods, boolean requireTls) {

    public AuthPolicy {
        methods = Collections.unmodifiableSet(methods.isEmpty()
                ? EnumSet.noneOf(AuthMethod.class) : EnumSet.copyOf(methods));
    }

    /** Everything on, TLS not required — the shape a local single-node install wants. */
    public static AuthPolicy permissive() {
        return new AuthPolicy(EnumSet.allOf(AuthMethod.class), false);
    }

    /**
     * Parse the configured method list.
     *
     * @param raw comma-separated {@link AuthMethod#id() ids}; blank means all of them
     * @throws IllegalArgumentException on an unknown id — a typo in an authentication allowlist
     *                                  must not silently narrow or widen what is accepted, and
     *                                  failing at startup is the only way to be sure it doesn't
     */
    public static AuthPolicy parse(String raw, boolean requireTls) {
        if (raw == null || raw.isBlank()) {
            return new AuthPolicy(EnumSet.allOf(AuthMethod.class), requireTls);
        }
        Set<AuthMethod> parsed = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            AuthMethod method = AuthMethod.fromId(part);
            if (method == null) {
                throw new IllegalArgumentException(
                        "aran.security.auth.methods: unknown authentication method '"
                                + part.trim() + "'. Known methods: "
                                + String.join(", ", ids()));
            }
            parsed.add(method);
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                    "aran.security.auth.methods lists no method, which would refuse every "
                            + "credential and lock the deployment out. Leave it unset to accept all.");
        }
        return new AuthPolicy(parsed, requireTls);
    }

    public boolean allows(AuthMethod method) {
        return methods.contains(method);
    }

    private static java.util.List<String> ids() {
        return EnumSet.allOf(AuthMethod.class).stream().map(AuthMethod::id).toList();
    }

    /** The ids, for the discovery endpoint and for log lines. */
    public java.util.List<String> methodIds() {
        return methods.stream().map(AuthMethod::id).toList();
    }
}

package edu.upc.dtak.aran.app.security;

import org.jspecify.annotations.NullMarked;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Publishes the deployment's {@link AuthPolicy} so a client can tell a user what this server
 * accepts <em>before</em> they type a credential.
 *
 * <p>Without this, "the administrator turned off password-style authentication" and "your token is
 * wrong" are the same 401, and the user's only recourse is to ask someone. A client that can read
 * the policy can say which it is.
 *
 * <p><b>Unauthenticated on purpose.</b> It answers "how may I authenticate", which is the one
 * question that cannot require an authenticated answer, and it discloses no more than an OIDC
 * discovery document does — a list of accepted mechanisms, no secrets, no user or tenant
 * information. It sits on {@code /api/**}, so the pre-auth rate limiter covers it like everything
 * else.
 */
@RestController
@NullMarked
public class AuthPolicyController {

    static final String PATH = "/api/auth/policy";

    /**
     * @param methods    accepted credential presentations
     * @param requireTls whether a credential is refused over an unencrypted connection
     */
    public record AuthPolicyDto(List<String> methods, boolean requireTls) {}

    private final AuthPolicy policy;

    public AuthPolicyController(AuthPolicy policy) {
        this.policy = policy;
    }

    @GetMapping(PATH)
    public AuthPolicyDto policy() {
        return new AuthPolicyDto(policy.methodIds(), policy.requireTls());
    }
}

package edu.upc.dtak.aran.adapter.license.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import edu.upc.dtak.aran.core.domain.LicenseEntitlements;
import edu.upc.dtak.aran.core.domain.LicenseFeature;
import edu.upc.dtak.aran.core.domain.LicensePlan;
import edu.upc.dtak.aran.core.domain.LicenseStatus;
import edu.upc.dtak.aran.core.licensing.LicenseProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Self-hosted licensing.
 *
 * <p>The customer's running instance is not the license authority. The vendor
 * issues a JWS-signed license token offline; the customer drops the token
 * into config (or a future admin UI), and this adapter verifies its signature
 * against the vendor's public key and parses out the entitlements.
 *
 * <p>Provides {@link LicenseProvider} only. There is intentionally no
 * {@code LicenseAdminPort} bean here — the customer's app cannot issue or
 * mutate licenses, only consume them. The platform-admin endpoints that
 * depend on {@code LicenseAdminPort} therefore don't load in self-hosted
 * mode at all (no bean → no controller).
 *
 * <p>The signed token format is a JWS with these claims:
 * <pre>{@code
 * {
 *   "iss": "aran-licensing",
 *   "sub": "<tenantId UUID>",
 *   "plan": "PRO",
 *   "status": "ACTIVE",
 *   "maxUsers": 50,
 *   "maxProjects": 100,
 *   "features": ["AUDIT_LOG_EXPORT"],
 *   "exp": 1893456000          // unix seconds, optional
 * }
 * }</pre>
 *
 * <p>Signature is RS256. The vendor's public key sits at
 * {@code aran.licensing.token.public-key-path} as a standard PEM file.
 *
 * <p>One license token per running instance: in self-hosted mode an instance
 * serves a single tenant. The token's {@code sub} must match the
 * {@code tenantId} the provider is asked about, otherwise the request is
 * treated as "no license on file" (so a token leaked to a different tenant
 * doesn't grant access).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "aran.licensing", name = "mode",
        havingValue = "signed-token")
@NullMarked
public class SignedTokenLicensingConfig {

    @Component
    static class SignedTokenLicenseProvider implements LicenseProvider {

        private static final Logger log = LoggerFactory.getLogger(SignedTokenLicenseProvider.class);

        private final RSAPublicKey publicKey;
        private final String tokenString;
        // Parsed once at startup — the token is immutable for the lifetime of
        // the process; re-verifying its signature on every request would burn
        // CPU for no benefit. A SIGHUP / config reload to swap tokens is the
        // operational answer when a customer renews.
        private final LicenseEntitlements parsed;

        SignedTokenLicenseProvider(
                @Value("${aran.licensing.token.public-key-path}") String publicKeyPath,
                @Value("${aran.licensing.token.value}") String tokenString) {
            this.publicKey = loadPublicKey(Path.of(publicKeyPath));
            this.tokenString = tokenString;
            this.parsed = verifyAndParse(this.tokenString, this.publicKey);
            log.info("self-hosted license loaded: tenant={} plan={} expires={}",
                    parsed.tenantId(), parsed.plan(), parsed.expiresAt());
        }

        @Override
        public Optional<LicenseEntitlements> findFor(UUID tenantId) {
            // Bind the token to the requested tenant. A leaked token issued
            // for tenant A is "no license" if presented to a deployment
            // configured for tenant B.
            return parsed.tenantId().equals(tenantId) ? Optional.of(parsed) : Optional.empty();
        }

        private static RSAPublicKey loadPublicKey(Path pemPath) {
            try {
                String pem = Files.readString(pemPath, StandardCharsets.UTF_8);
                String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", "");
                byte[] decoded = Base64.getDecoder().decode(body);
                X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
                return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
            } catch (IOException | java.security.GeneralSecurityException ex) {
                throw new IllegalStateException(
                        "failed to load license public key from " + pemPath, ex);
            }
        }

        private static LicenseEntitlements verifyAndParse(String tokenString, RSAPublicKey publicKey) {
            try {
                JWSObject jws = JWSObject.parse(tokenString);
                if (!JWSAlgorithm.RS256.equals(jws.getHeader().getAlgorithm())) {
                    throw new IllegalStateException("license token must be signed with RS256");
                }
                if (!jws.verify(new RSASSAVerifier(publicKey))) {
                    throw new IllegalStateException(
                            "license token signature is invalid — token may be forged " +
                            "or signed with the wrong key");
                }
                Map<String, Object> claims = jws.getPayload().toJSONObject();
                return toEntitlements(claims);
            } catch (ParseException | JOSEException ex) {
                throw new IllegalStateException("license token is malformed", ex);
            }
        }

        @SuppressWarnings("unchecked")
        private static LicenseEntitlements toEntitlements(Map<String, Object> claims) {
            UUID tenantId = UUID.fromString((String) claims.get("sub"));
            LicensePlan plan = LicensePlan.valueOf((String) claims.get("plan"));
            LicenseStatus status = LicenseStatus.valueOf((String) claims.get("status"));
            int maxUsers = ((Number) claims.get("maxUsers")).intValue();
            int maxProjects = ((Number) claims.get("maxProjects")).intValue();
            // Optional claims: a token issued before byte ceilings existed carries neither, and
            // must keep meaning "no ceiling" rather than "zero bytes allowed".
            Long maxStorageBytes = optionalLong(claims.get("maxStorageBytes"));
            Long maxMonthlyEgressBytes = optionalLong(claims.get("maxMonthlyEgressBytes"));

            Set<LicenseFeature> features = EnumSet.noneOf(LicenseFeature.class);
            Object rawFeatures = claims.get("features");
            if (rawFeatures instanceof java.util.List<?> list) {
                for (Object f : list) {
                    features.add(LicenseFeature.valueOf((String) f));
                }
            }

            Instant expiresAt = null;
            Object exp = claims.get("exp");
            if (exp instanceof Number n) {
                expiresAt = Instant.ofEpochSecond(n.longValue());
            }

            return new LicenseEntitlements(
                    tenantId, plan, status, maxUsers, maxProjects,
                    maxStorageBytes, maxMonthlyEgressBytes, features, expiresAt);
        }
    }

    /** A claim that may be absent. Absent means unlimited, which is not the same as zero. */
    private static Long optionalLong(Object claim) {
        return claim instanceof Number n ? n.longValue() : null;
    }
}

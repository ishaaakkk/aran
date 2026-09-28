package edu.upc.dtak.aran.adapter.persistence.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA Attribute Converter that encrypts credentials at rest using AES-GCM (128-bit tag, 12-byte IV).
 * Falls back safely to plaintext for legacy values if decryption fails.
 */
@Converter
public class CredentialEncryptionConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionConverter.class);
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_SIZE = 12;
    private static final int TAG_BIT_LENGTH = 128;

    /**
     * Marks a value this class wrote, so a decrypt failure can be attributed.
     *
     * <p>Without it there is no way to tell "wrong key" from "plaintext written before this
     * class existed": both arrive as a string that fails to decrypt, and the only available
     * response is to hand it back and hope. A password of 20 characters drawn from the base64
     * alphabet decodes cleanly and is indistinguishable from ciphertext — that is not a corner
     * case, it is an ordinary password.
     *
     * <p>Costs 5 of the column's 255 characters, which leaves room for a ~150-character secret.
     */
    private static final String PREFIX = "gcm1:";
    
    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialEncryptionConverter() {
        this(resolveKeySource());
    }

    /**
     * Builds a converter over an explicit key.
     *
     * <p>Exists so the round-trip and the wrong-key path are testable. The no-arg constructor
     * reads {@code ARAN_ENCRYPTION_KEY} from the process environment, which a test cannot set —
     * so without this seam the only class that decides whether a credential is readable had no
     * tests at all.
     */
    CredentialEncryptionConverter(String keySource) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(keySource.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize CredentialEncryptionConverter key specification", e);
        }
    }

    /** The configured key, or the development default when a dev/test profile allows it. */
    private static String resolveKeySource() {
        String keySource = System.getenv("ARAN_ENCRYPTION_KEY");
        if (keySource != null && !keySource.isBlank()) {
            return keySource;
        }
        String profile = System.getenv("SPRING_PROFILES_ACTIVE");
        boolean isDev = profile != null && (profile.contains("dev") || profile.contains("test"));
        if (!isDev) {
            throw new IllegalStateException(
                    "ARAN_ENCRYPTION_KEY environment variable must be set in production. " +
                    "Set SPRING_PROFILES_ACTIVE=dev to allow the insecure development default.");
        }
        log.warn("ARAN_ENCRYPTION_KEY not set — using insecure development default. " +
                 "DO NOT deploy this configuration to production.");
        return "aran-development-default-key-32c";
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[IV_SIZE];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_SIZE + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_SIZE);
            System.arraycopy(ciphertext, 0, combined, IV_SIZE, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed for repository credential attribute", e);
            throw new IllegalStateException("Failed to encrypt credential attribute", e);
        }
    }

    /**
     * Decrypts a stored credential, tolerating values written before encryption existed.
     *
     * <p>Two failures used to look identical, and the difference between them matters:
     *
     * <ul>
     *   <li><b>Not ours.</b> Plaintext written before this class existed. Returning it verbatim
     *       is the migration path and is correct.</li>
     *   <li><b>Ours, wrong key.</b> The ciphertext is intact and the tag does not verify — a
     *       rotated, mistyped or missing {@code ARAN_ENCRYPTION_KEY}.</li>
     * </ul>
     *
     * <p>Returning the ciphertext for the second case is the worst available outcome. The caller
     * cannot tell it apart from a password, so it gets used as one: dialled into a connection
     * attempt, reported in a failure message, written to the log. A key problem surfaces as "the
     * database password stopped working", with the ciphertext scattered across everywhere a
     * failed connection is recorded.
     *
     * <p>Telling them apart needs a marker, because the value alone cannot: an ordinary
     * 20-character password from the base64 alphabet decodes as cleanly as ciphertext does. So
     * {@link #PREFIX} decides. A marked value that fails is a key error and throws. An unmarked
     * one keeps the old lenient behaviour, because that is exactly the population whose
     * provenance is unknown — and every value written from now on is marked, so that population
     * only shrinks.
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        boolean marked = dbData.startsWith(PREFIX);
        String payload = marked ? dbData.substring(PREFIX.length()) : dbData;

        try {
            byte[] combined = Base64.getDecoder().decode(payload);
            if (combined.length <= IV_SIZE) {
                if (marked) {
                    throw new IllegalStateException("Stored credential is marked as encrypted but "
                            + "is too short to be — the value has been truncated or altered.");
                }
                return dbData;
            }

            byte[] iv = new byte[IV_SIZE];
            System.arraycopy(combined, 0, iv, 0, IV_SIZE);

            int ciphertextLength = combined.length - IV_SIZE;
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertextLength);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (AEADBadTagException wrongKey) {
            if (marked) {
                log.error("A stored credential could not be decrypted: it is marked as written by "
                        + "this converter, but its authentication tag does not verify. "
                        + "ARAN_ENCRYPTION_KEY has changed or is not the key this row was written "
                        + "with. Restore the original key — the ciphertext is intact.");
                throw new IllegalStateException(
                        "Stored credential could not be decrypted — ARAN_ENCRYPTION_KEY does not "
                        + "match the key this value was encrypted with.", wrongKey);
            }
            // Unmarked: either pre-marker ciphertext under a changed key, or a plaintext secret
            // that happens to be valid base64. Indistinguishable, so keep the lenient behaviour
            // — but say so, because the first case is a key error hiding in a debug line.
            log.warn("An unmarked stored credential failed to decrypt and is being treated as "
                    + "plaintext. If this repository's credential stops working, ARAN_ENCRYPTION_KEY "
                    + "is the thing to check.");
            return dbData;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Value is not in this converter's format; treating as plaintext", e);
            return dbData;
        }
    }
}

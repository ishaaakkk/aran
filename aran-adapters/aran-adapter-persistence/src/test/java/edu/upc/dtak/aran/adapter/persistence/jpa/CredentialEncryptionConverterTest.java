package edu.upc.dtak.aran.adapter.persistence.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * This converter decides whether a stored repository credential is readable, and until now had no
 * tests. The interesting behaviour is not the round trip — it is what happens when the key is
 * wrong, because the answer used to be "hand the ciphertext back and let the caller use it as a
 * password".
 */
class CredentialEncryptionConverterTest {

    private static final String KEY = "a-key-with-plenty-of-entropy-0001";
    private static final String OTHER_KEY = "a-different-key-entirely-000002";

    @Test
    @DisplayName("a credential survives the round trip")
    void roundTrips() {
        CredentialEncryptionConverter converter = new CredentialEncryptionConverter(KEY);
        String stored = converter.convertToDatabaseColumn("s3cr3t-p@ssw0rd");

        assertThat(stored).isNotEqualTo("s3cr3t-p@ssw0rd");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("s3cr3t-p@ssw0rd");
    }

    @Test
    @DisplayName("each write gets a fresh IV, so the same secret does not encrypt to itself")
    void usesAFreshIvPerWrite() {
        CredentialEncryptionConverter converter = new CredentialEncryptionConverter(KEY);

        assertThat(converter.convertToDatabaseColumn("same"))
                .isNotEqualTo(converter.convertToDatabaseColumn("same"));
    }

    @Test
    @DisplayName("the wrong key fails loudly instead of returning the ciphertext as the password")
    void wrongKeyThrows() {
        String stored = new CredentialEncryptionConverter(KEY)
                .convertToDatabaseColumn("s3cr3t-p@ssw0rd");

        // The old behaviour returned `stored` here. The caller cannot tell that apart from a
        // password, so it would be dialled into a connection attempt and written to the log.
        assertThatThrownBy(() -> new CredentialEncryptionConverter(OTHER_KEY)
                .convertToEntityAttribute(stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARAN_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("values written before encryption existed are still read as plaintext")
    void legacyPlaintextStillReadable() {
        CredentialEncryptionConverter converter = new CredentialEncryptionConverter(KEY);

        // Not base64 at all.
        assertThat(converter.convertToEntityAttribute("plain password!"))
                .isEqualTo("plain password!");
        // Base64-shaped but far too short to carry a 12-byte IV and a tag.
        assertThat(converter.convertToEntityAttribute("YWJj")).isEqualTo("YWJj");
    }

    @Test
    @DisplayName("a plaintext password that happens to be valid base64 is not mistaken for ours")
    void legacyPlaintextThatLooksLikeBase64() {
        // This is why the marker exists rather than a heuristic. 20 characters from the base64
        // alphabet decode to 15 bytes — past the IV-length check, straight into a tag failure.
        // Without the marker this would now throw, and a working deployment would stop working
        // on upgrade.
        String looksEncrypted = "SuperSecretPassword1";
        assertThat(looksEncrypted.length() % 4).isZero();

        assertThat(new CredentialEncryptionConverter(KEY).convertToEntityAttribute(looksEncrypted))
                .isEqualTo(looksEncrypted);
    }

    @Test
    @DisplayName("ciphertext written before the marker existed still decrypts")
    void unmarkedCiphertextStillDecrypts() {
        // Rows written by the previous version of this class carry no prefix. Stripping it
        // reproduces exactly what is in those columns.
        CredentialEncryptionConverter converter = new CredentialEncryptionConverter(KEY);
        String unmarked = converter.convertToDatabaseColumn("s3cr3t").substring("gcm1:".length());

        assertThat(converter.convertToEntityAttribute(unmarked)).isEqualTo("s3cr3t");
    }

    @Test
    @DisplayName("new writes carry the marker, so the next wrong key is attributable")
    void newWritesAreMarked() {
        assertThat(new CredentialEncryptionConverter(KEY).convertToDatabaseColumn("s3cr3t"))
                .startsWith("gcm1:");
    }

    @Test
    @DisplayName("null and empty pass through untouched, in both directions")
    void nullAndEmptyPassThrough() {
        CredentialEncryptionConverter converter = new CredentialEncryptionConverter(KEY);

        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn("")).isEmpty();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }
}

package edu.upc.dtak.aran.adapter.persistence.jpa;

import edu.upc.dtak.aran.adapter.persistence.entity.IcebergRestRepositoryEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.ObjectStorageRepositoryEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.RelationalDatabaseRepositoryEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.SingerTapRepositoryEntity;
import jakarta.persistence.Convert;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every column that holds a secret must carry {@link CredentialEncryptionConverter}, and the
 * inventory is the thing worth asserting: audit SOURCE_REGISTRATION-1 (= MIGRATIONS-4, T3.1) was
 * exactly one column missing the annotation while its four siblings had it. A converter test
 * cannot catch that — the converter was working perfectly; nothing called it.
 *
 * <p>Reflection rather than a persistence round trip on purpose: the mapping is what decides
 * whether ciphertext reaches the column, and it is checkable without a database, so this runs in
 * the Docker-free subset. The round trip itself is pinned by
 * {@code DataRepositoryControllerIT} against a real Postgres.
 */
class CredentialColumnsEncryptedAtRestTest {

    private record CredentialField(Class<?> entity, String field) {}

    /**
     * Owning entity + column-bearing field. Add a row here whenever a secret gains a column.
     * A list of pairs rather than a map because one entity can hold two independent credentials —
     * ICEBERG_REST is the first (catalog token + data-plane secret), and a map keyed by class
     * would have silently dropped one of them.
     */
    private static java.util.List<CredentialField> credentialFields() {
        return java.util.List.of(
                new CredentialField(RelationalDatabaseRepositoryEntity.class, "password"),
                new CredentialField(ObjectStorageRepositoryEntity.class, "secretKey"),
                // T6: one JSON object holding whatever the tap declared, secret keys among the rest.
                new CredentialField(SingerTapRepositoryEntity.class, "tapConfig"),
                // Gap 2: two planes, two credentials, each encrypted on its own column.
                new CredentialField(IcebergRestRepositoryEntity.class, "catalogToken"),
                new CredentialField(IcebergRestRepositoryEntity.class, "secretKey"));
    }

    @Test
    @DisplayName("every credential column is mapped through the encryption converter (T3.1)")
    void everyCredentialColumnIsEncrypted() throws Exception {
        for (CredentialField entry : credentialFields()) {
            Field field = entry.entity().getDeclaredField(entry.field());
            Convert convert = field.getAnnotation(Convert.class);

            assertThat(convert)
                    .describedAs("%s.%s must be @Convert(CredentialEncryptionConverter.class) — "
                                    + "without it the secret is stored in the clear",
                            entry.entity().getSimpleName(), entry.field())
                    .isNotNull();
            assertThat(convert.converter()).isEqualTo(CredentialEncryptionConverter.class);
        }
    }
}

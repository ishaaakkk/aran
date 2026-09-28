package edu.upc.dtak.aran.core.domain;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Singer tap repository kind (T6).
 *
 * <p>The invariant worth pinning is the one the rest of the kind rests on: a tap repository
 * without a {@code tapId} names nothing the catalogue could authorise.
 */
class SingerTapRepositoryTest {

    private static SingerTapRepository repo(String tapId, String configJson) {
        return new SingerTapRepository(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "rates", "", tapId, configJson);
    }

    @Test
    @DisplayName("a tap repository is SINGER_TAP")
    void type() {
        assertThat(repo("exchangeratesapi", "{}").type()).isEqualTo(RepositoryType.SINGER_TAP);
    }

    @Test
    @DisplayName("a blank tap id is refused — nothing in the catalogue could authorise it")
    void blankTapIdRefused() {
        assertThatThrownBy(() -> repo("  ", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tap");
    }

    @Test
    @DisplayName("an absent config is the empty object, never null")
    void nullConfigBecomesEmptyObject() {
        assertThat(repo("exchangeratesapi", null).configJson()).isEqualTo("{}");
        assertThat(repo("exchangeratesapi", "  ").configJson()).isEqualTo("{}");
    }
}

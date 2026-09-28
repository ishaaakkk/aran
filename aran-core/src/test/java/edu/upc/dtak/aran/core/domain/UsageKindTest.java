package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.domain.UsageKind.Direction;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partition the admin screen's byte columns are built on.
 *
 * <p>These read as change-detector tests, and that is the intent: the aggregate queries sum
 * {@code byte_count} into three columns purely by direction, so mis-classifying a kind silently
 * moves bytes between "someone uploaded this" and "this left our network", which cost different
 * money. Anyone adding a kind has to decide, and anyone changing one has to say so here.
 */
class UsageKindTest {

    @Test
    @DisplayName("egress is exactly the paths that hand bytes to a client")
    void egressKinds() {
        // ICEBERG joined them in 2026-09: the produce catalog serves a product's whole parquet to
        // an external engine, which is egress by every definition the other two use.
        assertThat(UsageKind.namesWithDirection(Direction.EGRESS))
                .containsExactlyInAnyOrder("EXPORT", "JDBC", "ICEBERG");
    }

    @Test
    @DisplayName("ingress is exactly the upload path")
    void ingressKinds() {
        assertThat(UsageKind.namesWithDirection(Direction.INGRESS))
                .containsExactly("UPLOAD");
    }

    @Test
    @DisplayName("materialization is stored, not moved — it grows the lake, it does not cross the wire")
    void storedKinds() {
        assertThat(UsageKind.namesWithDirection(Direction.STORED))
                .containsExactly("MATERIALIZE");
    }

    @Test
    @DisplayName("a workbench query moves no bytes, so it belongs in no byte column")
    void queryCarriesNoBytes() {
        assertThat(UsageKind.QUERY.direction()).isEqualTo(Direction.NONE);
    }

    @Test
    @DisplayName("every kind is classified into exactly one direction")
    void everyKindIsPartitioned() {
        long classified = Arrays.stream(Direction.values())
                .mapToLong(d -> UsageKind.namesWithDirection(d).size())
                .sum();
        assertThat(classified)
                .describedAs("each kind must appear under one direction and no other")
                .isEqualTo(UsageKind.values().length);
    }
}

package edu.upc.dtak.aran.adapter.persistence.mapper;

import edu.upc.dtak.aran.adapter.persistence.entity.ColumnProfileEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.DatasetEntity;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V31's {@code subject}/{@code class_iri} discriminator has to survive {@code EntityMapper}'s
 * hand-written, field-by-field copy in both directions — a mapper that forgets one of the two new
 * columns would compile fine (both are plain strings) and only show up as silently-wrong data.
 */
class EntityMapperColumnProfileTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static DatasetEntity dataset() {
        return new DatasetEntity(UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, "orders", "", null);
    }

    private static ColumnProfile snapshotProfile() {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), "col", 0, "VARCHAR",
                100L, null, 0.5, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    @Test
    @DisplayName("a WRAPPER_OUTPUT profile's subject and class IRI survive toEntity -> toDomain")
    void wrapperOutputProfileRoundTripsThroughTheEntity() {
        ColumnProfile original = new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), "total", 2, "DOUBLE",
                50L, 1.2, 0.9, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of("1", "2"), List.of("A", "B"),
                "WRAPPER_OUTPUT", "http://ex/Paper");

        DatasetEntity dataset = dataset();
        ColumnProfileEntity entity = EntityMapper.toEntity(original, dataset, TENANT_ID);

        assertThat(entity.getSubject()).isEqualTo("WRAPPER_OUTPUT");
        assertThat(entity.getClassIri()).isEqualTo("http://ex/Paper");

        ColumnProfile roundTripped = EntityMapper.toDomain(entity);

        assertThat(roundTripped.subject()).isEqualTo("WRAPPER_OUTPUT");
        assertThat(roundTripped.classIri()).isEqualTo("http://ex/Paper");
    }

    @Test
    @DisplayName("the value-hash sample survives toEntity -> toDomain, in order")
    void valueHashSampleRoundTripsThroughTheEntity() {
        List<String> sample = List.of("00411460f7c92d21", "006f52e9102a8d3b", "00ac8ed3b4327bdd");
        ColumnProfile original = new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), "id", 0, "VARCHAR",
                1000L, null, 1.0, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of("1", "10"), List.of(),
                ColumnProfile.SUBJECT_SNAPSHOT, null,
                sample);

        ColumnProfileEntity entity = EntityMapper.toEntity(original, dataset(), TENANT_ID);

        assertThat(EntityMapper.toDomain(entity).valueHashSample()).isEqualTo(sample);
    }

    @Test
    @DisplayName("a profile without a sample stores NULL and reads back as no sample, not as one empty hash")
    void aMissingValueHashSampleIsNullInTheEntity() {
        ColumnProfileEntity entity = EntityMapper.toEntity(snapshotProfile(), dataset(), TENANT_ID);

        assertThat(entity.getValueHashSample()).isNull();
        assertThat(EntityMapper.toDomain(entity).valueHashSample()).isEmpty();
    }

    @Test
    @DisplayName("a legacy-constructed (pre-3B) profile maps to the SNAPSHOT/null defaults, not to nulls the column forbids")
    void legacyProfileMapsToSnapshotDefaultsThroughTheEntity() {
        ColumnProfile original = snapshotProfile();

        ColumnProfileEntity entity = EntityMapper.toEntity(original, dataset(), TENANT_ID);

        assertThat(entity.getSubject())
                .as("column_profile.subject is NOT NULL; the entity must never carry a null here")
                .isEqualTo("SNAPSHOT");
        assertThat(entity.getClassIri()).isNull();

        ColumnProfile roundTripped = EntityMapper.toDomain(entity);
        assertThat(roundTripped.subject()).isEqualTo("SNAPSHOT");
        assertThat(roundTripped.classIri()).isNull();
    }
}

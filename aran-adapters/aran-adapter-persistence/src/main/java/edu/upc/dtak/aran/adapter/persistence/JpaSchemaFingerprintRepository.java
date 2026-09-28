package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.DatasetSchemaFingerprintEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.DatasetSchemaFingerprintJpaRepository;
import edu.upc.dtak.aran.core.domain.drift.SchemaFingerprintRepository;
import edu.upc.dtak.aran.core.domain.drift.StoredSchemaFingerprint;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * JPA-backed implementation of {@link SchemaFingerprintRepository} (V22).
 *
 * <p>Tenant scoping is explicit in every predicate, matching the other adapters in this package.
 */
@Component
@NullMarked
class JpaSchemaFingerprintRepository implements SchemaFingerprintRepository {

    private final DatasetSchemaFingerprintJpaRepository fingerprints;

    JpaSchemaFingerprintRepository(DatasetSchemaFingerprintJpaRepository fingerprints) {
        this.fingerprints = fingerprints;
    }

    @Override
    public Optional<StoredSchemaFingerprint> findLatest(TenantContext tenant, UUID datasetId,
                                                        StoredSchemaFingerprint.CaptureMethod via) {
        return fingerprints
                .findFirstByTenantIdAndDatasetIdAndCapturedViaOrderByCapturedAtDesc(
                        tenantId(tenant), datasetId, via.name())
                .map(JpaSchemaFingerprintRepository::toDomain);
    }

    @Override
    public StoredSchemaFingerprint save(TenantContext tenant, StoredSchemaFingerprint fingerprint) {
        DatasetSchemaFingerprintEntity entity = new DatasetSchemaFingerprintEntity(
                fingerprint.id(),
                tenantId(tenant),
                fingerprint.datasetId(),
                fingerprint.hash(),
                fingerprint.fingerprintJson(),
                fingerprint.snapshotId(),
                fingerprint.capturedVia().name(),
                fingerprint.capturedAt());
        return toDomain(fingerprints.save(entity));
    }

    @Override
    public List<StoredSchemaFingerprint> history(TenantContext tenant, UUID datasetId, int limit) {
        return fingerprints
                .findAllByTenantIdAndDatasetIdOrderByCapturedAtDesc(
                        tenantId(tenant), datasetId, Limit.of(limit))
                .stream().map(JpaSchemaFingerprintRepository::toDomain).toList();
    }

    private static StoredSchemaFingerprint toDomain(DatasetSchemaFingerprintEntity e) {
        return new StoredSchemaFingerprint(
                e.getId(), e.getTenantId(), e.getDatasetId(), e.getFingerprintHash(),
                e.getFingerprint(), e.getSnapshotId(),
                StoredSchemaFingerprint.CaptureMethod.valueOf(e.getCapturedVia()),
                e.getCapturedAt());
    }

    private static UUID tenantId(TenantContext tenant) {
        return UUID.fromString(tenant.value());
    }
}

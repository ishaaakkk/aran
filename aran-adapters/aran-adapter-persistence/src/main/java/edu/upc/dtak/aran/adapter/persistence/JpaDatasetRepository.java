package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.adapter.persistence.entity.ColumnProfileEntity;
import edu.upc.dtak.aran.adapter.persistence.entity.DatasetEntity;
import edu.upc.dtak.aran.adapter.persistence.jpa.JpaRepositories.DatasetJpaRepository;
import edu.upc.dtak.aran.adapter.persistence.mapper.EntityMapper;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed implementation of the core {@link DatasetRepository} port.
 *
 * <p>Every query here names {@code tenant_id} in its own predicate; the shared-schema
 * {@code tenantFilter} enabled by {@code TenantFilterAspect} is defence in depth behind it.
 * Two independent reasons, both load-bearing: Hibernate never applies a {@code @Filter} to a
 * load by primary key ({@code CrossTenantByIdIT}), and the aspect cannot enable the filter at
 * all outside a transaction — audit AUTH_IDENTITY-3, pinned by
 * {@code CrossTenantListWithoutTransactionIT}.
 */
@Component
@NullMarked
class JpaDatasetRepository implements DatasetRepository {

    private final DatasetJpaRepository repositoryJpa;

    JpaDatasetRepository(DatasetJpaRepository repositoryJpa) {
        this.repositoryJpa = repositoryJpa;
    }

    @Override
    public List<Dataset> findAllByRepository(TenantContext tenant, UUID repositoryId) {
        return repositoryJpa
                .findAllByRepositoryIdAndTenantId(repositoryId, UUID.fromString(tenant.value()))
                .stream()
                .map(EntityMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Dataset> findById(TenantContext tenant, UUID datasetId) {
        return repositoryJpa.findByIdAndTenantId(datasetId, UUID.fromString(tenant.value()))
                .map(EntityMapper::toDomain);
    }

    /**
     * Replace a dataset's row and its whole column-profile collection.
     *
     * <p><b>The flush between the clear and the re-add is the fix for V40/V44, not tidiness.</b>
     * Hibernate orders a collection's re-INSERTs BEFORE its orphan-removal DELETEs within one
     * flush, so clearing the profiles and adding a fresh generation under the same natural key
     * — which every re-profile and every re-harvest does, with a new row id each time — asks the
     * database to insert a duplicate before the original is gone. Live that surfaced as {@code
     * duplicate key value violates unique constraint}: every compile after the first silently kept
     * the first pass's numbers (V40), and enrolling a second table of a relational repository
     * answered HTTP 500 (V44). Flushing the empty collection first turns the sequence into
     * DELETE-then-INSERT, which is what the code always meant.
     *
     * <p>{@code @Transactional} keeps both halves atomic for callers that have no transaction of
     * their own — {@code ProfilingOperator} persists from a job worker thread, and without it the
     * intermediate flush would commit a dataset with no profiles at all if the re-add then failed.
     * Callers that already have one (every {@code DatasetService} method) simply join it.
     */
    @Override
    @Transactional
    public Dataset save(TenantContext tenant, Dataset dataset) {
        // One transaction for the whole find → clear → flush → re-add sequence (V40,
        // audit PROFILING-1). Callers that already run in a transaction join it; callers that
        // don't — the operator invoke path has none — previously executed each repository call
        // in its OWN transaction over a DETACHED entity, where the mid-method flush turns into
        // a merge that deletes the old rows and the follow-up save's merge then fails with
        // ObjectRetrievalFailureException. Managed-entity semantics need a single transaction.
        UUID tenantId = UUID.fromString(tenant.value());
        Optional<DatasetEntity> existingOpt = repositoryJpa.findByIdAndTenantId(dataset.id(), tenantId);
        if (existingOpt.isPresent()) {
            DatasetEntity existing = existingOpt.get();
            existing.setName(dataset.name());
            existing.setDescription(dataset.description());
            existing.setSnapshotId(dataset.snapshotId());
            existing.setGraphJson(dataset.graphJson());
            existing.setBootstrapStatus(dataset.bootstrapStatus());

            // Clear and rebuild columns to let Hibernate execute orphan removal cleanly.
            // The flush between clear and re-add is required (V40, audit
            // PROFILING-1): Hibernate orders a flush's INSERTs before its orphan-removal
            // DELETEs, so re-writing a profile row with the same (dataset, subject, class_iri,
            // column_name) identity — every re-profile does exactly that — collided with
            // uq_column_profile_identity while the old row was still on its way out. Flushing
            // the deletes first makes replace-a-generation actually a replace.
            existing.getColumns().clear();
            repositoryJpa.saveAndFlush(existing);
            if (dataset.columns() != null) {
                List<ColumnProfileEntity> newCols = dataset.columns().stream()
                        .map(cp -> EntityMapper.toEntity(cp, existing, tenantId))
                        .toList();
                existing.getColumns().addAll(newCols);
            }
            return EntityMapper.toDomain(repositoryJpa.save(existing));
        } else {
            return EntityMapper.toDomain(repositoryJpa.save(EntityMapper.toEntity(dataset, tenantId)));
        }
    }

    @Override
    public void delete(TenantContext tenant, UUID datasetId) {
        // Resolve inside the tenant first: deleteById(id) would happily delete
        // another tenant's dataset.
        repositoryJpa.findByIdAndTenantId(datasetId, UUID.fromString(tenant.value()))
                .ifPresent(repositoryJpa::delete);
    }
}

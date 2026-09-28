package edu.upc.dtak.aran.adapter.persistence.mapper;

import edu.upc.dtak.aran.adapter.persistence.entity.*;
import edu.upc.dtak.aran.core.domain.*;
import org.jspecify.annotations.NullMarked;
import java.util.UUID;

/**
 * Translates between the adapter's JPA {@code *Entity} types and the core's
 * domain records.
 *
 * <p>This mapper is the reason the core can stay free of JPA: nothing outside
 * this package ever holds an {@code @Entity} instance. Pure functions, no
 * state — kept as static methods deliberately.
 */
@NullMarked
public final class EntityMapper {

    private EntityMapper() {
    }

    // --- User ---------------------------------------------------------------

    public static User toDomain(UserEntity e) {
        return new User(e.getId(), e.getSubject(), e.getEmail(), e.getDisplayName(), e.getCreatedAt());
    }

    public static UserEntity toEntity(User u) {
        return new UserEntity(u.id(), u.subject(), u.email(), u.displayName(), u.createdAt());
    }

    // --- Tenant -------------------------------------------------------------

    public static Tenant toDomain(TenantEntity e) {
        return new Tenant(e.getId(), e.getSlug(), e.getName(), e.getCreatedAt());
    }

    public static TenantEntity toEntity(Tenant t) {
        return new TenantEntity(t.id(), t.slug(), t.name(), t.createdAt());
    }

    // --- Membership ---------------------------------------------------------

    public static Membership toDomain(MembershipEntity e) {
        return new Membership(e.getId(), e.getUserId(), e.getTenantId(), e.getRole(), e.getJoinedAt());
    }

    public static MembershipEntity toEntity(Membership m) {
        return new MembershipEntity(m.id(), m.userId(), m.tenantId(), m.role(), m.joinedAt());
    }

    // --- Project ------------------------------------------------------------

    public static Project toDomain(ProjectEntity e) {
        return new Project(e.getId(), e.getTenantId(), e.getName(), e.getDescription(),
                e.getCreatedBy(), e.getCreatedAt(), e.getColor(), e.getPrivacy(),
                e.getStatus(), e.getProgress());
    }

    public static ProjectEntity toEntity(Project p, UUID tenantId) {
        if (!p.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Project tenant ID does not match the transactional tenant context");
        }
        return new ProjectEntity(p.id(), tenantId, p.name(), p.description(),
                p.createdBy(), p.createdAt(), p.color(), p.privacy(),
                p.status(), p.progress());
    }

    // --- ProjectMember ------------------------------------------------------

    public static ProjectMember toDomain(ProjectMemberEntity e) {
        return new ProjectMember(e.getId(), e.getProjectId(), e.getUserId(), e.getAddedAt());
    }

    public static ProjectMemberEntity toEntity(ProjectMember pm) {
        return new ProjectMemberEntity(pm.id(), pm.projectId(), pm.userId(), pm.addedAt());
    }

    // --- DataRepository -----------------------------------------------------

    public static DataRepository toDomain(DataRepositoryEntity e) {
        if (e instanceof RelationalDatabaseRepositoryEntity relational) {
            return new RelationalDatabaseRepository(
                    relational.getId(),
                    relational.getProjectId(),
                    relational.getTenantId(),
                    relational.getName(),
                    relational.getDescription() != null ? relational.getDescription() : "",
                    relational.getEngine(),
                    relational.getHost(),
                    relational.getPort() != null ? relational.getPort() : 0,
                    relational.getDatabase(),
                    relational.getUsername() != null ? relational.getUsername() : "",
                    relational.getPassword() != null ? relational.getPassword() : "",
                    relational.getSchema() != null ? relational.getSchema() : "",
                    relational.isFederated()
            );
        } else if (e instanceof ObjectStorageRepositoryEntity objectStorage) {
            return new ObjectStorageRepository(
                    objectStorage.getId(),
                    objectStorage.getProjectId(),
                    objectStorage.getTenantId(),
                    objectStorage.getName(),
                    objectStorage.getDescription() != null ? objectStorage.getDescription() : "",
                    objectStorage.getProvider(),
                    objectStorage.getEndpoint() != null ? objectStorage.getEndpoint() : "",
                    objectStorage.getBucketName(),
                    objectStorage.getAccessKey() != null ? objectStorage.getAccessKey() : "",
                    objectStorage.getSecretKey() != null ? objectStorage.getSecretKey() : "",
                    objectStorage.getRegion() != null ? objectStorage.getRegion() : ""
            );
        } else if (e instanceof FileSystemRepositoryEntity fileSystem) {
            return new FileSystemRepository(
                    fileSystem.getId(),
                    fileSystem.getProjectId(),
                    fileSystem.getTenantId(),
                    fileSystem.getName(),
                    fileSystem.getDescription() != null ? fileSystem.getDescription() : ""
            );
        } else if (e instanceof SingerTapRepositoryEntity tap) {
            return new SingerTapRepository(
                    tap.getId(),
                    tap.getProjectId(),
                    tap.getTenantId(),
                    tap.getName(),
                    tap.getDescription() != null ? tap.getDescription() : "",
                    tap.getTapId(),
                    // The record normalises a null/blank config to "{}" itself.
                    tap.getTapConfig()
            );
        } else if (e instanceof IcebergRestRepositoryEntity iceberg) {
            return new IcebergRestRepository(
                    iceberg.getId(),
                    iceberg.getProjectId(),
                    iceberg.getTenantId(),
                    iceberg.getName(),
                    iceberg.getDescription() != null ? iceberg.getDescription() : "",
                    iceberg.getCatalogUri(),
                    iceberg.getWarehouse() != null ? iceberg.getWarehouse() : "",
                    iceberg.getCatalogToken() != null ? iceberg.getCatalogToken() : "",
                    iceberg.getProvider() != null ? iceberg.getProvider() : "S3",
                    iceberg.getEndpoint() != null ? iceberg.getEndpoint() : "",
                    iceberg.getAccessKey() != null ? iceberg.getAccessKey() : "",
                    iceberg.getSecretKey() != null ? iceberg.getSecretKey() : "",
                    iceberg.getRegion() != null ? iceberg.getRegion() : ""
            );
        }
        throw new IllegalArgumentException("Unknown repository entity type: " + e.getClass());
    }

    public static DataRepositoryEntity toEntity(DataRepository r, UUID tenantId) {
        if (!r.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Repository tenant ID does not match the transactional tenant context");
        }
        return switch (r) {
            case RelationalDatabaseRepository relational -> new RelationalDatabaseRepositoryEntity(
                    relational.id(),
                    relational.projectId(),
                    tenantId,
                    relational.name(),
                    relational.description(),
                    relational.engine(),
                    relational.host(),
                    relational.port(),
                    relational.database(),
                    relational.username(),
                    relational.password(),
                    relational.schema(),
                    relational.federated()
            );
            case ObjectStorageRepository objectStorage -> new ObjectStorageRepositoryEntity(
                    objectStorage.id(),
                    objectStorage.projectId(),
                    tenantId,
                    objectStorage.name(),
                    objectStorage.description(),
                    objectStorage.provider(),
                    objectStorage.endpoint(),
                    objectStorage.bucketName(),
                    objectStorage.accessKey(),
                    objectStorage.secretKey(),
                    objectStorage.region()
            );
            case FileSystemRepository fileSystem -> new FileSystemRepositoryEntity(
                    fileSystem.id(),
                    fileSystem.projectId(),
                    tenantId,
                    fileSystem.name(),
                    fileSystem.description()
            );
            case SingerTapRepository tap -> new SingerTapRepositoryEntity(
                    tap.id(),
                    tap.projectId(),
                    tenantId,
                    tap.name(),
                    tap.description(),
                    tap.tapId(),
                    tap.configJson()
            );
            case IcebergRestRepository iceberg -> new IcebergRestRepositoryEntity(
                    iceberg.id(),
                    iceberg.projectId(),
                    tenantId,
                    iceberg.name(),
                    iceberg.description(),
                    iceberg.catalogUri(),
                    iceberg.warehouse(),
                    iceberg.token(),
                    iceberg.provider(),
                    iceberg.endpoint(),
                    iceberg.accessKey(),
                    iceberg.secretKey(),
                    iceberg.region()
            );
        };
    }

    // --- Dataset ------------------------------------------------------------

    public static Dataset toDomain(DatasetEntity e) {
        java.util.List<ColumnProfile> columns = e.getColumns() != null
                ? e.getColumns().stream().map(EntityMapper::toDomain).toList()
                : java.util.List.of();
        return new Dataset(e.getId(), e.getRepositoryId(), e.getName(), e.getDescription() != null ? e.getDescription() : "", e.getSnapshotId(), columns, e.getGraphJson(), e.getBootstrapStatus());
    }

    public static DatasetEntity toEntity(Dataset d, UUID tenantId) {
        DatasetEntity datasetEntity = new DatasetEntity(d.id(), d.repositoryId(), tenantId, d.name(), d.description(), d.snapshotId());
        datasetEntity.setGraphJson(d.graphJson());
        datasetEntity.setBootstrapStatus(d.bootstrapStatus());
        if (d.columns() != null) {
            java.util.List<ColumnProfileEntity> columns = d.columns().stream()
                    .map(cp -> EntityMapper.toEntity(cp, datasetEntity, tenantId))
                    .toList();
            datasetEntity.getColumns().addAll(columns);
        }
        return datasetEntity;
    }

    // --- ColumnProfile -------------------------------------------------------

    public static ColumnProfile toDomain(ColumnProfileEntity e) {
        java.util.List<String> commonValues = e.getCommonValues() == null || e.getCommonValues().isBlank()
                ? java.util.List.of()
                : java.util.Arrays.asList(e.getCommonValues().split(","));
        java.util.List<String> commonValuesSoundex = e.getCommonValuesSoundex() == null || e.getCommonValuesSoundex().isBlank()
                ? java.util.List.of()
                : java.util.Arrays.asList(e.getCommonValuesSoundex().split(","));
        java.util.List<String> valueHashSample = e.getValueHashSample() == null || e.getValueHashSample().isBlank()
                ? java.util.List.of()
                : java.util.Arrays.asList(e.getValueHashSample().split(","));

        return new ColumnProfile(
                e.getId(),
                e.getDataset().getId(),
                e.getColumnName(),
                e.getColumnIndex(),
                e.getSourceType(),
                e.getCardinality(),
                e.getEntropy(),
                e.getUniqueness(),
                e.getIncompleteness(),
                e.getFrequencyAvg(),
                e.getFrequencyMin(),
                e.getFrequencyMax(),
                e.getFrequencySd(),
                e.getFrequencyIqr(),
                e.getValPctMin(),
                e.getValPctMax(),
                e.getValPctStd(),
                e.getConstancy(),
                e.getLenMaxWord(),
                e.getLenMinWord(),
                e.getLenAvgWord(),
                e.getNumberWords(),
                e.getWordsCntMax(),
                e.getWordsCntMin(),
                e.getWordsCntAvg(),
                e.getWordsCntSd(),
                e.getFirstWord(),
                e.getLastWord(),
                e.isEmpty(),
                e.isBinary(),
                commonValues,
                commonValuesSoundex,
                e.getSubject(),
                e.getClassIri(),
                valueHashSample
        );
    }

    public static ColumnProfileEntity toEntity(ColumnProfile cp, DatasetEntity dataset, UUID tenantId) {
        String commonValues = String.join(",", cp.commonValues());
        String commonValuesSoundex = String.join(",", cp.commonValuesSoundex());

        return new ColumnProfileEntity(
                cp.id(),
                dataset,
                tenantId,
                cp.columnName(),
                cp.columnIndex(),
                cp.sourceType(),
                cp.cardinality(),
                cp.entropy(),
                cp.uniqueness(),
                cp.incompleteness(),
                cp.frequencyAvg(),
                cp.frequencyMin(),
                cp.frequencyMax(),
                cp.frequencySd(),
                cp.frequencyIqr(),
                cp.valPctMin(),
                cp.valPctMax(),
                cp.valPctStd(),
                cp.constancy(),
                cp.lenMaxWord(),
                cp.lenMinWord(),
                cp.lenAvgWord(),
                cp.numberWords(),
                cp.wordsCntMax(),
                cp.wordsCntMin(),
                cp.wordsCntAvg(),
                cp.wordsCntSd(),
                cp.firstWord(),
                cp.lastWord(),
                cp.isEmpty(),
                cp.isBinary(),
                commonValues,
                commonValuesSoundex,
                cp.subject(),
                cp.classIri(),
                cp.valueHashSample().isEmpty() ? null : String.join(",", cp.valueHashSample())
        );
    }

    // --- GraphVersion -------------------------------------------------------

    public static GraphVersion toDomain(TenantGraphVersionEntity e) {
        return new GraphVersion(
                e.getId(),
                e.getTenantId(),
                e.getVersionNumber(),
                e.getStatus(),
                e.getCreatedAt(),
                e.getCreatedBy(),
                e.getPromotedAt(),
                e.getNotes()
        );
    }

    public static TenantGraphVersionEntity toEntity(GraphVersion v, UUID tenantId) {
        if (!v.tenantId().equals(tenantId)) {
            // Name both sides: this guard fires on a genuine cross-tenant write
            // attempt, and without the ids there is no way to tell whose version
            // reached whose transaction — which is the only interesting question.
            throw new IllegalArgumentException(
                    "GraphVersion tenant ID does not match the transactional tenant context: version "
                            + v.id() + " belongs to tenant " + v.tenantId()
                            + " but the transaction is bound to tenant " + tenantId);
        }
        return new TenantGraphVersionEntity(
                v.id(),
                tenantId,
                v.versionNumber(),
                v.status(),
                v.createdAt(),
                v.createdBy(),
                v.promotedAt(),
                v.notes()
        );
    }

    // --- DeclaredForeignKey (V33, Phase 4B) ----------------------------------

    public static DeclaredForeignKey toDomain(DeclaredForeignKeyEntity e) {
        return new DeclaredForeignKey(
                e.getId(), e.getTenantId(), e.getRepositoryId(),
                e.getFromDatasetId(), e.getFromCols(),
                e.getToDatasetId(), e.getToCols());
    }

    public static DeclaredForeignKeyEntity toEntity(DeclaredForeignKey fk) {
        return new DeclaredForeignKeyEntity(
                fk.id(), fk.tenantId(), fk.repositoryId(),
                fk.fromDatasetId(), new java.util.ArrayList<>(fk.fromCols()),
                fk.toDatasetId(), new java.util.ArrayList<>(fk.toCols()));
    }
}

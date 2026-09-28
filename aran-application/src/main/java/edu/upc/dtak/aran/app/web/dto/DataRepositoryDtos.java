package edu.upc.dtak.aran.app.web.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import edu.upc.dtak.aran.core.domain.*;
import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class DataRepositoryDtos {

    private DataRepositoryDtos() {
    }

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.PROPERTY,
            property = "type"
    )
    @JsonSubTypes({
            @JsonSubTypes.Type(value = RelationalDatabaseRepositoryRequest.class, name = "RELATIONAL_DB"),
            @JsonSubTypes.Type(value = ObjectStorageRepositoryRequest.class, name = "OBJECT_STORAGE"),
            @JsonSubTypes.Type(value = FileSystemRepositoryRequest.class, name = "FILE_SYSTEM"),
            @JsonSubTypes.Type(value = SingerTapRepositoryRequest.class, name = "SINGER_TAP"),
            @JsonSubTypes.Type(value = IcebergRestRepositoryRequest.class, name = "ICEBERG_REST")
    })
    public sealed interface DataRepositoryRequest permits
            RelationalDatabaseRepositoryRequest,
            ObjectStorageRepositoryRequest,
            FileSystemRepositoryRequest,
            SingerTapRepositoryRequest,
            IcebergRestRepositoryRequest {

        String name();
        String description();

        DataRepository toDomain(UUID id, UUID projectId, UUID tenantId);
    }

    public record RelationalDatabaseRepositoryRequest(
            @NotBlank String name,
            String description,
            @NotBlank String engine,
            @NotBlank String host,
            @NotNull Integer port,
            @NotBlank String database,
            String username,
            String password,
            String schema,
            // Boxed so a request without the field means "not federated" instead of a
            // Jackson 500 (FAIL_ON_NULL_FOR_PRIMITIVES) — pre-V17 clients omit it.
            Boolean federated
    ) implements DataRepositoryRequest {
        @Override
        public DataRepository toDomain(UUID id, UUID projectId, UUID tenantId) {
            return new RelationalDatabaseRepository(
                    id, projectId, tenantId, name,
                    description != null ? description : "",
                    engine, host, port, database,
                    username != null ? username : "",
                    password != null ? password : "",
                    schema != null ? schema : "",
                    federated != null && federated
            );
        }
    }

    public record ObjectStorageRepositoryRequest(
            @NotBlank String name,
            String description,
            @NotBlank String provider,
            String endpoint,
            @NotBlank String bucketName,
            String accessKey,
            String secretKey,
            String region
    ) implements DataRepositoryRequest {
        @Override
        public DataRepository toDomain(UUID id, UUID projectId, UUID tenantId) {
            return new ObjectStorageRepository(
                    id, projectId, tenantId, name,
                    description != null ? description : "",
                    provider,
                    endpoint != null ? endpoint : "",
                    bucketName,
                    accessKey != null ? accessKey : "",
                    secretKey != null ? secretKey : "",
                    region != null ? region : ""
            );
        }
    }

    /**
     * Upload-only (D1): a name and nothing to reach. A body that still carries the old
     * {@code rootPath} is accepted and the field ignored — there is no directory behind this kind.
     */
    public record FileSystemRepositoryRequest(
            @NotBlank String name,
            String description
    ) implements DataRepositoryRequest {
        @Override
        public DataRepository toDomain(UUID id, UUID projectId, UUID tenantId) {
            return new FileSystemRepository(
                    id, projectId, tenantId, name,
                    description != null ? description : ""
            );
        }
    }

    /**
     * A repository reached by running a Singer tap (T6).
     *
     * @param tapId  an id the deployment's {@code SingerTapCatalog} declares. Only an id ever
     *               crosses the wire — the command is configuration, and a request that could name
     *               it would be remote code execution as the backend
     * @param config the tap's settings. Values of secret keys come back masked; on an update, a
     *               masked value means "keep the stored one", per key (the T3.1 sentinel, applied
     *               entry by entry because one map holds both public and secret settings)
     */
    public record SingerTapRepositoryRequest(
            @NotBlank String name,
            String description,
            @NotBlank String tapId,
            java.util.Map<String, String> config
    ) implements DataRepositoryRequest {
        @Override
        public DataRepository toDomain(UUID id, UUID projectId, UUID tenantId) {
            return new SingerTapRepository(
                    id, projectId, tenantId, name,
                    description != null ? description : "",
                    tapId,
                    edu.upc.dtak.aran.core.source.TapConfig.toJson(config)
            );
        }
    }

    /**
     * An Iceberg REST catalog (Gap 2). Two credentials, masked independently: {@code token}
     * authenticates the catalog (control plane), {@code secretKey} the object store its metadata
     * points at (data plane). Either may legitimately be blank — a public catalog, a public store.
     */
    public record IcebergRestRepositoryRequest(
            @NotBlank String name,
            String description,
            @NotBlank String catalogUri,
            String warehouse,
            String token,
            String provider,
            String endpoint,
            String accessKey,
            String secretKey,
            String region
    ) implements DataRepositoryRequest {
        @Override
        public DataRepository toDomain(UUID id, UUID projectId, UUID tenantId) {
            return new IcebergRestRepository(
                    id, projectId, tenantId, name,
                    description != null ? description : "",
                    catalogUri,
                    warehouse != null ? warehouse : "",
                    token != null ? token : "",
                    provider != null && !provider.isBlank() ? provider : "S3",
                    endpoint != null ? endpoint : "",
                    accessKey != null ? accessKey : "",
                    secretKey != null ? secretKey : "",
                    region != null ? region : ""
            );
        }
    }

    // --- Response DTOs ------------------------------------------------------

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.PROPERTY,
            property = "type"
    )
    @JsonSubTypes({
            @JsonSubTypes.Type(value = RelationalDatabaseRepositoryResponse.class, name = "RELATIONAL_DB"),
            @JsonSubTypes.Type(value = ObjectStorageRepositoryResponse.class, name = "OBJECT_STORAGE"),
            @JsonSubTypes.Type(value = FileSystemRepositoryResponse.class, name = "FILE_SYSTEM"),
            @JsonSubTypes.Type(value = SingerTapRepositoryResponse.class, name = "SINGER_TAP"),
            @JsonSubTypes.Type(value = IcebergRestRepositoryResponse.class, name = "ICEBERG_REST")
    })
    public sealed interface DataRepositoryResponse permits
            RelationalDatabaseRepositoryResponse,
            ObjectStorageRepositoryResponse,
            FileSystemRepositoryResponse,
            SingerTapRepositoryResponse,
            IcebergRestRepositoryResponse {

        UUID id();
        UUID projectId();
        UUID tenantId();
        String name();
        String description();

        /**
         * The last connection test someone ran against this repository as stored, or null when
         * none has run since it was registered or last saved (V46, UI audit J2-F19). Listing never
         * dials a source: this is what the repositories list shows instead.
         */
        @Nullable ConnectionCheckDto lastConnectionCheck();

        /**
         * The masked view, for every kind but {@code SINGER_TAP}.
         *
         * <p>A tap's settings need the catalogue to know which keys are secret, so this overload
         * masks <em>all</em> of them — fail closed. Callers that hold the catalogue should use
         * {@link #from(DataRepository, SingerTapCatalog)} instead, and the controller does.
         */
        static DataRepositoryResponse from(DataRepository r) {
            return from(r, SingerTapCatalog.empty());
        }

        static DataRepositoryResponse from(DataRepository r, SingerTapCatalog taps) {
            return from(r, taps, null);
        }

        static DataRepositoryResponse from(DataRepository r, SingerTapCatalog taps,
                                           @Nullable ConnectionCheck lastCheck) {
            ConnectionCheckDto check = lastCheck == null ? null : ConnectionCheckDto.from(lastCheck);
            return switch (r) {
                case RelationalDatabaseRepository relational -> new RelationalDatabaseRepositoryResponse(
                        relational.id(), relational.projectId(), relational.tenantId(),
                        relational.name(), relational.description(),
                        relational.engine(), relational.host(), relational.port(),
                        relational.database(), relational.username(),
                        (relational.password() == null || relational.password().isEmpty()) ? "" : "********",
                        relational.schema(),
                        relational.federated(),
                        check
                );
                case ObjectStorageRepository objectStorage -> new ObjectStorageRepositoryResponse(
                        objectStorage.id(), objectStorage.projectId(), objectStorage.tenantId(),
                        objectStorage.name(), objectStorage.description(),
                        objectStorage.provider(), objectStorage.endpoint(),
                        objectStorage.bucketName(), objectStorage.accessKey(),
                        (objectStorage.secretKey() == null || objectStorage.secretKey().isEmpty()) ? "" : "********",
                        objectStorage.region(),
                        check
                );
                case FileSystemRepository fileSystem -> new FileSystemRepositoryResponse(
                        fileSystem.id(), fileSystem.projectId(), fileSystem.tenantId(),
                        fileSystem.name(), fileSystem.description(),
                        check
                );
                case SingerTapRepository tap -> new SingerTapRepositoryResponse(
                        tap.id(), tap.projectId(), tap.tenantId(),
                        tap.name(), tap.description(), tap.tapId(),
                        maskTapConfig(tap, taps),
                        check
                );
                case IcebergRestRepository iceberg -> new IcebergRestRepositoryResponse(
                        iceberg.id(), iceberg.projectId(), iceberg.tenantId(),
                        iceberg.name(), iceberg.description(),
                        iceberg.catalogUri(), iceberg.warehouse(),
                        // Both credentials masked independently — see the request record.
                        (iceberg.token() == null || iceberg.token().isEmpty()) ? "" : "********",
                        iceberg.provider(), iceberg.endpoint(), iceberg.accessKey(),
                        (iceberg.secretKey() == null || iceberg.secretKey().isEmpty()) ? "" : "********",
                        iceberg.region(),
                        check
                );
            };
        }

        /**
         * The tap's settings with only its declared secrets masked.
         *
         * <p>Per key rather than wholesale because one map holds both: masking {@code base} along
         * with {@code api_key} would leave a user editing a repository unable to see the setting
         * they came to change. The tap said which is which, so the answer is the tap's, not this
         * file's — and a tap the catalogue no longer declares gets everything masked, because
         * "which of these is a credential" then has no answer at all.
         */
        private static java.util.Map<String, String> maskTapConfig(
                SingerTapRepository repository, SingerTapCatalog taps) {
            java.util.Set<String> secretKeys = taps.find(repository.tapId())
                    .map(SingerTapCatalog.Tap::secretKeys)
                    .orElse(null);
            java.util.Map<String, String> masked = new java.util.LinkedHashMap<>();
            edu.upc.dtak.aran.core.source.TapConfig.toMap(repository.configJson())
                    .forEach((key, value) -> masked.put(key,
                            (secretKeys != null && !secretKeys.contains(key)) || value.isEmpty()
                                    ? value
                                    : "********"));
            return masked;
        }
    }

    public record RelationalDatabaseRepositoryResponse(
            UUID id,
            UUID projectId,
            UUID tenantId,
            String name,
            String description,
            String engine,
            String host,
            int port,
            String database,
            String username,
            String password,
            String schema,
            boolean federated,
            @Nullable ConnectionCheckDto lastConnectionCheck
    ) implements DataRepositoryResponse {
    }

    public record ObjectStorageRepositoryResponse(
            UUID id,
            UUID projectId,
            UUID tenantId,
            String name,
            String description,
            String provider,
            String endpoint,
            String bucketName,
            String accessKey,
            String secretKey,
            String region,
            @Nullable ConnectionCheckDto lastConnectionCheck
    ) implements DataRepositoryResponse {
    }

    public record FileSystemRepositoryResponse(
            UUID id,
            UUID projectId,
            UUID tenantId,
            String name,
            String description,
            @Nullable ConnectionCheckDto lastConnectionCheck
    ) implements DataRepositoryResponse {
    }

    public record SingerTapRepositoryResponse(
            UUID id,
            UUID projectId,
            UUID tenantId,
            String name,
            String description,
            String tapId,
            java.util.Map<String, String> config,
            @Nullable ConnectionCheckDto lastConnectionCheck
    ) implements DataRepositoryResponse {
    }

    public record IcebergRestRepositoryResponse(
            UUID id,
            UUID projectId,
            UUID tenantId,
            String name,
            String description,
            String catalogUri,
            String warehouse,
            String token,
            String provider,
            String endpoint,
            String accessKey,
            String secretKey,
            String region,
            @Nullable ConnectionCheckDto lastConnectionCheck
    ) implements DataRepositoryResponse {
    }

    /**
     * The outcome of a connection test, and when it ran.
     *
     * @param testedAt when the probe ran — shown next to the status so a stale "connected" reads as
     *                 stale
     */
    public record ConnectionCheckDto(
            boolean success,
            String message,
            @Nullable String details,
            Instant testedAt
    ) {
        static ConnectionCheckDto from(ConnectionCheck check) {
            return new ConnectionCheckDto(check.success(), check.message(), check.details(), check.testedAt());
        }
    }
}

package edu.upc.dtak.aran.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The live binding federated profiling hands freyja must be the SAME scan a query would run,
 * with the same secret shapes — composed from the query path's single authorities, so the two
 * cannot drift. And because both halves can carry credentials, nothing here may leak them
 * through {@code toString}.
 */
class FederatedProfilingBindingTest {

    private static IcebergCatalogClient catalogAnswering(String metadataLocation) {
        return new IcebergCatalogClient() {
            @Override public Map<String, String> config(IcebergRestRepository repository) {
                return Map.of();
            }
            @Override public List<String> listTables(IcebergRestRepository repository) {
                return List.of();
            }
            @Override public IcebergTable loadTable(IcebergRestRepository repository,
                                                    String tableIdentifier) {
                return new IcebergTable(metadataLocation, List.of());
            }
        };
    }

    private static IcebergRestRepository icebergRepo(String token) {
        return new IcebergRestRepository(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "lakehouse", "", "http://localhost:8181/api/catalog", "prod", token,
                "MINIO", "http://localhost:9000", "AKIA", "SEKRET", "us-east-1");
    }

    @Test
    @DisplayName("postgres: the drift-probe/query attached-catalog read, secret and ATTACH included — nothing else")
    void relationalBinding() {
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "",
                "POSTGRESQL", "db-host", 5432, "db", "user", "pw-sentinel", "public", true);

        FederatedProfilingBinding binding =
                FederatedProfilingBinding.forDataset(repo, "people", null);
        FederatedScan scan = edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression
                .forRepository(repo, "people");

        assertThat(binding).isNotNull();
        assertThat(binding.expression()).isEqualTo(scan.expression())
                .doesNotContain("pw-sentinel");
        assertThat(binding.setupSql()).isEqualTo(scan.setupSql());
        assertThat(binding.setupSql()).first().isEqualTo("INSTALL postgres");
        assertThat(binding.setupSql()).last().asString()
                .startsWith("ATTACH IF NOT EXISTS ''").contains("TYPE postgres");
        assertThat(binding.toString()).doesNotContain("pw-sentinel");
    }

    @Test
    @DisplayName("mysql: the query path's attached-catalog read, secret and ATTACH included — nothing else")
    void mysqlBinding() {
        RelationalDatabaseRepository repo = new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "",
                "MYSQL", "db-host", 3306, "db", "user", "pw", "public", true);

        FederatedProfilingBinding binding =
                FederatedProfilingBinding.forDataset(repo, "people", null);
        FederatedScan scan = edu.upc.dtak.aran.core.domain.drift.FederatedScanExpression
                .forRepository(repo, "people");

        assertThat(binding).isNotNull();
        assertThat(binding.expression()).isEqualTo(scan.expression());
        assertThat(binding.setupSql()).isEqualTo(scan.setupSql());
        assertThat(binding.setupSql()).last().asString().startsWith("ATTACH IF NOT EXISTS ''");
        assertThat(binding.toString()).doesNotContain("pw");
    }

    @Test
    @DisplayName("iceberg over s3: query-time metadata location, httpfs+iceberg, bucket-scoped S3 secret")
    void icebergS3Binding() {
        FederatedProfilingBinding binding = FederatedProfilingBinding.forDataset(
                icebergRepo("tok"), "db.people",
                catalogAnswering("s3://lake/db/people/metadata/00007.metadata.json"));

        assertThat(binding).isNotNull();
        assertThat(binding.expression())
                .isEqualTo("iceberg_scan('s3://lake/db/people/metadata/00007.metadata.json')");
        assertThat(binding.setupSql().subList(0, 4)).containsExactly(
                "INSTALL httpfs", "LOAD httpfs", "INSTALL iceberg", "LOAD iceberg");
        assertThat(binding.setupSql().get(4))
                .startsWith("CREATE OR REPLACE SECRET aran_ice_profile (TYPE S3")
                .contains("SCOPE 's3://lake'")
                .contains("SEKRET"); // the one legitimate carrier — freyja executes it
    }

    @Test
    @DisplayName("iceberg over http(s): the catalog bearer authenticates the file reads too")
    void icebergHttpBinding() {
        FederatedProfilingBinding binding = FederatedProfilingBinding.forDataset(
                icebergRepo("bearer-tok"), "db.people",
                catalogAnswering("http://other-aran/api/iceberg/files/p/s/m.json"));

        assertThat(binding).isNotNull();
        assertThat(binding.setupSql().get(4))
                .startsWith("CREATE OR REPLACE SECRET aran_ice_profile_http (TYPE HTTP")
                .contains("BEARER_TOKEN 'bearer-tok'");
    }

    @Test
    @DisplayName("a kind with no live binding answers null; iceberg without a client throws loudly")
    void unsupportedShapes() {
        assertThat(FederatedProfilingBinding.forDataset(
                new FileSystemRepository(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "files", ""),
                "orphan", null)).isNull();
        assertThat(FederatedProfilingBinding.forDataset(null, "x", null)).isNull();
        // A dataset believed live must not silently misreport a missing client as "unsupported".
        assertThatThrownBy(() -> FederatedProfilingBinding.forDataset(
                icebergRepo("tok"), "db.people", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("toString redacts: no DSN, no secret key, no token — counts only")
    void toStringRedacts() {
        FederatedProfilingBinding binding = FederatedProfilingBinding.forDataset(
                icebergRepo("bearer-tok"), "db.people",
                catalogAnswering("s3://lake/m.json"));

        assertThat(binding.toString())
                .doesNotContain("SEKRET").doesNotContain("bearer-tok").doesNotContain("s3://");
    }
}

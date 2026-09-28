package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which access mode a dataset is actually served through.
 *
 * <p>The distinction these tests exist to pin: a repository asking to be federated is a request,
 * and it is only granted when the engine has a scanner. Before {@link SourceAccess} that decision
 * lived inside the query engine's resolver where nothing else could ask it.
 */
class SourceAccessTest {

    private static RelationalDatabaseRepository relational(String engine, boolean federated) {
        return new RelationalDatabaseRepository(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src", "", engine, "localhost", 5432, "db", "u", "p", "public", federated);
    }

    @Nested
    @DisplayName("resolve — the configuration question")
    class Resolve {

        @ParameterizedTest
        @ValueSource(strings = {"POSTGRESQL", "MYSQL", "MARIADB"})
        @DisplayName("an engine with a scanner, asked to federate, is federated")
        void federatable_engines(String engine) {
            assertThat(SourceAccess.resolve(relational(engine, true), "papers"))
                    .isEqualTo(AccessMode.FEDERATED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ORACLE", "HBASE", "SQLSERVER", "DUCKDB"})
        @DisplayName("an engine with no scanner resolves to MATERIALIZED even when asked to federate")
        void unfederatable_engines_degrade(String engine) {
            // Preserved behaviour, and a sharp edge worth naming: nothing materialises such a
            // source today, so the query fails later with "No snapshot recorded for dataset" —
            // a message about a missing snapshot for a source the user believed was live.
            assertThat(SourceAccess.resolve(relational(engine, true), "papers"))
                    .isEqualTo(AccessMode.MATERIALIZED);
        }

        @Test
        @DisplayName("a federatable engine that was not asked to federate is materialized")
        void not_requested() {
            assertThat(SourceAccess.resolve(relational("POSTGRESQL", false), "papers"))
                    .isEqualTo(AccessMode.MATERIALIZED);
        }

        @Test
        @DisplayName("a non-relational repository is materialized")
        void non_relational() {
            DataRepository files = new FileSystemRepository(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "files", "");
            assertThat(SourceAccess.resolve(files, "papers")).isEqualTo(AccessMode.MATERIALIZED);
        }

        @Test
        @DisplayName("an object-storage dataset with no lake copy is served federated — live over httpfs")
        void object_storage_without_snapshot_is_federated() {
            // The repository default: an object-store object is read in place via
            // read_parquet('s3://…'). The 2-arg form (no per-dataset copy known) resolves the same.
            assertThat(SourceAccess.resolve(objectStore(), "people")).isEqualTo(AccessMode.FEDERATED);
            assertThat(SourceAccess.resolve(objectStore(), "people", false))
                    .isEqualTo(AccessMode.FEDERATED);
        }

        @Test
        @DisplayName("an object-storage dataset copied into the lake is served materialized")
        void object_storage_with_snapshot_is_materialized() {
            // The per-dataset override: a dataset that has been copied into the lake reads from that
            // snapshot, whatever the repository default — the object-store equivalent of a relational
            // dimension table imported rather than federated. This is the whole point of the copy
            // path: one bucket can hold an object read live and one read from a copy.
            assertThat(SourceAccess.resolve(objectStore(), "people", true))
                    .isEqualTo(AccessMode.MATERIALIZED);
        }

        private static DataRepository objectStore() {
            return new edu.upc.dtak.aran.core.domain.ObjectStorageRepository(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "lake", "",
                    "MINIO", "http://localhost:9000", "bucket", "AKID", "SEKRET", "us-east-1");
        }

        @Test
        @DisplayName("an Iceberg dataset follows the object-storage model: live without a copy, copy wins")
        void iceberg_follows_object_storage() {
            // Same reasoning, same per-dataset signal (Gap 2): one catalog can hold a table worth
            // reading live via iceberg_scan and a table worth copying, so the lake copy — not a
            // repository flag — is what flips a dataset to MATERIALIZED.
            DataRepository catalog = new edu.upc.dtak.aran.core.domain.IcebergRestRepository(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "lakehouse", "",
                    "https://catalog.example/api/catalog", "prod", "tok",
                    "MINIO", "http://localhost:9000", "AKID", "SEKRET", "us-east-1");
            assertThat(SourceAccess.resolve(catalog, "db.people")).isEqualTo(AccessMode.FEDERATED);
            assertThat(SourceAccess.resolve(catalog, "db.people", true))
                    .isEqualTo(AccessMode.MATERIALIZED);
        }

        @Test
        @DisplayName("a repository that cannot be loaded is materialized, never federated")
        void null_repository() {
            // A source Aran cannot identify is not one it can federate. Failing closed here means
            // the worst case is a missing snapshot rather than a scan built from nothing.
            assertThat(SourceAccess.resolve(null, "papers")).isEqualTo(AccessMode.MATERIALIZED);
        }
    }

    @Nested
    @DisplayName("supportsFederation — the capability question")
    class Capability {

        @ParameterizedTest
        @ValueSource(strings = {"POSTGRESQL", "MYSQL", "MARIADB"})
        @DisplayName("capability does not depend on whether federation was requested")
        void capability_is_independent_of_configuration(String engine) {
            // This is what separates "you have not turned it on" from "this engine cannot do it".
            // Telling a user the first when the second is true is the honesty gap that runs one
            // level below the repository-type fix.
            assertThat(SourceAccess.supportsFederation(relational(engine, false))).isTrue();
            assertThat(SourceAccess.supportsFederation(relational(engine, true))).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"ORACLE", "HBASE"})
        @DisplayName("an engine with no scanner reports no capability")
        void no_capability(String engine) {
            assertThat(SourceAccess.supportsFederation(relational(engine, true))).isFalse();
        }

        @Test
        @DisplayName("capability and resolution disagree exactly when federation was not requested")
        void capability_vs_resolution() {
            RelationalDatabaseRepository pg = relational("POSTGRESQL", false);

            assertThat(SourceAccess.supportsFederation(pg)).isTrue();
            assertThat(SourceAccess.resolve(pg, "papers")).isEqualTo(AccessMode.MATERIALIZED);
        }
    }
}

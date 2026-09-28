package edu.upc.dtak.aran.core.source;

import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.domain.RelationalDatabaseRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J2-F3: how a dataset is actually read, per dataset — so the Overview of a live table stops
 * saying "No upstream — this dataset's data was uploaded" while every query reads production.
 */
class DatasetAccessModeTest {

    private static final UUID ID = UUID.randomUUID();

    private static Dataset dataset(String snapshotId) {
        return new Dataset(UUID.randomUUID(), UUID.randomUUID(), "orders", "", snapshotId);
    }

    private static RelationalDatabaseRepository relational(String engine, boolean federated) {
        return new RelationalDatabaseRepository(ID, ID, ID, "db", "", engine, "localhost", 5432,
                "shop", "u", "p", "public", federated);
    }

    @Test
    @DisplayName("an uploaded file is read from its snapshot")
    void uploadIsSnapshot() {
        DataRepository files = new FileSystemRepository(ID, ID, ID, "files", "");
        assertThat(DatasetAccessMode.of(files, dataset("snap-1"))).isEqualTo(DatasetAccessMode.SNAPSHOT);
    }

    @Test
    @DisplayName("a table on a federated Postgres repository is read live; with a lake copy it is live + copy")
    void federatedRelationalIsLive() {
        assertThat(DatasetAccessMode.of(relational("POSTGRESQL", true), dataset(null)))
                .isEqualTo(DatasetAccessMode.LIVE);
        // A federated relational repository keeps reading live after an import: the copy is only
        // served when an interval refresh keeps it fresh (placement), so it is dual-access.
        assertThat(DatasetAccessMode.of(relational("POSTGRESQL", true), dataset("snap-1")))
                .isEqualTo(DatasetAccessMode.LIVE_WITH_COPY);
    }

    @Test
    @DisplayName("a table imported from a repository that is not federated is read from its copy")
    void importedTableIsSnapshot() {
        assertThat(DatasetAccessMode.of(relational("POSTGRESQL", false), dataset("snap-1")))
                .isEqualTo(DatasetAccessMode.SNAPSHOT);
        // Federation requested on an engine with no scanner is not granted (SourceAccess).
        assertThat(DatasetAccessMode.of(relational("ORACLE", true), dataset("snap-1")))
                .isEqualTo(DatasetAccessMode.SNAPSHOT);
    }

    @Test
    @DisplayName("object storage and Iceberg read in place until the dataset is copied into the lake")
    void perDatasetCopyDecidesForObjectStorageAndIceberg() {
        DataRepository bucket = new ObjectStorageRepository(ID, ID, ID, "lake", "", "S3", "", "b", "ak", "sk", "eu-west-1");
        assertThat(DatasetAccessMode.of(bucket, dataset(null))).isEqualTo(DatasetAccessMode.LIVE);
        assertThat(DatasetAccessMode.of(bucket, dataset("snap-1"))).isEqualTo(DatasetAccessMode.SNAPSHOT);

        DataRepository catalog = new IcebergRestRepository(ID, ID, ID, "cat", "",
                "http://iceberg.example:8181", "", "", "S3", "", "", "", "");
        assertThat(DatasetAccessMode.of(catalog, dataset(null))).isEqualTo(DatasetAccessMode.LIVE);
        assertThat(DatasetAccessMode.of(catalog, dataset("snap-1"))).isEqualTo(DatasetAccessMode.SNAPSHOT);
    }

    @Test
    @DisplayName("a repository that cannot be loaded is not one Aran can read live")
    void unknownRepositoryIsSnapshot() {
        assertThat(DatasetAccessMode.of(null, dataset(null))).isEqualTo(DatasetAccessMode.SNAPSHOT);
    }
}

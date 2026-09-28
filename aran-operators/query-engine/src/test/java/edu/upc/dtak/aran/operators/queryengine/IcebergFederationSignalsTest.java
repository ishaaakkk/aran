package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * The two DDL-driven decisions {@code iceberg_scan} must trip (Gap 2): the federation predicate —
 * an Iceberg read is external access, so the filesystem lockdown must be skipped rather than cut
 * the query off — and the extension install, which must bring httpfs alongside iceberg because
 * the scan reads its metadata and files over httpfs whatever the scheme.
 */
@DisplayName("iceberg_scan trips the federation predicate and installs its extensions")
class IcebergFederationSignalsTest {

    private static final String SCAN_DDL =
            "CREATE OR REPLACE VIEW src_ds AS SELECT * FROM "
                    + "iceberg_scan('s3://lake/db/t/metadata/00001.metadata.json')";

    @Test
    @DisplayName("a DDL containing iceberg_scan is federated")
    void icebergScanIsFederated() {
        assertThat(DuckDbExecutor.isFederated(List.of(SCAN_DDL))).isTrue();
        assertThat(DuckDbExecutor.isFederated(List.of(
                "CREATE OR REPLACE VIEW src_x AS SELECT * FROM read_parquet('/tmp/x.parquet')")))
                .isFalse();
    }

    @Test
    @DisplayName("iceberg_scan installs httpfs AND iceberg; a local read installs neither")
    void extensionsFollowTheScan() throws Exception {
        Statement stmt = mock(Statement.class);
        DuckDbExecutor.ensureFederationExtensions(stmt, List.of(SCAN_DDL));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Mockito.verify(stmt, Mockito.atLeastOnce()).execute(sql.capture());
        List<String> executed = new ArrayList<>(sql.getAllValues());
        assertThat(executed).containsSubsequence("INSTALL httpfs;", "LOAD httpfs;");
        assertThat(executed).containsSubsequence("INSTALL iceberg;", "LOAD iceberg;");

        Statement local = mock(Statement.class);
        DuckDbExecutor.ensureFederationExtensions(local, List.of(
                "CREATE OR REPLACE VIEW src_x AS SELECT * FROM read_parquet('/tmp/x.parquet')"));
        Mockito.verifyNoInteractions(local);
    }
}

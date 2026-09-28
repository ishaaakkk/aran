package edu.upc.dtak.aran.core.service;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W-B6 / J5-F14: the file a user downloads is named for the product they downloaded it from.
 */
class QueryExportFilenameTest {

    @Test
    @DisplayName("an export is offered as aran-export-<date>.<format>")
    void exportFileIsNamedForAran() {
        QueryExportService.Export csv = new QueryExportService.Export(Path.of("x.csv"), "CSV", 3);
        QueryExportService.Export parquet = new QueryExportService.Export(Path.of("x.parquet"), "PARQUET", 3);

        assertThat(csv.filename()).matches("aran-export-\\d{4}-\\d{2}-\\d{2}\\.csv");
        assertThat(parquet.filename()).matches("aran-export-\\d{4}-\\d{2}-\\d{2}\\.parquet");
    }
}

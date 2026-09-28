package edu.upc.lima;

import edu.upc.data.dataset.CSVDataset;
import edu.upc.data.schema.Column;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ConstraintDiscoveryTest {

    @Test
    public void testTypeInferenceAndConstraintDiscovery(@TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("test_dataset.csv");

        // Column A determines column B, giving LIMA a real functional dependency to find
        // (i.e. !(t1.A == t2.A & t1.B != t2.B)); column C is unconstrained text.
        try (FileWriter writer = new FileWriter(csvFile.toFile())) {
            writer.write("colA,colB,colC\n");
            writer.write("1,10.5,alpha\n");
            writer.write("1,10.5,beta\n");
            writer.write("2,20.0,gamma\n");
            writer.write("2,20.0,delta\n");
            writer.write("3,35.2,epsilon\n");
            writer.write("3,35.2,zeta\n");
        }

        CSVDataset dataset = new CSVDataset(csvFile.toAbsolutePath().toString(), 6, 42L);

        assertThat(dataset.schema.columns).hasSize(3);
        
        Column colA = dataset.schema.columns[0];
        Column colB = dataset.schema.columns[1];
        Column colC = dataset.schema.columns[2];

        assertThat(colA.name).isEqualTo("colA");
        assertThat(colA.type).isEqualTo(Column.Type.INTEGER);

        assertThat(colB.name).isEqualTo("colB");
        assertThat(colB.type).isEqualTo(Column.Type.REAL);

        assertThat(colC.name).isEqualTo("colC");
        assertThat(colC.type).isEqualTo(Column.Type.STRING);

        Scheduler.minGrad = 0.000001; // aprox = 0.0001
        Scheduler scheduler = new Scheduler(dataset);
        List<String> constraints = scheduler.populatePredicates();

        System.out.println("Discovered constraints: " + constraints);

        // colA perfectly determines colB, so !(t1.colA == t2.colA & t1.colB != t2.colB) should surface.
        assertThat(constraints).isNotEmpty();
        assertThat(constraints.stream().anyMatch(c -> c.contains("colA") && c.contains("colB"))).isTrue();
    }
}

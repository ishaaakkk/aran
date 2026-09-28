package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class WrapperExecutionDuckDbTest {

    private final CsvBootstrap csvEngine = new CsvBootstrap();
    private final JsonBootstrap jsonEngine = new JsonBootstrap();
    private Connection connection;
    private final List<Path> tempFiles = new ArrayList<>();

    @BeforeEach
    public void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterEach
    public void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        for (Path p : tempFiles) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
        tempFiles.clear();
    }

    private Path writeTempFile(String content, String ext) throws IOException {
        Path p = Files.createTempFile("test_", ext);
        Files.writeString(p, content);
        tempFiles.add(p);
        return p;
    }

    private List<String> getWrappers(Model m) {
        List<String> sqls = new ArrayList<>();
        StmtIterator it = m.listStatements(null, WrapperVocab.WRAPPER_SQL, (org.apache.jena.rdf.model.RDFNode) null);
        while (it.hasNext()) {
            sqls.add(it.next().getObject().asLiteral().getString());
        }
        return sqls;
    }

    private void assertWrapperExecutes(String wrapperSql, Path dataFile, String format) throws SQLException {
        String readFunc = "CSV".equalsIgnoreCase(format) ? "read_csv_auto" : "read_json_auto";
        String executableSql = wrapperSql.replace(WrapperVocab.SNAPSHOT_PLACEHOLDER,
                readFunc + "('" + dataFile.toAbsolutePath() + "')");
        
        try (java.sql.Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(executableSql);
            assertThat(rs.next()).as("Wrapper query should return at least one row").isTrue();
        }
    }

    @Test
    public void executeCsvWrapper() throws Exception {
        String csv = "id,name\n1,Alice\n2,Bob";
        Path p = writeTempFile(csv, ".csv");
        
        Model m = csvEngine.bootstrap("csv1", "test", "snap1", csv.getBytes());
        WrapperGenerator.attachPerClassWrappers(m);
        
        List<String> wrappers = getWrappers(m);
        assertThat(wrappers).hasSize(1);
        
        assertWrapperExecutes(wrappers.get(0), p, "CSV");
    }

    @Test
    public void executeFlatJsonWrapper() throws Exception {
        String json = "[{\"id\": 1, \"name\": \"Alice\"}]";
        Path p = writeTempFile(json, ".json");
        
        Model m = jsonEngine.bootstrap("json1", "test", "snap2", json.getBytes());
        WrapperGenerator.attachPerClassWrappers(m);
        
        List<String> wrappers = getWrappers(m);
        assertThat(wrappers).hasSize(1);
        
        assertWrapperExecutes(wrappers.get(0), p, "JSON");
    }

    @Test
    public void executeNestedArrayJsonWrapper() throws Exception {
        String json = "{\"department\": \"Engineering\", \"employees\": [{\"name\": \"Alice\", \"skills\": [\"Java\", \"SQL\"]}]}";
        Path p = writeTempFile(json, ".json");
        
        Model m = jsonEngine.bootstrap("json2", "test", "snap3", json.getBytes());
        WrapperGenerator.attachPerClassWrappers(m);
        
        List<String> wrappers = getWrappers(m);
        // At least root + employees classes (skills is a primitive array, so it may not add a class).
        assertThat(wrappers.size()).isGreaterThanOrEqualTo(2);
        
        for (String w : wrappers) {
            assertWrapperExecutes(w, p, "JSON");
        }
    }
}

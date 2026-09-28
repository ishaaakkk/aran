package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public abstract class DuckDbTestSupport {

    protected Connection connection;
    private final List<Path> tempFiles = new ArrayList<>();

    @BeforeEach
    public void setUpDuckDb() throws SQLException {
        connection = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterEach
    public void tearDownDuckDb() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        for (Path tempFile : tempFiles) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                // best effort
            }
        }
        tempFiles.clear();
    }

    public void registerJsonView(String viewName, String jsonContent) throws IOException, SQLException {
        Path tempFile = Files.createTempFile("duckdb_test_", ".json");
        tempFiles.add(tempFile);
        Files.writeString(tempFile, jsonContent);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE VIEW " + viewName + " AS SELECT * FROM read_json_auto('" + tempFile.toAbsolutePath() + "')");
        }
    }

    public void registerCsvView(String viewName, String csvContent) throws IOException, SQLException {
        Path tempFile = Files.createTempFile("duckdb_test_", ".csv");
        tempFiles.add(tempFile);
        Files.writeString(tempFile, csvContent);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE VIEW " + viewName + " AS SELECT * FROM read_csv_auto('" + tempFile.toAbsolutePath() + "')");
        }
    }
}

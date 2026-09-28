package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.Test;
import java.sql.Statement;
import java.sql.ResultSet;
import static org.assertj.core.api.Assertions.assertThat;

public class DuckDbTestSupportTest extends DuckDbTestSupport {

    @Test
    public void testDuckDbConnection() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT 1 AS val");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("val")).isEqualTo(1);
        }
    }

    @Test
    public void testRegisterJsonView() throws Exception {
        String json = "[{\"id\": 1, \"name\": \"test\"}]";
        registerJsonView("test_json", json);
        
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM test_json");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("id")).isEqualTo(1);
            assertThat(rs.getString("name")).isEqualTo("test");
        }
    }

    @Test
    public void testRegisterCsvView() throws Exception {
        String csv = "id,name\n2,test2";
        registerCsvView("test_csv", csv);
        
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM test_csv");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("id")).isEqualTo(2);
            assertThat(rs.getString("name")).isEqualTo("test2");
        }
    }
}

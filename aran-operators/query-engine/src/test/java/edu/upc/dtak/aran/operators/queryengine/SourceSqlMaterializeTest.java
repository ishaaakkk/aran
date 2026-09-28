package edu.upc.dtak.aran.operators.queryengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code materialize-source-sql}: client SQL over the sources-tier alias views, written whole to
 * a parquet file — the sources analogue of {@code materialize-product-sql}, running against the
 * same snapshot view stack as {@code run}.
 */
class SourceSqlMaterializeTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String NX = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC_CLASS = "http://ex.com/source/ds1/acm";

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    private final GraphStore graphStore = mock(GraphStore.class);
    private final LakeClient lake = mock(LakeClient.class);
    private final List<Path> cleanup = new ArrayList<>();

    private QueryEngineOperator operator() {
        return new QueryEngineOperator(
                graphStore, catalogOperator, lake, new ObjectMapper(),
                mock(IntegrationModelRepository.class),
                mock(DatasetRepository.class),
                mock(DataRepositoryRepository.class),
                new SnapshotCache(lake, "", 0L),
                mock(edu.upc.dtak.aran.core.domain.DataProductRepository.class));
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Path p : cleanup) {
            Files.deleteIfExists(p);
        }
    }

    /** One class fed by dataset ds1, wrapper aliasing raw columns to source IRIs, alias {@code acm}. */
    private void wireCatalogAndSnapshot() throws Exception {
        String wrapperSql = "SELECT title AS \"" + SRC_CLASS + "/title\", year AS \""
                + SRC_CLASS + "/year\" FROM ${SNAPSHOT}";
        MappingCatalog cat = new MappingCatalog(Map.of(
                "urn:IPaper", new MappingCatalog.IntegratedClass("urn:IPaper", List.of(
                        new MappingCatalog.SourceView("ds1", wrapperSql,
                                Map.of("http://ex.com/g/title", SRC_CLASS + "/title",
                                        "http://ex.com/g/year", SRC_CLASS + "/year"),
                                0L, List.of(), SRC_CLASS)))),
                List.of(), List.of(), "v1");
        when(catalogOperator.getCatalog(any(TenantContext.class))).thenReturn(cat);

        // snapshotMap reads (datasetId, snapshotId, format) triples from the schema graph.
        Model m = ModelFactory.createDefaultModel();
        Resource c = m.createResource(SRC_CLASS);
        m.add(c, m.createProperty(NX + "datasetId"), "ds1");
        m.add(c, m.createProperty(NX + "snapshotId"), "snap1");
        m.add(c, m.createProperty(NX + "format"), "json");
        when(graphStore.query(any(TenantContext.class), anyString())).thenReturn(m);

        when(lake.downloadSnapshotTo(any(), eq("snap1"), any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(2);
            Files.writeString(target, "{\"title\":\"a\",\"year\":2001}\n{\"title\":\"b\",\"year\":2002}\n");
            return target.toAbsolutePath().toString();
        });
    }

    private OperatorResult execute(String sql) {
        return operator().execute(new OperatorRequest(TENANT, null, "materialize-source-sql",
                Map.of("sql", sql)));
    }

    @Test
    @DisplayName("client SQL over an alias view lands whole in a parquet file")
    void sqlOverAliasMaterializes() throws Exception {
        wireCatalogAndSnapshot();

        OperatorResult result = execute("SELECT title, year FROM acm ORDER BY year");

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        Path parquet = Path.of(result.data().get("path"));
        cleanup.add(parquet);
        assertThat(parquet).exists();
        assertThat(result.data().get("rowCount")).isEqualTo("2");
        // Echoed byte-identical — the "no rewrite" claim, assertable.
        assertThat(result.data().get("sql")).isEqualTo("SELECT title, year FROM acm ORDER BY year");

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT title FROM read_parquet('" + parquet.toAbsolutePath() + "') ORDER BY year")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("a");
        }
    }

    @Test
    @DisplayName("a forbidden function is rejected before anything is prepared")
    void guardShortCircuits() {
        // No catalog stubbing: reaching the catalog would NPE, so a clean INVALID_SQL proves
        // the guard ran first.
        OperatorResult result = execute("SELECT * FROM read_csv_auto('/etc/passwd')");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_SQL");
        assertThat(result.errorMessage()).contains("read_csv_auto");
    }

    @Test
    @DisplayName("the catalog payload carries each source's sources-tier table and column names")
    void catalogPayloadCarriesSourceNames() throws Exception {
        wireCatalogAndSnapshot();

        OperatorResult result = operator().execute(
                new OperatorRequest(TENANT, null, "catalog", Map.of()));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        com.fasterxml.jackson.databind.JsonNode payload =
                new ObjectMapper().readTree(result.data().get("catalog"));
        com.fasterxml.jackson.databind.JsonNode source =
                payload.get("classes").get(0).get("sources").get(0);
        // The authoring names for SQL mode: what a user actually types.
        assertThat(source.get("sourceTable").asText()).isEqualTo("acm");
        List<String> cols = new ArrayList<>();
        source.get("sourceColumns").forEach(c -> cols.add(c.asText()));
        assertThat(cols).containsExactly("title", "year");
    }

    @Test
    @DisplayName("an unknown table comes back with the source names that would have worked")
    void unknownTableListsSourceNames() throws Exception {
        wireCatalogAndSnapshot();

        OperatorResult result = execute("SELECT * FROM acm_papers");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_SQL");
        assertThat(result.errorMessage()).contains("acm");
    }
}

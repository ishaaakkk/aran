package edu.upc.dtak.aran.operators.queryengine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbExecutorTest {

    @Test
    void testDuckDbExecutor() throws Exception {
        Path tempDir = Files.createTempDirectory("aran-duckdb-test");

        File cmoaFile = tempDir.resolve("cmoa.json").toFile();
        try (FileWriter fw = new FileWriter(cmoaFile)) {
            fw.write("[{\"artist_name\": \"Picasso\", \"title\": \"Guernica\"}]");
        }
        File cmoaArtistFile = tempDir.resolve("cmoa_artist.json").toFile();
        try (FileWriter fw = new FileWriter(cmoaArtistFile)) {
            fw.write("[{\"name\": \"Picasso\", \"Nationality\": \"Spanish\"}]");
        }

        File momaFile = tempDir.resolve("moma.json").toFile();
        try (FileWriter fw = new FileWriter(momaFile)) {
            fw.write("[{\"artist\": \"Dali\", \"title\": \"Persistence of Memory\"}]");
        }
        File momaArtistFile = tempDir.resolve("moma_artist.json").toFile();
        try (FileWriter fw = new FileWriter(momaArtistFile)) {
            fw.write("[{\"name\": \"Dali\", \"Nationality\": \"Spanish\"}]");
        }

        DuckDbExecutor.SnapshotResolver resolver = datasetId -> {
            if (datasetId.equals("cmoa")) return "read_json_auto('" + cmoaFile.getAbsolutePath() + "')";
            if (datasetId.equals("cmoa_artist")) return "read_json_auto('" + cmoaArtistFile.getAbsolutePath() + "')";
            if (datasetId.equals("moma")) return "read_json_auto('" + momaFile.getAbsolutePath() + "')";
            if (datasetId.equals("moma_artist")) return "read_json_auto('" + momaArtistFile.getAbsolutePath() + "')";
            throw new IllegalArgumentException("Unknown dataset: " + datasetId);
        };

        Map<String, MappingCatalog.IntegratedClass> classes = new HashMap<>();

        Map<String, String> cmoaArtworksMap = Map.of("title", "title", "_artist", "artist_name");
        MappingCatalog.SourceView cmoaArtworks = new MappingCatalog.SourceView("cmoa", "SELECT * FROM ${SNAPSHOT}", cmoaArtworksMap);
        Map<String, String> momaArtworksMap = Map.of("title", "title", "_artist", "artist");
        MappingCatalog.SourceView momaArtworks = new MappingCatalog.SourceView("moma", "SELECT * FROM ${SNAPSHOT}", momaArtworksMap);
        classes.put("urn:Artworks", new MappingCatalog.IntegratedClass("urn:Artworks", List.of(cmoaArtworks, momaArtworks)));

        Map<String, String> cmoaArtistMap = Map.of("_name", "name", "Nationality", "Nationality");
        MappingCatalog.SourceView cmoaArtist = new MappingCatalog.SourceView("cmoa_artist", "SELECT * FROM ${SNAPSHOT}", cmoaArtistMap);
        Map<String, String> momaArtistMap = Map.of("_name", "name", "Nationality", "Nationality");
        MappingCatalog.SourceView momaArtist = new MappingCatalog.SourceView("moma_artist", "SELECT * FROM ${SNAPSHOT}", momaArtistMap);
        classes.put("urn:IArtist", new MappingCatalog.IntegratedClass("urn:IArtist", List.of(cmoaArtist, momaArtist)));

        MappingCatalog catalog = new MappingCatalog(classes, List.of(), List.of(), "v1");

        try (DuckDbExecutor executor = new DuckDbExecutor(catalog, resolver)) {
            executor.prepare();

            DuckDbExecutor.QueryResult artworks = executor.run("SELECT * FROM int_Artworks ORDER BY title");
            assertThat(artworks.rows()).hasSize(2); // Guernica, Persistence of Memory

            String joinQuery = "SELECT a.title, b._name FROM int_Artworks a JOIN int_IArtist b ON a._artist = b._name WHERE b.Nationality='Spanish' ORDER BY a.title";
            DuckDbExecutor.QueryResult joined = executor.run(joinQuery);
            
            assertThat(joined.rows()).hasSize(2);
            assertThat(joined.rows().get(0)).containsExactly("Guernica", "Picasso");
            assertThat(joined.rows().get(1)).containsExactly("Persistence of Memory", "Dali");
        }
    }

    @Test
    @DisplayName("buildDdl: a catalog with no recorded column types projects exactly as it always has")
    void noColumnTypesProjectionIsByteIdenticalToToday() {
        String propA = "urn:prop:a";
        String propB = "urn:prop:b";
        MappingCatalog.SourceView srcOne = new MappingCatalog.SourceView(
                "w1", "SELECT * FROM ${SNAPSHOT}", Map.of(propA, "colA", propB, "colB"));
        // w2 does not map propB — exercises the NULL-pad branch alongside the mapped one.
        MappingCatalog.SourceView srcTwo = new MappingCatalog.SourceView(
                "w2", "SELECT * FROM ${SNAPSHOT}", Map.of(propA, "colA"));
        MappingCatalog catalog = new MappingCatalog(
                Map.of("urn:Widget", new MappingCatalog.IntegratedClass(
                        "urn:Widget", List.of(srcOne, srcTwo))),
                List.of(), List.of(), "v1");

        List<String> ddl = DuckDbExecutor.buildDdl(catalog, ds -> "read_json_auto('x')");

        String memberOne = ddl.stream()
                .filter(s -> s.startsWith("CREATE OR REPLACE VIEW u_w1_Widget AS "))
                .findFirst().orElseThrow();
        String memberTwo = ddl.stream()
                .filter(s -> s.startsWith("CREATE OR REPLACE VIEW u_w2_Widget AS "))
                .findFirst().orElseThrow();

        // Captured from the implementation before typed columns: with no columnTypes recorded
        // anywhere, every widened type is VARCHAR, so the projection must stay exactly this.
        // This is the regression gate for typed row types.
        assertThat(memberOne).isEqualTo("CREATE OR REPLACE VIEW u_w1_Widget AS SELECT "
                + "\"colA\" AS \"urn:prop:a\", \"colB\" AS \"urn:prop:b\" FROM w_w1_Widget");
        assertThat(memberTwo).isEqualTo("CREATE OR REPLACE VIEW u_w2_Widget AS SELECT "
                + "\"colA\" AS \"urn:prop:a\", NULL AS \"urn:prop:b\" FROM w_w2_Widget");
    }

    @Test
    @DisplayName("buildDdl: a typed catalog TRY_CASTs mapped columns and typed-NULL-pads unmapped ones")
    void typedCatalogEmitsTryCastAndTypedNullPad() {
        String year = "urn:prop:year";
        Map<String, String> yearType = Map.of(year, "http://www.w3.org/2001/XMLSchema#integer");
        MappingCatalog.SourceView dsA = new MappingCatalog.SourceView(
                "dsA", "SELECT * FROM ${SNAPSHOT}", Map.of(year, "year"),
                0L, List.of(), "", yearType);
        // dsB has no "year" column at all, but still carries the same recorded range — the
        // documented XsdTypes.widenedType edge case (a source can be missing the column
        // entirely, not just untyped) — so the class-level type stays BIGINT and this member
        // must pad with a typed NULL, not a bare one.
        MappingCatalog.SourceView dsB = new MappingCatalog.SourceView(
                "dsB", "SELECT * FROM ${SNAPSHOT}", Map.of(),
                0L, List.of(), "", yearType);
        MappingCatalog catalog = new MappingCatalog(
                Map.of("urn:Measurement", new MappingCatalog.IntegratedClass(
                        "urn:Measurement", List.of(dsA, dsB))),
                List.of(), List.of(), "v1");

        List<String> ddl = DuckDbExecutor.buildDdl(catalog, ds -> "read_json_auto('x')");

        String memberA = ddl.stream()
                .filter(s -> s.startsWith("CREATE OR REPLACE VIEW u_dsA_Measurement AS "))
                .findFirst().orElseThrow();
        String memberB = ddl.stream()
                .filter(s -> s.startsWith("CREATE OR REPLACE VIEW u_dsB_Measurement AS "))
                .findFirst().orElseThrow();

        assertThat(memberA).contains("TRY_CAST(\"year\" AS BIGINT) AS \"" + year + "\"");
        assertThat(memberB).contains("CAST(NULL AS BIGINT) AS \"" + year + "\"");
    }
}

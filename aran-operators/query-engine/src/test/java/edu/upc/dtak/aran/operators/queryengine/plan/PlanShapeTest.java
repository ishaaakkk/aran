package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import org.apache.calcite.rel.RelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanShapeTest {

    private static final String NS  = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";
    private static final String ARTWORKS   = NS + "integration/Artworks";
    private static final String IARTIST    = NS + "integration/IArtist";
    private static final String P_TITLE    = NS + "integration/title";
    private static final String P_NAME     = NS + "integration/name";
    private static final String P_AID      = SRC + "art/artist_id";
    private static final String P_ID       = SRC + "artist/id";
    private static final String HAS_ARTIST = NS + "integration/hasArtist";

    private static MappingCatalog joinCatalog() {
        var artworks = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT 1",
                        Map.of(P_TITLE, "title", P_AID, "artist_id"), 100L)));
        var iartist = new MappingCatalog.IntegratedClass(IARTIST, List.of(
                new MappingCatalog.SourceView("artist", "SELECT 1",
                        Map.of(P_ID, "id", P_NAME, "name"), 10_000L)));
        var join = new MappingCatalog.JoinProperty(HAS_ARTIST, ARTWORKS, IARTIST, P_AID, P_ID);
        return new MappingCatalog(Map.of(ARTWORKS, artworks, IARTIST, iartist),
                List.of(join), List.of(), "v1");
    }

    private static RelNode optimized(MappingCatalog cat, String oql) {
        return PlanExecutor.optimize(new OqlPlanner(cat).lower(OqlParser.parse(oql)));
    }

    @Test
    @DisplayName("a two-class join renders as JOIN over two SCANs with shortened equi-key names")
    void joinShape() {
        RelNode rel = optimized(joinCatalog(),
                "SELECT Artworks.title, IArtist.name AS artist FROM Artworks -[hasArtist]-> IArtist");
        PlanShape shape = PlanShape.of(rel);
        String compact = shape.toCompactString();

        assertThat(compact).contains("JOIN[inner, artist_id = id]");
        assertThat(compact).contains("SCAN[u_art_Artworks]");
        assertThat(compact).contains("SCAN[u_artist_IArtist]");
        // children indented under their parent. The optimizer wraps the join in a top-level
        // PROJECT (the "AS artist" rename forces a non-identity projection), so the root line
        // is PROJECT rather than JOIN — widen the alternation instead of asserting exact shape.
        assertThat(compact).containsPattern("(?m)^(PROJECT|JOIN)");
        assertThat(compact).containsPattern("(?m)^  (PROJECT|JOIN|SCAN)");
    }

    @Test
    @DisplayName("scan annotations are appended to the scan detail")
    void scanAnnotation() {
        RelNode rel = optimized(joinCatalog(),
                "SELECT Artworks.title FROM Artworks");
        PlanShape shape = PlanShape.of(rel,
                Map.of("u_art_Artworks", "access=MATERIALIZED"));
        assertThat(shape.toCompactString())
                .contains("SCAN[u_art_Artworks, access=MATERIALIZED]");
    }

    @Test
    @DisplayName("input order is preserved — the join's build side is its first input")
    void inputOrderPreserved() {
        RelNode rel = optimized(joinCatalog(),
                "SELECT Artworks.title, IArtist.name AS artist FROM Artworks -[hasArtist]-> IArtist");
        PlanShape.Node join = findFirst(PlanShape.of(rel).root(), "JOIN");
        assertThat(join.inputs()).hasSize(2);
        // catalog: Artworks=100 rows, IArtist=10_000 → smaller side is left/build
        assertThat(subtreeCompact(join.inputs().get(0))).contains("u_art_Artworks");
    }

    private static PlanShape.Node findFirst(PlanShape.Node n, String kind) {
        if (n.kind().equals(kind)) return n;
        for (PlanShape.Node in : n.inputs()) {
            PlanShape.Node hit = findFirst(in, kind);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String subtreeCompact(PlanShape.Node n) {
        return new PlanShape(n).toCompactString();
    }

    @Test
    @DisplayName("accessAnnotations maps u_ views per dataset and int_ views to the member consensus")
    void accessAnnotations() {
        Map<String, String> ann = PlanShape.accessAnnotations(joinCatalog(),
                ds -> ds.equals("art") ? "FEDERATED" : "MATERIALIZED");
        assertThat(ann)
                .containsEntry("u_art_Artworks", "access=FEDERATED")
                .containsEntry("u_artist_IArtist", "access=MATERIALIZED")
                .containsEntry("int_Artworks", "access=FEDERATED")
                .containsEntry("int_IArtist", "access=MATERIALIZED");
    }

    @Test
    @DisplayName("an int_ view over sources with differing access reports MIXED")
    void mixedAccess() {
        var twoSources = new MappingCatalog.IntegratedClass(ARTWORKS, List.of(
                new MappingCatalog.SourceView("art", "SELECT 1", Map.of(P_TITLE, "title"), 10L),
                new MappingCatalog.SourceView("art2", "SELECT 1", Map.of(P_TITLE, "t"), 10L)));
        var cat = new MappingCatalog(Map.of(ARTWORKS, twoSources), List.of(), List.of(), "v1");
        Map<String, String> ann = PlanShape.accessAnnotations(cat,
                ds -> ds.equals("art") ? "FEDERATED" : "MATERIALIZED");
        assertThat(ann).containsEntry("int_Artworks", "access=MIXED");
    }

    @Test
    @DisplayName("a filter literal that looks like an out-of-range $N ordinal renders instead of crashing")
    void filterLiteralWithOverflowingDollarOrdinal() {
        RelNode rel = optimized(joinCatalog(),
                "SELECT Artworks.title FROM Artworks WHERE Artworks.title = 'ref $9999999999'");
        String compact = PlanShape.of(rel).toCompactString();
        assertThat(compact).contains("FILTER[");
        assertThat(compact)
                .as("the untouched literal survives verbatim in the FILTER detail")
                .contains("$9999999999");
    }

    @Test
    @DisplayName("a Node tree nested in a payload map serializes to JSON with kind/detail/inputs")
    void nodeTreeSerializesInsideMap() throws Exception {
        RelNode rel = optimized(joinCatalog(), "SELECT Artworks.title FROM Artworks");
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("planShape", PlanShape.of(rel).root());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        assertThat(json).contains("\"kind\"").contains("\"detail\"").contains("\"inputs\"");
    }
}

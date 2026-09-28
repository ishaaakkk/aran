package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the per-class wrapper generation. These assertions encode the
 * contract the query rewriter depends on:
 *   - one wrapperSql per rdfs:Class,
 *   - columns aliased to property IRIs (quoted),
 *   - ${SNAPSHOT} placeholder in the FROM clause,
 *   - nested arrays produce LATERAL UNNEST chains, not flat denormalisation.
 *
 * NB: SQL validity against DuckDB is verified separately; here we assert structure.
 */
public class PerClassWrapperTest {

    private final CsvBootstrap csvEngine = new CsvBootstrap();
    private final JsonBootstrap jsonEngine = new JsonBootstrap();

    @Test
    public void csvWrapperProjectsColumnsByName() throws Exception {
        String csv = "title,web_url\nSunflowers,http://x/1\n";
        Model m = csvEngine.bootstrap("ds1", "cmoa", "snap1", csv.getBytes());
        int n = WrapperGenerator.attachPerClassWrappers(m);

        assertEquals(1, n, "CSV has exactly one class → one wrapper");
        String sql = onlyWrapper(m);
        assertTrue(sql.contains("\"title\" AS"), "projects column 'title' explicitly");
        assertTrue(sql.contains("\"web_url\" AS"), "projects column 'web_url' explicitly");
        assertTrue(sql.contains(WrapperVocab.SNAPSHOT_PLACEHOLDER), "uses ${SNAPSHOT} placeholder");
        assertFalse(sql.equals("SELECT * FROM snapshot"), "no longer the blind star-select");
    }

    @Test
    public void jsonNestedObjectStaysOnRootRow() throws Exception {
        String json = "[{\"title\":\"A\",\"creator\":{\"fullName\":\"X\",\"nationality\":\"Y\"}}]";
        Model m = jsonEngine.bootstrap("ds2", "cmoa", "snap2", json.getBytes());
        WrapperGenerator.attachPerClassWrappers(m);

        List<String> sqls = allWrappers(m);
        assertEquals(2, sqls.size(), "root + nested object = two classes");

        System.out.println(sqls); boolean creatorWrapperOk = sqls.stream().anyMatch(s ->
                s.contains("s.\"creator\".\"fullName\"") || s.contains("s.\"creator\".\"nationality\""));
        assertTrue(creatorWrapperOk, "nested object wrapper uses struct-field access");
        assertTrue(sqls.stream().noneMatch(s -> s.contains("UNNEST(s.\"creator\")")),
                "a nested OBJECT must not be unnested (only arrays are)");
    }

    @Test
    public void jsonNestedArrayProducesLateralUnnest() throws Exception {
        String json = "{\"artworks\":[{\"title\":\"A\",\"creators\":[{\"name\":\"x\"}]}]}";
        Model m = jsonEngine.bootstrap("ds3", "museum", "snap3", json.getBytes());
        WrapperGenerator.attachPerClassWrappers(m);

        List<String> sqls = allWrappers(m);
        assertTrue(sqls.stream().anyMatch(s -> s.contains("UNNEST(s.\"artworks\")")),
                "artworks[*] class unnests the artworks array");
        assertTrue(sqls.stream().anyMatch(s ->
                        s.contains("UNNEST(s.\"artworks\")") && s.contains("UNNEST(_r1.\"creators\")")),
                "creators[*] chains a second LATERAL UNNEST off the first");
    }

    @Test
    public void reBootstrapIsIdempotent() throws Exception {
        String csv = "a,b\n1,2\n";
        Model m = csvEngine.bootstrap("ds4", "t", "snap4", csv.getBytes());
        WrapperGenerator.attachPerClassWrappers(m);
        int after1 = countWrappers(m);
        WrapperGenerator.attachPerClassWrappers(m);
        int after2 = countWrappers(m);
        assertEquals(after1, after2, "re-attaching wrappers does not duplicate triples");
    }

    private static String onlyWrapper(Model m) {
        List<String> all = allWrappers(m);
        assertEquals(1, all.size());
        return all.get(0);
    }

    private static List<String> allWrappers(Model m) {
        List<String> out = new ArrayList<>();
        StmtIterator it = m.listStatements(null, WrapperVocab.WRAPPER_SQL, (org.apache.jena.rdf.model.RDFNode) null);
        while (it.hasNext()) {
            Statement s = it.next();
            out.add(s.getObject().asLiteral().getString());
        }
        return out;
    }

    private static int countWrappers(Model m) {
        return allWrappers(m).size();
    }
}

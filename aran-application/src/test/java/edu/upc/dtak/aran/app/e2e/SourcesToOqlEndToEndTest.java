package edu.upc.dtak.aran.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.operators.bootstrapping.BootstrapEngine;
import edu.upc.dtak.aran.operators.bootstrapping.CsvConnector;
import edu.upc.dtak.aran.operators.bootstrapping.JsonConnector;
import edu.upc.dtak.aran.operators.bootstrapping.WrapperGenerator;
import edu.upc.dtak.aran.operators.queryengine.DuckDbExecutor;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalogOperator;
import edu.upc.dtak.aran.operators.queryengine.oql.OqlParser;
import edu.upc.dtak.aran.operators.queryengine.plan.OqlPlanner;
import edu.upc.dtak.aran.operators.queryengine.plan.PlanExecutor;
import edu.upc.dtak.aran.operators.schemaintegration.SchemaIntegrationOperator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.calcite.rel.RelNode;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole pipeline on four real sources, with no mocked middle: bootstrap raw bytes into
 * source graphs, integrate them incrementally through the real operator, extract the mapping
 * catalog from the promoted graph, then answer an OQL query on DuckDB over the same files.
 *
 * <p>Every other test stops short of this. The query-engine harness hand-builds its
 * {@code MappingCatalog}, so nothing else proves that what bootstrapping and schema
 * integration actually emit is something the query engine can execute.
 *
 * <p>Shape of the scenario — two artwork sources and two artist sources, mixed JSON/CSV:
 * <pre>
 *   cmoa.json          artworks {title, artist_id}   a1, a2
 *   cooperhewitt.csv   artworks {title, artist_id}   a3
 *   artists.csv        artists  {id, name}           a1, a2
 *   wikidata.json      artists  {id, name}           a3
 * </pre>
 * A single OQL join must return rows drawn from all four, which only works if the integrated
 * classes really union their sources and the join key really lines up across them.
 *
 * <p>No Docker: the file connectors are pure, the graph store is in-memory, and DuckDB reads
 * the fixture files directly.
 *
 * <p><strong>The join is n-ary.</strong> Both artwork sources are aligned onto the same join edge
 * via two JOIN alignments sharing a label, so the single {@code P_s} carries an
 * {@code nx:joinOnLeft} per contributing source. That is the case QUERY 2 of
 * {@code MappingCatalogOperator} must collapse to one {@link MappingCatalog.JoinProperty}; without
 * it the planner rejects the repeated name as an ambiguous join property.
 */
@DisplayName("End to end — bootstrap ×4 → incremental integration → OQL")
class SourcesToOqlEndToEndTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String INT = NS + "integration/";
    private static final String TENANT = UUID.randomUUID().toString();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MODEL = UUID.randomUUID();

    private final TenantContext tenant = new TenantContext(TENANT);
    private final ObjectMapper om = new ObjectMapper();
    private final InMemoryGraphStore store = new InMemoryGraphStore();
    private final SchemaIntegrationOperator integration =
            new SchemaIntegrationOperator(store, om, mock(DatasetRepository.class));
    private final List<Path> tempFiles = new ArrayList<>();

    // Dataset ids double as the SnapshotResolver keys (bootstrapping stamps them as provenance).
    private final String cmoa = UUID.randomUUID().toString();
    private final String cooperhewitt = UUID.randomUUID().toString();
    private final String artists = UUID.randomUUID().toString();
    private final String wikidata = UUID.randomUUID().toString();

    private final Map<String, Path> snapshots = new HashMap<>();

    @AfterEach
    void cleanUp() {
        for (Path p : tempFiles) {
            try { Files.deleteIfExists(p); } catch (IOException ignore) { /* best effort */ }
        }
    }

    @Test
    @DisplayName("four bootstrapped sources, integrated incrementally, answer one OQL join")
    void four_sources_to_oql() throws Exception {
        // ── 1. Bootstrap: raw bytes → source graph (+ per-class wrapper SQL), as the
        //       bootstrapping operator does, staged at urn:aran:source:{datasetId}.
        bootstrap(cmoa, "artworks", "cmoa.json", JSON,
                "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"},"
                        + "{\"title\":\"The Persistence of Memory\",\"artist_id\":\"a3\"}]");
        bootstrap(cooperhewitt, "artworks", "cooperhewitt.csv", CSV,
                "title,artist_id\nConvergence,a2\n");
        bootstrap(artists, "artists", "artists.csv", CSV,
                "id,name\na1,Picasso\na2,Pollock\n");
        bootstrap(wikidata, "artists", "wikidata.json", JSON,
                "[{\"id\":\"a3\",\"name\":\"Dali\"}]");

        // ── 2a. Baseline rebuild over the two artwork sources.
        OperatorResult baseline = compile(List.of(cmoa, cooperhewitt), List.of(
                align(cls(cmoa, "artworks"), cls(cooperhewitt, "artworks"), "Artworks", "class"),
                align(prop(cmoa, "artworks", "title"), prop(cooperhewitt, "artworks", "title"),
                        "title", "datatype")));
        assertThat(baseline.success()).as(String.valueOf(baseline.errorMessage())).isTrue();
        GraphVersion base = promote(baseline);

        // ── 2b. Incremental: add the first artist source, joined from BOTH artwork sources.
        //        Two JOIN alignments sharing a label collapse onto one P_l/P_s pair, so every
        //        artwork source contributes its key column to the same join edge.
        OperatorResult step2 = incremental(base, artists, List.of(
                align(prop(cmoa, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join"),
                align(prop(cooperhewitt, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join")));
        assertThat(step2.success()).as(String.valueOf(step2.errorMessage())).isTrue();
        base = promote(step2);

        // ── 2c. Incremental: add the second artist source, folded into the same class.
        OperatorResult step3 = incremental(base, wikidata, List.of(
                align(cls(wikidata, "artists"), cls(artists, "artists"), "Artists", "class"),
                align(prop(artists, "artists", "name"), prop(wikidata, "artists", "name"),
                        "artistName", "datatype"),
                // The new source must also contribute to the join-key column, or its rows sit
                // in the class with a NULL key and can never match. artists.id is already a
                // sub-property of P_l, so this is I3 reuse onto that same key.
                align(prop(artists, "artists", "id"), prop(wikidata, "artists", "id"),
                        "artistKey", "datatype")));
        assertThat(step3.success()).as(String.valueOf(step3.errorMessage())).isTrue();
        promote(step3);

        // ── 3. Catalog straight off the promoted integrated graph.
        MappingCatalog catalog = new MappingCatalogOperator(store, null).getCatalog(tenant);
        // One edge, not one per contributing source: the two JOIN alignments share a P_s, whose
        // two nx:joinOnLeft values QUERY 2 would otherwise cross-product into duplicate rows —
        // which the planner rejects as an ambiguous join-property name.
        assertThat(catalog.joinProperties())
                .as("the n-ary join must collapse to a single catalog edge").hasSize(1);
        String artworksClass = INT + "Artworks";
        assertThat(catalog.classes()).containsKey(artworksClass);
        // All four datasets must be reachable as source views of some integrated class.
        List<String> datasets = catalog.classes().values().stream()
                .flatMap(c -> c.sources().stream())
                .map(MappingCatalog.SourceView::datasetId).toList();
        assertThat(datasets).contains(cmoa, cooperhewitt, artists, wikidata);

        // ── 4a. The integrated class really unions its two artwork sources: the projection
        //        must return rows bootstrapped from JSON and from CSV alike.
        assertThat(runOql(catalog, "SELECT Artworks.title FROM Artworks").rows())
                .as("Artworks must union cmoa (JSON) and cooperhewitt (CSV)")
                .containsExactlyInAnyOrder(
                        List.<Object>of("Guernica"),
                        List.<Object>of("The Persistence of Memory"),
                        List.<Object>of("Convergence"));

        // ── 4b. The join resolves across the *other* pair of sources: a1 comes from the CSV
        //        artist source and a3 from the JSON one, so both sides of this result required
        //        the incremental step that folded wikidata into the same integrated class.
        String artistsClass = localName(catalog.joinProperties().get(0).rightClass());
        String oql = "SELECT Artworks.title, " + artistsClass + ".artistName AS artist "
                + "FROM Artworks -[hasArtist]-> " + artistsClass;

        assertThat(runOql(catalog, oql).rows())
                .as("the join must span all four sources — both artwork sources on the left, "
                        + "both artist sources on the right")
                .containsExactlyInAnyOrder(
                        List.<Object>of("Guernica", "Picasso"),               // cmoa   × artists
                        List.<Object>of("The Persistence of Memory", "Dali"), // cmoa   × wikidata
                        List.<Object>of("Convergence", "Pollock"));           // cooperhewitt × artists
    }

    /**
     * THREE sources on one join edge, where the test above proves TWO.
     *
     * <p>That difference is the whole point. {@code b8a1a3e} taught QUERY 2 to collapse an
     * n-ary join edge into one catalog entry, and the test above pins it — but only ever
     * for two contributing sources. The browser suite's seven-source scenario put a third
     * on the same edge and the planner rejected it with
     * {@code Ambiguous join-property name}, so the collapse is not actually N-agnostic.
     * This reproduces that here, where it is seconds rather than minutes to run.
     *
     * <p>Shape: three artwork sources unioned into one integrated class, each joining the
     * same artist source through JOIN alignments that share a label — so one {@code P_s}
     * should carry three {@code nx:joinOnLeft} values and still resolve to a single edge.
     */
    @Test
    @DisplayName("three sources on one join edge still collapse to a single catalog entry")
    void three_sources_one_join_edge() throws Exception {
        String moma = UUID.randomUUID().toString();

        bootstrap(cmoa, "artworks", "cmoa.json", JSON,
                "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}]");
        bootstrap(cooperhewitt, "artworks", "cooperhewitt.csv", CSV,
                "title,artist_id\nConvergence,a2\n");
        bootstrap(moma, "artworks", "moma.csv", CSV,
                "title,artist_id\nNo. 5,a3\n");
        bootstrap(artists, "artists", "artists.csv", CSV,
                "id,name\na1,Picasso\na2,Pollock\na3,Rothko\n");

        // Baseline: all three artwork sources unioned into one integrated class.
        OperatorResult baseline = compile(List.of(cmoa, cooperhewitt, moma), List.of(
                align(cls(cmoa, "artworks"), cls(cooperhewitt, "artworks"), "Artworks", "class"),
                align(cls(cmoa, "artworks"), cls(moma, "artworks"), "Artworks", "class"),
                align(prop(cmoa, "artworks", "title"), prop(cooperhewitt, "artworks", "title"),
                        "title", "datatype"),
                align(prop(cmoa, "artworks", "title"), prop(moma, "artworks", "title"),
                        "title", "datatype")));
        assertThat(baseline.success()).as(String.valueOf(baseline.errorMessage())).isTrue();
        GraphVersion base = promote(baseline);

        // All three artwork sources onto ONE edge, via a shared join label.
        OperatorResult step2 = incremental(base, artists, List.of(
                align(prop(cmoa, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join"),
                align(prop(cooperhewitt, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join"),
                align(prop(moma, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join")));
        assertThat(step2.success()).as(String.valueOf(step2.errorMessage())).isTrue();
        promote(step2);

        MappingCatalog catalog = new MappingCatalogOperator(store, null).getCatalog(tenant);

        assertThat(catalog.joinProperties())
                .as("three sources sharing one join label must still be ONE edge — more than "
                        + "one entry here is what the planner later rejects as an ambiguous "
                        + "join-property name")
                .hasSize(1);

        String artistsClass = localName(catalog.joinProperties().get(0).rightClass());
        assertThat(runOql(catalog,
                "SELECT Artworks.title, " + artistsClass + ".name AS artist "
                        + "FROM Artworks -[hasArtist]-> " + artistsClass).rows())
                .as("every one of the three artwork sources must reach the artist source")
                .containsExactlyInAnyOrder(
                        List.<Object>of("Guernica", "Picasso"),
                        List.<Object>of("Convergence", "Pollock"),
                        List.<Object>of("No. 5", "Rothko"));
    }

    /**
     * The same three-source edge, but every alignment applied in ONE compile.
     *
     * <p>The test above splits the work: class alignments in a baseline compile, promoted,
     * then the joins in an incremental step. The browser suite's seven-source scenario does
     * not — a user builds all their alignments in the alignments view and presses integrate
     * once, so classes, datatypes and joins are all evaluated in a single pass. Since
     * {@code integrate} runs its phases in order (class, datatype, object, join), the join
     * phase then sees classes that became integrated moments earlier in the same pass
     * rather than in an already-promoted graph — a different starting state, and the one
     * real users actually produce.
     */
    @Test
    @DisplayName("three sources on one join edge, all alignments in a single compile")
    void three_sources_one_join_edge_single_compile() throws Exception {
        String moma = UUID.randomUUID().toString();

        bootstrap(cmoa, "artworks", "cmoa.json", JSON,
                "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}]");
        bootstrap(cooperhewitt, "artworks", "cooperhewitt.csv", CSV,
                "title,artist_id\nConvergence,a2\n");
        bootstrap(moma, "artworks", "moma.csv", CSV,
                "title,artist_id\nNo. 5,a3\n");
        bootstrap(artists, "artists", "artists.csv", CSV,
                "id,name\na1,Picasso\na2,Pollock\na3,Rothko\n");

        OperatorResult all = compile(List.of(cmoa, cooperhewitt, moma, artists), List.of(
                align(cls(cmoa, "artworks"), cls(cooperhewitt, "artworks"), "Artworks", "class"),
                align(cls(cmoa, "artworks"), cls(moma, "artworks"), "Artworks", "class"),
                align(prop(cmoa, "artworks", "title"), prop(cooperhewitt, "artworks", "title"),
                        "title", "datatype"),
                align(prop(cmoa, "artworks", "title"), prop(moma, "artworks", "title"),
                        "title", "datatype"),
                align(prop(cmoa, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join"),
                align(prop(cooperhewitt, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join"),
                align(prop(moma, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join")));
        assertThat(all.success()).as(String.valueOf(all.errorMessage())).isTrue();
        promote(all);

        MappingCatalog catalog = new MappingCatalogOperator(store, null).getCatalog(tenant);

        assertThat(catalog.joinProperties())
                .as("one shared join label must be ONE edge however the alignments were "
                        + "batched — a second entry here is what the planner rejects as an "
                        + "ambiguous join-property name")
                .hasSize(1);
    }

    /**
     * A join key that is ALSO the subject of its own datatype alignment.
     *
     * <p>This is the shape the browser suite's seven-source scenario actually produces, and
     * the one difference the two tests above do not have. There, two artist-side sources are
     * unioned, so their key columns are aligned to each other as a datatype
     * ({@code rid} ≡ {@code id}) — which makes that column {@code rdfs:subPropertyOf} the
     * minted datatype IR. The join alignment then makes the very same column a
     * sub-property of the join's own {@code P_l}. The column now has TWO integrated
     * parents.
     *
     * <p>QUERY 2 resolves each join column through
     * {@code OPTIONAL { ?raw subPropertyOf ?ip . ?ip a IntegratedResource }} and a
     * {@code COALESCE}. An OPTIONAL that matches twice yields two solutions, so the same
     * edge comes back with two different {@code rightCol} values, {@code SELECT DISTINCT}
     * has nothing to collapse, and the planner sees two join properties sharing one local
     * name — exactly the reported {@code Ambiguous join-property name}.
     *
     * <p>If this test fails while the two above pass, the trigger is the double parent, not
     * the arity.
     */
    @Test
    @DisplayName("a join key that also carries a datatype alignment stays one edge")
    void join_key_with_its_own_datatype_alignment() throws Exception {
        String wiki = UUID.randomUUID().toString();

        bootstrap(cmoa, "artworks", "cmoa.json", JSON,
                "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}]");
        bootstrap(artists, "artists", "artists.csv", CSV,
                "id,name\na1,Picasso\n");
        bootstrap(wiki, "artists", "wikidata.json", JSON,
                "[{\"id\":\"a2\",\"name\":\"Dali\"}]");

        OperatorResult all = compile(List.of(cmoa, artists, wiki), List.of(
                // Union the two artist sources, and align their key columns to each other —
                // this is what gives the join key a datatype parent of its own.
                align(cls(artists, "artists"), cls(wiki, "artists"), "Artists", "class"),
                align(prop(artists, "artists", "name"), prop(wiki, "artists", "name"),
                        "artistName", "datatype"),
                align(prop(artists, "artists", "id"), prop(wiki, "artists", "id"),
                        "artistIdKey", "datatype"),
                // ...and now join onto that same column.
                align(prop(cmoa, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join")));
        assertThat(all.success()).as(String.valueOf(all.errorMessage())).isTrue();
        promote(all);

        MappingCatalog catalog = new MappingCatalogOperator(store, null).getCatalog(tenant);

        assertThat(catalog.joinProperties())
                .as("a join column with both a datatype parent and the join's own P_l must "
                        + "still resolve to ONE edge; two entries here are what the planner "
                        + "rejects as an ambiguous join-property name")
                .hasSize(1);
    }

    /**
     * A5's full shape: three sources on the left of one join edge, and a two-source union
     * on the right whose key columns are datatype-aligned to each other.
     *
     * <p>Neither of the tests above combines the two. The three-source test joins onto a
     * single artist source with no datatype alignment on its key; the double-parent test
     * has the key alignment but only ONE source on the left. The browser suite's
     * seven-source scenario has both at once, and after the QUERY 2 fix it still returns
     * ten rows where thirteen are expected — exactly the first source's count, with the
     * other two contributing none.
     *
     * <p>Asserts the union BEFORE the join, which is the experiment that separates the two
     * candidate causes: if the class projection already omits the other sources' rows the
     * union is at fault, and if it returns all of them while the join returns one source's
     * worth, the join is.
     */
    @Test
    @DisplayName("three left sources joining a two-source union keep every row")
    void three_left_sources_joining_a_unioned_right_side() throws Exception {
        String moma = UUID.randomUUID().toString();
        String wiki = UUID.randomUUID().toString();

        bootstrap(cmoa, "artworks", "cmoa.json", JSON,
                "[{\"title\":\"Guernica\",\"artist_id\":\"a1\"}]");
        bootstrap(cooperhewitt, "artworks", "cooperhewitt.csv", CSV,
                "title,artist_id\nConvergence,a2\n");
        bootstrap(moma, "artworks", "moma.csv", CSV,
                "title,artist_id\nNo. 5,a3\n");
        bootstrap(artists, "artists", "artists.csv", CSV,
                "id,name\na1,Picasso\na2,Pollock\n");
        bootstrap(wiki, "artists", "wikidata.json", JSON,
                "[{\"id\":\"a3\",\"name\":\"Rothko\"}]");

        OperatorResult all = compile(List.of(cmoa, cooperhewitt, moma, artists, wiki), List.of(
                align(cls(cmoa, "artworks"), cls(cooperhewitt, "artworks"), "Artworks", "class"),
                align(cls(cmoa, "artworks"), cls(moma, "artworks"), "Artworks", "class"),
                align(prop(cmoa, "artworks", "title"), prop(cooperhewitt, "artworks", "title"),
                        "title", "datatype"),
                align(prop(cmoa, "artworks", "title"), prop(moma, "artworks", "title"),
                        "title", "datatype"),
                align(cls(artists, "artists"), cls(wiki, "artists"), "Artists", "class"),
                align(prop(artists, "artists", "name"), prop(wiki, "artists", "name"),
                        "artistName", "datatype"),
                // Labelled "artistKey" — the JOIN's own label, deliberately, not a name of
                // its own. A second source folded into the right-hand class only takes part
                // in the join if its key column becomes a sub-property of that join's P_l,
                // and the label is what decides which integrated property it lands on. Give
                // it any other name and wiki's rows sit in Artists with a NULL join key and
                // can never match — see the first test in this class, which makes the same
                // point in its own comment. Not a defect; a rule the alignment author has to
                // know, which is the usability half of F22b.
                align(prop(artists, "artists", "id"), prop(wiki, "artists", "id"),
                        "artistKey", "datatype"),
                align(prop(cmoa, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join"),
                align(prop(cooperhewitt, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join"),
                align(prop(moma, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join")));
        assertThat(all.success()).as(String.valueOf(all.errorMessage())).isTrue();
        promote(all);

        MappingCatalog catalog = new MappingCatalogOperator(store, null).getCatalog(tenant);
        assertThat(catalog.joinProperties()).hasSize(1);

        // The union first — this is what tells the two candidate causes apart.
        assertThat(runOql(catalog, "SELECT Artworks.title FROM Artworks").rows())
                .as("all three artwork sources must be in the integrated class BEFORE any join")
                .containsExactlyInAnyOrder(
                        List.<Object>of("Guernica"),
                        List.<Object>of("Convergence"),
                        List.<Object>of("No. 5"));

        assertThat(runOql(catalog,
                "SELECT Artworks.title, Artists.artistName AS artist "
                        + "FROM Artworks -[hasArtist]-> Artists").rows())
                .as("every left source must match through the shared edge, and the right-hand "
                        + "union must supply artists from both of its sources")
                .containsExactlyInAnyOrder(
                        List.<Object>of("Guernica", "Picasso"),
                        List.<Object>of("Convergence", "Pollock"),
                        List.<Object>of("No. 5", "Rothko"));
    }

    /**
     * A three-hop chain: Artworks → Artists → Countries → Continents.
     *
     * <p>The browser suite's seven-source scenario stops at two hops, and two hops cannot
     * distinguish "n-hop works" from "the second hop happens to be the last one". The
     * structural risk lives in {@code OqlPlanner.buildScope}: each hop resolves its left key
     * with {@code builder.field(2, 0, leftCol)}, which looks the column up <em>by name across
     * the whole accumulated left side</em> — every class joined so far, not just the previous
     * one. Two hops only ever search one prior class; three search two, so a third hop is the
     * first that can find the name ambiguous or resolve it against the wrong table.
     *
     * <p>Each class here has exactly one source, so this isolates hop depth from union width.
     */
    @Test
    @DisplayName("a three-hop path threads all the way through")
    void three_hop_path() throws Exception {
        String countries  = UUID.randomUUID().toString();
        String continents = UUID.randomUUID().toString();

        bootstrap(cmoa, "artworks", "artworks.csv", CSV,
                "title,artist_id\nGuernica,a1\nConvergence,a2\nNo. 5,a3\n");
        bootstrap(artists, "artists", "artists.csv", CSV,
                "id,name,country_code\na1,Picasso,ES\na2,Pollock,US\na3,Rothko,US\n");
        bootstrap(countries, "countries", "countries.csv", CSV,
                "code,country,continent_code\nES,Spain,EU\nUS,United States,AM\n");
        bootstrap(continents, "continents", "continents.csv", CSV,
                "code,continent\nEU,Europe\nAM,Americas\n");

        OperatorResult chain = compile(List.of(cmoa, artists, countries, continents), List.of(
                align(prop(cmoa, "artworks", "artist_id"), prop(artists, "artists", "id"),
                        "artistKey|hasArtist|forward", "join"),
                align(prop(artists, "artists", "country_code"), prop(countries, "countries", "code"),
                        "countryKey|hasCountry|forward", "join"),
                align(prop(countries, "countries", "continent_code"),
                        prop(continents, "continents", "code"),
                        "continentKey|hasContinent|forward", "join")));
        assertThat(chain.success()).as(String.valueOf(chain.errorMessage())).isTrue();
        promote(chain);

        MappingCatalog catalog = new MappingCatalogOperator(store, null).getCatalog(tenant);
        assertThat(catalog.joinProperties())
                .as("three declared edges must produce three catalog entries")
                .hasSize(3);

        // Derive every class name from the catalog rather than assuming how the
        // integrator names a class that came from a single source.
        String artworksCls   = localName(join(catalog, "hasArtist").leftClass());
        String artistsCls    = localName(join(catalog, "hasArtist").rightClass());
        String countriesCls  = localName(join(catalog, "hasCountry").rightClass());
        String continentsCls = localName(join(catalog, "hasContinent").rightClass());

        assertThat(runOql(catalog,
                "SELECT " + artworksCls + ".title, " + artistsCls + ".name AS artist, "
                        + countriesCls + ".country, " + continentsCls + ".continent "
                        + "FROM " + artworksCls + " -[hasArtist]-> " + artistsCls
                        + " -[hasCountry]-> " + countriesCls
                        + " -[hasContinent]-> " + continentsCls).rows())
                .as("all three hops must resolve against the right table")
                .containsExactlyInAnyOrder(
                        List.<Object>of("Guernica", "Picasso", "Spain", "Europe"),
                        List.<Object>of("Convergence", "Pollock", "United States", "Americas"),
                        List.<Object>of("No. 5", "Rothko", "United States", "Americas"));
    }

    // ── pipeline steps ─────────────────────────────────────────────────────────

    /** The catalog edge whose relationship name is used in OQL, e.g. {@code -[hasArtist]->}. */
    private static MappingCatalog.JoinProperty join(MappingCatalog catalog, String relationship) {
        return catalog.joinProperties().stream()
                .filter(j -> localName(j.iri()).equals(relationship))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no join named '" + relationship + "'; catalog has "
                        + catalog.joinProperties().stream()
                                .map(j -> localName(j.iri())).toList()));
    }

    private static final SourceConnector CSV = new CsvConnector();
    private static final SourceConnector JSON = new JsonConnector();

    /** Bootstrap bytes into a source graph, attach wrappers, stage the snapshot file. */
    private void bootstrap(String datasetId, String datasetName, String fileName,
                           SourceConnector connector, String content) throws IOException {
        String suffix = fileName.substring(fileName.lastIndexOf('.'));
        Path file = Files.createTempFile("aran-e2e-" + datasetName + "-", suffix);
        tempFiles.add(file);
        Files.writeString(file, content);
        snapshots.put(datasetId, file);

        SourceDescriptor descriptor = SourceDescriptor.ofFile(
                datasetId, datasetName, "snap-" + datasetId, null, fileName, null,
                content.getBytes(StandardCharsets.UTF_8));
        Model schema = connector.introspect(descriptor).rdfs();
        WrapperGenerator.attachPerClassWrappers(schema);
        store.addTriples(tenant, BootstrapEngine.sourceGraphUri(datasetId), schema);
    }

    private OperatorResult compile(List<String> datasetIds, List<Map<String, String>> alignments) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sourceDatasetIds", json(datasetIds));
        params.put("alignments", json(alignments));
        params.put("modelId", MODEL.toString());
        return integration.execute(new OperatorRequest(TENANT, USER, "compile", params));
    }

    private OperatorResult incremental(GraphVersion base, String newDatasetId,
                                       List<Map<String, String>> alignments) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("modelId", MODEL.toString());
        params.put("baseVersionId", base.id().toString());
        params.put("newDatasetId", newDatasetId);
        params.put("newMappings", json(alignments));
        params.put("deferredMappings", "[]");
        return integration.execute(
                new OperatorRequest(TENANT, USER, "incremental-integrate", params));
    }

    private GraphVersion promote(OperatorResult r) {
        return store.promote(tenant, UUID.fromString(r.data().get("draftVersionId")),
                new AuditedActor(USER));
    }

    /** The real engine path: parse → plan → optimize → SQL → DuckDB. */
    private DuckDbExecutor.QueryResult runOql(MappingCatalog catalog, String oql) throws Exception {
        DuckDbExecutor.SnapshotResolver resolver = ds -> {
            Path p = snapshots.get(ds);
            if (p == null) throw new IllegalArgumentException("unknown dataset: " + ds);
            String fn = p.toAbsolutePath().toString();
            return fn.endsWith(".csv") ? "read_csv_auto('" + fn + "')" : "read_json_auto('" + fn + "')";
        };
        RelNode logical = new OqlPlanner(catalog).lower(OqlParser.parse(oql));
        RelNode optimized = PlanExecutor.optimize(logical);
        String sql = PlanExecutor.toSqlFromOptimized(optimized);
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver)) {
            exec.prepare();
            return exec.run(sql);
        }
    }

    // ── naming helpers (must match BootstrapEngine's IRI scheme) ───────────────

    private static String cls(String datasetId, String datasetName) {
        return NS + "source/" + datasetId + "/" + datasetName;
    }

    private static String prop(String datasetId, String datasetName, String column) {
        return cls(datasetId, datasetName) + "/" + column;
    }

    private static String localName(String iri) {
        return iri.substring(Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#')) + 1);
    }

    private static Map<String, String> align(String iriA, String iriB, String label, String type) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("iriA", iriA);
        row.put("iriB", iriB);
        row.put("label", label);
        row.put("type", type);
        return row;
    }

    private String json(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

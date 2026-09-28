package edu.upc.dtak.aran.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.Names;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.calcite.rel.RelNode;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The docs walkthrough ({@code docs-site/docs/getting-started/walkthrough.md}) performed
 * in-process: the real connectors bootstrap {@code demo/seminar}'s three files, the real
 * integration operator compiles the page's own alignment tables, the page's own Step 8
 * constraints are enforced, and each of Step 9's queries — read from the page's code blocks —
 * runs through the real planner on DuckDB.
 *
 * <p>The browser suite's {@code t1-walkthrough} performs the same page through the UI, but it
 * needs a compose stack and ten minutes; this runs on every build, with no Docker. The page had
 * rotted three ways at once before either existed (S4-2 refused its JSON fixture, its join label
 * equalled its relationship name, its wrappers renamed the columns the integrated views select),
 * each found by running it in-process exactly like this.
 *
 * <p>Expected rows come from the fixture files, never from the page's prose. Step 6 (join
 * discovery) is the one step not performed: it needs the freyja sidecar, and its outcome — a
 * ranking — changes no row here.
 */
@DisplayName("The docs walkthrough, in-process — bootstrap ×3 → compile → constraints → Q1–Q5")
class WalkthroughEndToEndTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String TENANT = UUID.randomUUID().toString();
    private static final UUID USER = UUID.randomUUID();
    private static final String NULL = "∅";
    /**
     * How the page's queries name the researchers' class. Asserted against the name the catalog
     * mints, never substituted: a reader types the page's name, so a naming change must fail here.
     */
    private static final String PAGE_RESEARCHER_CLASS = "Int_researchers";

    private static Path seminar;
    private static Map<String, Section> page;

    private final TenantContext tenant = new TenantContext(TENANT);
    private final ObjectMapper om = new ObjectMapper();
    private final InMemoryGraphStore store = new InMemoryGraphStore();
    private final SchemaIntegrationOperator integration =
            new SchemaIntegrationOperator(store, om, mock(DatasetRepository.class));
    private final Map<String, String> datasetIds = new LinkedHashMap<>();
    private final Map<String, Path> snapshots = new HashMap<>();

    @BeforeAll
    static void readThePage() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above " + System.getProperty("user.dir")).isNotNull();
        seminar = dir.resolve("demo/seminar");
        page = sections(Files.readString(
                dir.resolve("docs-site/docs/getting-started/walkthrough.md")));
    }

    // ── the page's queries, pinned to the rows the fixtures produce ────────────

    private record Query(String oql, List<String> columns,
                         BiFunction<List<PaperAuthor>, Function<PaperAuthor, Researcher>,
                                 List<List<Object>>> expected) {}

    private static final Map<String, Query> QUERIES = new LinkedHashMap<>();
    static {
        QUERIES.put("Q1 — The union, visible", new Query("SELECT IPaper.*",
                List.of("pid", "authorId", "title", "year", "venue", "citation_count"),
                (rows, of) -> rows.stream().map(r -> row(r.pid, r.authorId, r.title, r.year,
                        r.venue, r.citationCount)).toList()));
        QUERIES.put("Q2 — Traverse the join", new Query(
                "SELECT IPaper.title, Int_researchers.full_name, Int_researchers.affiliation "
                        + "FROM IPaper -[hasAuthor]-> Int_researchers",
                List.of("title", "full_name", "affiliation"),
                (rows, of) -> rows.stream().map(r -> row(r.title, of.apply(r).fullName,
                        of.apply(r).affiliation)).toList()));
        QUERIES.put("Q3 — Filter on the right-hand class", new Query(
                "SELECT IPaper.title, IPaper.year, Int_researchers.full_name "
                        + "FROM IPaper -[hasAuthor]-> Int_researchers "
                        + "WHERE Int_researchers.affiliation = 'UPC'",
                List.of("title", "year", "full_name"),
                (rows, of) -> rows.stream().filter(r -> of.apply(r).affiliation.equals("UPC"))
                        .map(r -> row(r.title, r.year, of.apply(r).fullName)).toList()));
        QUERIES.put("Q4 — Filter on the left-hand class", new Query(
                "SELECT IPaper.title, Int_researchers.full_name, Int_researchers.affiliation "
                        + "FROM IPaper -[hasAuthor]-> Int_researchers "
                        + "WHERE IPaper.year > 2023",
                List.of("title", "full_name", "affiliation"),
                (rows, of) -> rows.stream().filter(r -> r.year > 2023)
                        .map(r -> row(r.title, of.apply(r).fullName, of.apply(r).affiliation))
                        .toList()));
        QUERIES.put("Q5 — An aggregate", new Query(
                "SELECT Int_researchers.affiliation, COUNT(*) AS paper_count "
                        + "FROM IPaper -[hasAuthor]-> Int_researchers "
                        + "GROUP BY Int_researchers.affiliation "
                        + "HAVING COUNT(*) > 5",
                List.of("affiliation", "paper_count"),
                (rows, of) -> rows.stream()
                        .collect(Collectors.groupingBy(r -> of.apply(r).affiliation,
                                LinkedHashMap::new, Collectors.counting()))
                        .entrySet().stream().filter(e -> e.getValue() > 5)
                        .map(e -> row(e.getKey(), e.getValue())).toList()));
    }

    @Test
    @DisplayName("a reader following the walkthrough gets the rows Step 9 promises")
    void the_walkthrough_answers_q1_to_q5() throws Exception {
        // Step 3 — the three uploads, bootstrapped by the connectors the upload path uses.
        assertThat(prose(section("Step 3 — Upload the three datasets")))
                .contains("use `acm`, `semantic`, and `researchers`");
        bootstrap("acm", "acm_papers.csv", new CsvConnector());
        bootstrap("semantic", "semantic_papers.json", new JsonConnector());
        bootstrap("researchers", "researchers.json", new JsonConnector());

        // Step 4 — the seeded Semantic Scholar wrapper still says what the page quotes.
        String unnest = fencedBlocks(section("Step 4 — Enroll the datasets and check their wrappers"))
                .get(0);
        assertThat(wrapperSqlOf(sourceGraph("semantic"))).contains(unnest);

        // Step 5 — the page's two alignment tables, as the Alignments view sends them.
        List<Map<String, String>> alignments = new ArrayList<>();
        Table union = backtickTable(section("5a — Union the two paper sources into IPaper"));
        for (List<String> r : union.rows()) {
            String kind = r.get(2);
            alignments.add(align(node(union.header().get(0), r.get(0), kind),
                    node(union.header().get(1), r.get(1), kind), r.get(3), kind));
        }
        Table join = backtickTable(section("5b — Join the papers to their researchers"));
        assertThat(join.rows()).hasSize(1);
        List<String> j = join.rows().get(0);
        alignments.add(align(node(join.header().get(0), j.get(0), j.get(2)),
                node(join.header().get(1), j.get(1), j.get(2)),
                j.get(3) + "|" + j.get(4) + "|forward", j.get(2)));

        // Step 7 — rebuild all three enrolled sources, then promote.
        OperatorResult compiled = integration.execute(new OperatorRequest(TENANT, USER, "compile",
                Map.of("sourceDatasetIds", om.writeValueAsString(List.copyOf(datasetIds.values())),
                        "alignments", om.writeValueAsString(alignments),
                        "modelId", UUID.randomUUID().toString())));
        assertThat(compiled.success())
                .as("compile: %s / %s", compiled.errorCode(), compiled.errorMessage()).isTrue();
        store.promote(tenant, UUID.fromString(compiled.data().get("draftVersionId")),
                new AuditedActor(USER));
        MappingCatalog catalog = new MappingCatalogOperator(store, null).getCatalog(tenant);

        String relationship = j.get(4);
        MappingCatalog.JoinProperty edge = catalog.joinProperties().stream()
                .filter(p -> Names.localName(p.iri()).equals(relationship)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + relationship + "' edge in "
                        + catalog.joinProperties()));
        String paperClass = union.rows().get(0).get(3);
        assertThat(Names.localName(edge.leftClass())).isEqualTo(paperClass);
        assertThat(Names.localName(edge.rightClass()))
                .as("the class the page's queries name").isEqualTo(PAGE_RESEARCHER_CLASS);

        // Step 8 — the page's constraints, enforced on the two integrated classes.
        List<String> blocks = fencedBlocks(section("Step 8 — Add denial constraints"));
        assertThat(blocks).as("one constraint block per class").hasSize(2);
        Map<String, List<String>> enforced = Map.of(
                edge.leftClass(), lines(blocks.get(0)),
                edge.rightClass(), lines(blocks.get(1)));

        // Step 9 — each query, off the page, against the rows the fixtures produce.
        List<PaperAuthor> rows = paperAuthorRows();
        Map<String, Researcher> researchers = researchers();
        Function<PaperAuthor, Researcher> researcherOf = r -> Objects.requireNonNull(
                researchers.get(r.authorId), r.pid + "'s author " + r.authorId + " is unknown");
        for (Map.Entry<String, Query> q : QUERIES.entrySet()) {
            List<String> code = fencedBlocks(section(q.getKey()));
            assertThat(code).as("%s holds exactly one query", q.getKey()).hasSize(1);
            assertThat(code.get(0).replaceAll("\\s+", " ").trim())
                    .as("the page's %s changed — update QUERIES with it", q.getKey())
                    .isEqualTo(q.getValue().oql());
            DuckDbExecutor.QueryResult result = runOql(catalog, enforced, code.get(0));

            assertThat(rowKeys(project(result, q.getValue().columns(), q.getKey())))
                    .as(q.getKey())
                    .isEqualTo(rowKeys(q.getValue().expected().apply(rows, researcherOf)));
        }
    }

    // ── pipeline ───────────────────────────────────────────────────────────────

    private void bootstrap(String name, String file, SourceConnector connector) throws IOException {
        String datasetId = UUID.randomUUID().toString();
        datasetIds.put(name, datasetId);
        Path path = seminar.resolve(file);
        snapshots.put(datasetId, path);
        SourceDescriptor descriptor = SourceDescriptor.ofFile(datasetId, name, "snap-" + datasetId,
                null, file, null, Files.readAllBytes(path));
        Model schema = connector.introspect(descriptor).rdfs();
        WrapperGenerator.attachPerClassWrappers(schema);
        store.addTriples(tenant, BootstrapEngine.sourceGraphUri(datasetId), schema);
    }

    private Model sourceGraph(String name) {
        return store.query(tenant, "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <"
                + BootstrapEngine.sourceGraphUri(datasetIds.get(name)) + "> { ?s ?p ?o } }");
    }

    /** A node of the named source's schema graph, picked by its label as the Alignments view shows it. */
    private String node(String dataset, String label, String kind) {
        Model graph = sourceGraph(dataset);
        Resource type = "class".equals(kind) ? RDFS.Class : RDF.Property;
        return graph.listResourcesWithProperty(RDFS.label, label)
                .filterKeep(r -> graph.contains(r, RDF.type, type))
                .toList().stream().map(Resource::getURI).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + kind + " node labelled '" + label + "' in " + dataset));
    }

    private static String wrapperSqlOf(Model source) {
        return source.listObjectsOfProperty(source.createProperty(NS + "wrapperSql")).toList()
                .stream().map(n -> n.asLiteral().getString()).findFirst()
                .orElseThrow(() -> new AssertionError("no nx:wrapperSql on the source graph"));
    }

    private DuckDbExecutor.QueryResult runOql(MappingCatalog catalog,
                                              Map<String, List<String>> enforced, String oql)
            throws Exception {
        DuckDbExecutor.SnapshotResolver resolver = ds -> {
            String fn = Objects.requireNonNull(snapshots.get(ds), ds).toAbsolutePath().toString();
            return fn.endsWith(".csv") ? "read_csv_auto('" + fn + "')" : "read_json_auto('" + fn + "')";
        };
        RelNode logical = new OqlPlanner(catalog, enforced).lower(OqlParser.parse(oql));
        String sql = PlanExecutor.toSqlFromOptimized(PlanExecutor.optimize(logical));
        try (DuckDbExecutor exec = new DuckDbExecutor(catalog, resolver, enforced)) {
            exec.prepare();
            return exec.run(sql);
        }
    }

    /** The named columns of each row, in order; a star query's headers are full IRIs. */
    private static List<List<Object>> project(DuckDbExecutor.QueryResult result,
                                              List<String> columns, String heading) {
        List<String> locals = result.headers().stream().map(Names::localName).toList();
        List<Integer> at = columns.stream().map(c -> {
            int i = locals.indexOf(c);
            assertThat(i).as("%s has no '%s' column: %s", heading, c, result.headers())
                    .isNotNegative();
            return i;
        }).toList();
        return result.rows().stream()
                .map(r -> at.stream().map(r::get).toList()).toList();
    }

    /** Rows as sorted, comparable strings — a mis-joined pair cannot hide in a column check. */
    private static List<String> rowKeys(List<List<Object>> rows) {
        return rows.stream()
                .map(r -> r.stream().map(c -> c == null ? NULL : String.valueOf(c))
                        .collect(Collectors.joining(" | ")))
                .sorted().toList();
    }

    private static List<Object> row(@Nullable Object... cells) {
        return java.util.Arrays.asList(cells);
    }

    private Map<String, String> align(String iriA, String iriB, String label, String type) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("iriA", iriA);
        row.put("iriB", iriB);
        row.put("label", label);
        row.put("type", type);
        return row;
    }

    // ── the fixtures ───────────────────────────────────────────────────────────

    private record PaperAuthor(String pid, String title, int year, @Nullable String venue,
                               @Nullable Integer citationCount, String authorId) {}

    private record Researcher(String fullName, String affiliation) {}

    /** One row per (paper, author) across both paper sources — IPaper's grain once unnested. */
    private List<PaperAuthor> paperAuthorRows() throws IOException {
        List<String> csv = Files.readAllLines(seminar.resolve("acm_papers.csv"));
        List<String> header = List.of(csv.get(0).split(","));
        List<PaperAuthor> rows = new ArrayList<>();
        for (String line : csv.subList(1, csv.size())) {
            if (line.isBlank()) continue;
            String[] cells = line.split(",");
            // No quoted fields in the file; a comma inside a title would shift every later column.
            assertThat(cells).as("acm_papers.csv: %s", line).hasSize(header.size());
            Function<String, String> c = col -> cells[header.indexOf(col)];
            rows.add(new PaperAuthor(c.apply("paper_id"), c.apply("title"),
                    Integer.parseInt(c.apply("year")), c.apply("venue"), null, c.apply("author_id")));
        }
        for (JsonNode p : om.readTree(seminar.resolve("semantic_papers.json").toFile())) {
            for (JsonNode author : p.get("authors")) {
                rows.add(new PaperAuthor(p.get("doi").asText(), p.get("title").asText(),
                        p.get("year").asInt(), null, p.get("citation_count").asInt(),
                        author.asText()));
            }
        }
        return rows;
    }

    private Map<String, Researcher> researchers() throws IOException {
        Map<String, Researcher> byId = new HashMap<>();
        for (JsonNode r : om.readTree(seminar.resolve("researchers.json").toFile())) {
            byId.put(r.get("researcher_id").asText(),
                    new Researcher(r.get("full_name").asText(), r.get("affiliation").asText()));
        }
        return byId;
    }

    // ── the page ───────────────────────────────────────────────────────────────

    private record Section(int level, String body) {}

    private record Table(List<String> header, List<List<String>> rows) {}

    private static final Pattern HEADING = Pattern.compile("^(#{2,3}) (.+)$");
    private static final Pattern FENCE = Pattern.compile("(?ms)^```[^\\n]*\\n(.*?)^```$");
    private static final Pattern TICKS = Pattern.compile("`([^`]+)`");

    /** Every {@code ##}/{@code ###} section, fenced code skipped when looking for headings. */
    private static Map<String, Section> sections(String markdown) {
        List<String> lines = List.of(markdown.split("\n", -1));
        List<int[]> heads = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("```")) inFence = !inFence;
            Matcher m = HEADING.matcher(lines.get(i));
            if (!inFence && m.matches()) {
                heads.add(new int[] {i, m.group(1).length()});
                names.add(m.group(2).trim());
            }
        }
        Map<String, Section> out = new LinkedHashMap<>();
        for (int h = 0; h < heads.size(); h++) {
            int level = heads.get(h)[1];
            int end = lines.size();
            for (int n = h + 1; n < heads.size(); n++) {
                if (heads.get(n)[1] <= level) {
                    end = heads.get(n)[0];
                    break;
                }
            }
            out.put(names.get(h), new Section(level,
                    String.join("\n", lines.subList(heads.get(h)[0] + 1, end))));
        }
        return out;
    }

    private static String section(String heading) {
        Section s = page.get(heading);
        assertThat(s).as("walkthrough has no heading '%s'; it has %s", heading, page.keySet())
                .isNotNull();
        return s.body();
    }

    private static List<String> fencedBlocks(String body) {
        List<String> blocks = new ArrayList<>();
        Matcher m = FENCE.matcher(body);
        while (m.find()) blocks.add(m.group(1).trim());
        return blocks;
    }

    private static List<String> lines(String block) {
        return block.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    /** The first markdown table: backticked tokens per row — what a reader copies and types. */
    private static Table backtickTable(String body) {
        List<String> rows = body.lines().filter(l -> l.trim().startsWith("|")).toList();
        assertThat(rows.size()).as("a table: header, separator, rows").isGreaterThanOrEqualTo(3);
        Function<String, List<String>> ticks = l -> {
            List<String> out = new ArrayList<>();
            Matcher m = TICKS.matcher(l);
            while (m.find()) out.add(m.group(1));
            return out;
        };
        return new Table(ticks.apply(rows.get(0)),
                rows.subList(2, rows.size()).stream().map(ticks).toList());
    }

    private static String prose(String body) {
        return body.replaceAll("\\s+", " ");
    }
}

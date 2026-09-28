package edu.upc.dtak.aran.operators.queryengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the operator itself — the module's largest class, previously covered only by its
 * manifest and its SQL guard.
 */
class QueryEngineOperatorBehaviourTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    // A field (not an inline mock() at the call site) so verify-wrapper's tests can stub the
    // (datasetId, snapshotId, format) triples snapshotResolver reads — see wireSnapshot below.
    private final GraphStore graphStore = mock(GraphStore.class);
    private final LakeClient lake = mock(LakeClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    // Disabled cache: these tests exercise operator behaviour, not caching.
    private final SnapshotCache snapshotCache = new SnapshotCache(lake, "", 0L);
    private final StubProductRepository products = new StubProductRepository();
    // A field so verify-wrapper's bind check can be told the dataset row's current snapshot.
    private final DatasetRepository datasets = mock(DatasetRepository.class);

    private QueryEngineOperator operator() {
        return new QueryEngineOperator(
                graphStore, catalogOperator, lake, mapper,
                mock(IntegrationModelRepository.class),
                datasets,
                mock(DataRepositoryRepository.class),
                snapshotCache,
                products);
    }

    private static OperatorRequest request(String operation, Map<String, String> params) {
        return new OperatorRequest(TENANT, null, operation, params);
    }

    // ── dispatch and guards ──────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown operation names the ones that exist")
    void unknownOperation() {
        OperatorResult result = operator().execute(request("nope", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNKNOWN_OPERATION");
        assertThat(result.errorMessage()).contains("run-oql").contains("preview-parquet");
    }

    @Test
    @DisplayName("run rejects a forbidden function before it touches the catalog")
    void runRejectsForbiddenFunction() {
        // No catalog stubbing: if the guard did not short-circuit, the mock would return null
        // and this would fail differently. The guard has to come first.
        OperatorResult result = operator().execute(request("run",
                Map.of("sql", "SELECT * FROM read_csv_auto('/etc/passwd')")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_SQL");
        assertThat(result.errorMessage()).contains("read_csv_auto");
    }

    @Test
    @DisplayName("a non-numeric preview limit is rejected rather than defaulted")
    void previewRejectsNonNumericLimit() {
        OperatorResult result = operator().execute(request("preview-parquet",
                Map.of("snapshotId", UUID.randomUUID().toString(), "limit", "all")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_LIMIT");
    }

    @Test
    @DisplayName("a blank snapshot id is rejected")
    void previewRequiresSnapshotId() {
        OperatorResult result = operator().execute(request("preview-parquet",
                Map.of("snapshotId", "   ")));

        assertThat(result.errorCode()).isEqualTo("INVALID_SNAPSHOT");
    }

    @Test
    @DisplayName("an unparseable tenant id fails as a message, not a stack trace")
    void badTenantId() {
        OperatorResult result = operator().execute(new OperatorRequest(
                "not-a-uuid", null, "preview-parquet",
                Map.of("snapshotId", UUID.randomUUID().toString())));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Invalid tenant identifier");
    }

    // ── error reporting ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the catch-all reports the root cause, not the wrapper's fixed message")
    void rootCauseReachesTheCaller() {
        // Everything in this operator that touches DuckDB wraps failures in a RuntimeException
        // with a constant message ("Query execution failed"), and the catch-all used to report
        // e.getMessage(). A user who mistyped a column name was told nothing at all.
        when(catalogOperator.getCatalog(any())).thenThrow(
                new RuntimeException("Query execution failed",
                        new IllegalStateException("Binder Error: Referenced column \"titel\" not found")));

        OperatorResult result = operator().execute(request("run",
                Map.of("sql", "SELECT titel FROM int_Paper")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
                .as("the DuckDB diagnostic must survive to the caller")
                .contains("Referenced column")
                .contains("titel");
    }

    // ── rewrite-oql / run-oql plan shape ───────────────────────────────────────

    private static final String NS  = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String SRC = NS + "source/";
    private static final String ARTWORKS   = NS + "integration/Artworks";
    private static final String IARTIST    = NS + "integration/IArtist";
    private static final String P_TITLE    = NS + "integration/title";
    private static final String P_NAME     = NS + "integration/name";
    private static final String P_AID      = SRC + "art/artist_id";
    private static final String P_ID       = SRC + "artist/id";
    private static final String HAS_ARTIST = NS + "integration/hasArtist";

    /** Same two-class join catalog as {@code PlanShapeTest.joinCatalog()} — a fixture already
     * proven to optimize into JOIN-over-two-SCANs shape. */
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

    @Test
    @DisplayName("rewrite-oql payload carries planShape + planShapeText alongside the plan strings")
    void rewriteOqlCarriesPlanShape() {
        when(catalogOperator.getCatalog(any())).thenReturn(joinCatalog());

        OperatorResult result = operator().execute(request("rewrite-oql", Map.of(
                "oql", "SELECT Artworks.title, IArtist.name AS artist "
                        + "FROM Artworks -[hasArtist]-> IArtist")));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("planShapeText");
        assertThat(result.data().get("planShapeText")).contains("SCAN[");
        assertThat(result.data()).containsKey("planShape");
        assertThat(result.data().get("planShape")).contains("\"kind\"");
        // regression guard: the pre-existing fields are still present, unaffected
        assertThat(result.data()).containsKeys("sql", "logicalPlan", "optimizedPlan");
    }

    @Test
    @DisplayName("rewrite-oql payload carries the columns a VIRTUAL product records as its "
            + "output_schema — derived from the row type, no execution")
    void rewriteOqlCarriesColumns() throws Exception {
        when(catalogOperator.getCatalog(any())).thenReturn(joinCatalog());

        OperatorResult result = operator().execute(request("rewrite-oql", Map.of(
                "oql", "SELECT Artworks.title, IArtist.name AS artist "
                        + "FROM Artworks -[hasArtist]-> IArtist")));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("columns");
        List<ProductColumn> columns = ProductColumn.fromJson(result.data().get("columns"));
        // A plain (non-star) projection names its output column from the OQL's own local property
        // name or explicit alias (OqlPlanner.buildProject: `p.as() != null ? p.as() : p.property()`)
        // — not the full IRI, which only star-expansion (Class.*) uses. "title" here, not P_TITLE.
        assertThat(columns).extracting(ProductColumn::name)
                .containsExactly("title", "artist");
        // No catalog/lake interaction beyond the one getCatalog() stub call rewrite-oql always
        // makes: verifying the mock was never asked for anything DuckDB-shaped is the negative
        // half of "run nothing" — materialize/preview/lake methods simply do not exist on these
        // mocks for this test to invoke by accident.
        verifyNoInteractions(lake);
    }

    // ── preview-parquet ──────────────────────────────────────────────────────

    @Test
    @DisplayName("preview keeps numeric and boolean columns typed, like run and run-oql")
    void previewPreservesTypes() throws Exception {
        Path parquet = writeParquet("SELECT 42 AS n, 'text' AS s, true AS b, NULL::INTEGER AS z");
        try {
            when(lake.downloadSnapshot(any(UUID.class), anyString()))
                    .thenReturn(new LakeClient.SnapshotBytes(
                            Files.readAllBytes(parquet), "application/octet-stream"));

            OperatorResult result = operator().execute(request("preview-parquet",
                    Map.of("snapshotId", UUID.randomUUID().toString())));

            assertThat(result.success()).isTrue();
            JsonNode row = mapper.readTree(result.data().get("result")).get("rows").get(0);

            // This path called toString() on every value, so the whole row arrived as strings
            // and the shared ResultTable sorted a product preview differently from the same
            // data in the workbench.
            assertThat(row.get(0).isNumber()).as("42 must stay a number: %s", row).isTrue();
            assertThat(row.get(0).intValue()).isEqualTo(42);
            assertThat(row.get(1).isTextual()).isTrue();
            assertThat(row.get(2).isBoolean()).as("true must stay a boolean: %s", row).isTrue();
            assertThat(row.get(3).isNull()).as("NULL must stay null, not \"null\"").isTrue();
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    @Test
    @DisplayName("preview clamps the limit into range and reports truncation")
    void previewClampsLimit() throws Exception {
        Path parquet = writeParquet("SELECT * FROM range(10) t(n)");
        try {
            when(lake.downloadSnapshot(any(UUID.class), anyString()))
                    .thenReturn(new LakeClient.SnapshotBytes(
                            Files.readAllBytes(parquet), "application/octet-stream"));

            Map<String, String> params = new LinkedHashMap<>();
            params.put("snapshotId", UUID.randomUUID().toString());
            params.put("limit", "3");
            OperatorResult result = operator().execute(request("preview-parquet", params));

            JsonNode payload = mapper.readTree(result.data().get("result"));
            assertThat(payload.get("rows")).hasSize(3);
            assertThat(payload.get("truncated").asBoolean()).isTrue();

            params.put("limit", "-5");   // clamped up to 1, not passed through as a negative LIMIT
            JsonNode clamped = mapper.readTree(
                    operator().execute(request("preview-parquet", params)).data().get("result"));
            assertThat(clamped.get("rows")).hasSize(1);
        } finally {
            Files.deleteIfExists(parquet);
        }
    }

    // ── verify-wrapper (compile-time round-trip check) ─────────────────────────

    private static final String NX = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String VW_DATASET = "ds1";
    private static final String VW_CLASS = "urn:IThing";
    private static final String VW_PROP_A = "http://ex/a";
    private static final String VW_PROP_B = "http://ex/b";

    private static MappingCatalog catalogWithSourceView(MappingCatalog.SourceView sv, String classIri) {
        return new MappingCatalog(
                Map.of(classIri, new MappingCatalog.IntegratedClass(classIri, List.of(sv))),
                List.of(), List.of(), "v1");
    }

    /** A source view whose raw columns "a"/"b" are recorded via {@code sourceRefs} (post-V31 shape). */
    private static MappingCatalog.SourceView sourceViewForVerify() {
        return new MappingCatalog.SourceView(
                VW_DATASET, "SELECT * FROM ${SNAPSHOT}", Map.of(), 0L, List.of(), "",
                Map.of(), Map.of("a", VW_PROP_A, "b", VW_PROP_B), "OPAQUE");
    }

    /** Wires snapshotResolver's two reads for {@code datasetId}: the schema-graph triples
     *  ({@code snapshotMap}) and the lake download itself, writing {@code json} to the temp file
     *  the resolver creates. Mirrors {@code SourceSqlMaterializeTest.wireCatalogAndSnapshot}. */
    private void wireSnapshot(String datasetId, String json) {
        String snapshotId = "snap-" + datasetId;
        Model m = ModelFactory.createDefaultModel();
        Resource c = m.createResource("urn:snap-subject:" + datasetId);
        m.add(c, m.createProperty(NX + "datasetId"), datasetId);
        m.add(c, m.createProperty(NX + "snapshotId"), snapshotId);
        m.add(c, m.createProperty(NX + "format"), "json");
        when(graphStore.query(any(), anyString())).thenReturn(m);

        when(lake.downloadSnapshotTo(any(), eq(snapshotId), any(Path.class))).thenAnswer(inv -> {
            Path target = inv.getArgument(2);
            Files.writeString(target, json);
            return target.toAbsolutePath().toString();
        });
    }

    @Test
    @DisplayName("verify-wrapper: a pure projection over the raw snapshot columns verifies as EXPANDED")
    void verifyWrapperPureProjectionIsExpanded() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(sourceViewForVerify(), VW_CLASS));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"},{\"a\":\"x2\",\"b\":\"y2\"}]");

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS,
                "sql", "SELECT \"a\" AS x, \"b\" AS y FROM ${SNAPSHOT}")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("status")).isEqualTo("EXPANDED");
    }

    @Test
    @DisplayName("verify-wrapper: the SOURCE class IRI — what the wrapper tab sends — resolves on a"
            + " promoted model whose catalog is keyed by the integrated IRI (V41)")
    void verifyWrapperResolvesTheSourceClassIriOnAPromotedModel() {
        // On any compiled model the catalog's classes() map is keyed by the INTEGRATED class IRI,
        // while ModelSource.classIri — what the Wrapper tab and the save path pass — is the SOURCE
        // class IRI, carried on each member as SourceView.srcClass. The old lookup only tried the
        // map key, so every normally-enrolled source answered "verify unavailable: no source view
        // found" forever and no wrapper could ever earn EXPANDED in a real deployment (V41).
        String sourceIri = "http://www.essi.upc.edu/DTIM/NextiaDI/source/" + VW_DATASET + "/thing";
        String integratedIri = "http://www.essi.upc.edu/DTIM/NextiaDI/integration/Int_thing";
        var member = new MappingCatalog.SourceView(
                VW_DATASET, "SELECT * FROM ${SNAPSHOT}", Map.of(), 0L, List.of(), sourceIri,
                Map.of(), Map.of("a", VW_PROP_A, "b", VW_PROP_B), "OPAQUE");
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(member, integratedIri));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"},{\"a\":\"x2\",\"b\":\"y2\"}]");

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", sourceIri,
                "sql", "SELECT \"a\" AS x, \"b\" AS y FROM ${SNAPSHOT}")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("status"))
                .as("reason: " + result.data().get("reason"))
                .isEqualTo("EXPANDED");
    }

    @Test
    @DisplayName("a failure whose message embeds a federated connection string never leaks the"
            + " password to the caller (audit QUERY_EXECUTION-1)")
    void failureMessagesScrubEmbeddedCredentials() {
        // No scan expression carries a password any more (both engines attach through a secret),
        // but a DuckDB extension can still echo a connection string in a connection-failure
        // message, and one did when Postgres read through postgres_scan('dbname=… password=…').
        // rootMessage is the one funnel every
        // client-facing failure string passes through — it must scrub key=value credential
        // tokens, whatever path the exception took.
        when(catalogOperator.getCatalog(any())).thenThrow(new RuntimeException(
                "IO Error: Unable to connect to Postgres at dbname=sales user=app"
                        + " password=hunter2 host=10.0.0.5 port=5432: connection refused"));

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS,
                "sql", "SELECT \"a\" FROM ${SNAPSHOT}")));

        String reason = result.data().get("reason");
        assertThat(reason).doesNotContain("hunter2");
        assertThat(reason).contains("password=***");
        assertThat(reason).as("the rest of the diagnostic survives").contains("connection refused");
    }

    @Test
    @DisplayName("a quoted password with a space is scrubbed whole — PostgresEngine quotes every DSN"
            + " value, and a whitespace-bounded scrub left the password's tail in the message")
    void failureMessagesScrubAQuotedPasswordWhole() {
        // DuckDB's own spelling of the refusal: the DSN as PostgresEngine.dsn wrote it, libpq
        // quotes and \' escape included.
        when(catalogOperator.getCatalog(any())).thenThrow(new RuntimeException(
                "IO Error: Unable to connect to Postgres at \"dbname='sales' user='app'"
                        + " password='hunter2 tail\\'s' host='10.0.0.5' port=5432\":"
                        + " connection refused"));

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS,
                "sql", "SELECT \"a\" FROM ${SNAPSHOT}")));

        String reason = result.data().get("reason");
        assertThat(reason).doesNotContain("hunter2").doesNotContain("tail");
        assertThat(reason).contains("password=***").contains("host='10.0.0.5'");
    }

    @Test
    @DisplayName("verify-wrapper: an aggregate can round-trip its own unparse but is NOT a"
            + " shape-preserving projection — the verdict must predict the planner's refusal")
    void verifyWrapperAggregateIsOpaqueNotExpanded() {
        // COUNT(*) plans cleanly and its unparse returns identical rows to the user's own text,
        // so the round-trip fingerprint alone would say EXPANDED — but OqlPlanner's structural
        // gate (unrebuildablePart) refuses anything beyond Project/Filter/Sort over the scan,
        // forever. A verdict the planner will never honor is a lie on the badge
        // (audit WRAPPER_VERIFY-9, structural half; reachable since the V41 lookup fix).
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(sourceViewForVerify(), VW_CLASS));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"},{\"a\":\"x2\",\"b\":\"y2\"}]");

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS,
                "sql", "SELECT count(*) AS c FROM ${SNAPSHOT}")));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("reason")).contains("shape-preserving");
    }

    @Test
    @DisplayName("verify-wrapper: a wrapper that does not plan over its raw columns is OPAQUE with a"
            + " reason, without ever touching the snapshot resolver or DuckDB")
    void verifyWrapperUnparseableIsOpaqueWithReason() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(sourceViewForVerify(), VW_CLASS));
        // Deliberately NOT calling wireSnapshot: graphStore/lake stay unstubbed. A resolver that
        // ran anyway would either NPE on graphStore.query()'s unstubbed null (caught by the
        // outer catch as a *different*, "verify unavailable"-prefixed reason) or reach
        // lake.downloadSnapshotTo — the reason substring and verifyNoInteractions below together
        // rule out both, not just an accidental non-blank string.

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS,
                "sql", "SELECT \"nope\" FROM ${SNAPSHOT}")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("reason")).contains("does not plan cleanly");
        verifyNoInteractions(graphStore, lake);
    }

    @Test
    @DisplayName("verify-wrapper: a dataset with no recorded snapshot is OPAQUE ('verify unavailable'),"
            + " not an operator failure")
    void verifyWrapperMissingSnapshotIsOpaqueVerifyUnavailable() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(sourceViewForVerify(), VW_CLASS));
        // A real (empty) model, not null: snapshotMap must find no entry for VW_DATASET, and
        // resolveReadExpression must throw "No snapshot recorded" rather than NPE-ing on a null
        // graphStore.query() result — this pins the OPERATIONAL-failure path, not an accident.
        when(graphStore.query(any(), anyString())).thenReturn(ModelFactory.createDefaultModel());

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS,
                "sql", "SELECT \"a\" AS x, \"b\" AS y FROM ${SNAPSHOT}")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("reason")).contains("verify unavailable");
    }

    // ── verify-wrapper: the columns the class reads out of the wrapper ─────────
    //
    // The u_ view selects each columnMap VALUE out of the wrapper by name. A wrapper that renames
    // or drops one of those aliases used to save and verify like any other and then fail every
    // query over the class with a binder error; the save path refuses on missingColumns.

    private static final String VW_SRC_A = "http://src/ds1/thing/a";
    private static final String VW_SRC_B = "http://src/ds1/thing/b";

    /** A class that reads two columns out of its wrapper, named as bootstrap aliases them. */
    private static MappingCatalog.SourceView mappedSourceView() {
        return new MappingCatalog.SourceView(
                VW_DATASET, "SELECT * FROM ${SNAPSHOT}", Map.of(VW_PROP_A, VW_SRC_A, VW_PROP_B, VW_SRC_B),
                0L, List.of(), "", Map.of(), Map.of("a", VW_SRC_A, "b", VW_SRC_B), "OPAQUE");
    }

    private OperatorResult verify(String sql) {
        return operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS, "sql", sql)));
    }

    @Test
    @DisplayName("verify-wrapper: a wrapper that renames a column its class reads reports it missing")
    void verifyWrapperReportsARenamedMappedColumn() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(mappedSourceView(), VW_CLASS));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"}]");

        OperatorResult result = verify(
                "SELECT \"a\" AS \"" + VW_SRC_A + "\", \"b\" AS renamed FROM ${SNAPSHOT}");

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("missingColumns")).isEqualTo(VW_SRC_B);
        assertThat(result.data().get("reason")).contains(VW_SRC_B).doesNotContain(VW_SRC_A);
    }

    @Test
    @DisplayName("verify-wrapper: each missing column comes with its raw source column, so the save"
            + " can tell a dropped alias from a column the source itself lost")
    void verifyWrapperNamesTheRawSourceColumnOfEachMissingOne() throws Exception {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(mappedSourceView(), VW_CLASS));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\"}]");

        OperatorResult result = verify("SELECT \"a\" AS \"" + VW_SRC_A + "\" FROM ${SNAPSHOT}");

        assertThat(result.data().get("missingColumns")).isEqualTo(VW_SRC_B);
        JsonNode sourceColumns = mapper.readTree(result.data().get("missingSourceColumns"));
        assertThat(sourceColumns.size()).isEqualTo(1);
        assertThat(sourceColumns.get(VW_SRC_B).asText()).isEqualTo("b");
    }

    @Test
    @DisplayName("verify-wrapper: a wrapper Calcite cannot plan is still checked for the columns it drops")
    void verifyWrapperChecksColumnsOfAnUnplannableWrapper() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(mappedSourceView(), VW_CLASS));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"}]");

        // list_value is DuckDB's, unknown to Calcite: the probe fails, DuckDB still describes it.
        OperatorResult result = verify(
                "SELECT \"a\" AS \"" + VW_SRC_A + "\", list_value(\"b\") AS bs FROM ${SNAPSHOT}");

        assertThat(result.data().get("missingColumns")).isEqualTo(VW_SRC_B);
    }

    @Test
    @DisplayName("verify-wrapper: a wrapper ending in a line comment is still checked for the columns it drops")
    void verifyWrapperEndingInALineCommentReportsTheDroppedColumn() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(mappedSourceView(), VW_CLASS));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"}]");

        OperatorResult result = verify(
                "SELECT \"a\" AS \"" + VW_SRC_A + "\" FROM ${SNAPSHOT} -- b removed");

        assertThat(result.data().get("missingColumns")).isEqualTo(VW_SRC_B);
    }

    @Test
    @DisplayName("verify-wrapper: a wrapper keeping every mapped alias reports nothing missing,"
            + " matched as DuckDB binds — case-insensitively")
    void verifyWrapperKeepingItsAliasesReportsNothingMissing() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(mappedSourceView(), VW_CLASS));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"}]");

        OperatorResult result = verify("SELECT \"a\" AS \"" + VW_SRC_A + "\", \"b\" AS \""
                + VW_SRC_B.toUpperCase(Locale.ROOT) + "\", 'extra' AS more FROM ${SNAPSHOT}");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).doesNotContainKey("missingColumns");
    }

    @Test
    @DisplayName("verify-wrapper: SELECT DISTINCT over every mapped alias keeps its columns and is"
            + " refused as an aggregate — the shape the v32 browser spec saves")
    void verifyWrapperDistinctKeepsItsColumnsAndIsAnAggregate() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(mappedSourceView(), VW_CLASS));
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"},{\"a\":\"x1\",\"b\":\"y1\"}]");

        OperatorResult result = verify("SELECT DISTINCT \"a\" AS \"" + VW_SRC_A + "\", \"b\" AS \""
                + VW_SRC_B + "\" FROM ${SNAPSHOT}");

        assertThat(result.data()).doesNotContainKey("missingColumns");
        assertThat(result.data().get("reason"))
                .contains("not a shape-preserving projection").contains("Aggregate");
    }

    // ── verify-wrapper: a text that does not bind at all ──────────────────────
    //
    // A misspelt raw column keeps every alias, so the coverage check used to read nothing, pass,
    // and the save landed OPAQUE "does not plan cleanly" while every query over the class failed
    // with the same binder error. It is reported only when it is provably the text's.

    private static final String BIND_DATASET = "7d3c1a52-5b8e-4f0e-9c2a-1f4b6d8e0a11";

    /** mappedSourceView's class, under a dataset id the operator can look its row up by. */
    private void wireBindCheck(String rowSnapshotId) {
        var view = new MappingCatalog.SourceView(
                BIND_DATASET, "SELECT * FROM ${SNAPSHOT}", Map.of(VW_PROP_A, VW_SRC_A, VW_PROP_B, VW_SRC_B),
                0L, List.of(), "", Map.of(), Map.of("a", VW_SRC_A, "b", VW_SRC_B), "OPAQUE");
        when(catalogOperator.getCatalog(any())).thenReturn(catalogWithSourceView(view, VW_CLASS));
        wireSnapshot(BIND_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"}]");
        UUID id = UUID.fromString(BIND_DATASET);
        when(datasets.findById(any(), eq(id))).thenReturn(Optional.of(
                new Dataset(id, UUID.randomUUID(), "things", "", rowSnapshotId)));
    }

    private OperatorResult verifyBind(String sql) {
        return operator().execute(request("verify-wrapper", Map.of(
                "datasetId", BIND_DATASET, "classIri", VW_CLASS, "sql", sql)));
    }

    private static final String MISSPELT = "SELECT \"titel\" AS \"" + VW_SRC_A + "\", \"b\" AS \""
            + VW_SRC_B + "\" FROM ${SNAPSHOT}";

    @Test
    @DisplayName("verify-wrapper: a misspelt raw column over the dataset's current snapshot is a"
            + " bindError naming DuckDB's binder message")
    void verifyWrapperReportsATextThatDoesNotBind() {
        wireBindCheck("snap-" + BIND_DATASET);

        OperatorResult result = verifyBind(MISSPELT);

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("bindError"))
                .startsWith("Binder Error: Referenced column \"titel\" not found")
                .as("the LINE echo is this check's wrapping, not the user's text")
                .doesNotContain("LINE 1").doesNotContain("_aran_wrapper");
        assertThat(result.data().get("reason")).contains("does not bind")
                .contains(result.data().get("bindError"));
        assertThat(result.data()).doesNotContainKey("missingColumns");
    }

    @Test
    @DisplayName("verify-wrapper: a bound wrapper Calcite cannot plan keeps its 'does not plan cleanly'"
            + " verdict — the shape the v32 browser spec saves")
    void verifyWrapperBoundButUnplannableIsNotABindError() {
        wireBindCheck("snap-" + BIND_DATASET);

        // v32's unplannableOf: the seeded projection plus a DuckDB-only function.
        OperatorResult result = verifyBind("SELECT \"a\" AS \"" + VW_SRC_A + "\", \"b\" AS \"" + VW_SRC_B
                + "\", list_value(1) AS \"unplannable_extra\" FROM ${SNAPSHOT}");

        assertThat(result.data()).doesNotContainKey("bindError").doesNotContainKey("missingColumns");
        assertThat(result.data().get("reason")).contains("does not plan cleanly over its src_ relation");
    }

    @Test
    @DisplayName("verify-wrapper: after a refresh the active schema has not caught up with, a text"
            + " that does not bind over the pinned snapshot is not blamed — it may name a new column")
    void verifyWrapperDoesNotBlameTheTextOverAnOutdatedPin() {
        wireBindCheck("snap-refreshed-since");

        OperatorResult result = verifyBind(MISSPELT);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).doesNotContainKey("bindError");
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
    }

    @Test
    @DisplayName("column check: an IO error, a relation that no longer binds, or an outdated source is"
            + " never reported as the text's bind error")
    void checkColumnsReportsOnlyTheTextsOwnBindError() throws Exception {
        List<String> mapped = List.of("http://x/a");
        Path file = Files.createTempFile("aran-bind-check", ".json");
        Files.writeString(file, "[{\"a\":1}]");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute("CREATE VIEW src_live AS SELECT * FROM read_json_auto('" + file + "')");
            st.execute("CREATE TABLE t (a INTEGER)");
            st.execute("CREATE VIEW src_drifted AS SELECT a FROM t");

            // The text's own binding, over a relation that binds: reported.
            assertThat(QueryEngineOperator.checkColumns(conn, "src_live",
                    "SELECT \"titel\" AS \"http://x/a\" FROM src_live", mapped, true).bindError())
                    .startsWith("Binder Error:");
            assertThat(QueryEngineOperator.checkColumns(conn, "src_live",
                    "SELECT nofn(a) AS \"http://x/a\" FROM src_live", mapped, true).bindError())
                    .startsWith("Catalog Error:");
            // The same text, but the relation is not the data queries read: not reported.
            assertThat(QueryEngineOperator.checkColumns(conn, "src_live",
                    "SELECT \"titel\" AS \"http://x/a\" FROM src_live", mapped, false))
                    .isEqualTo(QueryEngineOperator.ColumnCheck.UNKNOWN);

            // A binder error raised by the relation itself, not the text: not reported.
            st.execute("DROP TABLE t");
            st.execute("CREATE TABLE t (b INTEGER)");
            assertThat(QueryEngineOperator.checkColumns(conn, "src_drifted",
                    "SELECT a AS \"http://x/a\" FROM src_drifted", mapped, true))
                    .isEqualTo(QueryEngineOperator.ColumnCheck.UNKNOWN);

            // The source went away (an IO error, as a federated source that drops reports): not
            // reported, whatever the text says.
            Files.delete(file);
            assertThat(QueryEngineOperator.checkColumns(conn, "src_live",
                    "SELECT \"titel\" AS \"http://x/a\" FROM src_live", mapped, true))
                    .isEqualTo(QueryEngineOperator.ColumnCheck.UNKNOWN);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("column check: DuckDB's wrapped error is unwrapped and trimmed; IO and HTTP errors are not the text's")
    void textBindErrorClassifiesDuckDbMessages() {
        assertThat(QueryEngineOperator.textBindError("Invalid Input Error: Attempting to execute an"
                + " unsuccessful or closed pending query result\nError: Binder Error: Referenced column"
                + " \"x\" not found in FROM clause!\nCandidate bindings: \"a\"\n\nLINE 1: SELECT *"))
                .isEqualTo("Binder Error: Referenced column \"x\" not found in FROM clause!\n"
                        + "Candidate bindings: \"a\"");
        assertThat(QueryEngineOperator.textBindError("Catalog Error: Scalar Function with name nofn"
                + " does not exist!")).startsWith("Catalog Error:");
        assertThat(QueryEngineOperator.textBindError("Invalid Input Error: Attempting to execute an"
                + " unsuccessful or closed pending query result\nError: IO Error: Unable to connect to"
                + " Postgres at \"host=db\"")).isNull();
        assertThat(QueryEngineOperator.textBindError("HTTP Error: 503")).isNull();
        assertThat(QueryEngineOperator.textBindError("Parser Error: syntax error at or near \")\""))
                .isNull();
        assertThat(QueryEngineOperator.textBindError(null)).isNull();
    }

    @Test
    @DisplayName("verify-wrapper: when the snapshot is unavailable an unplannable wrapper keeps its"
            + " content-determined verdict instead of turning 'verify unavailable'")
    void verifyWrapperUnplannableWithoutSnapshotKeepsItsVerdict() {
        when(catalogOperator.getCatalog(any()))
                .thenReturn(catalogWithSourceView(mappedSourceView(), VW_CLASS));
        when(graphStore.query(any(), anyString())).thenReturn(ModelFactory.createDefaultModel());

        OperatorResult result = verify("SELECT \"nope\" FROM ${SNAPSHOT}");

        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("reason")).contains("does not plan cleanly");
    }

    // ── verify-wrapper: V41 — the namespace the real callers actually pass ─────
    //
    // Every verify-wrapper test above builds a catalog keyed by the very IRI it then passes in,
    // which is not how production calls this operation: IntegrationModelService's wrapper save
    // path hands it the SOURCE class IRI (its own javadoc documents that contract), while
    // MappingCatalog.classes() is keyed by the INTEGRATED class IRI — two namespaces disjoint by
    // construction (BootstrapEngine.SOURCE_NS vs IntegrationVocab.INTEGRATION_NS). The lookup
    // therefore missed for every source promoted into an IntegratedResource, i.e. every source in
    // a compiled model, so EXPANDED was unreachable in a real deployment and every wrapper save
    // persisted "verify unavailable: no source view found …" (V41, reproduced in a
    // browser). These two pin the resolution, and its limit.

    private static final String VW_SRC_CLASS = NS + "source/" + VW_DATASET + "/acm";
    private static final String VW_INT_CLASS = NS + "integration/Int_acm";
    private static final String OTHER_DATASET = "ds2";
    private static final String OTHER_SRC_CLASS = NS + "source/" + OTHER_DATASET + "/researchers";
    private static final String OTHER_INT_CLASS = NS + "integration/Int_researchers";

    /**
     * A compiled model as the catalog really presents it: more than one integrated class, each
     * keyed by its integration-namespaced IRI and carrying its own source view under a
     * source-namespaced {@code srcClass}. Multi-class on purpose — a resolution that simply took
     * the first source view it found for the dataset, or ignored the dataset entirely, would pass
     * a single-class fixture and still be wrong.
     */
    private static MappingCatalog promotedCatalog() {
        var acm = new MappingCatalog.IntegratedClass(VW_INT_CLASS, List.of(
                new MappingCatalog.SourceView(
                        VW_DATASET, "SELECT * FROM ${SNAPSHOT}", Map.of(), 0L, List.of(),
                        VW_SRC_CLASS, Map.of(), Map.of("a", VW_PROP_A, "b", VW_PROP_B), "OPAQUE")));
        var researchers = new MappingCatalog.IntegratedClass(OTHER_INT_CLASS, List.of(
                new MappingCatalog.SourceView(
                        OTHER_DATASET, "SELECT * FROM ${SNAPSHOT}", Map.of(), 0L, List.of(),
                        OTHER_SRC_CLASS, Map.of(), Map.of("r", NS + "source/ds2/rid"), "OPAQUE")));
        return new MappingCatalog(
                Map.of(VW_INT_CLASS, acm, OTHER_INT_CLASS, researchers),
                List.of(), List.of(), "v1");
    }

    @Test
    @DisplayName("verify-wrapper: a SOURCE class IRI — what every production caller passes —"
            + " resolves to the integrated class owning that (dataset, source class) membership")
    void verifyWrapperResolvesTheIntegratedClassForASourceIri() {
        when(catalogOperator.getCatalog(any())).thenReturn(promotedCatalog());
        wireSnapshot(VW_DATASET, "[{\"a\":\"x1\",\"b\":\"y1\"},{\"a\":\"x2\",\"b\":\"y2\"}]");

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_SRC_CLASS,
                "sql", "SELECT \"a\" AS x, \"b\" AS y FROM ${SNAPSHOT}")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("reason"))
                .as("V41: the lookup missed and no round-trip check ever ran")
                .doesNotContain("no source view found");
        assertThat(result.data().get("status")).isEqualTo("EXPANDED");
    }

    @Test
    @DisplayName("verify-wrapper: a source class belonging to a DIFFERENT dataset is still a miss —"
            + " the resolution is per (dataset, source class), not per class name")
    void verifyWrapperDoesNotResolveASourceClassFromAnotherDataset() {
        when(catalogOperator.getCatalog(any())).thenReturn(promotedCatalog());
        // Unstubbed graphStore/lake: a match would have to resolve a snapshot to get a verdict,
        // so verifyNoInteractions below is what proves the miss happened at the lookup.

        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", OTHER_SRC_CLASS,
                "sql", "SELECT \"a\" AS x, \"b\" AS y FROM ${SNAPSHOT}")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("reason")).contains("no source view found");
        verifyNoInteractions(graphStore, lake);
    }

    // ── verify-wrapper: the WrapperSqlValidator gate (mirrors ConstraintInjectionTest) ──────
    //
    // WrapperExpansion.probe's narrow one-table Calcite schema happens to reject read_csv_auto
    // etc. today too (Calcite's default operator table does not know DuckDB's readers/scanners),
    // but that is an accident of what Calcite's parser recognises, not an asserted security
    // property, and it does not exist at all for the string-literal-in-FROM shape once the text
    // is executed for real. These two pin the actual security boundary — WrapperSqlValidator,
    // the same screen every other SQL-accepting path in this operator applies — deterministically,
    // by asserting the exact reason text the screen (not Calcite) produces, and that neither the
    // catalog, the snapshot resolver nor the lake is ever reached to get there.

    @Test
    @DisplayName("verify-wrapper: a forbidden function call is rejected deterministically, before"
            + " the catalog, the resolver or the lake are ever touched")
    void verifyWrapperForbiddenFunctionCallIsRejected() {
        // Deliberately no catalogOperator/graphStore/lake stubbing: verifyNoInteractions below
        // is only meaningful if nothing here already made them return something usable.
        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS,
                "sql", "SELECT * FROM read_csv_auto('/etc/passwd')")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("reason")).contains("forbidden construct");
        verifyNoInteractions(catalogOperator, graphStore, lake);
    }

    @Test
    @DisplayName("verify-wrapper: a bare string literal in FROM position is rejected — DuckDB's"
            + " replacement scan would read it as a file with no function call in sight")
    void verifyWrapperStringLiteralInFromIsRejected() {
        OperatorResult result = operator().execute(request("verify-wrapper", Map.of(
                "datasetId", VW_DATASET, "classIri", VW_CLASS,
                "sql", "SELECT * FROM '/etc/passwd.csv'")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("status")).isEqualTo("OPAQUE");
        assertThat(result.data().get("reason")).contains("forbidden construct");
        verifyNoInteractions(catalogOperator, graphStore, lake);
    }

    /** Materialise {@code query} to a temp parquet file via DuckDB, and return its path. */
    private static Path writeParquet(String query) throws Exception {
        Path target = Files.createTempFile("aran-preview-test-", ".parquet");
        Files.deleteIfExists(target);
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            s.execute("COPY (" + query + ") TO '"
                    + target.toAbsolutePath().toString().replace("'", "''")
                    + "' (FORMAT PARQUET)");
        }
        return target;
    }
}

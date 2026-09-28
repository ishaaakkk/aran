package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.fake.FakeGraphStore;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Task 3 (phase 3B): after a successful compile, {@link IntegrationModelService} orchestrates
 * wrapper-output profiling for every source class whose compiled verdict is NOT EXPANDED, screened
 * by {@code WrapperSqlValidator.findForbiddenConstruct} first, with every failure logged WARN and
 * swallowed — a compile must never fail because profiling hiccuped.
 *
 * <p>Reads the JUST-COMPILED draft graph (via {@code GraphStore.readVersion(draftVersionId)}, not
 * the active promoted one — the schema-integration operator's own return payload names the draft),
 * because the class this task profiles is precisely the one {@code MappingCatalogOperator} would
 * only ever see after a promotion that has not (yet) happened.
 */
class IntegrationModelServiceWrapperOutputProfilingTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final FakeGraphStore graphStore = new FakeGraphStore();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);

    /** Every request the fake "profiling" operator received, in order. */
    private final List<OperatorRequest> profilingRequests = new CopyOnWriteArrayList<>();

    private Operator schemaIntegrationReturning(UUID draftVersionId) {
        return new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "stub"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                return OperatorResult.success(Map.of("draftVersionId", draftVersionId.toString()));
            }
        };
    }

    private Operator capturingProfilingOperator(OperatorResult toReturn) {
        return new Operator() {
            @Override public String getOperatorId() { return "profiling"; }
            @Override public String getDisplayName() { return "capturing"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                profilingRequests.add(request);
                return toReturn;
            }
        };
    }

    private IntegrationModelService service(UUID draftVersionId, OperatorResult profilingResult) {
        OperatorOrchestrator orchestrator = new OperatorOrchestrator(List.of(
                schemaIntegrationReturning(draftVersionId), capturingProfilingOperator(profilingResult)));
        return new IntegrationModelService(repo, graphStore, orchestrator, objectMapper, datasetRepository,
                org.mockito.Mockito.mock(DataProductService.class));
    }

    /** Runs {@code body} with {@link IntegrationModelService}'s WARNs captured. */
    private static List<ILoggingEvent> capturingWarnings(Runnable body) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IntegrationModelService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            body.run();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        return captured.list;
    }

    private static void assertWarned(List<ILoggingEvent> events, String... fragments) {
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(fragments);
        });
    }

    /** Shapes one compiled source class fact the way SchemaIntegrationOperator writes it. */
    private static Resource sourceClass(Model m, String classIri, String wrapperSql, UUID datasetId,
                                        @Nullable String expansionStatus) {
        Resource cls = m.createResource(classIri);
        m.add(cls, m.createProperty(NS + "wrapperSql"), wrapperSql);
        m.add(cls, m.createProperty(NS + "provenance"), datasetId.toString());
        if (expansionStatus != null) {
            m.add(cls, m.createProperty(NS + "wrapperExpansion"), expansionStatus);
        }
        return cls;
    }

    @Test
    @DisplayName("compile triggers wrapper-output profiling for an OPAQUE class, with the dataset's own snapshotId")
    void compileTriggersProfilingForOpaqueClass() {
        UUID draftVersionId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();

        Model draft = ModelFactory.createDefaultModel();
        sourceClass(draft, "http://ex/Opaque", "SELECT * FROM ${SNAPSHOT}", datasetId, null);
        graphStore.stage(tenant, draft);

        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "ds", "", "snap-1", List.of());
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(dataset));

        IntegrationModelService service = service(draftVersionId, OperatorResult.success(Map.of()));

        service.rebuildToDraft(tenant, projectId, actor);

        assertThat(profilingRequests).hasSize(1);
        OperatorRequest req = profilingRequests.get(0);
        assertThat(req.operation()).isEqualTo("profile");
        assertThat(req.userId()).as("attributed to the actor who triggered the compile").isEqualTo(actor);
        assertThat(req.parameters())
                .containsEntry("snapshotId", "snap-1")
                .containsEntry("datasetId", datasetId.toString())
                .containsEntry("wrapperSql", "SELECT * FROM ${SNAPSHOT}")
                .containsEntry("classIri", "http://ex/Opaque")
                .containsEntry("subject", "WRAPPER_OUTPUT");
    }

    @Test
    @DisplayName("compile does not trigger profiling for an EXPANDED class")
    void compileSkipsExpandedClass() {
        UUID draftVersionId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        Model draft = ModelFactory.createDefaultModel();
        sourceClass(draft, "http://ex/Expanded", "SELECT 1", datasetId, "EXPANDED");
        graphStore.stage(tenant, draft);

        IntegrationModelService service = service(draftVersionId, OperatorResult.success(Map.of()));
        service.rebuildToDraft(tenant, projectId, actor);

        assertThat(profilingRequests).isEmpty();
    }

    @Test
    @DisplayName("a wrapper SQL containing a forbidden construct is screened out before reaching the orchestrator")
    void compileScreensForbiddenConstructBeforeProfiling() {
        UUID draftVersionId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        Model draft = ModelFactory.createDefaultModel();
        sourceClass(draft, "http://ex/Malicious", "SELECT * FROM read_csv_auto('/etc/passwd')",
                datasetId, null);
        graphStore.stage(tenant, draft);

        IntegrationModelService service = service(draftVersionId, OperatorResult.success(Map.of()));
        List<ILoggingEvent> events = capturingWarnings(
                () -> service.rebuildToDraft(tenant, projectId, actor));

        assertThat(profilingRequests)
                .as("a forbidden construct must never reach the orchestrator, screened or not")
                .isEmpty();
        assertWarned(events, "http://ex/Malicious", "forbidden function call");
    }

    @Test
    @DisplayName("a failing profiling operator result is logged and swallowed — compile still succeeds")
    void compileSwallowsProfilingFailure() {
        UUID draftVersionId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        Model draft = ModelFactory.createDefaultModel();
        sourceClass(draft, "http://ex/Opaque", "SELECT * FROM ${SNAPSHOT}", datasetId, null);
        graphStore.stage(tenant, draft);
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "ds", "", "snap-1", List.of());
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(dataset));

        IntegrationModelService service = service(draftVersionId, OperatorResult.failure(
                "PROFILING_ERROR", "wrapper output column named 'snapshot' is not allowed"));

        AtomicReference<Map<String, String>> resultHolder = new AtomicReference<>();
        List<ILoggingEvent> events = capturingWarnings(() ->
                resultHolder.set(service.rebuildToDraft(tenant, projectId, actor)));

        assertThat(resultHolder.get()).containsEntry("draftVersionId", draftVersionId.toString());
        assertThat(profilingRequests).as("profiling was attempted, just failed").hasSize(1);
        // Required: freyja's reason (here, the 400 for a wrapper output column
        // named "snapshot") must be surfaced in the WARN, not swallowed into a generic message.
        assertWarned(events, "http://ex/Opaque", "wrapper output column named 'snapshot' is not allowed");
    }

    @Test
    @DisplayName("a dataset with no recorded snapshot is skipped rather than sent to freyja")
    void compileSkipsWhenDatasetHasNoSnapshot() {
        UUID draftVersionId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        Model draft = ModelFactory.createDefaultModel();
        sourceClass(draft, "http://ex/Opaque", "SELECT * FROM ${SNAPSHOT}", datasetId, null);
        graphStore.stage(tenant, draft);
        Dataset dataset = new Dataset(datasetId, UUID.randomUUID(), "ds", "", null, List.of());
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(dataset));

        IntegrationModelService service = service(draftVersionId, OperatorResult.success(Map.of()));
        service.rebuildToDraft(tenant, projectId, actor);

        assertThat(profilingRequests).isEmpty();
    }

    @Test
    @DisplayName("an unknown dataset id is skipped rather than throwing")
    void compileSkipsWhenDatasetIsUnknown() {
        UUID draftVersionId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        Model draft = ModelFactory.createDefaultModel();
        sourceClass(draft, "http://ex/Opaque", "SELECT * FROM ${SNAPSHOT}", datasetId, null);
        graphStore.stage(tenant, draft);
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.empty());

        IntegrationModelService service = service(draftVersionId, OperatorResult.success(Map.of()));
        Map<String, String> result = service.rebuildToDraft(tenant, projectId, actor);

        assertThat(result).containsEntry("draftVersionId", draftVersionId.toString());
        assertThat(profilingRequests).isEmpty();
    }
}

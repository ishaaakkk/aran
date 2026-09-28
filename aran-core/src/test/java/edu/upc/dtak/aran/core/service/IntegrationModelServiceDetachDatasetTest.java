package edu.upc.dtak.aran.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationRunStatus;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * T1.8 (audit MIGRATIONS-3 + JOIN_DISCOVERY-5 + SCHEMA_BOOTSTRAPPING-10 + INTEGRATION_COMPILE-3):
 * deleting a dataset must not leave the integrated model describing things that no longer exist,
 * and the model promote path must stale-mark data products like the other two promote paths do.
 */
class IntegrationModelServiceDetachDatasetTest {

    private static final String SOURCE_NS = "http://www.essi.upc.edu/DTIM/NextiaDI/source/";

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final SchemaVersionServiceRollbackTest.InMemoryGraphVersionPort port =
            new SchemaVersionServiceRollbackTest.InMemoryGraphVersionPort(tenant);
    private final SchemaVersionServiceRollbackTest.InMemoryNamedGraphStore store =
            new SchemaVersionServiceRollbackTest.InMemoryNamedGraphStore(port);
    private final ObjectMapper om = new ObjectMapper();
    private final DataProductService dataProducts = mock(DataProductService.class);

    private IntegrationModelService service() {
        return new IntegrationModelService(repo, store,
                new OperatorOrchestrator(List.of(stubOperator())), om,
                mock(DatasetRepository.class), dataProducts);
    }

    /** Minimal schema-integration stub: writes a draft and echoes empty applied-mapping ids. */
    private Operator stubOperator() {
        return new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "stub"; }
            @Override public OperatorResult execute(OperatorRequest req) {
                Model graph = ModelFactory.createDefaultModel();
                graph.add(graph.createResource("urn:aran:schema"),
                        graph.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/integrationModel"),
                        req.parameters().get("modelId"));
                GraphVersion draft = store.replaceDraft(tenant, graph, new AuditedActor(req.userId()));
                return OperatorResult.success(Map.of(
                        "draftVersionId", draft.id().toString(),
                        "appliedMappingIds", "",
                        "deferredMappingIds", ""));
            }
        };
    }

    private ModelMapping mapping(UUID modelId, String kind, String src, String tgt, String label) {
        return repo.addMapping(tenant, new ModelMapping(
                UUID.randomUUID(), UUID.fromString(tenant.value()), modelId, kind,
                src, tgt, label, ModelMapping.ACCEPTED, null, actor, null, Instant.now()));
    }

    private String statusOf(UUID mappingId) {
        return repo.load(tenant, projectId).mappings().stream()
                .filter(m -> m.id().equals(mappingId))
                .findFirst().orElseThrow().status();
    }

    /** Stage a promoted baseline, so rebuild-flag semantics are exercised for real. */
    private void promoteABaseline(IntegrationModelService service) {
        Map<String, String> rebuild = service.rebuildToDraft(tenant, projectId, actor);
        service.promoteRun(tenant, projectId,
                UUID.fromString(rebuild.get("integrationRunId")), actor);
    }

    @Test
    @DisplayName("detachDataset removes the enrolment, REJECTs its mappings, flags a rebuild, and clears the source graph")
    void detachCleansEnrolmentMappingsFlagAndGraph() {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        promoteABaseline(service);
        UUID tenantId = UUID.fromString(tenant.value());
        UUID deadDataset = UUID.randomUUID();
        UUID survivingDataset = UUID.randomUUID();

        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), tenantId, model.id(),
                deadDataset, SOURCE_NS + deadDataset + "/orders", null, null, null, false));
        // A join whose SOURCE side lives under the dead dataset, one whose TARGET side does,
        // and one mapping between other IRIs that must stay untouched.
        ModelMapping bySource = mapping(model.id(), MappingKinds.JOIN,
                SOURCE_NS + deadDataset + "/orders/customer_id",
                SOURCE_NS + survivingDataset + "/customers/id", "orders-customers");
        ModelMapping byTarget = mapping(model.id(), MappingKinds.JOIN,
                SOURCE_NS + survivingDataset + "/customers/id",
                SOURCE_NS + deadDataset + "/orders/customer_id", "customers-orders");
        ModelMapping untouched = mapping(model.id(), MappingKinds.CLASS,
                SOURCE_NS + survivingDataset + "/customers",
                "http://integrated/Customer", "Customer");

        // The source graph the bootstrap wrote for the dataset.
        String graphUri = "urn:aran:source:" + deadDataset;
        Model sourceGraph = ModelFactory.createDefaultModel();
        sourceGraph.add(sourceGraph.createResource(SOURCE_NS + deadDataset + "/orders"),
                sourceGraph.createProperty("http://x/p"), "v");
        store.addTriples(tenant, graphUri, sourceGraph);

        service.detachDataset(tenant, projectId, deadDataset);

        assertThat(repo.load(tenant, projectId).sources())
                .as("the enrolment row must be gone")
                .noneMatch(s -> s.datasetId().equals(deadDataset));
        assertThat(statusOf(bySource.id())).isEqualTo(ModelMapping.REJECTED);
        assertThat(statusOf(byTarget.id())).isEqualTo(ModelMapping.REJECTED);
        assertThat(statusOf(untouched.id()))
                .as("mappings not referencing the dataset stay untouched")
                .isEqualTo(ModelMapping.ACCEPTED);
        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired())
                .as("the next compile must rebuild without the ghost class")
                .isTrue();
        assertThat(store.query(tenant, "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + graphUri
                + "> { ?s ?p ?o } }").isEmpty())
                .as("the source graph must be cleared")
                .isTrue();
    }

    @Test
    @DisplayName("detachDataset on a never-enrolled dataset is a quiet no-op — no rebuild flag, no error")
    void detachOfUnenrolledDatasetIsNoOp() {
        IntegrationModelService service = service();
        repo.getOrCreate(tenant, projectId);
        promoteABaseline(service);

        service.detachDataset(tenant, projectId, UUID.randomUUID());

        assertThat(repo.getOrCreate(tenant, projectId).rebuildRequired())
                .as("nothing was detached, so nothing needs a rebuild — even with a baseline")
                .isFalse();
    }

    @Test
    @DisplayName("promoteRun stale-marks data products, like the other two promote paths (INTEGRATION_COMPILE-3)")
    void promoteRunMarksProductsStale() {
        IntegrationModelService service = service();
        repo.getOrCreate(tenant, projectId);

        Map<String, String> rebuild = service.rebuildToDraft(tenant, projectId, actor);
        UUID runId = UUID.fromString(rebuild.get("integrationRunId"));
        assertThat(repo.findRun(tenant, runId).orElseThrow().status())
                .isEqualTo(IntegrationRunStatus.DRAFT);

        service.promoteRun(tenant, projectId, runId, actor);

        verify(dataProducts).markStaleForVersionChange(any(TenantContext.class));
    }

    @Test
    @DisplayName("a compile alone does not stale-mark anything — only the promote does")
    void compileAloneDoesNotTouchProducts() {
        IntegrationModelService service = service();
        repo.getOrCreate(tenant, projectId);

        service.rebuildToDraft(tenant, projectId, actor);

        verifyNoInteractions(dataProducts);
    }
}

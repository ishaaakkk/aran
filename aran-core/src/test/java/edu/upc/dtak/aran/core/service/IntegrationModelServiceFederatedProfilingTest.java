package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.FileSystemRepository;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.fake.FakeGraphStore;
import edu.upc.dtak.aran.core.fake.InMemoryDataRepositoryRepository;
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
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The compile tail's FEDERATED branch (Gap 2 follow-up, per the design ruling): a class whose
 * dataset has no lake snapshot but resolves FEDERATED is profiled by handing the profiling
 * operator {@code federated=true} — freyja then executes the profiling suite over the same live
 * binding queries use, and nothing is ever staged to the lake. Everything else with no snapshot
 * keeps the honest skip.
 */
class IntegrationModelServiceFederatedProfilingTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String WRAPPER = "SELECT * FROM ${SNAPSHOT}";

    private final UUID tenantId = UUID.randomUUID();
    private final TenantContext tenant = new TenantContext(tenantId.toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final FakeGraphStore graphStore = new FakeGraphStore();
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final InMemoryDataRepositoryRepository repositoryStore = new InMemoryDataRepositoryRepository();

    private final List<OperatorRequest> profilingRequests = new CopyOnWriteArrayList<>();

    private Operator stub(String id, java.util.function.Function<OperatorRequest, OperatorResult> body) {
        return new Operator() {
            @Override public String getOperatorId() { return id; }
            @Override public String getDisplayName() { return "stub"; }
            @Override public OperatorResult execute(OperatorRequest request) {
                return body.apply(request);
            }
        };
    }

    private IntegrationModelService service(OperatorResult profilingResult) {
        UUID draftVersionId = UUID.randomUUID();
        OperatorOrchestrator orchestrator = new OperatorOrchestrator(List.of(
                stub("schema-integration", r -> OperatorResult.success(
                        Map.of("draftVersionId", draftVersionId.toString()))),
                stub("profiling", r -> {
                    profilingRequests.add(r);
                    return profilingResult;
                })));
        return new IntegrationModelService(repo, graphStore, orchestrator, new ObjectMapper(),
                datasetRepository, mock(DataProductService.class),
                Optional.of(repositoryStore));
    }

    private void stageOpaqueClass() {
        Model draft = ModelFactory.createDefaultModel();
        var cls = draft.createResource("http://ex/Federated");
        draft.add(cls, draft.createProperty(NS + "wrapperSql"), WRAPPER);
        draft.add(cls, draft.createProperty(NS + "provenance"), datasetId.toString());
        graphStore.stage(tenant, draft);
    }

    private void federatedIcebergDataset() {
        repositoryStore.save(tenant, new IcebergRestRepository(repositoryId, projectId, tenantId,
                "lakehouse", "", "http://localhost:8181/api/catalog", "prod", "tok",
                "MINIO", "http://localhost:9000", "AKIA", "SEKRET", "us-east-1"));
        Dataset dataset = new Dataset(datasetId, repositoryId, "db.people", "", null, List.of());
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(dataset));
    }

    @Test
    @DisplayName("a federated class is profiled over its LIVE binding: federated=true, no snapshot, no staging")
    void federatedClassIsProfiledOverTheLiveBinding() {
        stageOpaqueClass();
        federatedIcebergDataset();

        service(OperatorResult.success(Map.of())).rebuildToDraft(tenant, projectId, actor);

        // One profiling call, told to build and use the dataset's live binding itself — the
        // wrapper rides along because the suite binds ${SNAPSHOT} over the scan, exactly as a
        // query would. No snapshotId anywhere: nothing was staged to the lake.
        assertThat(profilingRequests).hasSize(1);
        OperatorRequest profile = profilingRequests.get(0);
        assertThat(profile.parameters())
                .containsEntry("federated", "true")
                .containsEntry("datasetId", datasetId.toString())
                .containsEntry("wrapperSql", WRAPPER)
                .containsEntry("classIri", "http://ex/Federated")
                .containsEntry("subject", "FEDERATED_LIVE")
                .containsEntry("varcharOnly", "false")
                .doesNotContainKey("snapshotId");

        // The dataset row was never given a snapshot — SourceAccess keeps resolving FEDERATED
        // (the row is the signal; verify no save call).
        org.mockito.Mockito.verify(datasetRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a failed federated profiling is swallowed — the compile still succeeds")
    void failedFederatedProfilingIsSwallowed() {
        stageOpaqueClass();
        federatedIcebergDataset();

        Map<String, String> result = service(OperatorResult.failure("FEDERATED_BINDING_FAILED",
                "catalog unreachable")).rebuildToDraft(tenant, projectId, actor);

        assertThat(result).containsKey("draftVersionId");
        assertThat(profilingRequests).hasSize(1);
    }

    @Test
    @DisplayName("a snapshot-less dataset that does NOT resolve federated keeps the honest skip")
    void nonFederatedSnapshotlessDatasetStillSkips() {
        stageOpaqueClass();
        repositoryStore.save(tenant, new FileSystemRepository(
                repositoryId, projectId, tenantId, "files", ""));
        Dataset dataset = new Dataset(datasetId, repositoryId, "orphan", "", null, List.of());
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(dataset));

        service(OperatorResult.success(Map.of())).rebuildToDraft(tenant, projectId, actor);

        assertThat(profilingRequests).isEmpty();
    }

    @Test
    @DisplayName("a build with no repository store wired keeps the honest skip, not a crash")
    void noRepositoryStoreSkips() {
        stageOpaqueClass();
        Dataset dataset = new Dataset(datasetId, repositoryId, "db.people", "", null, List.of());
        when(datasetRepository.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(dataset));

        UUID draftVersionId = UUID.randomUUID();
        OperatorOrchestrator orchestrator = new OperatorOrchestrator(List.of(
                stub("schema-integration", r -> OperatorResult.success(
                        Map.of("draftVersionId", draftVersionId.toString()))),
                stub("profiling", r -> {
                    profilingRequests.add(r);
                    return OperatorResult.success(Map.of());
                })));
        IntegrationModelService bare = new IntegrationModelService(repo, graphStore, orchestrator,
                new ObjectMapper(), datasetRepository, mock(DataProductService.class));

        Map<String, String> result = bare.rebuildToDraft(tenant, projectId, actor);

        assertThat(result).containsKey("draftVersionId");
        assertThat(profilingRequests).isEmpty();
    }
}

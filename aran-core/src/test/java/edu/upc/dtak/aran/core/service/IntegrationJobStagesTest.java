package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelSource;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ProgressReporter;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UI audit J4-F16: a rebuild with seven sources ran for minutes as "model:compile — waiting for
 * the first stage…", because the job body never reported one. The compile paths now report their
 * real phase boundaries through the job's {@link ProgressReporter}, in order.
 */
@DisplayName("Rebuild and incremental add report their stages (J4-F16)")
class IntegrationJobStagesTest {

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final SchemaVersionServiceRollbackTest.InMemoryGraphVersionPort port =
            new SchemaVersionServiceRollbackTest.InMemoryGraphVersionPort(tenant);
    private final SchemaVersionServiceRollbackTest.InMemoryNamedGraphStore store =
            new SchemaVersionServiceRollbackTest.InMemoryNamedGraphStore(port);

    /** Records each stage, in order. */
    private static final class RecordingProgress implements ProgressReporter {
        final List<String> stages = new ArrayList<>();

        @Override public void stage(String stage) { stages.add(stage); }

        @Override public void percent(int percent) { }
    }

    /** Writes a draft (rebuild or incremental) and applies nothing — only the stages matter here. */
    private Operator stubOperator() {
        return new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "stub"; }
            @Override public OperatorResult execute(OperatorRequest req) {
                Model graph = ModelFactory.createDefaultModel();
                AuditedActor auditedActor = new AuditedActor(req.userId());
                GraphVersion draft = "incremental-integrate".equals(req.operation())
                        ? store.replaceDraftFromBase(tenant,
                                UUID.fromString(req.parameters().get("baseVersionId")), graph, auditedActor)
                        : store.replaceDraft(tenant, graph, auditedActor);
                return OperatorResult.success(Map.of(
                        "draftVersionId", draft.id().toString(),
                        "appliedMappingIds", "",
                        "deferredMappingIds", ""));
            }
        };
    }

    private IntegrationModelService service() {
        return new IntegrationModelService(repo, store,
                new OperatorOrchestrator(List.of(stubOperator())), new ObjectMapper(),
                mock(DatasetRepository.class), mock(DataProductService.class));
    }

    private void enrol(IntegrationModel model, UUID datasetId, String classIri) {
        repo.addSource(tenant, new ModelSource(UUID.randomUUID(), UUID.fromString(tenant.value()),
                model.id(), datasetId, classIri, null, null, null, false));
    }

    @Test
    @DisplayName("a rebuild reports re-verify, integrate and profile, in that order")
    void rebuildReportsItsStages() {
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        enrol(model, UUID.randomUUID(), "http://a/Person");
        enrol(model, UUID.randomUUID(), "http://b/Worker");
        RecordingProgress progress = new RecordingProgress();

        service().rebuildToDraft(tenant, projectId, actor, progress);

        assertThat(progress.stages).containsExactly(
                "re-verifying wrappers of 2 source class(es)",
                "integrating 2 dataset(s) into a draft schema",
                "profiling wrapper output of the draft");
    }

    @Test
    @DisplayName("an incremental add reports integrate and profile")
    void incrementalReportsItsStages() {
        IntegrationModelService service = service();
        IntegrationModel model = repo.getOrCreate(tenant, projectId);
        enrol(model, UUID.randomUUID(), "http://a/Person");
        service.addMapping(tenant, projectId, MappingKinds.CLASS,
                "http://a/Person", "http://a/Person", "Person", actor, null);
        Map<String, String> rebuild = service.rebuildToDraft(tenant, projectId, actor);
        service.promoteRun(tenant, projectId, UUID.fromString(rebuild.get("integrationRunId")), actor);

        UUID datasetB = UUID.randomUUID();
        enrol(model, datasetB, "http://b/Worker");
        RecordingProgress progress = new RecordingProgress();

        service.incrementalIntegrateToDraft(tenant, projectId, datasetB, actor, progress);

        assertThat(progress.stages).containsExactly(
                "integrating the new source onto the promoted baseline",
                "profiling wrapper output of the draft");
    }
}

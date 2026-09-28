package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.app.BaseIntegrationTest;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end for the M4 compile path, exercised through the real
 * {@link IntegrationModelService} + {@code OperatorOrchestrator} + {@code SchemaIntegrationOperator}
 * + {@code SchemaIntegrator}, with the integration model persisted to the real Postgres.
 *
 * <p>Fuseki I/O is the one thing stubbed — the repo has no Fuseki test harness, and the
 * bootstrapping operator points at {@code localhost:3030}. A {@link FakeSourceGraphStore}
 * (marked {@code @Primary}) serves the two staged source graphs to {@code addSource} and to
 * the operator's union, and captures the DRAFT the operator writes via {@code replaceDraft}.
 * Everything else is the production code path.
 */
@DisplayName("Integration model → compile → DRAFT")
class ModelCompileIntegrationIT extends BaseIntegrationTest {

    @Autowired IntegrationModelService modelService;
    @Autowired InMemoryGraphStore graphStore;
    @Autowired edu.upc.dtak.aran.core.domain.TenantRepository tenants;
    @Autowired edu.upc.dtak.aran.core.domain.UserRepository users;
    @Autowired edu.upc.dtak.aran.core.domain.ProjectRepository projects;
    @Autowired edu.upc.dtak.aran.core.domain.DataRepositoryRepository repositories;
    @Autowired edu.upc.dtak.aran.core.domain.DatasetRepository datasets;

    /** V37: model_source.dataset_id has a real FK — enrolments need real dataset rows. */
    private UUID provisionDataset(TenantContext ctx, UUID repoId) {
        UUID datasetId = UUID.randomUUID();
        datasets.save(ctx, new edu.upc.dtak.aran.core.domain.Dataset(
                datasetId, repoId, "ds-" + datasetId.toString().substring(0, 8), ""));
        return datasetId;
    }

    @Test
    @Transactional
    @DisplayName("two enrolled sources + one class alignment compile to a DRAFT with triples")
    void compile_produces_draft_with_triples() {
        var tenant = new TenantContext(UUID.randomUUID().toString());
        UUID actor = UUID.randomUUID();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = UUID.fromString(tenant.value());
        UUID userId = UUID.randomUUID();
        users.save(new edu.upc.dtak.aran.core.domain.User(
                userId, "sub-" + suffix, suffix + "@test.com", "Test", java.time.Instant.now()));
        tenants.save(new edu.upc.dtak.aran.core.domain.Tenant(
                tenantId, "tenant-" + suffix, "Tenant", java.time.Instant.now()));
        var project = projects.save(tenant, new edu.upc.dtak.aran.core.domain.Project(
                UUID.randomUUID(), tenantId, "Project-" + suffix, "", userId, java.time.Instant.now()));
        // V42: the model's project_id is a real FK, so it is this row's id, not a fresh UUID.
        UUID projectId = project.id();
        var dataRepo = repositories.save(tenant, new edu.upc.dtak.aran.core.domain.FileSystemRepository(
                UUID.randomUUID(), project.id(), tenantId, "repo-" + suffix, ""));
        UUID datasetA = provisionDataset(tenant, dataRepo.id());
        UUID datasetB = provisionDataset(tenant, dataRepo.id());
        String classA = "http://ex/A/Paper";
        String classB = "http://ex/B/Article";

        graphStore.stage(datasetA, sourceGraph(datasetA, classA, "SELECT * FROM ${SNAPSHOT}"));
        graphStore.stage(datasetB, sourceGraph(datasetB, classB, "SELECT * FROM ${SNAPSHOT}"));

        // Enrol both datasets — seeds model_source rows from the staged graphs.
        assertThat(modelService.addSource(tenant, projectId, datasetA)).hasSize(1);
        assertThat(modelService.addSource(tenant, projectId, datasetB)).hasSize(1);

        // One accepted class-equivalence alignment.
        modelService.addMapping(tenant, projectId, MappingKinds.CLASS, classA, classB, "Paper", actor, null);

        Map<String, String> result = modelService.rebuildToDraft(tenant, projectId, actor);

        assertThat(result).containsKey("draftVersionId");
        assertThat(Integer.parseInt(result.get("tripleCount"))).isGreaterThan(0);

        // The operator wrote a DRAFT via replaceDraft, and it is non-empty.
        GraphVersion draft = graphStore.lastDraft();
        assertThat(draft).isNotNull();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(graphStore.lastDraftSize()).isGreaterThan(0L);
    }

    /** A minimal source graph shaped like bootstrapping output: one rdfs:Class + wrapper. */
    private static Model sourceGraph(UUID datasetId, String classIri, String wrapperSql) {
        Model m = ModelFactory.createDefaultModel();
        Resource cls = m.createResource(classIri);
        m.add(cls, RDF.type, RDFS.Class);
        m.add(cls, m.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/format"), "CSV");
        m.add(cls, m.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/iterator"), "row");
        m.add(cls, m.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"), wrapperSql);
        m.add(cls, m.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/provenance"), datasetId.toString());
        return m;
    }
}

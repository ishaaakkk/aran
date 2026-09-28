package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationRunStatus;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The promote guard (UI-OD2, audit J4-F1) and the one promote owner for model drafts (J4-F2).
 *
 * <p>The ACTIVE graph is tenant-wide and a compile reads one project's sources, so promoting
 * project B's draft used to replace every class project A had promoted — its queries, virtual
 * products and enforced constraints went with it, after one unconfirmed click. The owner ruled a
 * guard: such a promote is refused, naming the classes. A model may still drop its own classes.
 */
@DisplayName("IntegrationModelService — promote guard (UI-OD2) and generic-promote delegation (J4-F2)")
class IntegrationModelServicePromoteGuardTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/integration/";

    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final UUID actor = UUID.randomUUID();
    private final UUID projectA = UUID.randomUUID();
    private final UUID projectB = UUID.randomUUID();

    private final InMemoryIntegrationModelRepository repo = new InMemoryIntegrationModelRepository();
    private final SchemaVersionServiceRollbackTest.InMemoryGraphVersionPort port =
            new SchemaVersionServiceRollbackTest.InMemoryGraphVersionPort(tenant);
    private final SchemaVersionServiceRollbackTest.InMemoryNamedGraphStore store =
            new SchemaVersionServiceRollbackTest.InMemoryNamedGraphStore(port);

    /** What the stub compile writes for each project: the local names of its classes. */
    private final Map<String, List<String>> classesByProject = new HashMap<>();

    /** Answers no project by default: the promoter is a member of none it is not told about. */
    private final ProjectRepository projects = mock(ProjectRepository.class);

    private final IntegrationModelService service = new IntegrationModelService(repo, store,
            new OperatorOrchestrator(List.of(stubCompiler())), new ObjectMapper(),
            mock(DatasetRepository.class), mock(DataProductService.class), Optional.empty(),
            Optional.of(projects));

    private void visibleToActor(UUID projectId, String name) {
        when(projects.findVisibleToUser(tenant, projectId, actor)).thenReturn(Optional.of(new Project(
                projectId, UUID.fromString(tenant.value()), name, "", actor, Instant.now(),
                "#000000", "private", "active", 0)));
    }

    /** A schema-integration stand-in whose draft holds exactly the classes its project declares. */
    private Operator stubCompiler() {
        return new Operator() {
            @Override public String getOperatorId() { return "schema-integration"; }
            @Override public String getDisplayName() { return "stub"; }
            @Override public OperatorResult execute(OperatorRequest req) {
                Model graph = graphOf(classesByProject.getOrDefault(req.parameters().get("projectId"), List.of()));
                GraphVersion draft = store.replaceDraft(tenant, graph, new AuditedActor(req.userId()));
                return OperatorResult.success(Map.of("draftVersionId", draft.id().toString(),
                        "appliedMappingIds", "", "deferredMappingIds", ""));
            }
        };
    }

    private static Model graphOf(List<String> localNames) {
        Model graph = ModelFactory.createDefaultModel();
        for (String name : localNames) {
            graph.add(graph.createResource(NS + name), RDF.type, RDFS.Class);
        }
        return graph;
    }

    /** Compile a project with the given classes; returns the run id. */
    private UUID compile(UUID project, String... classes) {
        classesByProject.put(project.toString(), List.of(classes));
        return UUID.fromString(service.rebuildToDraft(tenant, project, actor).get("integrationRunId"));
    }

    private UUID draftOf(UUID runId) {
        return repo.findRun(tenant, runId).orElseThrow().draftVersionId();
    }

    @Test
    @DisplayName("UI-OD2: a promote that would remove another model's classes is refused, naming them")
    void promoteRemovingAnotherModelsClassesIsRefused() {
        service.promoteRun(tenant, projectA, compile(projectA, "Author", "Paper"), actor);
        GraphVersion activeBefore = store.currentVersion(tenant);
        UUID runB = compile(projectB, "Venue");

        assertThatThrownBy(() -> service.promoteRun(tenant, projectB, runB, actor))
                .isInstanceOfSatisfying(PromoteConflictException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(PromoteConflictException.REMOVES_OTHER_MODELS_CLASSES);
                    assertThat(e.classIris()).containsExactly(NS + "Author", NS + "Paper");
                    assertThat(e.getMessage()).contains("Author").contains("Paper").contains("another project");
                });
        assertThat(store.currentVersion(tenant).id())
                .as("nothing was promoted — the tenant still reads project A's schema")
                .isEqualTo(activeBefore.id());
        assertThat(repo.findRun(tenant, runB).orElseThrow().status()).isEqualTo(IntegrationRunStatus.DRAFT);
    }

    @Test
    @DisplayName("UI-OD2: the refusal names the project that owns the classes and says what to do instead")
    void refusalNamesTheOwningProjectAndTheWayForward() {
        visibleToActor(projectA, "Bibliography");
        service.promoteRun(tenant, projectA, compile(projectA, "Author", "Paper"), actor);
        UUID runB = compile(projectB, "Venue");

        assertThatThrownBy(() -> service.promoteRun(tenant, projectB, runB, actor))
                .isInstanceOfSatisfying(PromoteConflictException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(PromoteConflictException.REMOVES_OTHER_MODELS_CLASSES);
                    assertThat(e.classIris()).containsExactly(NS + "Author", NS + "Paper");
                    assertThat(e.ownerProjects()).containsExactly("Bibliography");
                    assertThat(e.getMessage())
                            .contains("project \"Bibliography\"")
                            .contains("Author, Paper")
                            .contains("To promote")
                            .contains("enrol their sources in this project")
                            .contains("from \"Bibliography\"");
                });
    }

    @Test
    @DisplayName("UI-OD2: a project the promoter is not a member of is said to exist, never named")
    void aProjectThePromoterCannotSeeIsNotNamed() {
        service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        UUID runB = compile(projectB, "Venue");

        assertThatThrownBy(() -> service.promoteRun(tenant, projectB, runB, actor))
                .isInstanceOfSatisfying(PromoteConflictException.class, e -> {
                    assertThat(e.ownerProjects()).isEmpty();
                    assertThat(e.getMessage())
                            .contains("another project you are not a member of")
                            .doesNotContain(projectA.toString())
                            .contains("To promote");
                });
    }

    @Test
    @DisplayName("UI-OD2: a model may drop its own classes — the guard is about other projects' work")
    void aModelMayRemoveItsOwnClasses() {
        service.promoteRun(tenant, projectA, compile(projectA, "Author", "Paper"), actor);

        GraphVersion promoted = service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);

        assertThat(store.currentVersion(tenant).id()).isEqualTo(promoted.id());
    }

    @Test
    @DisplayName("UI-OD2: a promote that removes nothing is never refused, even from another project")
    void addingClassesIsNeverRefused() {
        service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);

        GraphVersion promoted = service.promoteRun(tenant, projectB, compile(projectB, "Author", "Venue"), actor);

        assertThat(store.currentVersion(tenant).id()).isEqualTo(promoted.id());
    }

    @Test
    @DisplayName("UI-OD2: after a rollback no run owns ACTIVE, and the classes are attributed to the model that promoted them")
    void rollbackActiveIsAttributedThroughEachModelsLastPromote() {
        GraphVersion v1 = service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        service.promoteRun(tenant, projectA, compile(projectA, "Author", "Paper"), actor);
        new SchemaVersionService(port, store, mock(DataProductService.class))
                .rollback(tenant, v1.id(), new AuditedActor(actor));
        UUID runB = compile(projectB, "Venue");

        assertThatThrownBy(() -> service.promoteRun(tenant, projectB, runB, actor))
                .isInstanceOfSatisfying(PromoteConflictException.class,
                        e -> assertThat(e.classIris()).containsExactly(NS + "Author"));
    }

    @Test
    @DisplayName("UI-OD2: a class another project also promoted is not dropped by the project that owns ACTIVE")
    void aClassAnotherProjectAlsoPromotedIsNotDroppedByTheActiveOwner() {
        // Review UA5 round 2, case 1: B follows the refusal's own advice — enrols A's sources and
        // compiles A's classes too — then un-enrols them. B owns ACTIVE, but A's last promote
        // still carries Author and A has not compiled without it, so Author is still A's.
        visibleToActor(projectA, "Bibliography");
        service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        GraphVersion shared = service.promoteRun(tenant, projectB, compile(projectB, "Author", "Venue"), actor);
        UUID runB = compile(projectB, "Venue");

        assertThatThrownBy(() -> service.promoteRun(tenant, projectB, runB, actor))
                .isInstanceOfSatisfying(PromoteConflictException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(PromoteConflictException.REMOVES_OTHER_MODELS_CLASSES);
                    assertThat(e.classIris()).containsExactly(NS + "Author");
                    assertThat(e.ownerProjects()).containsExactly("Bibliography");
                });
        assertThat(store.currentVersion(tenant).id()).isEqualTo(shared.id());
    }

    @Test
    @DisplayName("UI-OD2: after a rollback, a class two projects promoted is still the other project's")
    void afterARollbackASharedClassIsStillTheOtherProjects() {
        // Review UA5 round 2, case 2: no run owns ACTIVE after a rollback, and B's own last promote
        // carrying Author no longer excuses dropping it — A's carries it too.
        service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        GraphVersion v2 = service.promoteRun(tenant, projectB, compile(projectB, "Author", "Venue"), actor);
        service.promoteRun(tenant, projectB, compile(projectB, "Author", "Venue", "Keyword"), actor);
        new SchemaVersionService(port, store, mock(DataProductService.class))
                .rollback(tenant, v2.id(), new AuditedActor(actor));
        UUID runB = compile(projectB, "Venue");

        assertThatThrownBy(() -> service.promoteRun(tenant, projectB, runB, actor))
                .isInstanceOfSatisfying(PromoteConflictException.class,
                        e -> assertThat(e.classIris()).containsExactly(NS + "Author"));
    }

    @Test
    @DisplayName("UI-OD2: a class two projects promoted leaves once the other project has compiled a draft without it")
    void aSharedClassLeavesOnceTheOtherProjectHasCompiledWithoutIt() {
        // Without a release, a shared class could never leave: each project's promote would be
        // refused on the other's last promote. Compiling without it is how a project lets it go.
        service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        service.promoteRun(tenant, projectB, compile(projectB, "Author", "Venue"), actor);
        UUID runB = compile(projectB, "Venue");
        compile(projectA, "Venue");

        GraphVersion promoted = service.promoteRun(tenant, projectB, runB, actor);

        assertThat(store.currentVersion(tenant).id()).isEqualTo(promoted.id());
    }

    @Test
    @DisplayName("UI-OD2: the other way round too — the first project drops a shared class once the second compiled without it")
    void theFirstOwnerDropsASharedClassOnceTheSecondCompiledWithoutIt() {
        service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        service.promoteRun(tenant, projectB, compile(projectB, "Author", "Venue"), actor);
        // A enrolled B's source to keep Venue, and wants Author gone; B's newest draft drops it.
        compile(projectB, "Venue");
        UUID runA = compile(projectA, "Venue");

        GraphVersion promoted = service.promoteRun(tenant, projectA, runA, actor);

        assertThat(store.currentVersion(tenant).id()).isEqualTo(promoted.id());
    }

    @Test
    @DisplayName("UI-OD2: an unpromoted draft never releases a class to a project that never promoted it")
    void anUnpromotedDraftDoesNotReleaseAClassToAProjectThatNeverPromotedIt() {
        // The release is for a class the promoter shares; A's live classes stay A's until A promotes.
        service.promoteRun(tenant, projectA, compile(projectA, "Author", "Paper"), actor);
        compile(projectA, "Author");
        UUID runB = compile(projectB, "Author", "Venue");

        assertThatThrownBy(() -> service.promoteRun(tenant, projectB, runB, actor))
                .isInstanceOfSatisfying(PromoteConflictException.class,
                        e -> assertThat(e.classIris()).containsExactly(NS + "Paper"));
    }

    @Test
    @DisplayName("UI-OD2: a refused shared class says how it can leave — the other project compiles without it")
    void theRefusalOfASharedClassSaysHowItCanLeave() {
        visibleToActor(projectA, "Bibliography");
        service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        service.promoteRun(tenant, projectB, compile(projectB, "Author", "Venue"), actor);
        UUID runB = compile(projectB, "Venue");

        assertThatThrownBy(() -> service.promoteRun(tenant, projectB, runB, actor))
                .isInstanceOfSatisfying(PromoteConflictException.class, e -> assertThat(e.getMessage())
                        .contains("This project promoted Author too")
                        .contains("once \"Bibliography\" compiles a draft without")
                        .contains("then promote this again"));
    }

    @Test
    @DisplayName("UI-OD2 exemption: a rollback is typed and tenant-wide, and is not refused")
    void aRollbackIsNotBehindTheGuard() {
        // A rollback may remove classes another project promoted since the target version. Its
        // dialog shows that diff and asks for the version number typed; nothing refuses it. This
        // pins the current behaviour so that a ruling either way changes it on purpose.
        GraphVersion v1 = service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        service.promoteRun(tenant, projectB, compile(projectB, "Author", "Venue"), actor);

        GraphVersion rolledBack = new SchemaVersionService(port, store, mock(DataProductService.class))
                .rollback(tenant, v1.id(), new AuditedActor(actor));

        assertThat(store.currentVersion(tenant).id()).isEqualTo(rolledBack.id());
    }

    @Test
    @DisplayName("J4-F2: a model draft promoted from Schema versions becomes that model's baseline")
    void genericPromoteOfAModelDraftDelegatesToTheRun() {
        UUID run = compile(projectA, "Author");

        GraphVersion promoted = service.promoteDraftVersion(tenant, draftOf(run), actor);

        IntegrationModel model = repo.getOrCreate(tenant, projectA);
        assertThat(model.activeIntegrationRunId())
                .as("without this the next incremental add is refused NO_BASELINE")
                .isEqualTo(run);
        assertThat(repo.findRun(tenant, run).orElseThrow().status()).isEqualTo(IntegrationRunStatus.PROMOTED);
        assertThat(store.currentVersion(tenant).id()).isEqualTo(promoted.id());
    }

    @Test
    @DisplayName("J4-F2: a superseded draft is refused, not promoted behind its model's back")
    void genericPromoteOfASupersededDraftIsRefused() {
        UUID first = compile(projectA, "Author");
        compile(projectA, "Author", "Paper");

        assertThatThrownBy(() -> service.promoteDraftVersion(tenant, draftOf(first), actor))
                .isInstanceOfSatisfying(PromoteConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PromoteConflictException.DRAFT_SUPERSEDED));
        assertThat(store.currentVersion(tenant)).isNull();
    }

    @Test
    @DisplayName("UI-OD2: a draft no model owns is guarded too")
    void aDraftNoModelOwnsIsGuarded() {
        service.promoteRun(tenant, projectA, compile(projectA, "Author"), actor);
        GraphVersion orphan = store.replaceDraft(tenant, graphOf(List.of("Venue")), new AuditedActor(actor));

        assertThatThrownBy(() -> service.promoteDraftVersion(tenant, orphan.id(), actor))
                .isInstanceOfSatisfying(PromoteConflictException.class,
                        e -> assertThat(e.classIris()).containsExactly(NS + "Author"));
    }
}

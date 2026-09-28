package edu.upc.dtak.aran.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.TenantRole;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.AuditService;
import edu.upc.dtak.aran.core.service.DataProductService;
import edu.upc.dtak.aran.core.service.SchemaDiffService;
import edu.upc.dtak.aran.core.service.SchemaVersionService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.app.web.dto.SchemaVersionDtos.PromoteRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audit J7-F1: "who promoted v3?" had no answer in Activity. Promotion has three doors and no
 * single owner yet (D5 part A), so each door records its own line — these are the two that are not
 * a model run (that one is pinned in {@code IntegrationModelControllerTest}).
 */
@DisplayName("Schema promote and rollback are recorded in the audit trail")
class SchemaChangeAuditTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser caller =
            new AuthenticatedUser(userId, "sub", tenantId, TenantRole.OWNER);
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    private final AuditService audit = mock(AuditService.class);

    private GraphVersion active(UUID id, int number) {
        return new GraphVersion(id, tenantId, number, "ACTIVE", Instant.now(), userId,
                Instant.now(), null);
    }

    @Test
    @DisplayName("a promote from Schema versions is recorded with no project — tenant-wide")
    void promoteFromVersions() {
        when(currentUser.require()).thenReturn(caller);
        GraphStore graphStore = mock(GraphStore.class);
        // The controller promotes through the model service (UA5, J4-F2 / UI-OD2), not the store.
        var modelService = mock(edu.upc.dtak.aran.core.service.IntegrationModelService.class);
        UUID draft = UUID.randomUUID();
        when(modelService.promoteDraftVersion(any(), eq(draft), any())).thenReturn(active(draft, 4));
        SourceGraphController controller = new SourceGraphController(graphStore,
                mock(GraphVersionPort.class), mock(DatasetRepository.class), currentUser,
                modelService, mock(edu.upc.dtak.aran.core.identity.ProjectAccess.class),
                mock(DataProductService.class), audit);

        controller.promoteDraft(draft.toString(), null);

        verify(audit).schemaPromoted(caller, draft, 4, null);
    }

    @Test
    @DisplayName("a failing audit write neither fails the promote nor skips stale-marking and notes")
    void auditFailureIsNotFatal() {
        // The promote has happened by the time the line is written; an error here would send the
        // user to retry a promote that no longer exists, with products left READY on a
        // superseded version.
        when(currentUser.require()).thenReturn(caller);
        GraphStore graphStore = mock(GraphStore.class);
        // The controller promotes through the model service (UA5, J4-F2 / UI-OD2), not the store.
        var modelService = mock(edu.upc.dtak.aran.core.service.IntegrationModelService.class);
        GraphVersionPort versions = mock(GraphVersionPort.class);
        DataProductService products = mock(DataProductService.class);
        UUID draft = UUID.randomUUID();
        when(modelService.promoteDraftVersion(any(), eq(draft), any())).thenReturn(active(draft, 4));
        when(versions.save(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        doThrow(new IllegalStateException("audit insert failed"))
                .when(audit).schemaPromoted(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        SourceGraphController controller = new SourceGraphController(graphStore, versions,
                mock(DatasetRepository.class), currentUser,
                modelService, mock(edu.upc.dtak.aran.core.identity.ProjectAccess.class),
                products, audit);

        var response = controller.promoteDraft(draft.toString(), new PromoteRequest("why"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // Stale-marking now happens inside promoteDraftVersion (UA5); this pins notes-then-audit.
        var order = inOrder(versions, audit);
        order.verify(versions).save(any(), any());
        order.verify(audit).schemaPromoted(caller, draft, 4, null);
    }

    @Test
    @DisplayName("a failing audit write does not fail a rollback that happened")
    void rollbackAuditFailureIsNotFatal() {
        when(currentUser.require()).thenReturn(caller);
        SchemaVersionService versions = mock(SchemaVersionService.class);
        UUID restored = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        when(versions.rollback(any(), eq(restored), any())).thenReturn(active(fresh, 7));
        doThrow(new IllegalStateException("audit insert failed"))
                .when(audit).schemaRolledBack(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        SchemaVersionController controller = new SchemaVersionController(versions,
                mock(SchemaDiffService.class), currentUser, audit);

        assertThat(controller.rollback(restored)).isNotNull();
    }

    @Test
    @DisplayName("a promote the graph store refuses records nothing")
    void refusedPromote() {
        when(currentUser.require()).thenReturn(caller);
        GraphStore graphStore = mock(GraphStore.class);
        // The controller promotes through the model service (UA5, J4-F2 / UI-OD2), not the store.
        var modelService = mock(edu.upc.dtak.aran.core.service.IntegrationModelService.class);
        when(modelService.promoteDraftVersion(any(), any(), any()))
                .thenThrow(new IllegalStateException("Only DRAFT versions can be promoted"));
        SourceGraphController controller = new SourceGraphController(graphStore,
                mock(GraphVersionPort.class), mock(DatasetRepository.class), currentUser,
                modelService, mock(edu.upc.dtak.aran.core.identity.ProjectAccess.class),
                mock(DataProductService.class), audit);

        assertThatThrownBy(() -> controller.promoteDraft(UUID.randomUUID().toString(), null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a rollback is recorded with the new version and the one it restored")
    void rollback() {
        when(currentUser.require()).thenReturn(caller);
        SchemaVersionService versions = mock(SchemaVersionService.class);
        UUID restored = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        when(versions.rollback(any(), eq(restored), any())).thenReturn(active(fresh, 7));
        SchemaVersionController controller = new SchemaVersionController(versions,
                mock(SchemaDiffService.class), currentUser, audit);

        controller.rollback(restored);

        verify(audit).schemaRolledBack(caller, fresh, 7, restored);
    }
}

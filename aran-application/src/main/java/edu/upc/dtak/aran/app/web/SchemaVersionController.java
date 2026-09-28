package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.SchemaVersionDtos.VersionSummary;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.service.SchemaDiffService;
import edu.upc.dtak.aran.core.service.SchemaDiffService.SchemaDiff;
import edu.upc.dtak.aran.core.service.SchemaVersionService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the tenant's schema {@code GraphVersion} history (M7):
 * list, class/property-level diff between any two versions, and rollback of a
 * {@code HISTORICAL} version to active.
 *
 * <p>Promotion itself (and promote-with-notes) stays on {@link SourceGraphController}, the
 * long-standing home of the {@code /api/graph/promote/{id}} endpoint.
 */
@RestController
@RequestMapping("/api/schema/versions")
@NullMarked
public class SchemaVersionController {

    private final SchemaVersionService versionService;
    private final SchemaDiffService diffService;
    private final CurrentUserResolver currentUser;
    private final edu.upc.dtak.aran.core.service.AuditService audit;

    public SchemaVersionController(SchemaVersionService versionService,
                                   SchemaDiffService diffService,
                                   CurrentUserResolver currentUser,
                                   edu.upc.dtak.aran.core.service.AuditService audit) {
        this.versionService = versionService;
        this.diffService = diffService;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @GetMapping
    public List<VersionSummary> list() {
        AuthenticatedUser caller = currentUser.require();
        return versionService.list(tenant(caller)).stream()
                .map(VersionSummary::from)
                .toList();
    }

    @GetMapping("/{a}/diff/{b}")
    public SchemaDiff diff(@PathVariable UUID a, @PathVariable UUID b) {
        AuthenticatedUser caller = currentUser.require();
        return diffService.diff(tenant(caller), a, b);
    }

    @PostMapping("/{id}/rollback")
    public VersionSummary rollback(@PathVariable UUID id) {
        AuthenticatedUser caller = currentUser.require();
        GraphVersion promoted = versionService.rollback(
                tenant(caller), id, new AuditedActor(caller.userId()));
        // Audit J7-F1, never fatal: the rollback has happened (AfterTheFactAudit).
        AfterTheFactAudit.record("rollback to schema version " + promoted.versionNumber(),
                () -> audit.schemaRolledBack(caller, promoted.id(), promoted.versionNumber(), id));
        return VersionSummary.from(promoted);
    }

    private static TenantContext tenant(AuthenticatedUser caller) {
        return new TenantContext(caller.tenantId().toString());
    }
}

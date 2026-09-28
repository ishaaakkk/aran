package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CreateCubeRequest;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CubeDefinitionDto;
import edu.upc.dtak.aran.app.web.dto.CubeDtos.CubeDto;
import edu.upc.dtak.aran.core.domain.cube.CubeEvidence;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.CubeService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for a project's cubes (V40).
 *
 * <p>A cube is a dimensional model over the integrated schema: one fact class, its measures, the
 * dimensions reached from it in one hop, and an optional generated calendar. Creating one is a
 * single act with three artefacts — the definition row, one MATERIALIZED data product per star
 * table, and the cube's QB4OLAP named graph — so {@code POST} answers 201 with the cube <em>and</em>
 * the ids of the materialization jobs its star tables are running. There is no update endpoint,
 * mirroring both {@link edu.upc.dtak.aran.core.service.CubeService} and data products: a cube's
 * gates passed against one pinned schema version, and re-validating against a later one is
 * creating a new cube.
 *
 * <p>Every validation gate in the service throws {@link IllegalArgumentException}, which
 * {@link ApiExceptionHandler} maps to 400 — a measure over a non-numeric column, a dimension on a
 * join the platform cannot show to be to-one, a name the star's OQL cannot write.
 *
 * <p><b>Off unless the deployment asks for it</b> ({@code aran.cubes.enabled}, default false).
 * Its first live run (V62) found open defects, so v0.1.0 ships it disabled until they are fixed.
 * The gate is this one annotation because the whole
 * feature is reachable only through this controller — a cube's star tables are ordinary
 * MATERIALIZED data products and keep serving through the products tier either way. With the flag
 * off there is no bean, so there is no handler mapping, so a request dies in the DispatcherServlet
 * as a <b>404</b> before any project or cube work runs. 404 rather than 403 on purpose: the
 * feature does not exist for that deployment, and a 403 would both assert that it does and invite
 * a retry with different credentials.
 */
@RestController
@ConditionalOnProperty(name = "aran.cubes.enabled", havingValue = "true")
@RequestMapping("/api/projects/{projectId}/cubes")
public class CubeController {

    private final CubeService cubeService;
    private final CurrentUserResolver currentUser;
    private final ProjectAccess projectAccess;

    public CubeController(CubeService cubeService, CurrentUserResolver currentUser,
                          ProjectAccess projectAccess) {
        this.cubeService = cubeService;
        this.currentUser = currentUser;
        this.projectAccess = projectAccess;
    }

    @GetMapping
    public List<CubeDto> list(@PathVariable UUID projectId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        return cubeService.list(tenant(caller), projectId).stream().map(CubeDto::from).toList();
    }

    /**
     * What the shape-as-cube wizard pre-fills every step from: the integrated classes with their
     * typed, profiled properties and the referential edges with their trust tiers.
     *
     * <p>Answered as the parsed {@link CubeEvidence} rather than as the engine's payload string.
     * The engine's JSON is stringly typed by construction — the query-engine module is not on
     * core's compile path — and {@code CubeEvidence} is the one place in the platform that reads
     * it. Passing the raw string through here would make this controller a second reader of that
     * seam, and the field the two disagreed about would surface as a wizard rendering nothing,
     * with nothing failing.
     *
     * <p>Mapped ahead of {@code /{cubeId}} by Spring's literal-over-template precedence; the
     * declaration order here is not what decides it.
     */
    @GetMapping("/evidence")
    public CubeEvidence evidence(@PathVariable UUID projectId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        return cubeService.evidence(tenant(caller));
    }

    @PostMapping
    public ResponseEntity<CubeDto> create(@PathVariable UUID projectId,
                                          @RequestBody CreateCubeRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        // Missing pieces of the body must be a legible 400, not an NPE's 500 (V48).
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (body.definition() == null) {
            throw new IllegalArgumentException("definition is required");
        }
        CubeDefinitionDto definition = body.definition();
        CubeService.CubeCreation created = cubeService.create(
                tenant(caller), projectId, body.name(), body.description(),
                definition.factClassIri(), definition.toMeasures(), definition.toDimensions(),
                definition.toCalendar(), caller.userId());
        CubeDto dto = CubeDto.from(created.cube(), created.jobIds());
        return ResponseEntity
                .created(URI.create("/api/projects/" + projectId + "/cubes/" + dto.id()))
                .body(dto);
    }

    @GetMapping("/{cubeId}")
    public CubeDto get(@PathVariable UUID projectId, @PathVariable UUID cubeId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        return CubeDto.from(cubeService.get(tenant(caller), projectId, cubeId));
    }

    /** The cube as {@code cube.yaml} — the payload <em>is</em> the text, so it is not wrapped. */
    @GetMapping("/{cubeId}/yaml")
    public ResponseEntity<String> yaml(@PathVariable UUID projectId, @PathVariable UUID cubeId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        cubeService.get(tenant(caller), projectId, cubeId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(cubeService.getYaml(tenant(caller), cubeId));
    }

    @DeleteMapping("/{cubeId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID cubeId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        cubeService.get(tenant(caller), projectId, cubeId);
        cubeService.delete(tenant(caller), cubeId);
        return ResponseEntity.noContent().build();
    }

    private static TenantContext tenant(AuthenticatedUser caller) {
        return new TenantContext(caller.tenantId().toString());
    }
}

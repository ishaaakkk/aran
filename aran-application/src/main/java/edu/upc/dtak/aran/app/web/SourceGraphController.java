package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.GraphDtos;
import edu.upc.dtak.aran.app.web.dto.SchemaVersionDtos.PromoteRequest;
import edu.upc.dtak.aran.app.web.dto.SchemaVersionDtos.PromoteResponse;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.IntegrationModelService;
import edu.upc.dtak.aran.core.service.DataProductService;
import edu.upc.dtak.aran.core.service.ResourceNotFoundException;
import edu.upc.dtak.aran.core.service.SourceGraphSerializer;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/graph")
@NullMarked
public class SourceGraphController {

    private static final Logger log = LoggerFactory.getLogger(SourceGraphController.class);

    private final GraphStore graphStore;
    private final GraphVersionPort versionPort;
    private final DatasetRepository datasetRepository;
    private final CurrentUserResolver currentUser;
    private final IntegrationModelService modelService;
    private final ProjectAccess projectAccess;
    private final DataProductService dataProductService;
    private final edu.upc.dtak.aran.core.service.AuditService audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SourceGraphController(GraphStore graphStore,
                                 GraphVersionPort versionPort,
                                 DatasetRepository datasetRepository,
                                 CurrentUserResolver currentUser,
                                 IntegrationModelService modelService,
                                 ProjectAccess projectAccess,
                                 DataProductService dataProductService,
                                 edu.upc.dtak.aran.core.service.AuditService audit) {
        this.graphStore = graphStore;
        this.versionPort = versionPort;
        this.datasetRepository = datasetRepository;
        this.currentUser = currentUser;
        this.modelService = modelService;
        this.projectAccess = projectAccess;
        this.dataProductService = dataProductService;
        this.audit = audit;
    }

    @GetMapping("/source/{datasetId}")
    public ResponseEntity<GraphDtos.SourceGraphResponseDto> getSourceGraph(@PathVariable String datasetId) {
        var caller = currentUser.require();
        var tenant = new TenantContext(caller.tenantId().toString());

        UUID datasetUuid;
        try {
            datasetUuid = UUID.fromString(datasetId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        var datasetOpt = datasetRepository.findById(tenant, datasetUuid);
        if (datasetOpt.isPresent() && datasetOpt.get().graphJson() != null) {
            try {
                GraphDtos.SourceGraphResponseDto cachedGraph = objectMapper.readValue(datasetOpt.get().graphJson(), GraphDtos.SourceGraphResponseDto.class);
                return ResponseEntity.ok(cachedGraph);
            } catch (Exception e) {
                // fall back to Fuseki on deserialization failure
            }
        }

        String graphUri = "urn:aran:source:" + datasetId;
        Model model = queryModel(tenant, graphUri,
                "Could not query Fuseki for dataset graph {} (likely empty or not yet bootstrapped): {}",
                datasetId);

        var graph = SourceGraphSerializer.build(model, false);

        var response = new GraphDtos.SourceGraphResponseDto(
                datasetId,
                graphUri,
                model.size(),
                model.isEmpty(),
                toNodeDtos(graph),
                toEdgeDtos(graph)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/promote/{draftVersionId}")
    public ResponseEntity<PromoteResponse> promoteDraft(
            @PathVariable String draftVersionId,
            @RequestBody(required = false) PromoteRequest body) {
        var caller = currentUser.require();
        var tenant = new TenantContext(caller.tenantId().toString());

        UUID draftUuid;
        try {
            draftUuid = UUID.fromString(draftVersionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        // A project's draft is promoted as that project's run, so it writes that project's state
        // (run status, baseline): only its members may, as on the model route (review UA5, D11).
        // The port's own refusal names the project id; this one names nothing it need not.
        modelService.owningProjectOfDraft(tenant, draftUuid).ifPresent(projectId -> {
            try {
                projectAccess.getVisibleProject(caller, projectId);
            } catch (ResourceNotFoundException notVisible) {
                throw new ResourceNotFoundException("This draft was compiled by a project you are "
                        + "not a member of; only its members can promote it. Nothing was promoted.");
            }
        });

        // One promote owner (J4-F2): a model run's draft is promoted as that run, so the model
        // gets its baseline; every path is behind the UI-OD2 guard; stale-marking happens inside.
        var promoted = modelService.promoteDraftVersion(tenant, draftUuid, caller.userId());

        // Optional promotion notes: persist them onto the freshly-promoted version.
        String notes = body != null ? body.notes() : null;
        if (notes != null && !notes.isBlank()) {
            promoted = versionPort.save(tenant, new GraphVersion(
                    promoted.id(),
                    promoted.tenantId(),
                    promoted.versionNumber(),
                    promoted.status(),
                    promoted.createdAt(),
                    promoted.createdBy(),
                    promoted.promotedAt(),
                    notes));
        }

        // Audit J7-F1, written last and never fatal (AfterTheFactAudit). No project: a promote
        // from Schema versions is tenant-wide, so its line reaches owners' dashboards and not
        // members' (UI-OD11).
        var recorded = promoted;
        AfterTheFactAudit.record("promote of schema version " + recorded.versionNumber(),
                () -> audit.schemaPromoted(caller, recorded.id(), recorded.versionNumber(), null));

        return ResponseEntity.ok(PromoteResponse.from(promoted));
    }

    @GetMapping("/schema/{versionId}")
    public ResponseEntity<GraphDtos.SchemaGraphResponseDto> getSchemaGraph(@PathVariable String versionId) {
        var caller = currentUser.require();
        var tenant = new TenantContext(caller.tenantId().toString());

        UUID versionUuid;
        try {
            versionUuid = UUID.fromString(versionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        String graphUri = "urn:aran:schema:version:" + versionId;
        Model model = queryModel(tenant, graphUri,
                "Could not query Fuseki for schema version graph {} (likely empty): {}",
                versionId);

        var graph = SourceGraphSerializer.build(model, true);

        var response = new GraphDtos.SchemaGraphResponseDto(
                versionId,
                graphUri,
                model.size(),
                model.isEmpty(),
                toNodeDtos(graph),
                toEdgeDtos(graph)
        );
        return ResponseEntity.ok(response);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static List<GraphDtos.GraphNodeDto> toNodeDtos(SourceGraphSerializer.Graph graph) {
        return graph.nodes().stream()
                .map(n -> new GraphDtos.GraphNodeDto(n.id(), n.label(), n.iri(), n.type()))
                .toList();
    }

    private static List<GraphDtos.GraphEdgeDto> toEdgeDtos(SourceGraphSerializer.Graph graph) {
        return graph.edges().stream()
                .map(e -> new GraphDtos.GraphEdgeDto(e.source(), e.target(), e.label()))
                .toList();
    }

    private Model queryModel(TenantContext tenant, String graphUri, String warnTemplate, String id) {
        String sparql = "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }";
        try {
            return graphStore.query(tenant, sparql);
        } catch (Exception e) {
            log.warn(warnTemplate, id, e.getMessage());
            return org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        }
    }
}

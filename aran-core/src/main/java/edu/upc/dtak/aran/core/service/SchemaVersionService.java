package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;

/**
 * Read + lifecycle operations over a tenant's schema {@link GraphVersion} history:
 * listing every version and rolling a {@code HISTORICAL} one back to active.
 *
 * <p>Promotion (DRAFT → ACTIVE) already lives on {@link GraphStore}; this service adds the
 * two capabilities the versions view needs on top of it without mutating status in place.
 */
@Service
@NullMarked
public class SchemaVersionService {

    private final GraphVersionPort versionPort;
    private final GraphStore graphStore;
    private final DataProductService dataProductService;

    public SchemaVersionService(GraphVersionPort versionPort, GraphStore graphStore,
                                DataProductService dataProductService) {
        this.versionPort = versionPort;
        this.graphStore = graphStore;
        this.dataProductService = dataProductService;
    }

    /** Every version the tenant owns, newest version number first. */
    public List<GraphVersion> list(TenantContext tenant) {
        return versionPort.findAll(tenant);
    }

    /**
     * Roll a {@code HISTORICAL} version back to active. Never mutates a status field in
     * place: it re-materialises that version's full graph as a fresh DRAFT and promotes it,
     * so the returned ACTIVE version is a <em>new</em> version number carrying the old shape.
     *
     * @throws ResourceNotFoundException when the version does not exist (or is not visible)
     * @throws IllegalArgumentException  when the version is not {@code HISTORICAL}
     */
    public GraphVersion rollback(TenantContext tenant, UUID versionId, AuditedActor actor) {
        GraphVersion target = versionPort.findById(tenant, versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Schema version not found: " + versionId));
        if (!"HISTORICAL".equals(target.status())) {
            throw new IllegalArgumentException(
                    "Only HISTORICAL versions can be rolled back; version " + target.versionNumber()
                            + " is " + target.status());
        }

        String graphUri = "urn:aran:schema:version:" + versionId;
        String sparql = "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }";
        Model snapshot = graphStore.query(tenant, sparql);

        GraphVersion draft = graphStore.replaceDraft(tenant, snapshot, actor);
        GraphVersion promoted = graphStore.promote(tenant, draft.id(), actor);
        // A new ACTIVE version exists — READY data products pinned elsewhere go STALE (M8).
        dataProductService.markStaleForVersionChange(tenant);
        return promoted;
    }
}

package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.apache.jena.rdf.model.Model;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Agnostic Port interface for querying, modifying, and versioning semantic RDF triple graphs.
 */
@NullMarked
public interface GraphStore {
    /** Provision a new isolated dataset for the tenant dynamically. */
    void provision(TenantContext tenant);

    /** Query a graph via SPARQL, returning the results as an RDF Model. */
    Model query(TenantContext tenant, String sparql);

    /**
     * Write a complete replacement draft — the provided graph is stored as-is without
     * inheriting any triples from the current active version. Used by operators that
     * produce the full integrated graph in one shot (e.g. schema-integration).
     *
     * <p>This is the only way to make a draft. A merge-on-top-of-active sibling,
     * {@code appendDraft}, existed until 2026-08-27 (audit INTEGRATION_COMPILE-9) with zero
     * production callers: every compile path produces the whole integrated graph, so there was
     * never a fragment to merge. It is not worth reviving from history — it built its draft with
     * SPARQL {@code COPY}, the same primitive {@code promote} documents as truncating whatever
     * is already in the target graph.
     */
    GraphVersion replaceDraft(TenantContext tenant, Model fullGraph, AuditedActor actor);

    /** Promote a draft graph version to active. */
    GraphVersion promote(TenantContext tenant, UUID draftVersionId, AuditedActor actor);

    /** Retrieve the currently active graph version of the tenant. */
    @Nullable
    GraphVersion currentVersion(TenantContext tenant);

    /**
     * Read a specific historical/active graph version as an immutable snapshot. Used
     * by incremental integration to load the exact {@code G_I^old} an incremental step
     * is based on.
     */
    Model readVersion(TenantContext tenant, UUID versionId);

    /**
     * Write {@code fullGraph} as a replacement draft, but only if the tenant's active
     * version is still {@code expectedActiveVersionId}; otherwise throw
     * {@link StaleGraphBaseException}. This is the optimistic-concurrency guard that
     * makes an incremental write safe against a concurrent promote: read the base
     * version, compute the new graph, then compare-and-write.
     *
     * <p>The default is impl-agnostic — it compares {@link #currentVersion} to the
     * expected id and, on a match, reuses {@link #replaceDraft}. Implementations with a
     * cheaper atomic path may override.
     */
    default GraphVersion replaceDraftFromBase(TenantContext tenant, UUID expectedActiveVersionId,
                                              Model fullGraph, AuditedActor actor) {
        GraphVersion active = currentVersion(tenant);
        UUID activeId = active == null ? null : active.id();
        if (!Objects.equals(activeId, expectedActiveVersionId)) {
            throw new StaleGraphBaseException(expectedActiveVersionId, activeId);
        }
        return replaceDraft(tenant, fullGraph, actor);
    }

    /** Add triples (Jena Model) directly to a specific named graph. */
    void addTriples(TenantContext tenant, String graphUri, Model triples);

    /** Clear all triples in a specific named graph. */
    void clearGraph(TenantContext tenant, String graphUri);
}


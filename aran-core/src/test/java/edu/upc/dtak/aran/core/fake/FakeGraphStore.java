package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * A {@link GraphStore} for service tests: {@link #query} returns whatever Model was
 * staged for the tenant (defaulting to an empty one, which is what a missing source
 * graph looks like), and the mutating methods are not exercised by these tests.
 */
public class FakeGraphStore implements GraphStore {

    private final Map<String, Model> stagedByTenant = new HashMap<>();
    private final Map<String, Map<String, Model>> stagedByGraph = new HashMap<>();
    private final List<String> queries = new ArrayList<>();

    /** Every SPARQL text {@link #query} was asked, in order — for tests that bound the reads. */
    public List<String> queries() {
        return queries;
    }

    /** Stage the Model that {@link #query} will hand back under this tenant. */
    public void stage(TenantContext tenant, Model model) {
        stagedByTenant.put(tenant.value(), model);
    }

    /**
     * Stage one named graph, matched by the graph URI appearing in the query text.
     *
     * <p>Needed once a caller reads more than one graph and the answers must differ — V47's
     * stale-wrapper check compares the promoted {@code urn:aran:schema} against a freshly
     * bootstrapped {@code urn:aran:source:<datasetId>}, and a store that returned the same Model
     * for both could not express the drift it exists to detect. A tenant-wide {@link #stage}
     * still answers anything not staged here, so existing tests are untouched.
     */
    public void stageGraph(TenantContext tenant, String graphUri, Model model) {
        stagedByGraph.computeIfAbsent(tenant.value(), k -> new HashMap<>()).put(graphUri, model);
    }

    @Override
    public Model query(TenantContext tenant, String sparql) {
        queries.add(sparql);
        for (Map.Entry<String, Model> e
                : stagedByGraph.getOrDefault(tenant.value(), Map.of()).entrySet()) {
            if (sparql.contains("<" + e.getKey() + ">")) {
                return e.getValue();
            }
        }
        return stagedByTenant.getOrDefault(tenant.value(), ModelFactory.createDefaultModel());
    }

    @Override
    public void provision(TenantContext tenant) {
    }

    @Override
    public GraphVersion replaceDraft(TenantContext tenant, Model fullGraph, AuditedActor actor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public GraphVersion promote(TenantContext tenant, UUID draftVersionId, AuditedActor actor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public GraphVersion currentVersion(TenantContext tenant) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Model readVersion(TenantContext tenant, UUID versionId) {
        return stagedByTenant.getOrDefault(tenant.value(), ModelFactory.createDefaultModel());
    }

    @Override
    public void addTriples(TenantContext tenant, String graphUri, Model triples) {
    }

    @Override
    public void clearGraph(TenantContext tenant, String graphUri) {
    }
}

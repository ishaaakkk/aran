package edu.upc.dtak.aran.operators.schemaintegration;

import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.jspecify.annotations.Nullable;

/**
 * A faithful in-memory {@link GraphStore} for operator-level tests — named graphs plus
 * version bookkeeping — mirroring {@code FusekiGraphStoreAdapter}'s draft/promote/version
 * semantics (the version's own {@code urn:aran:schema:version:{id}} graph is written at
 * draft time and left intact by promote, so {@link #readVersion} is a stable snapshot).
 * CI-safe: no Fuseki required. Stage source graphs with {@link #stageSource}.
 */
final class StatefulGraphStore implements GraphStore {

    private static final Pattern GRAPH_URI = Pattern.compile("GRAPH <([^>]+)>");
    private static final String ACTIVE_GRAPH = "urn:aran:schema";

    private final Map<String, Model> graphs = new HashMap<>();
    private final List<GraphVersion> versions = new ArrayList<>();

    void stageSource(UUID datasetId, Model model) {
        graphs.put("urn:aran:source:" + datasetId, copy(model));
    }

    @Override
    public Model query(TenantContext tenant, String sparql) {
        Matcher m = GRAPH_URI.matcher(sparql);
        Model out = ModelFactory.createDefaultModel();
        if (m.find()) {
            Model stored = graphs.get(m.group(1));
            if (stored != null) {
                out.add(stored);
            }
        }
        return out;
    }

    @Override
    public Model readVersion(TenantContext tenant, UUID versionId) {
        return copy(graphs.getOrDefault("urn:aran:schema:version:" + versionId,
                ModelFactory.createDefaultModel()));
    }

    @Override
    public GraphVersion replaceDraft(TenantContext tenant, Model fullGraph, AuditedActor actor) {
        UUID id = UUID.randomUUID();
        int next = versions.stream().mapToInt(GraphVersion::versionNumber).max().orElse(0) + 1;
        graphs.put("urn:aran:schema:version:" + id, copy(fullGraph));
        GraphVersion draft = new GraphVersion(id, UUID.fromString(tenant.value()), next, "DRAFT",
                Instant.now(), actor.userId(), null, "Draft " + next);
        versions.add(draft);
        return draft;
    }

    @Override
    public GraphVersion promote(TenantContext tenant, UUID draftVersionId, AuditedActor actor) {
        GraphVersion draft = versions.stream().filter(v -> v.id().equals(draftVersionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("draft not found: " + draftVersionId));
        graphs.put(ACTIVE_GRAPH, copy(graphs.getOrDefault("urn:aran:schema:version:" + draftVersionId,
                ModelFactory.createDefaultModel())));
        // Demote the current active to HISTORICAL.
        for (int i = 0; i < versions.size(); i++) {
            GraphVersion v = versions.get(i);
            if ("ACTIVE".equals(v.status())) {
                versions.set(i, new GraphVersion(v.id(), v.tenantId(), v.versionNumber(), "HISTORICAL",
                        v.createdAt(), v.createdBy(), v.promotedAt(), v.notes()));
            }
        }
        GraphVersion promoted = new GraphVersion(draft.id(), draft.tenantId(), draft.versionNumber(),
                "ACTIVE", draft.createdAt(), draft.createdBy(), Instant.now(), draft.notes());
        versions.replaceAll(v -> v.id().equals(draft.id()) ? promoted : v);
        return promoted;
    }

    @Override
    public @Nullable GraphVersion currentVersion(TenantContext tenant) {
        return versions.stream().filter(v -> "ACTIVE".equals(v.status())).reduce((a, b) -> b).orElse(null);
    }

    private static Model copy(Model m) {
        return ModelFactory.createDefaultModel().add(m);
    }

    @Override public void provision(TenantContext tenant) { }
    @Override public void addTriples(TenantContext tenant, String graphUri, Model triples) {
        graphs.computeIfAbsent(graphUri, k -> ModelFactory.createDefaultModel()).add(triples);
    }
    @Override public void clearGraph(TenantContext tenant, String graphUri) {
        graphs.remove(graphUri);
    }
}

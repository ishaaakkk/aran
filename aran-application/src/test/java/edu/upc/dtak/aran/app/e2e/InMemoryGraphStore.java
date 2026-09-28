package edu.upc.dtak.aran.app.e2e;

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
 * A {@link GraphStore} mirroring {@code FusekiGraphStoreAdapter}'s version semantics, for the
 * Docker-free end-to-end tests in this package.
 *
 * <p>Named graphs plus version bookkeeping: a draft is written to its own
 * {@code urn:aran:schema:version:{id}} graph and left intact by promote, which copies it
 * into {@code urn:aran:schema} — so {@code readVersion} is a stable snapshot and the
 * catalog reads the active graph exactly as it does against Fuseki.
 */
final class InMemoryGraphStore implements GraphStore {
    private static final Pattern GRAPH_URI = Pattern.compile("GRAPH <([^>]+)>");
    private static final String ACTIVE = "urn:aran:schema";

    private final Map<String, Model> graphs = new HashMap<>();
    private final List<GraphVersion> versions = new ArrayList<>();

    @Override public Model query(TenantContext t, String sparql) {
        Matcher m = GRAPH_URI.matcher(sparql);
        Model out = ModelFactory.createDefaultModel();
        if (m.find()) {
            Model stored = graphs.get(m.group(1));
            if (stored != null) out.add(stored);
        }
        return out;
    }

    @Override public Model readVersion(TenantContext t, UUID versionId) {
        return copy(graphs.getOrDefault("urn:aran:schema:version:" + versionId,
                ModelFactory.createDefaultModel()));
    }

    @Override public GraphVersion replaceDraft(TenantContext t, Model full, AuditedActor actor) {
        UUID id = UUID.randomUUID();
        int next = versions.stream().mapToInt(GraphVersion::versionNumber).max().orElse(0) + 1;
        graphs.put("urn:aran:schema:version:" + id, copy(full));
        GraphVersion draft = new GraphVersion(id, UUID.fromString(t.value()), next, "DRAFT",
                Instant.now(), actor.userId(), null, "Draft " + next);
        versions.add(draft);
        return draft;
    }

    @Override public GraphVersion promote(TenantContext t, UUID draftId, AuditedActor actor) {
        GraphVersion draft = versions.stream().filter(v -> v.id().equals(draftId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("draft not found: " + draftId));
        graphs.put(ACTIVE, copy(graphs.getOrDefault("urn:aran:schema:version:" + draftId,
                ModelFactory.createDefaultModel())));
        versions.replaceAll(v -> "ACTIVE".equals(v.status())
                ? new GraphVersion(v.id(), v.tenantId(), v.versionNumber(), "HISTORICAL",
                        v.createdAt(), v.createdBy(), v.promotedAt(), v.notes())
                : v);
        GraphVersion promoted = new GraphVersion(draft.id(), draft.tenantId(),
                draft.versionNumber(), "ACTIVE", draft.createdAt(), draft.createdBy(),
                Instant.now(), draft.notes());
        versions.replaceAll(v -> v.id().equals(draft.id()) ? promoted : v);
        return promoted;
    }

    @Override public @Nullable GraphVersion currentVersion(TenantContext t) {
        return versions.stream().filter(v -> "ACTIVE".equals(v.status()))
                .reduce((a, b) -> b).orElse(null);
    }

    @Override public void addTriples(TenantContext t, String graphUri, Model triples) {
        graphs.computeIfAbsent(graphUri, k -> ModelFactory.createDefaultModel()).add(triples);
    }

    @Override public void clearGraph(TenantContext t, String graphUri) {
        graphs.remove(graphUri);
    }

    @Override public void provision(TenantContext t) { }

    private static Model copy(Model m) {
        return ModelFactory.createDefaultModel().add(m);
    }
}

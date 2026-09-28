package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.domain.StaleGraphBaseException;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rollback exercise for {@link SchemaVersionService} against an in-memory named-graph store
 * that mirrors {@code FusekiGraphStore}'s promote/replaceDraft bookkeeping — the repo ships no
 * Fuseki test harness, so a faithful in-memory double keeps this CI-safe.
 *
 * <p>Flow: promote v1 (Paper only), promote v2 (Paper + Author), roll v1 back → the new ACTIVE
 * version is number 3 and carries v1's shape (proven by reusing {@link SchemaDiffService}:
 * {@code diff(v3, v1)} is empty).
 */
@DisplayName("SchemaVersionService.rollback")
class SchemaVersionServiceRollbackTest {

    private static final String NS = "http://ex/";
    private final TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
    private final AuditedActor actor = new AuditedActor(UUID.randomUUID());

    @Test
    @DisplayName("rolling a HISTORICAL version back produces a new ACTIVE version with its shape")
    void rollback_restores_historical_shape_as_new_version() {
        var port = new InMemoryGraphVersionPort(tenant);
        var store = new InMemoryNamedGraphStore(port);
        var versionService = new SchemaVersionService(port, store, productService(store));
        var diffService = new SchemaDiffService(store);

        // v1: Paper only.
        GraphVersion v1 = store.promote(tenant, store.replaceDraft(tenant, schema("Paper"), actor).id(), actor);
        assertThat(v1.versionNumber()).isEqualTo(1);

        // v2: Paper + Author.
        GraphVersion v2 = store.promote(tenant,
                store.replaceDraft(tenant, schema("Paper", "Author"), actor).id(), actor);
        assertThat(v2.versionNumber()).isEqualTo(2);
        assertThat(port.findById(tenant, v1.id()).orElseThrow().status()).isEqualTo("HISTORICAL");

        // Roll v1 (now HISTORICAL) back.
        GraphVersion rolled = versionService.rollback(tenant, v1.id(), actor);

        assertThat(rolled.status()).isEqualTo("ACTIVE");
        assertThat(rolled.versionNumber()).isEqualTo(3);
        assertThat(port.findCurrentActive(tenant).orElseThrow().id()).isEqualTo(rolled.id());
        // v3 carries v1's exact shape.
        assertThat(diffService.diff(tenant, rolled.id(), v1.id()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("rolling back a non-HISTORICAL version is rejected")
    void rollback_of_active_version_is_rejected() {
        var port = new InMemoryGraphVersionPort(tenant);
        var store = new InMemoryNamedGraphStore(port);
        var versionService = new SchemaVersionService(port, store, productService(store));

        GraphVersion v1 = store.promote(tenant, store.replaceDraft(tenant, schema("Paper"), actor).id(), actor);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> versionService.rollback(tenant, v1.id(), actor));
    }

    @Test
    @DisplayName("readVersion returns a promoted version's triples as a snapshot")
    void readVersion_returns_version_snapshot() {
        var port = new InMemoryGraphVersionPort(tenant);
        var store = new InMemoryNamedGraphStore(port);

        GraphVersion v1 = store.promote(tenant, store.replaceDraft(tenant, schema("Paper"), actor).id(), actor);

        Model snap = store.readVersion(tenant, v1.id());
        assertThat(snap.contains(snap.createResource(NS + "Paper"), RDF.type, RDFS.Class)).isTrue();
    }

    @Test
    @DisplayName("replaceDraftFromBase writes when the base matches and rejects a stale base after a concurrent promote")
    void replaceDraftFromBase_enforces_cas() {
        var port = new InMemoryGraphVersionPort(tenant);
        var store = new InMemoryNamedGraphStore(port);

        GraphVersion v1 = store.promote(tenant, store.replaceDraft(tenant, schema("Paper"), actor).id(), actor);

        // Base still matches the active version → a draft is written.
        GraphVersion draft = store.replaceDraftFromBase(tenant, v1.id(), schema("Paper", "Author"), actor);
        assertThat(draft.status()).isEqualTo("DRAFT");

        // A concurrent promote moves the active pointer to v2.
        GraphVersion v2 = store.promote(tenant,
                store.replaceDraft(tenant, schema("Paper", "Author"), actor).id(), actor);
        assertThat(port.findCurrentActive(tenant).orElseThrow().id()).isEqualTo(v2.id());

        // Now the v1 base is stale → the write is rejected.
        assertThatThrownBy(() -> store.replaceDraftFromBase(tenant, v1.id(), schema("Reviewer"), actor))
                .isInstanceOf(StaleGraphBaseException.class);
    }

    /** A real DataProductService over in-memory fakes — rollback's M8 stale-marking hook. */
    private static DataProductService productService(GraphStore store) {
        return new DataProductService(
                new edu.upc.dtak.aran.core.fake.InMemoryDataProductRepository(), store,
                new edu.upc.dtak.aran.core.operator.OperatorOrchestrator(List.of()),
                new JobService(new edu.upc.dtak.aran.core.fake.InMemoryJobRepository(),
                        Runnable::run, (id, job) -> { }),
                new edu.upc.dtak.aran.core.fake.CapturingLakeClient(),
                new UsageService(new edu.upc.dtak.aran.core.fake.RecordingUsageEventRepository()),
                edu.upc.dtak.aran.core.fake.UncappedLicenses.create(),
                new edu.upc.dtak.aran.core.fake.InMemoryCubeRepository());
    }

    private static Model schema(String... classLocals) {
        Model m = ModelFactory.createDefaultModel();
        for (String local : classLocals) {
            Resource c = m.createResource(NS + local);
            m.add(c, RDF.type, RDFS.Class);
            m.add(c, m.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql"),
                    "SELECT * FROM " + local);
        }
        return m;
    }

    // ── In-memory doubles ──────────────────────────────────────────────────────

    /** Named-graph store: query resolves {@code GRAPH <uri>}; promote/replaceDraft track versions. */
    static final class InMemoryNamedGraphStore implements GraphStore {
        private static final Pattern GRAPH_URI = Pattern.compile("GRAPH <([^>]+)>");
        private static final String ACTIVE_GRAPH = "urn:aran:schema";

        private final Map<String, Model> graphs = new HashMap<>();
        private final GraphVersionPort port;

        InMemoryNamedGraphStore(GraphVersionPort port) {
            this.port = port;
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
        public GraphVersion replaceDraft(TenantContext tenant, Model fullGraph, AuditedActor actor) {
            UUID id = UUID.randomUUID();
            int next = port.findLatest(tenant).map(v -> v.versionNumber() + 1).orElse(1);
            graphs.put("urn:aran:schema:version:" + id, copy(fullGraph));
            GraphVersion draft = new GraphVersion(id, UUID.fromString(tenant.value()), next, "DRAFT",
                    Instant.now(), actor.userId(), null, "Replacement draft version " + next);
            return port.save(tenant, draft);
        }

        @Override
        public GraphVersion promote(TenantContext tenant, UUID draftVersionId, AuditedActor actor) {
            GraphVersion draft = port.findById(tenant, draftVersionId).orElseThrow();
            graphs.put(ACTIVE_GRAPH, copy(graphs.getOrDefault("urn:aran:schema:version:" + draftVersionId,
                    ModelFactory.createDefaultModel())));
            port.findCurrentActive(tenant).ifPresent(active -> port.save(tenant, new GraphVersion(
                    active.id(), active.tenantId(), active.versionNumber(), "HISTORICAL",
                    active.createdAt(), active.createdBy(), active.promotedAt(), active.notes())));
            return port.save(tenant, new GraphVersion(draft.id(), draft.tenantId(), draft.versionNumber(),
                    "ACTIVE", draft.createdAt(), draft.createdBy(), Instant.now(), draft.notes()));
        }

        private static Model copy(Model m) {
            return ModelFactory.createDefaultModel().add(m);
        }

        @Override public void provision(TenantContext tenant) { }
        @Override public GraphVersion currentVersion(TenantContext tenant) {
            return port.findCurrentActive(tenant).orElse(null);
        }
        @Override public Model readVersion(TenantContext tenant, UUID versionId) {
            return copy(graphs.getOrDefault("urn:aran:schema:version:" + versionId,
                    ModelFactory.createDefaultModel()));
        }
        @Override public void addTriples(TenantContext tenant, String graphUri, Model triples) {
            graphs.computeIfAbsent(graphUri, k -> ModelFactory.createDefaultModel()).add(triples);
        }
        @Override public void clearGraph(TenantContext tenant, String graphUri) {
            graphs.remove(graphUri);
        }
    }

    /** Upsert-by-id version metadata store, scoped to a single tenant for the test. */
    static final class InMemoryGraphVersionPort implements GraphVersionPort {
        private final Map<UUID, GraphVersion> byId = new HashMap<>();
        private final TenantContext tenant;

        InMemoryGraphVersionPort(TenantContext tenant) {
            this.tenant = tenant;
        }

        @Override
        public GraphVersion save(TenantContext tenant, GraphVersion version) {
            byId.put(version.id(), version);
            return version;
        }

        @Override
        public Optional<GraphVersion> findById(TenantContext tenant, UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<GraphVersion> findCurrentActive(TenantContext tenant) {
            return byId.values().stream().filter(v -> "ACTIVE".equals(v.status())).findFirst();
        }

        @Override
        public Optional<GraphVersion> findLatest(TenantContext tenant) {
            return byId.values().stream().max((x, y) -> Integer.compare(x.versionNumber(), y.versionNumber()));
        }

        @Override
        public List<GraphVersion> findAll(TenantContext tenant) {
            List<GraphVersion> all = new ArrayList<>(byId.values());
            all.sort((x, y) -> Integer.compare(y.versionNumber(), x.versionNumber()));
            return all;
        }
    }
}

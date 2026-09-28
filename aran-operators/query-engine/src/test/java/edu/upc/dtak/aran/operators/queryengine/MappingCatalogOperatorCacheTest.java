package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKeyRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.ReferentialEdge;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * Phase 4B review Important 3 — {@link MappingCatalogOperator#getCatalog} caches per tenant keyed by
 * the active graph version, so a harvest that writes {@code declared_foreign_key} rows <em>without</em>
 * promoting the graph is invisible to the cache. In particular a refresh-cadence harvest that
 * <b>removes</b> a foreign key (the source dropped it) would leave the stale {@code DECLARED} edge
 * baked into the cached catalog, and join elimination would keep firing on a foreign key that no
 * longer exists.
 *
 * <p>{@link MappingCatalogOperator#invalidate} (the {@link
 * edu.upc.dtak.aran.core.domain.CatalogCacheInvalidator} the harvest-persistence path calls) closes
 * that window. This proves the whole loop against a fake harvest store: the edge is cached, a
 * removal is NOT seen while the version is unchanged (the staleness the fix targets), and the very
 * next {@code getCatalog} after {@code invalidate} no longer exposes it.
 */
class MappingCatalogOperatorCacheTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private static final UUID EMPLOYEES_DS = UUID.randomUUID();
    private static final UUID DEPARTMENTS_DS = UUID.randomUUID();
    private static final String EMP_SRC = "http://ex/source/Employees";
    private static final String DEPT_SRC = "http://ex/source/Departments";
    private static final String EMP_INT = "http://ex/integration/Employee";
    private static final String DEPT_INT = "http://ex/integration/Department";
    private static final String EMP_DEPT_ID_PROP = "http://ex/source/Employees/deptId";
    private static final String DEPT_ID_PROP = "http://ex/source/Departments/id";

    /** One integrated class fed by one source dataset, carrying one flat property with an nx:sourceRef. */
    private static void addClass(Model m, String intIri, String srcIri, UUID datasetId,
                                 String propIri, String sourceRef) {
        Resource intClass = m.createResource(intIri);
        intClass.addProperty(RDF.type, m.createResource(NS + "IntegratedResource"));
        intClass.addProperty(RDF.type, RDFS.Class);

        Resource srcClass = m.createResource(srcIri);
        m.add(srcClass, RDFS.subClassOf, intClass);
        srcClass.addProperty(m.createProperty(NS + "wrapperSql"), "SELECT * FROM ${SNAPSHOT}");
        srcClass.addProperty(m.createProperty(NS + "datasetId"), datasetId.toString());

        Resource prop = m.createResource(propIri);
        prop.addProperty(RDFS.domain, srcClass);
        prop.addProperty(RDFS.range, m.createResource(XSD_STRING));
        prop.addProperty(m.createProperty(NS + "sourceRef"), sourceRef);
    }

    private static Model schema() {
        Model m = ModelFactory.createDefaultModel();
        addClass(m, EMP_INT, EMP_SRC, EMPLOYEES_DS, EMP_DEPT_ID_PROP, "dept_id");
        addClass(m, DEPT_INT, DEPT_SRC, DEPARTMENTS_DS, DEPT_ID_PROP, "id");
        return m;
    }

    /** The Employees→Departments foreign key, physical column identity, dataset-id endpoints. */
    private static DeclaredForeignKey fk() {
        return new DeclaredForeignKey(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EMPLOYEES_DS, List.of("dept_id"), DEPARTMENTS_DS, List.of("id"));
    }

    /** A GraphStore that hands back a fixed schema and a fixed (never-changing) active version. */
    private static final class FixedGraphStore implements GraphStore {
        private final Model schema;
        private final GraphVersion version = new GraphVersion(
                UUID.randomUUID(), UUID.randomUUID(), 1, "ACTIVE", Instant.now(), null, null, null);

        FixedGraphStore(Model schema) {
            this.schema = schema;
        }

        @Override public Model query(TenantContext tenant, String sparql) {
            return schema;
        }

        @Override public @Nullable GraphVersion currentVersion(TenantContext tenant) {
            return version;
        }

        @Override public void provision(TenantContext tenant) {
            throw new UnsupportedOperationException();
        }

        @Override public GraphVersion replaceDraft(TenantContext tenant, Model fullGraph, AuditedActor actor) {
            throw new UnsupportedOperationException();
        }

        @Override public GraphVersion promote(TenantContext tenant, UUID draftVersionId, AuditedActor actor) {
            throw new UnsupportedOperationException();
        }

        @Override public Model readVersion(TenantContext tenant, UUID versionId) {
            throw new UnsupportedOperationException();
        }

        @Override public void addTriples(TenantContext tenant, String graphUri, Model triples) {
            throw new UnsupportedOperationException();
        }

        @Override public void clearGraph(TenantContext tenant, String graphUri) {
            throw new UnsupportedOperationException();
        }
    }

    /** A harvest store whose current answer can be mutated between catalog builds (the refresh tick). */
    private static final class MutableFkRepo implements DeclaredForeignKeyRepository {
        private List<DeclaredForeignKey> current = new ArrayList<>();

        @Override public List<DeclaredForeignKey> findAllByTenant(TenantContext tenant) {
            return List.copyOf(current);
        }

        @Override public void replaceForRepository(
                TenantContext tenant, UUID repositoryId, List<DeclaredForeignKey> foreignKeys) {
            current = new ArrayList<>(foreignKeys);
        }
    }

    private static boolean exposesEmployeesDeclaredEdge(MappingCatalog catalog) {
        return catalog.declaredEdges().stream().anyMatch(e ->
                e.tier() == Tier.DECLARED && e.leftClass().equals(EMP_INT) && e.rightClass().equals(DEPT_INT));
    }

    @Test
    @DisplayName("a harvest that drops an FK reaches the catalog only after invalidate — not while the version is unchanged")
    void droppedForeignKeyReachesTheCatalogOnlyAfterInvalidation() {
        TenantContext tenant = new TenantContext(UUID.randomUUID().toString());
        MutableFkRepo fks = new MutableFkRepo();
        fks.current = new ArrayList<>(List.of(fk()));
        MappingCatalogOperator operator =
                new MappingCatalogOperator(new FixedGraphStore(schema()), null, fks);

        // 1. First build resolves the harvested FK into a DECLARED edge.
        assertThat(exposesEmployeesDeclaredEdge(operator.getCatalog(tenant)))
                .as("the harvested FK must resolve to a DECLARED edge on first build").isTrue();

        // 2. A refresh-tick harvest removes the FK (source dropped it). The graph version is
        //    unchanged, so the cache still serves the stale catalog — the exact window I3 targets.
        fks.current = new ArrayList<>();
        assertThat(exposesEmployeesDeclaredEdge(operator.getCatalog(tenant)))
                .as("without invalidation the cached (stale) DECLARED edge is still served").isTrue();

        // 3. The harvest-persistence path invalidates the tenant's cache.
        operator.invalidate(tenant);

        // 4. The next build no longer exposes the dropped FK's edge.
        assertThat(exposesEmployeesDeclaredEdge(operator.getCatalog(tenant)))
                .as("after invalidate the dropped FK's DECLARED edge is gone").isFalse();
    }
}

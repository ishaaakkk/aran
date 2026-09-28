package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Computes a class/property-level diff between two schema {@link edu.upc.dtak.aran.core.domain.GraphVersion}s.
 *
 * <p>Each version's triples live in its own named graph {@code urn:aran:schema:version:<id>}
 * (see {@code GraphStore.promote} — the per-version graph survives promotion, and the
 * {@code /api/graph/schema/{versionId}} read path relies on the same URI scheme). The diff
 * loads both graphs and does pure set arithmetic on IRIs:
 *
 * <ul>
 *   <li><b>classes</b> — subjects typed {@code rdfs:Class} (the same typing
 *       {@code SourceGraphSerializer#build} keys on);</li>
 *   <li><b>properties of a class</b> — properties {@code P} with {@code P rdfs:domain C};</li>
 *   <li><b>wrapper</b> — the {@code nextiadi:wrapperSql} literal attached to the class.</li>
 * </ul>
 *
 * <p><b>Limits (v1):</b> pure IRI set arithmetic. No graph isomorphism and no blank-node
 * handling — two structurally identical schemas that differ only in blank-node identity are
 * not compared for equality here.
 */
@Service
@NullMarked
public class SchemaDiffService {

    /** Per-class wrapper SQL — mirrors {@code WrapperVocab.WRAPPER_SQL} (bootstrapping module). */
    private static final Property WRAPPER_SQL =
            ResourceFactory.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql");

    private final GraphStore graphStore;

    public SchemaDiffService(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    /** Diff of two persisted schema versions, by id, in the {@code a → b} direction. */
    public SchemaDiff diff(TenantContext tenant, UUID versionA, UUID versionB) {
        return diff(load(tenant, versionA), load(tenant, versionB));
    }

    /**
     * Pure diff of two already-loaded schema models, in the {@code a → b} direction:
     * "added" = present in {@code b} but not {@code a}; "removed" = present in {@code a}
     * but not {@code b}.
     */
    public SchemaDiff diff(Model a, Model b) {
        Map<String, ClassShape> shapesA = shapes(a);
        Map<String, ClassShape> shapesB = shapes(b);

        List<String> addedClasses = new ArrayList<>(minus(shapesB.keySet(), shapesA.keySet()));
        List<String> removedClasses = new ArrayList<>(minus(shapesA.keySet(), shapesB.keySet()));

        List<ChangedClass> changedClasses = new ArrayList<>();
        for (String classIri : shapesA.keySet()) {
            ClassShape sa = shapesA.get(classIri);
            ClassShape sb = shapesB.get(classIri);
            if (sb == null) {
                continue; // removed, already reported
            }
            List<String> addedProps = new ArrayList<>(minus(sb.properties(), sa.properties()));
            List<String> removedProps = new ArrayList<>(minus(sa.properties(), sb.properties()));
            boolean wrapperChanged = !Objects.equals(sa.wrapperSql(), sb.wrapperSql());
            if (!addedProps.isEmpty() || !removedProps.isEmpty() || wrapperChanged) {
                changedClasses.add(new ChangedClass(classIri, addedProps, removedProps, wrapperChanged));
            }
        }

        return new SchemaDiff(addedClasses, removedClasses, changedClasses);
    }

    /**
     * The class IRIs a persisted version declares, by the same rule the diff uses — so the promote
     * guard refuses exactly the classes the promote dialog lists as removed, never a near miss.
     * Takes the version id rather than a graph: aran-core's Jena surface is frozen (H-5).
     */
    public Set<String> classIris(TenantContext tenant, UUID versionId) {
        return shapes(load(tenant, versionId)).keySet();
    }

    private Model load(TenantContext tenant, UUID versionId) {
        String graphUri = "urn:aran:schema:version:" + versionId;
        String sparql = "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }";
        return graphStore.query(tenant, sparql);
    }

    /** Extract every {@code rdfs:Class} with its property set and wrapper SQL. */
    private static Map<String, ClassShape> shapes(Model model) {
        Map<String, Set<String>> propertiesByClass = new LinkedHashMap<>();

        // Classes: subjects typed rdfs:Class.
        StmtIterator classIt = model.listStatements(null, RDF.type, RDFS.Class);
        while (classIt.hasNext()) {
            Resource cls = classIt.next().getSubject();
            if (cls.isURIResource()) {
                propertiesByClass.computeIfAbsent(cls.getURI(), k -> new TreeSet<>());
            }
        }

        // Properties of each class: P rdfs:domain C.
        StmtIterator domainIt = model.listStatements(null, RDFS.domain, (RDFNode) null);
        while (domainIt.hasNext()) {
            Statement st = domainIt.next();
            if (!st.getSubject().isURIResource() || !st.getObject().isURIResource()) {
                continue;
            }
            String classIri = st.getObject().asResource().getURI();
            propertiesByClass.computeIfAbsent(classIri, k -> new TreeSet<>())
                    .add(st.getSubject().getURI());
        }

        Map<String, ClassShape> shapes = new LinkedHashMap<>();
        for (var entry : propertiesByClass.entrySet()) {
            String wrapper = wrapperSqlOf(model, entry.getKey());
            shapes.put(entry.getKey(), new ClassShape(entry.getValue(), wrapper));
        }
        return shapes;
    }

    private static @Nullable String wrapperSqlOf(Model model, String classIri) {
        Statement st = model.getProperty(model.getResource(classIri), WRAPPER_SQL);
        return (st != null && st.getObject().isLiteral()) ? st.getString() : null;
    }

    private static List<String> minus(Set<String> from, Set<String> remove) {
        List<String> out = new ArrayList<>();
        for (String s : from) {
            if (!remove.contains(s)) {
                out.add(s);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private record ClassShape(Set<String> properties, @Nullable String wrapperSql) {}

    /** The three lists a schema-version diff reports. */
    public record SchemaDiff(
            List<String> addedClasses,
            List<String> removedClasses,
            List<ChangedClass> changedClasses) {

        /** True when both versions describe the same classes, properties and wrappers. */
        public boolean isEmpty() {
            return addedClasses.isEmpty() && removedClasses.isEmpty() && changedClasses.isEmpty();
        }
    }

    /** A class present in both versions whose properties or wrapper changed. */
    public record ChangedClass(
            String classIri,
            List<String> addedProperties,
            List<String> removedProperties,
            boolean wrapperChanged) {}
}

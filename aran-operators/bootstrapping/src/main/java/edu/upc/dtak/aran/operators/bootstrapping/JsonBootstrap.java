package edu.upc.dtak.aran.operators.bootstrapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.util.Map;

/**
 * Bootstraps a JSON document into an RDFS-typed graph, with wrapper metadata
 * attached so the result is queryable end-to-end.
 *
 * <p>The schema layer follows Flores et al. (2024): each JSON object becomes an
 * {@code rdfs:Class}; each key becomes an {@code rdf:Property}; primitives become
 * datatype properties typed with the matching {@code xsd:*}; arrays become
 * {@code rdfs:ContainerMembershipProperty} typed by their first-element schema
 * (homogeneity assumption).
 *
 * <p>The wrapper layer ({@link WrapperVocab}) carries — for every class and
 * property generated — enough information for an RML mapping generator (paper
 * Algorithm 10) or a query rewriter to fetch data from the original source.
 * Specifically, each class gets its JSONPath {@code iterator}, and each property
 * gets a {@code sourceRef} relative to the iterator of its domain class.
 */
@NullMarked
final class JsonBootstrap {

    private final ObjectMapper mapper = new ObjectMapper();

    Model bootstrap(String datasetId, String datasetName, String snapshotId, byte[] bytes) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        JsonNode root = mapper.readTree(bytes);

        boolean rootIsArray = root.isArray();
        if (rootIsArray) {
            if (root.isEmpty()) return model;
            root = root.get(0);
        }
        if (!root.isObject()) return model; // bare primitives at root: no useful schema.

        String classIri = BootstrapEngine.sourceClassIri(datasetId, datasetName);
        Resource rootClass = model.createResource(classIri);
        model.add(rootClass, RDF.type, RDFS.Class);
        model.add(rootClass, RDFS.label, datasetName);

        String rootIterator = rootIsArray ? "$[*]" : "$";
        WrapperVocab.attachClassWrapper(model, rootClass, datasetId, snapshotId, "JSON", rootIterator,
                WrapperVocab.Shape.DOCUMENT);

        processObject(model, root, classIri, datasetId, datasetName, snapshotId, rootIterator);
        return model;
    }

    private void processObject(Model m, JsonNode obj, String parentClassIri,
                               String datasetId, String pathSoFar, String snapshotId,
                               String parentIterator) {
        Resource parentClass = m.createResource(parentClassIri);
        String primitiveArrayKey = null;   // S4-2: at most one per object, see below
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            String key = e.getKey();
            JsonNode value = e.getValue();

            String propIri = BootstrapEngine.sourcePropertyIri(datasetId, pathSoFar, key);
            Resource prop = m.createResource(propIri);
            m.add(prop, RDF.type, RDF.Property);
            m.add(prop, RDFS.label, key);
            m.add(prop, RDFS.domain, parentClass);

            if (value.isObject()) {
                // Nested object → new class. Its iterator extends the parent's.
                String childPath = pathSoFar + "." + key;
                String childIterator = parentIterator + "." + key;
                String childClassIri = BootstrapEngine.sourceClassIri(datasetId, childPath);
                Resource childClass = m.createResource(childClassIri);
                m.add(childClass, RDF.type, RDFS.Class);
                m.add(childClass, RDFS.label, key);
                m.add(prop, RDFS.range, childClass);
                // For the object-property itself, the sourceRef is the relative key.
                m.add(prop, WrapperVocab.SOURCE_REF, key);
                WrapperVocab.attachClassWrapper(m, childClass, datasetId, snapshotId, "JSON", childIterator,
                        WrapperVocab.Shape.DOCUMENT);
                processObject(m, value, childClassIri, datasetId, childPath, snapshotId, childIterator);
            } else if (value.isArray()) {
                m.add(prop, RDF.type, RDFS.ContainerMembershipProperty);
                if (value.isEmpty() || !value.get(0).isObject()) {
                    // S4-2. An array of primitives becomes an UNNEST lateral on the owning class's
                    // wrapper — one row per element, the documented 1NF choice. TWO of them on one
                    // object are two laterals over the same row, which DuckDB evaluates as a cross
                    // join: a record with 3 tags and 2 colors became 6 rows, and every scalar
                    // column and every COUNT/SUM over the class was multiplied, silently. Refused
                    // here, at bootstrap, until D1's child-class rule gives each array a class of
                    // its own — and not flattened to a LIST column, which D1 forbids because it
                    // would regress the single-array case that works today.
                    if (primitiveArrayKey != null) {
                        throw new IllegalStateException("object at '" + pathSoFar + "' has two"
                                + " array-of-primitive fields, '" + primitiveArrayKey + "' and '"
                                + key + "': unnesting both would multiply every row by both"
                                + " lengths (a cross join). Aran refuses this shape rather than"
                                + " return multiplied counts. Remove or restructure one of them;"
                                + " arrays of OBJECTS are fine (each becomes its own class).");
                    }
                    primitiveArrayKey = key;
                }
                if (value.isEmpty()) {
                    m.add(prop, RDFS.range, XSD.xstring);
                    m.add(prop, WrapperVocab.SOURCE_REF, key + "[*]");
                } else {
                    JsonNode first = value.get(0);
                    if (first.isObject()) {
                        String childPath = pathSoFar + "." + key;
                        String childIterator = parentIterator + "." + key + "[*]";
                        String childClassIri = BootstrapEngine.sourceClassIri(datasetId, childPath);
                        Resource childClass = m.createResource(childClassIri);
                        m.add(childClass, RDF.type, RDFS.Class);
                        m.add(childClass, RDFS.label, key);
                        m.add(prop, RDFS.range, childClass);
                        m.add(prop, WrapperVocab.SOURCE_REF, key + "[*]");
                        WrapperVocab.attachClassWrapper(m, childClass, datasetId, snapshotId, "JSON", childIterator,
                        WrapperVocab.Shape.DOCUMENT);
                        processObject(m, first, childClassIri, datasetId, childPath, snapshotId, childIterator);
                    } else {
                        // Array of primitives: no nested class, just a typed reference.
                        m.add(prop, RDFS.range, xsdTypeFor(first));
                        m.add(prop, WrapperVocab.SOURCE_REF, key + "[*]");
                    }
                }
            } else if (value.isNull()) {
                m.add(prop, RDFS.range, XSD.xstring);
                m.add(prop, WrapperVocab.SOURCE_REF, key);
            } else {
                m.add(prop, RDFS.range, xsdTypeFor(value));
                m.add(prop, WrapperVocab.SOURCE_REF, key);
            }
        }
    }

    private static Resource xsdTypeFor(JsonNode v) {
        if (v.isBoolean()) return XSD.xboolean;
        if (v.isIntegralNumber()) return XSD.integer;
        // Matches ParquetBootstrap/JdbcBootstrap: this mapper has no USE_BIG_DECIMAL_FOR_FLOATS
        // override, so every non-integral JSON number Jackson hands us is already a Java double —
        // xsd:double, not xsd:decimal, whose DECIMAL(38,9) view-body TRY_CAST would silently NULL
        // a value like 1e30 (|x| >= 1e29) or round one with more than 9 fractional digits.
        if (v.isFloatingPointNumber() || v.isNumber()) return XSD.xdouble;
        return XSD.xstring;
    }
}

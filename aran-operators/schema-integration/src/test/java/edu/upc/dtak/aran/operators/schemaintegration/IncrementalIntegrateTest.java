package edu.upc.dtak.aran.operators.schemaintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 5: the incremental-integrate operator flow and its acceptance test — the
 * equivalence oracle (incremental ≡ rebuild for an additive source sequence).
 */
@DisplayName("SchemaIntegrationOperator — incremental-integrate")
class IncrementalIntegrateTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MODEL = UUID.randomUUID();
    private static final UUID DS_A = UUID.randomUUID();
    private static final UUID DS_B = UUID.randomUUID();
    private static final UUID DS_C = UUID.randomUUID();

    private static final String A_NS = "http://src-a/";
    private static final String B_NS = "http://src-b/";
    private static final String C_NS = "http://src-c/";
    private static final String INTEGRATION_MODEL = IntegrationVocab.NS + "integrationModel";

    private final ObjectMapper om = new ObjectMapper();
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);

    private SchemaIntegrationOperator operatorOn(StatefulGraphStore store) {
        return new SchemaIntegrationOperator(store, om, datasetRepository);
    }

    // A source graph: one class with a datatype property.
    private static Model source(String ns, String cls, String prop) {
        Model m = ModelFactory.createDefaultModel();
        Resource c = m.createResource(ns + cls);
        m.add(c, RDF.type, RDFS.Class);
        m.add(c, RDFS.label, cls);
        Resource p = m.createResource(ns + prop);
        m.add(p, RDF.type, RDF.Property);
        m.add(p, RDFS.domain, c);
        m.add(p, RDFS.label, prop);
        return m;
    }

    @SafeVarargs
    private final String alignments(Map<String, String>... rows) {
        try {
            return om.writeValueAsString(rows);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> classAlign(String iriA, String iriB, String label, UUID mappingId) {
        return align(iriA, iriB, label, "class", mappingId);
    }

    private Map<String, String> align(String iriA, String iriB, String label,
                                      String type, UUID mappingId) {
        Map<String, String> row = new LinkedHashMap<>();
        if (mappingId != null) row.put("mappingId", mappingId.toString());
        row.put("iriA", iriA);
        row.put("iriB", iriB);
        row.put("label", label);
        row.put("type", type);
        return row;
    }

    /** A source graph: one class carrying several datatype properties. */
    private static Model sourceWithProps(String ns, String cls, String... props) {
        Model m = ModelFactory.createDefaultModel();
        Resource c = m.createResource(ns + cls);
        m.add(c, RDF.type, RDFS.Class);
        m.add(c, RDFS.label, cls);
        for (String prop : props) {
            Resource p = m.createResource(ns + prop);
            m.add(p, RDF.type, RDF.Property);
            m.add(p, RDFS.domain, c);
            m.add(p, RDFS.range, m.createResource("http://www.w3.org/2001/XMLSchema#string"));
            m.add(p, RDFS.label, prop);
        }
        return m;
    }

    /** Report the structural difference between two graphs, for a readable oracle failure. */
    private static String diff(Model expected, Model actual) {
        StringBuilder sb = new StringBuilder("\nonly in rebuild:\n");
        expected.difference(actual).listStatements()
                .forEachRemaining(s -> sb.append("  ").append(s).append('\n'));
        sb.append("only in incremental:\n");
        actual.difference(expected).listStatements()
                .forEachRemaining(s -> sb.append("  ").append(s).append('\n'));
        return sb.toString();
    }

    private OperatorResult compile(SchemaIntegrationOperator op, String datasetIdsJson, String alignmentsJson) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sourceDatasetIds", datasetIdsJson);
        params.put("alignments", alignmentsJson);
        params.put("modelId", MODEL.toString());
        return op.execute(new OperatorRequest(TENANT, USER, "compile", params));
    }

    private GraphVersion promoteDraft(StatefulGraphStore store, String draftVersionId) {
        return store.promote(new TenantContext(TENANT), UUID.fromString(draftVersionId),
                new AuditedActor(USER));
    }

    /** The active integrated graph with the model-marker triple removed, for structural comparison. */
    private static Model activeWithoutMarker(StatefulGraphStore store) {
        Model active = store.query(new TenantContext(TENANT),
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:schema> { ?s ?p ?o } }");
        Property marker = active.createProperty(INTEGRATION_MODEL);
        active.removeAll(null, marker, null);
        return active;
    }

    @Test
    @DisplayName("equivalence oracle: incremental (A+B baseline, then +C) ≡ full rebuild of A+B+C")
    void incremental_equals_rebuild() {
        // ── Full rebuild of A+B+C in one shot ──
        StatefulGraphStore rebuildStore = new StatefulGraphStore();
        rebuildStore.stageSource(DS_A, source(A_NS, "Person", "name"));
        rebuildStore.stageSource(DS_B, source(B_NS, "Employee", "fullName"));
        rebuildStore.stageSource(DS_C, source(C_NS, "Contractor", "displayName"));
        SchemaIntegrationOperator rebuildOp = operatorOn(rebuildStore);

        OperatorResult rebuild = compile(rebuildOp,
                json(DS_A, DS_B, DS_C),
                alignments(
                        classAlign(A_NS + "Person", B_NS + "Employee", "Person", null),
                        classAlign(A_NS + "Person", C_NS + "Contractor", "Person", null)));
        assertThat(rebuild.success()).isTrue();
        promoteDraft(rebuildStore, rebuild.data().get("draftVersionId"));
        Model gRebuild = activeWithoutMarker(rebuildStore);

        // ── Incremental: build A+B baseline, promote, then add C without the old sources ──
        StatefulGraphStore incrStore = new StatefulGraphStore();
        incrStore.stageSource(DS_A, source(A_NS, "Person", "name"));
        incrStore.stageSource(DS_B, source(B_NS, "Employee", "fullName"));
        incrStore.stageSource(DS_C, source(C_NS, "Contractor", "displayName"));
        SchemaIntegrationOperator incrOp = operatorOn(incrStore);

        OperatorResult baseline = compile(incrOp, json(DS_A, DS_B),
                alignments(classAlign(A_NS + "Person", B_NS + "Employee", "Person", null)));
        assertThat(baseline.success()).isTrue();
        GraphVersion base = promoteDraft(incrStore, baseline.data().get("draftVersionId"));

        // Prove the incremental step never reads the old sources: delete them.
        var t = new TenantContext(TENANT);
        incrStore.clearGraph(t, "urn:aran:source:" + DS_A);
        incrStore.clearGraph(t, "urn:aran:source:" + DS_B);

        OperatorResult incr = incrOp.execute(incrementalRequest(base.id(), DS_C,
                alignments(classAlign(A_NS + "Person", C_NS + "Contractor", "Person", null)), "[]"));
        assertThat(incr.success()).as("incremental add must succeed with old sources gone: " + incr.errorMessage())
                .isTrue();
        promoteDraft(incrStore, incr.data().get("draftVersionId"));
        Model gIncr = activeWithoutMarker(incrStore);

        assertThat(gRebuild.isIsomorphicWith(gIncr))
                .as("incremental integrated graph must be isomorphic to the full rebuild").isTrue();
    }

    @Test
    @DisplayName("equivalence oracle: JOIN alignment on the incrementally-added source ≡ full rebuild")
    void incremental_equals_rebuild_with_join() {
        // A carries the join key (deptId); C is the new source joined onto it.
        Model srcA = sourceWithProps(A_NS, "Person", "name", "deptId");
        Model srcB = sourceWithProps(B_NS, "Employee", "fullName");
        Model srcC = sourceWithProps(C_NS, "Dept", "deptCode");

        Map<String, String> cls1 = classAlign(A_NS + "Person", B_NS + "Employee", "Person", null);
        // label format is "joinLabel|relationshipName|direction" (see SchemaIntegrator).
        Map<String, String> join = align(A_NS + "deptId", C_NS + "deptCode",
                "deptLink|worksIn|forward", "join", null);

        // ── Rebuild A+B+C in one shot ──
        StatefulGraphStore rs = new StatefulGraphStore();
        rs.stageSource(DS_A, srcA);
        rs.stageSource(DS_B, srcB);
        rs.stageSource(DS_C, srcC);
        OperatorResult rebuild = compile(operatorOn(rs), json(DS_A, DS_B, DS_C), alignments(cls1, join));
        assertThat(rebuild.success()).as(String.valueOf(rebuild.errorMessage())).isTrue();
        promoteDraft(rs, rebuild.data().get("draftVersionId"));
        Model gRebuild = activeWithoutMarker(rs);

        // ── Incremental: A+B baseline, then +C carrying the join ──
        StatefulGraphStore is = new StatefulGraphStore();
        is.stageSource(DS_A, srcA);
        is.stageSource(DS_B, srcB);
        is.stageSource(DS_C, srcC);
        SchemaIntegrationOperator iop = operatorOn(is);
        OperatorResult baseline = compile(iop, json(DS_A, DS_B), alignments(cls1));
        assertThat(baseline.success()).as(String.valueOf(baseline.errorMessage())).isTrue();
        GraphVersion base = promoteDraft(is, baseline.data().get("draftVersionId"));

        var t = new TenantContext(TENANT);
        is.clearGraph(t, "urn:aran:source:" + DS_A);
        is.clearGraph(t, "urn:aran:source:" + DS_B);

        OperatorResult incr = iop.execute(incrementalRequest(base.id(), DS_C, alignments(join), "[]"));
        assertThat(incr.success()).as("incremental join add failed: " + incr.errorMessage()).isTrue();
        promoteDraft(is, incr.data().get("draftVersionId"));
        Model gIncr = activeWithoutMarker(is);

        assertThat(gRebuild.isIsomorphicWith(gIncr))
                .as("incremental JOIN must equal the rebuild" + diff(gRebuild, gIncr)).isTrue();
    }

    @Test
    @DisplayName("equivalence oracle: a mapping deferred at baseline retries on the incremental step ≡ rebuild")
    void incremental_equals_rebuild_with_deferred_retry() {
        Model srcA = sourceWithProps(A_NS, "Person", "name");
        Model srcB = sourceWithProps(B_NS, "Employee", "fullName");
        Model srcC = sourceWithProps(C_NS, "Contractor", "displayName");

        Map<String, String> cls1 = classAlign(A_NS + "Person", B_NS + "Employee", "Person", null);
        Map<String, String> cls2 = classAlign(A_NS + "Person", C_NS + "Contractor", "Person", null);
        // Targets C, which does not exist at baseline time — must defer, then retry.
        Map<String, String> dtAC = align(A_NS + "name", C_NS + "displayName", "name", "datatype", null);

        // ── Rebuild: everything applies in one pass ──
        StatefulGraphStore rs = new StatefulGraphStore();
        rs.stageSource(DS_A, srcA);
        rs.stageSource(DS_B, srcB);
        rs.stageSource(DS_C, srcC);
        OperatorResult rebuild = compile(operatorOn(rs), json(DS_A, DS_B, DS_C),
                alignments(cls1, dtAC, cls2));
        assertThat(rebuild.success()).as(String.valueOf(rebuild.errorMessage())).isTrue();
        promoteDraft(rs, rebuild.data().get("draftVersionId"));
        Model gRebuild = activeWithoutMarker(rs);

        // ── Incremental: dtAC is handed in at baseline and must come back deferred ──
        StatefulGraphStore is = new StatefulGraphStore();
        is.stageSource(DS_A, srcA);
        is.stageSource(DS_B, srcB);
        is.stageSource(DS_C, srcC);
        SchemaIntegrationOperator iop = operatorOn(is);
        OperatorResult baseline = compile(iop, json(DS_A, DS_B), alignments(cls1, dtAC));
        assertThat(baseline.success()).as(String.valueOf(baseline.errorMessage())).isTrue();
        assertThat(baseline.data().get("unusedAlignments"))
                .as("the alignment touching the absent source C must be deferred, not applied")
                .contains(C_NS + "displayName");
        GraphVersion base = promoteDraft(is, baseline.data().get("draftVersionId"));

        var t = new TenantContext(TENANT);
        is.clearGraph(t, "urn:aran:source:" + DS_A);
        is.clearGraph(t, "urn:aran:source:" + DS_B);

        // Feed the deferred mapping back alongside the new class alignment.
        OperatorResult incr = iop.execute(incrementalRequest(base.id(), DS_C,
                alignments(cls2), alignments(dtAC)));
        assertThat(incr.success()).as("incremental retry failed: " + incr.errorMessage()).isTrue();
        assertThat(incr.data().get("unusedAlignments"))
                .as("the retried mapping must now apply, leaving nothing deferred").isEqualTo("[]");
        promoteDraft(is, incr.data().get("draftVersionId"));
        Model gIncr = activeWithoutMarker(is);

        assertThat(gRebuild.isIsomorphicWith(gIncr))
                .as("deferred-then-retried mapping must land where the rebuild put it"
                        + diff(gRebuild, gIncr)).isTrue();
    }

    @Test
    @DisplayName("rejects a stale base (baseVersionId != current active) with STALE_INCREMENTAL_BASE")
    void rejects_stale_base() {
        StatefulGraphStore store = new StatefulGraphStore();
        store.stageSource(DS_A, source(A_NS, "Person", "name"));
        store.stageSource(DS_B, source(B_NS, "Employee", "fullName"));
        store.stageSource(DS_C, source(C_NS, "Contractor", "displayName"));
        SchemaIntegrationOperator op = operatorOn(store);

        OperatorResult baseline = compile(op, json(DS_A, DS_B),
                alignments(classAlign(A_NS + "Person", B_NS + "Employee", "Person", null)));
        promoteDraft(store, baseline.data().get("draftVersionId"));

        // A random base id that is not the current active version.
        OperatorResult incr = op.execute(incrementalRequest(UUID.randomUUID(), DS_C,
                alignments(classAlign(A_NS + "Person", C_NS + "Contractor", "Person", null)), "[]"));
        assertThat(incr.success()).isFalse();
        assertThat(incr.errorCode()).isEqualTo("STALE_INCREMENTAL_BASE");
    }

    @Test
    @DisplayName("rejects a base graph owned by a different model with INCREMENTAL_BASE_MODEL_MISMATCH")
    void rejects_foreign_model_base() {
        StatefulGraphStore store = new StatefulGraphStore();
        store.stageSource(DS_A, source(A_NS, "Person", "name"));
        store.stageSource(DS_B, source(B_NS, "Employee", "fullName"));
        store.stageSource(DS_C, source(C_NS, "Contractor", "displayName"));
        SchemaIntegrationOperator op = operatorOn(store);

        OperatorResult baseline = compile(op, json(DS_A, DS_B),
                alignments(classAlign(A_NS + "Person", B_NS + "Employee", "Person", null)));
        GraphVersion base = promoteDraft(store, baseline.data().get("draftVersionId"));

        // Request incremental with a DIFFERENT modelId than the marker in the base graph.
        Map<String, String> params = incrementalParams(base.id(), DS_C,
                alignments(classAlign(A_NS + "Person", C_NS + "Contractor", "Person", null)), "[]");
        params.put("modelId", UUID.randomUUID().toString());
        OperatorResult incr = op.execute(new OperatorRequest(TENANT, USER, "incremental-integrate", params));
        assertThat(incr.success()).isFalse();
        assertThat(incr.errorCode()).isEqualTo("INCREMENTAL_BASE_MODEL_MISMATCH");
    }

    @Test
    @DisplayName("rejects re-adding a source already represented in the base graph")
    void rejects_source_already_integrated() {
        StatefulGraphStore store = new StatefulGraphStore();
        store.stageSource(DS_A, source(A_NS, "Person", "name"));
        store.stageSource(DS_B, source(B_NS, "Employee", "fullName"));
        SchemaIntegrationOperator op = operatorOn(store);

        OperatorResult baseline = compile(op, json(DS_A, DS_B),
                alignments(classAlign(A_NS + "Person", B_NS + "Employee", "Person", null)));
        GraphVersion base = promoteDraft(store, baseline.data().get("draftVersionId"));

        // DS_A is already integrated in the base graph.
        OperatorResult incr = op.execute(incrementalRequest(base.id(), DS_A,
                alignments(classAlign(A_NS + "Person", B_NS + "Employee", "Person", null)), "[]"));
        assertThat(incr.success()).isFalse();
        assertThat(incr.errorCode()).isEqualTo("SOURCE_ALREADY_INTEGRATED");
    }

    // ── request helpers ──────────────────────────────────────────────────────────

    private OperatorRequest incrementalRequest(UUID baseVersionId, UUID newDatasetId,
                                               String newMappings, String deferredMappings) {
        return new OperatorRequest(TENANT, USER, "incremental-integrate",
                incrementalParams(baseVersionId, newDatasetId, newMappings, deferredMappings));
    }

    private Map<String, String> incrementalParams(UUID baseVersionId, UUID newDatasetId,
                                                  String newMappings, String deferredMappings) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("modelId", MODEL.toString());
        params.put("baseVersionId", baseVersionId.toString());
        params.put("newDatasetId", newDatasetId.toString());
        params.put("newMappings", newMappings);
        params.put("deferredMappings", deferredMappings);
        return params;
    }

    private static String json(UUID... ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ids[i]).append('"');
        }
        return sb.append(']').toString();
    }
}

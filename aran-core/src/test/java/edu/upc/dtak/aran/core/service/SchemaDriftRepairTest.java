package edu.upc.dtak.aran.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.MappingKinds;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.drift.SchemaFingerprintRepository;
import edu.upc.dtak.aran.core.domain.drift.StoredSchemaFingerprint;
import edu.upc.dtak.aran.core.fake.InMemoryIntegrationModelRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Repointing mappings after a confirmed rename.
 *
 * <p>Runs against the in-memory repository fake rather than mocks, because the repair's whole job
 * is a sequence of writes — add, delete, flag — and a mock would only prove the calls were made,
 * not that the mappings came out right.
 */
class SchemaDriftRepairTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final String CLS = "http://ex.org/source/ds/papers";

    private TenantContext tenant;
    private InMemoryIntegrationModelRepository integrationModels;
    private SchemaDriftService service;
    private UUID modelId;

    /** The drift service needs a fingerprint store; repair never touches it. */
    private static final SchemaFingerprintRepository NO_FINGERPRINTS = new SchemaFingerprintRepository() {
        @Override
        public Optional<StoredSchemaFingerprint> findLatest(TenantContext t, UUID d,
                                                            StoredSchemaFingerprint.CaptureMethod v) {
            return Optional.empty();
        }

        @Override
        public StoredSchemaFingerprint save(TenantContext t, StoredSchemaFingerprint f) {
            return f;
        }

        @Override
        public List<StoredSchemaFingerprint> history(TenantContext t, UUID d, int limit) {
            return List.of();
        }
    };

    @BeforeEach
    void setUp() {
        tenant = new TenantContext(TENANT.toString());
        integrationModels = new InMemoryIntegrationModelRepository();
        service = new SchemaDriftService(NO_FINGERPRINTS, integrationModels, new ObjectMapper());
        // A real model row: the repair flags it for rebuild and re-queues the mapping's
        // application, both of which look the model up.
        modelId = integrationModels.getOrCreate(tenant, PROJECT).id();
    }

    private ModelMapping given(String kind, String sourceIri, String targetIri, String status) {
        return integrationModels.addMapping(tenant, new ModelMapping(
                UUID.randomUUID(), TENANT, modelId, kind, sourceIri, targetIri, "label",
                status, null, null, null, Instant.now()));
    }

    private List<ModelMapping> current() {
        return integrationModels.findMappingsUnderIri(tenant, "http://ex.org/");
    }

    @Test
    @DisplayName("a renamed column repoints the mapping that referenced it")
    void repointsColumnRename() {
        given(MappingKinds.DATATYPE, CLS + "/venue", "http://global/venue", ModelMapping.ACCEPTED);

        SchemaDriftService.RepairResult result = service.repairRenames(tenant, List.of(
                new SchemaDriftService.IriRename(CLS + "/venue", CLS + "/conference")));

        assertThat(result.rewritten()).isEqualTo(1);
        assertThat(current()).singleElement().satisfies(m -> {
            assertThat(m.sourceIri()).isEqualTo(CLS + "/conference");
            assertThat(m.targetIri()).isEqualTo("http://global/venue");
            assertThat(m.status()).isEqualTo(ModelMapping.ACCEPTED);
            assertThat(m.kind()).isEqualTo(MappingKinds.DATATYPE);
        });
    }

    @Test
    @DisplayName("a renamed DATASET repoints every property beneath the class in one pass")
    void repointsClassIriRename() {
        // BootstrapEngine interpolates the dataset name into every IRI, so this is the shape a
        // dataset rename actually produces: one moved prefix, many broken mappings.
        given(MappingKinds.CLASS, CLS, "http://global/Paper", ModelMapping.ACCEPTED);
        given(MappingKinds.DATATYPE, CLS + "/venue", "http://global/venue", ModelMapping.ACCEPTED);
        given(MappingKinds.DATATYPE, CLS + "/year", "http://global/year", ModelMapping.ACCEPTED);

        String renamed = "http://ex.org/source/ds/publications";
        int rewritten = service.repairRenames(tenant,
                List.of(new SchemaDriftService.IriRename(CLS, renamed))).rewritten();

        assertThat(rewritten).isEqualTo(3);
        assertThat(current()).extracting(ModelMapping::sourceIri)
                .containsExactlyInAnyOrder(renamed, renamed + "/venue", renamed + "/year");
    }

    @Test
    @DisplayName("a sibling class sharing a stem is left alone")
    void doesNotSpillOntoSiblings() {
        // `…/papers` must not drag `…/papers_archive` along with it. The trailing slash in the
        // prefix rule is the whole guard.
        given(MappingKinds.DATATYPE, CLS + "_archive/venue", "http://global/venue",
                ModelMapping.ACCEPTED);

        int rewritten = service.repairRenames(tenant,
                List.of(new SchemaDriftService.IriRename(CLS, CLS + "_v2"))).rewritten();

        assertThat(rewritten).isZero();
        assertThat(current()).singleElement()
                .satisfies(m -> assertThat(m.sourceIri()).isEqualTo(CLS + "_archive/venue"));
    }

    @Test
    @DisplayName("a mapping whose TARGET side moved is repaired too")
    void repairsTargetSide() {
        // Alignment direction is the user's choice, so the source graph's IRI can land on either
        // side. Only checking sourceIri would silently leave half the breakage in place.
        given(MappingKinds.DATATYPE, "http://global/venue", CLS + "/venue", ModelMapping.ACCEPTED);

        service.repairRenames(tenant,
                List.of(new SchemaDriftService.IriRename(CLS + "/venue", CLS + "/conference")));

        assertThat(current()).singleElement()
                .satisfies(m -> assertThat(m.targetIri()).isEqualTo(CLS + "/conference"));
    }

    @Test
    @DisplayName("an undecided mapping keeps its status through the repair")
    void preservesStatus() {
        given(MappingKinds.DATATYPE, CLS + "/venue", "http://global/venue", ModelMapping.SUGGESTED);

        service.repairRenames(tenant,
                List.of(new SchemaDriftService.IriRename(CLS + "/venue", CLS + "/conference")));

        assertThat(current()).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(ModelMapping.SUGGESTED));
    }

    @Test
    @DisplayName("the repair flags the model for rebuild")
    void flagsRebuild() {
        // The integrated graph still holds material derived from the old column name, so the
        // mapping being correct again is not the same as the schema being correct again.
        given(MappingKinds.DATATYPE, CLS + "/venue", "http://global/venue", ModelMapping.ACCEPTED);

        SchemaDriftService.RepairResult result = service.repairRenames(tenant,
                List.of(new SchemaDriftService.IriRename(CLS + "/venue", CLS + "/conference")));

        assertThat(result.models()).containsExactly(modelId);
    }

    @Test
    @DisplayName("repairing nothing is a no-op, not an error")
    void emptyRepair() {
        assertThat(service.repairRenames(tenant, List.of()).rewritten()).isZero();
    }

    @Test
    @DisplayName("the old mapping is gone, not left behind as a duplicate")
    void removesTheStaleMapping() {
        given(MappingKinds.DATATYPE, CLS + "/venue", "http://global/venue", ModelMapping.ACCEPTED);

        service.repairRenames(tenant,
                List.of(new SchemaDriftService.IriRename(CLS + "/venue", CLS + "/conference")));

        assertThat(current()).hasSize(1);
    }
}

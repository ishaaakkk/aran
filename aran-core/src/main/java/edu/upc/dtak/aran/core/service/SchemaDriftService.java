package edu.upc.dtak.aran.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.ModelMapping;
import edu.upc.dtak.aran.core.domain.drift.DriftImpact;
import edu.upc.dtak.aran.core.domain.drift.DriftImpactAnalyzer;
import edu.upc.dtak.aran.core.domain.drift.DriftReport;
import edu.upc.dtak.aran.core.domain.drift.SchemaChange;
import edu.upc.dtak.aran.core.domain.drift.SchemaDiff;
import edu.upc.dtak.aran.core.domain.drift.SchemaFingerprint;
import edu.upc.dtak.aran.core.domain.drift.SchemaFingerprintRepository;
import edu.upc.dtak.aran.core.domain.drift.StoredSchemaFingerprint;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compares a candidate source schema against the dataset's stored baseline and says what it breaks.
 *
 * <p>The orchestration around the pure pieces: it loads the baseline, runs {@link SchemaDiff},
 * fetches the mappings that could depend on the affected IRIs, runs {@link DriftImpactAnalyzer},
 * and — separately, only when a caller decides to go ahead — records the new baseline.
 *
 * <p><b>Checking and recording are deliberately two calls.</b> {@link #check} is pure observation
 * and safe to run at any time; {@link #record} advances the baseline and must only happen once the
 * candidate has actually been applied. Folding them together would mean a rejected breaking change
 * still moved the baseline, so the next check would compare against a shape that was never live
 * and would report no drift at all.
 */
@Service
@NullMarked
public class SchemaDriftService {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftService.class);

    /** How many captures the history endpoint returns. Enough to see a pattern, not a data dump. */
    private static final int HISTORY_LIMIT = 20;

    private final SchemaFingerprintRepository fingerprints;
    private final IntegrationModelRepository integrationModels;
    private final ObjectMapper objectMapper;

    public SchemaDriftService(SchemaFingerprintRepository fingerprints,
                              IntegrationModelRepository integrationModels,
                              ObjectMapper objectMapper) {
        this.fingerprints = fingerprints;
        this.integrationModels = integrationModels;
        this.objectMapper = objectMapper;
    }

    /**
     * What would change if {@code candidate} replaced the dataset's current source schema.
     *
     * <p>Observation only — nothing is written, so this is safe to call before deciding anything.
     * A dataset with no stored baseline yields {@link DriftReport#baseline}: the first sighting of
     * a source cannot have drifted from anything.
     */
    public DriftReport check(TenantContext tenant, UUID datasetId, SchemaFingerprint candidate,
                             StoredSchemaFingerprint.CaptureMethod via) {
        Optional<StoredSchemaFingerprint> stored = fingerprints.findLatest(tenant, datasetId, via);
        if (stored.isEmpty()) {
            return DriftReport.baseline(datasetId, candidate.hash());
        }

        String baselineHash = stored.get().hash();
        if (baselineHash.equals(candidate.hash())) {
            // The overwhelmingly common case: nothing moved. One string comparison, no diff, no
            // mapping query.
            return new DriftReport(datasetId, baselineHash, candidate.hash(), List.of());
        }

        SchemaFingerprint baseline = deserialize(stored.get());
        if (baseline == null) {
            // A fingerprint we cannot read is not evidence of drift, and treating it as such would
            // block a re-bootstrap over a storage problem. Re-baseline instead and say so.
            log.warn("Unreadable stored fingerprint {} for dataset {} — treating this check as a baseline",
                    stored.get().id(), datasetId);
            return DriftReport.baseline(datasetId, candidate.hash());
        }

        List<SchemaChange> changes = SchemaDiff.between(baseline, candidate);
        List<ModelMapping> dependents = dependentsOf(tenant, baseline, candidate);
        List<DriftImpact> impacts = DriftImpactAnalyzer.analyse(changes, dependents);
        return new DriftReport(datasetId, baselineHash, candidate.hash(), impacts);
    }

    /**
     * Record {@code candidate} as the dataset's new baseline.
     *
     * <p>Call this only after the candidate has actually become the live source graph — see the
     * class javadoc for why this is not folded into {@link #check}.
     */
    public StoredSchemaFingerprint record(TenantContext tenant, UUID datasetId,
                                          SchemaFingerprint candidate, @Nullable String snapshotId,
                                          StoredSchemaFingerprint.CaptureMethod via) {
        return fingerprints.save(tenant, new StoredSchemaFingerprint(
                UUID.randomUUID(),
                UUID.fromString(tenant.value()),
                datasetId,
                candidate.hash(),
                serialize(candidate),
                snapshotId,
                via,
                Instant.now()));
    }

    /** The dataset's recent captures, newest first. */
    public List<StoredSchemaFingerprint> history(TenantContext tenant, UUID datasetId) {
        return fingerprints.history(tenant, datasetId, HISTORY_LIMIT);
    }

    /** An IRI that moved, and where it moved to. */
    public record IriRename(String fromIri, String toIri) {}

    /** @param rewritten how many mappings were repointed; @param models the models they belonged to */
    public record RepairResult(int rewritten, List<UUID> models) {
        public RepairResult {
            models = List.copyOf(models);
        }
    }

    /**
     * Repoint every mapping that referenced a renamed IRI at its replacement.
     *
     * <p>Handles both shapes of rename with one rule. A column rename moves a single property IRI,
     * so the match is exact. A dataset rename re-mints the class IRI and every property beneath it
     * ({@code <classIri>/<column>}), so the match is on the {@code fromIri + "/"} prefix and the
     * remainder is carried over. The trailing slash matters: without it {@code …/papers} would
     * also rewrite {@code …/papers_archive}.
     *
     * <p><b>Replaces rather than edits.</b> {@code ModelMappingEntity}'s IRI columns are
     * {@code updatable = false} — a mapping is a statement about two specific resources, not a
     * mutable pointer — so the repair adds the corrected mapping and deletes the stale one,
     * carrying over kind, label and status. The model is flagged {@code rebuildRequired} because
     * the integrated graph still holds material derived from the old column; that is the same
     * thing {@code IntegrationModelService.deleteMapping} does for an applied mapping, and for
     * the same reason.
     *
     * <p>Only ever called with renames a human has confirmed. Rename detection is a proposal —
     * see {@code SchemaDiff.matchRenames} — and repointing a mapping at the wrong column is worse
     * than the dangling reference it would be fixing.
     */
    public RepairResult repairRenames(TenantContext tenant, List<IriRename> renames) {
        int rewritten = 0;
        Set<UUID> touchedModels = new LinkedHashSet<>();

        for (IriRename rename : renames) {
            for (ModelMapping mapping : integrationModels.findMappingsUnderIri(tenant, rename.fromIri())) {
                String newSource = rewrite(mapping.sourceIri(), rename);
                String newTarget = rewrite(mapping.targetIri(), rename);
                if (newSource.equals(mapping.sourceIri()) && newTarget.equals(mapping.targetIri())) {
                    // Matched the over-inclusive prefix query but not the precise rule.
                    continue;
                }

                ModelMapping repaired = integrationModels.addMapping(tenant, new ModelMapping(
                        UUID.randomUUID(), mapping.tenantId(), mapping.modelId(), mapping.kind(),
                        newSource, newTarget, mapping.label(), mapping.status(),
                        mapping.evidenceJson(), mapping.author(), mapping.comment(), Instant.now()));
                if (ModelMapping.ACCEPTED.equals(repaired.status())) {
                    integrationModels.upsertMappingApplicationPending(
                            tenant, repaired.modelId(), repaired.id());
                }
                integrationModels.deleteMapping(tenant, mapping.id());
                integrationModels.setRebuildRequired(tenant, mapping.modelId(), true);

                touchedModels.add(mapping.modelId());
                rewritten++;
                log.info("Repaired mapping {} for dataset schema rename {} → {}",
                        mapping.id(), rename.fromIri(), rename.toIri());
            }
        }
        return new RepairResult(rewritten, List.copyOf(touchedModels));
    }

    private static String rewrite(String iri, IriRename rename) {
        if (iri.equals(rename.fromIri())) {
            return rename.toIri();
        }
        String prefix = rename.fromIri() + "/";
        return iri.startsWith(prefix) ? rename.toIri() + "/" + iri.substring(prefix.length()) : iri;
    }

    /**
     * Mappings that could reference anything in either side of the comparison.
     *
     * <p>Queried by class-IRI prefix because a property IRI is minted beneath its class
     * ({@code <classIri>/<column>}). Both sides are queried: the baseline's IRIs cover everything
     * that exists today, and the candidate's cover the case where a mapping was already written
     * against a shape the source is only now growing into.
     */
    private List<ModelMapping> dependentsOf(TenantContext tenant, SchemaFingerprint baseline,
                                            SchemaFingerprint candidate) {
        Set<String> prefixes = new LinkedHashSet<>();
        baseline.classes().forEach(c -> prefixes.add(c.iri()));
        candidate.classes().forEach(c -> prefixes.add(c.iri()));

        List<ModelMapping> out = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (String prefix : prefixes) {
            for (ModelMapping mapping : integrationModels.findMappingsUnderIri(tenant, prefix)) {
                // Prefixes overlap when classes nest (JSON), so the same mapping can come back
                // from several queries.
                if (seen.add(mapping.id())) {
                    out.add(mapping);
                }
            }
        }
        return out;
    }

    private String serialize(SchemaFingerprint fingerprint) {
        try {
            return objectMapper.writeValueAsString(fingerprint);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize schema fingerprint", e);
        }
    }

    private @Nullable SchemaFingerprint deserialize(StoredSchemaFingerprint stored) {
        try {
            return objectMapper.readValue(stored.fingerprintJson(), SchemaFingerprint.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize stored fingerprint {}", stored.id(), e);
            return null;
        }
    }
}

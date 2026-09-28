package edu.upc.dtak.aran.operators.joindiscovery;

import edu.upc.dtak.aran.core.util.Names;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.Operator;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.operator.ParameterSpec;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Join-discovery operator component.
 * Handles join and relationship discovery logic by calling the python FREYJA service.
 */
@Component
@NullMarked
public final class JoinDiscoveryOperator implements Operator {

    private static final Logger log = LoggerFactory.getLogger(JoinDiscoveryOperator.class);

    private final String freyjaUrl;
    private final String freyjaSecret;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final DatasetRepository datasetRepository;

    public JoinDiscoveryOperator(
            // Single property for the one FREYJA sidecar (profiling uses the
            // same key). Dev default lives in application.yml.
            @Value("${aran.freyja.url}") String freyjaUrl,
            @Value("${aran.freyja.secret:}") String freyjaSecret,
            ObjectMapper objectMapper,
            DatasetRepository datasetRepository) {
        this.freyjaUrl = freyjaUrl.replaceAll("/+$", "");
        this.freyjaSecret = freyjaSecret;
        this.objectMapper = objectMapper;
        this.datasetRepository = datasetRepository;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getOperatorId() {
        return "join-discovery";
    }

    @Override
    public String getDisplayName() {
        return "Join Discovery";
    }

    @Override
    public OperatorManifest manifest() {
        return new OperatorManifest(getOperatorId(), getDisplayName(), List.of(
                new OperationSpec("ping", "Health check", false, List.of()),
                new OperationSpec("discover", "Discover join candidates between two datasets", false, List.of(
                        ParameterSpec.required("datasetIdA", "UUID of dataset A"),
                        ParameterSpec.required("datasetIdB", "UUID of dataset B"),
                        ParameterSpec.optional("threshold", "Score threshold", "0.0"),
                        ParameterSpec.optional("method", "Ranking method", "exact"),
                        ParameterSpec.optional("strictness", "Strictness parameter", "0.25")
                ))
        ));
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        log.debug("Join Discovery handling operation '{}' for tenant '{}'",
                request.operation(), request.tenantId());

        return switch (request.operation()) {
            case "ping" -> OperatorResult.success(Map.of(
                    "status", "ok",
                    "operator", "join-discovery",
                    "message", "Join Discovery service operational"
            ));
            case "discover" -> discover(request);
            default -> OperatorResult.failure(
                    "UNSUPPORTED_OPERATION",
                    "Join Discovery does not support operation '" + request.operation() + "'"
            );
        };
    }

    private OperatorResult discover(OperatorRequest request) {
        String datasetIdAParam = request.parameters().get("datasetIdA");
        String datasetIdBParam = request.parameters().get("datasetIdB");
        if (datasetIdAParam == null || datasetIdAParam.isBlank() ||
                datasetIdBParam == null || datasetIdBParam.isBlank()) {
            return OperatorResult.failure("MISSING_PARAMETER", "datasetIdA and datasetIdB are required");
        }

        double threshold = 0.0;
        String thresholdParam = request.parameters().get("threshold");
        if (thresholdParam != null && !thresholdParam.isBlank()) {
            try {
                threshold = Double.parseDouble(thresholdParam);
            } catch (NumberFormatException e) {
                log.warn("Invalid threshold parameter, defaulting to 0.0: {}", e.getMessage());
            }
        }

        String method = request.parameters().getOrDefault("method", "exact");
        double strictness = 0.25;
        String strictnessParam = request.parameters().get("strictness");
        if (strictnessParam != null && !strictnessParam.isBlank()) {
            try {
                strictness = Double.parseDouble(strictnessParam);
            } catch (NumberFormatException e) {
                log.warn("Invalid strictness parameter, defaulting to 0.25: {}", e.getMessage());
            }
        }

        UUID datasetIdA;
        UUID datasetIdB;
        try {
            datasetIdA = UUID.fromString(datasetIdAParam);
            datasetIdB = UUID.fromString(datasetIdBParam);
        } catch (IllegalArgumentException e) {
            return OperatorResult.failure("INVALID_PARAMETER", "datasetIdA and datasetIdB must be valid UUIDs");
        }

        TenantContext tenant = new TenantContext(request.tenantId());
        Optional<Dataset> dsOptA = datasetRepository.findById(tenant, datasetIdA);
        Optional<Dataset> dsOptB = datasetRepository.findById(tenant, datasetIdB);

        if (dsOptA.isEmpty()) {
            return OperatorResult.failure("DATASET_NOT_FOUND", "Dataset A " + datasetIdA + " not found");
        }
        if (dsOptB.isEmpty()) {
            return OperatorResult.failure("DATASET_NOT_FOUND", "Dataset B " + datasetIdB + " not found");
        }

        Dataset dsA = dsOptA.get();
        Dataset dsB = dsOptB.get();

        // SNAPSHOT profiles only, on BOTH sides (ColumnProfile.measuredOverSnapshotRows) — audit
        // JOIN_DISCOVERY-1. Discovery is defined over the snapshot's physical columns; the other
        // two subjects are excluded for two independent reasons.
        //
        // Every metric this operator ships to freyja's /distance and /rank — frequency moments,
        // word-length statistics, value percentages, the common-value lists — is a structural zero
        // on a HARVESTED row, which carries a catalog NDV estimate and nothing else. Feeding one in
        // does not weaken a candidate, it invents one: freyja scores the zeros, and a join a user
        // may then accept gets picked from measurements nobody made. A WRAPPER_OUTPUT row (written
        // at every compile) names its columns by full property IRI, so it both duplicates each
        // physical column under a second name and makes {@link #columnIri} mint an iriA/iriB out of
        // an IRI-shaped name that no source graph can resolve — a candidate that can never be
        // applied. The pre-existing {@code isEmpty()} guard below catches neither: that field asks
        // whether the COLUMN is empty, and a harvested row writes a flat false.
        //
        // Newly URGENT since Flyway V36 (V40/V44): the widened uniqueness key finally lets a
        // SNAPSHOT and a HARVESTED row coexist for one column, which is the point — but it means an
        // ordinary materialized relational dataset now offers each column TWICE here, and
        // rankingResults has no (columnA, columnB) dedup before the sort, so one real pair came out
        // as up to four, three of them scored off zeros. Filtering by subject fixes that and the
        // older cases together: a federated dataset carrying only HARVESTED rows, and any dataset
        // compiled at least once, both produced meaningless candidates before V36 ever existed and
        // now correctly produce none.
        List<Map<String, Object>> candidatesList = new ArrayList<>();
        Map<String, ColumnProfile> measuredB = new LinkedHashMap<>();
        for (ColumnProfile colB : dsB.columns()) {
            if (!colB.measuredOverSnapshotRows()) continue;
            candidatesList.add(toProfileJson(colB));
            measuredB.putIfAbsent(colB.columnName(), colB);
        }
        List<ColumnProfile> measuredA = new ArrayList<>();
        for (ColumnProfile colA : dsA.columns()) {
            if (colA.measuredOverSnapshotRows() && !colA.isEmpty()) measuredA.add(colA);
        }

        List<Map<String, Object>> rankingResults = new ArrayList<>();
        // J3-F5: every failure below used to be a logged `continue`, so a sidecar that was down
        // produced the same "[]" as two sources that share nothing — and the UI advised lowering
        // the threshold. What was compared and what failed now travel with the result.
        List<String> failedColumns = new ArrayList<>();
        String firstFailure = null;
        int pairsScored = 0;
        // Pairs freyja ranked WITHOUT measuring their overlap: /distance omits exact_mj when a side
        // has no value sample (profiled before V45, or no sampled values), and /rank then scores the
        // pair 0 under 'exact'. Counted so an all-unsampled run is not reported as "no pair shares
        // values" — an unmeasured result is not a measured absence (the J3-F5 category).
        boolean exact = "exact".equals(method);
        int unsampledPairs = 0;

        // No measured column on a side is a precondition, not a failure: nothing is sent to freyja
        // and the counts say why the list is empty (the UI names the dataset to profile).
        if (!candidatesList.isEmpty()) {
            for (ColumnProfile colA : measuredA) {
                ScoredColumn scored;
                try {
                    scored = scoreColumn(tenant, colA, candidatesList, method, strictness);
                } catch (ScoringFailure failure) {
                    log.error("Join discovery could not score column '{}': {}",
                            colA.columnName(), failure.getMessage(), failure.getCause());
                    failedColumns.add(colA.columnName());
                    if (firstFailure == null) firstFailure = failure.getMessage();
                    continue;
                }
                pairsScored += scored.ranking().size();

                for (JsonNode candNode : scored.ranking()) {
                    String candidateColumn = candNode.get("candidate_column").asText();
                    double score = candNode.get("score").asDouble();
                    if (exact && !hasOverlap(scored.metricsByCandidate().get(candidateColumn))) {
                        unsampledPairs++;
                    }

                    if (score > 0.0 && score >= threshold) {
                        Map<String, Object> candidate = new HashMap<>();
                        candidate.put("iriA", columnIri(datasetIdA, dsA.name(), colA.columnName()));
                        candidate.put("iriB", columnIri(datasetIdB, dsB.name(), candidateColumn));
                        candidate.put("columnA", colA.columnName());
                        candidate.put("columnB", candidateColumn);
                        candidate.put("score", score);
                        candidate.put("label", "join_" + Names.iriFragment(colA.columnName()) + "_" + Names.iriFragment(candidateColumn));
                        candidate.put("evidence", evidence(colA, measuredB.get(candidateColumn),
                                scored.metricsByCandidate().get(candidateColumn)));
                        rankingResults.add(candidate);
                    }
                }
            }
        }

        // Sort candidates descending by score
        rankingResults.sort((o1, o2) -> Double.compare((Double) o2.get("score"), (Double) o1.get("score")));

        Map<String, String> data = new LinkedHashMap<>();
        try {
            data.put("candidates", objectMapper.writeValueAsString(rankingResults));
        } catch (Exception e) {
            log.error("Failed to serialize discovery results", e);
            data.put("candidates", "[]");
            if (firstFailure == null) firstFailure = "the candidate list could not be serialized";
        }
        data.put("datasetNameA", dsA.name());
        data.put("datasetNameB", dsB.name());
        data.put("profiledColumnsA", Integer.toString(measuredA.size()));
        data.put("profiledColumnsB", Integer.toString(candidatesList.size()));
        data.put("pairsScored", Integer.toString(pairsScored));
        // Exact only: the ML model scores from every profile distance, so a missing overlap there
        // does not zero the pair.
        if (exact) data.put("unsampledPairs", Integer.toString(unsampledPairs));
        // Present only when something failed, never "false" — the ConstraintDiscovery precedent
        // (CONSTRAINT_DISCOVERY-12): a caller tests for the key.
        if (firstFailure != null) {
            data.put("degraded", "true");
            data.put("reason", failedColumns.isEmpty()
                    ? firstFailure
                    : failedColumns.size() + " of " + measuredA.size() + " columns of " + dsA.name()
                            + " could not be scored — " + firstFailure);
            try {
                data.put("failedColumns", objectMapper.writeValueAsString(failedColumns));
            } catch (Exception e) {
                data.put("failedColumns", "[]");
            }
        }
        return OperatorResult.success(data);
    }

    /** One query column's ranking, plus the per-candidate metrics freyja's /distance measured. */
    private record ScoredColumn(List<JsonNode> ranking, Map<String, JsonNode> metricsByCandidate) {}

    /** Why one column could not be scored; the message is written for the person who ran discovery. */
    private static final class ScoringFailure extends Exception {
        private static final long serialVersionUID = 1L;

        ScoringFailure(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    private ScoredColumn scoreColumn(TenantContext tenant, ColumnProfile colA,
                                     List<Map<String, Object>> candidatesList,
                                     String method, double strictness) throws ScoringFailure {
        Map<String, Object> distanceReq = new HashMap<>();
        distanceReq.put("tenant_id", tenant.value());
        distanceReq.put("query", toProfileJson(colA));
        distanceReq.put("candidates", candidatesList);
        JsonNode distancesNode = callFreyja("/operators/distance", distanceReq).get("distances");
        if (distancesNode == null || !distancesNode.isArray()) {
            throw new ScoringFailure("the scoring service answered /operators/distance without a distance list", null);
        }
        Map<String, JsonNode> metricsByCandidate = new HashMap<>();
        for (JsonNode d : distancesNode) {
            JsonNode name = d.get("candidate_column");
            if (name != null) metricsByCandidate.putIfAbsent(name.asText(), d.path("metric_distances"));
        }

        Map<String, Object> rankReq = new HashMap<>();
        rankReq.put("tenant_id", tenant.value());
        rankReq.put("distances", distancesNode);
        rankReq.put("method", method);
        rankReq.put("strictness", strictness);
        JsonNode rankingNode = callFreyja("/operators/rank", rankReq).get("ranking");
        if (rankingNode == null || !rankingNode.isArray()) {
            throw new ScoringFailure("the scoring service answered /operators/rank without a ranking", null);
        }
        List<JsonNode> ranking = new ArrayList<>();
        rankingNode.forEach(ranking::add);
        return new ScoredColumn(ranking, metricsByCandidate);
    }

    private JsonNode callFreyja(String path, Map<String, Object> body) throws ScoringFailure {
        HttpResponse<String> response;
        try {
            HttpRequest requestHttp = withFreyjaAuth(HttpRequest.newBuilder()
                    .uri(URI.create(freyjaUrl + path)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    // Without a request timeout a hung sidecar pins this job thread forever.
                    .timeout(Duration.ofMinutes(2))
                    .build();
            response = httpClient.send(requestHttp, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScoringFailure("scoring was interrupted", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ScoringFailure("the scoring service did not answer " + path + " within 2 minutes", e);
        } catch (Exception e) {
            throw new ScoringFailure("the scoring service (freyja) could not be reached ("
                    + e.getClass().getSimpleName() + ")", e);
        }
        if (response.statusCode() != 200) {
            throw new ScoringFailure("the scoring service answered " + response.statusCode() + " on "
                    + path + detailOf(response.body()), null);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new ScoringFailure("the scoring service answered " + path + " with unreadable JSON", e);
        }
    }

    /** FastAPI's {@code {"detail": …}}, when the body carries one — the sidecar's own words for why. */
    private String detailOf(@Nullable String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode detail = objectMapper.readTree(body).get("detail");
            if (detail != null && detail.isTextual() && !detail.asText().isBlank()) {
                return ": " + detail.asText();
            }
        } catch (Exception ignored) {
            // not JSON; fall through to no detail rather than echoing an arbitrary body
        }
        return "";
    }

    private static boolean hasOverlap(@Nullable JsonNode metrics) {
        JsonNode mj = metrics == null ? null : metrics.get("exact_mj");
        return mj != null && mj.isNumber();
    }

    /**
     * The facts a person needs to refute a candidate (J3-F6): the score alone ranked two unrelated
     * serial keys first. Everything here was already measured — the profiles are the ones just sent
     * to freyja, the overlap and cardinality ratio are what its /distance computed for the pair.
     *
     * <p>{@code valueOverlap} is the Jaccard of the two columns' distinct values, estimated from
     * their bottom-k hash samples: freyja reports MJ = |A∩B|/(|A|+|B|), which for sets is J/(1+J),
     * so J = MJ/(1−MJ). It is null — not zero — when either side had no sample (profiled before
     * samples existed): an unmeasured overlap must not read as a measured absence.
     *
     * <p>{@code containment} is the share of the SMALLER column's distinct values found in the
     * other ({@code containmentOf} names it, A or B) — what a foreign key looks like, and what the
     * Jaccard undersells: a 300-value key wholly inside a 1000-value one is J = 0.3 but containment
     * 1.0. From the same measurements, |A∩B| = MJ·(|A|+|B|) over the profiled distinct counts,
     * divided by the smaller count and capped at 1; an estimate, null whenever the overlap is.
     */
    private static Map<String, Object> evidence(ColumnProfile colA, @Nullable ColumnProfile colB,
                                                @Nullable JsonNode metrics) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        Double overlap = null;
        Double containment = null;
        String containmentOf = null;
        Double cardinalityRatio = null;
        if (metrics != null) {
            JsonNode mj = metrics.get("exact_mj");
            if (mj != null && mj.isNumber()) {
                double m = Math.max(0.0, Math.min(0.5, mj.asDouble()));
                overlap = m >= 0.5 ? 1.0 : m / (1.0 - m);
                if (colB != null) {
                    long smaller = Math.min(colA.cardinality(), colB.cardinality());
                    if (smaller > 0) {
                        double shared = m * (colA.cardinality() + colB.cardinality());
                        containment = Math.min(1.0, shared / smaller);
                        containmentOf = colA.cardinality() <= colB.cardinality() ? "A" : "B";
                    }
                }
            }
            JsonNode k = metrics.get("exact_k");
            if (k != null && k.isNumber()) cardinalityRatio = k.asDouble();
        }
        evidence.put("valueOverlap", overlap);
        evidence.put("containment", containment);
        evidence.put("containmentOf", containmentOf);
        evidence.put("cardinalityRatio", cardinalityRatio);
        evidence.put("distinctA", colA.cardinality());
        evidence.put("uniquenessA", colA.uniqueness());
        evidence.put("nullFractionA", colA.incompleteness());
        evidence.put("typeA", colA.sourceType());
        if (colB != null) {
            evidence.put("distinctB", colB.cardinality());
            evidence.put("uniquenessB", colB.uniqueness());
            evidence.put("nullFractionB", colB.incompleteness());
            evidence.put("typeB", colB.sourceType());
        }
        return evidence;
    }

    private Map<String, Object> toProfileJson(ColumnProfile cp) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("cardinality", cp.cardinality());
        metrics.put("entropy", cp.entropy());
        metrics.put("uniqueness", cp.uniqueness());
        metrics.put("incompleteness", cp.incompleteness());
        metrics.put("frequency_avg", cp.frequencyAvg());
        metrics.put("frequency_min", cp.frequencyMin());
        metrics.put("frequency_max", cp.frequencyMax());
        metrics.put("frequency_sd", cp.frequencySd());
        metrics.put("frequency_iqr", cp.frequencyIqr());
        metrics.put("val_pct_min", cp.valPctMin());
        metrics.put("val_pct_max", cp.valPctMax());
        metrics.put("val_pct_std", cp.valPctStd());
        metrics.put("constancy", cp.constancy());
        metrics.put("len_max_word", cp.lenMaxWord());
        metrics.put("len_min_word", cp.lenMinWord());
        metrics.put("len_avg_word", cp.lenAvgWord());
        metrics.put("number_words", cp.numberWords());
        metrics.put("words_cnt_max", cp.wordsCntMax());
        metrics.put("words_cnt_min", cp.wordsCntMin());
        metrics.put("words_cnt_avg", cp.wordsCntAvg());
        metrics.put("words_cnt_sd", cp.wordsCntSd());
        metrics.put("first_word", cp.firstWord());
        metrics.put("last_word", cp.lastWord());
        metrics.put("is_empty", cp.isEmpty() ? 1 : 0);
        metrics.put("is_binary", cp.isBinary() ? 1 : 0);
        metrics.put("freq_word_containment", cp.commonValues());
        metrics.put("freq_word_soundex_containment", cp.commonValuesSoundex());
        // What the 'exact' score estimates value overlap from: freq_word_containment is chosen by
        // frequency, so it is no sample of the value set (freyja/profiling/value_sketch.py).
        metrics.put("value_hash_sample", cp.valueHashSample());

        Map<String, Object> root = new HashMap<>();
        root.put("column_name", cp.columnName());
        root.put("column_index", cp.columnIndex());
        root.put("source_type", cp.sourceType());
        root.put("metrics", metrics);
        return root;
    }


    private static String columnIri(UUID datasetId, String datasetName, String columnName) {
        return "http://www.essi.upc.edu/DTIM/NextiaDI/source/" + datasetId + "/" + Names.iriFragment(datasetName) + "/" + Names.iriFragment(columnName);
    }

    /**
     * Adds the FREYJA bearer token when one is configured.
     *
     * <p>freyja enforces this only when its own {@code FREYJA_SHARED_SECRET} is set, so an
     * empty value here is valid and simply sends no header — that keeps a sidecar started
     * before the secret existed working. Configure both sides together.
     */
    private HttpRequest.Builder withFreyjaAuth(HttpRequest.Builder builder) {
        if (freyjaSecret != null && !freyjaSecret.isBlank()) {
            builder.header("Authorization", "Bearer " + freyjaSecret);
        }
        return builder;
    }
}

package edu.upc.dtak.aran.adapter.persistence;

import edu.upc.dtak.aran.core.domain.AuditedActor;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.GraphVersionPort;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionFuseki;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fuseki-backed implementation of the {@link GraphStore} port.
 *
 * <p>Uses the Apache Jena RDFConnection interface to interact with an isolated
 * dataset-per-tenant, named dynamically by the tenant's UUID string.
 */
@Service
@NullMarked
public class FusekiGraphStoreAdapter implements GraphStore {

    private static final Logger log = LoggerFactory.getLogger(FusekiGraphStoreAdapter.class);

    private final GraphVersionPort versionPort;
    private final HttpClient httpClient;

    // No annotation defaults: a deployment missing aran.fuseki.* must fail at
    // startup rather than silently talk to localhost with admin/admin. The dev
    // values live in application.yml; the field initializers only serve tests
    // that construct this adapter outside Spring.
    @Value("${aran.fuseki.base-url}")
    private String baseUrl = "http://localhost:3030";

    @Value("${aran.fuseki.username}")
    private String username = "admin";

    @Value("${aran.fuseki.password}")
    private String password = "admin";

    public FusekiGraphStoreAdapter(GraphVersionPort versionPort) {
        this.versionPort = versionPort;
        this.httpClient = HttpClient.newHttpClient();
    }

    private String getNormalizedBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String getDatasetUrl(TenantContext tenant) {
        return getNormalizedBaseUrl() + "/" + tenant.value();
    }

    @Override
    public void provision(TenantContext tenant) {
        log.info("Provisioning Fuseki dataset for tenant: {}", tenant.value());
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8)
        );
        String form = "dbName=" + tenant.value() + "&dbType=tdb2";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getNormalizedBaseUrl() + "/$/datasets"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                log.warn("Fuseki dataset already exists for tenant: {}", tenant.value());
            } else if (response.statusCode() >= 400) {
                throw new RuntimeException("Failed to provision Fuseki dataset. Status: " + response.statusCode() + ", Body: " + response.body());
            } else {
                log.info("Successfully provisioned Fuseki dataset for tenant: {}", tenant.value());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP communication with Fuseki failed while provisioning dataset", e);
        }
    }

    private <T> T executeWithRetry(TenantContext tenant, Supplier<T> action) {
        try {
            return action.get();
        } catch (Exception e) {
            log.info("Fuseki operation failed, attempting to auto-provision dataset for tenant {}: {}", tenant.value(), e.getMessage());
            try {
                provision(tenant);
            } catch (Exception pe) {
                log.error("Failed to auto-provision dataset for tenant {}", tenant.value(), pe);
            }
            return action.get();
        }
    }

    private void executeWithRetry(TenantContext tenant, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.info("Fuseki operation failed, attempting to auto-provision dataset for tenant {}: {}", tenant.value(), e.getMessage());
            try {
                provision(tenant);
            } catch (Exception pe) {
                log.error("Failed to auto-provision dataset for tenant {}", tenant.value(), pe);
            }
            action.run();
        }
    }

    @Override
    public Model query(TenantContext tenant, String sparql) {
        return executeWithRetry(tenant, () -> {
            String datasetUrl = getDatasetUrl(tenant);
            try (RDFConnection conn = RDFConnectionFuseki.create()
                    .destination(datasetUrl)
                    .build()) {
                return conn.queryConstruct(sparql);
            }
        });
    }

    @Override
    public GraphVersion replaceDraft(TenantContext tenant, Model fullGraph, AuditedActor actor) {
        String datasetUrl = getDatasetUrl(tenant);
        UUID draftId = UUID.randomUUID();
        String draftGraphUri = "urn:aran:schema:version:" + draftId;

        Optional<GraphVersion> latestOpt = versionPort.findLatest(tenant);
        int nextVersion = latestOpt.map(v -> v.versionNumber() + 1).orElse(1);

        // No COPY from active: this draft fully replaces the active graph on promote.
        executeWithRetry(tenant, () -> {
            try (RDFConnection conn = RDFConnectionFuseki.create()
                    .destination(datasetUrl)
                    .build()) {
                // Make sure the draft graph is empty before loading (defensive — UUIDs collide ~ never).
                conn.update("CLEAR SILENT GRAPH <" + draftGraphUri + ">");
                conn.load(draftGraphUri, fullGraph);
            }
        });

        GraphVersion draft = new GraphVersion(
                draftId,
                UUID.fromString(tenant.value()),
                nextVersion,
                "DRAFT",
                Instant.now(),
                actor.userId(),
                null,
                "Replacement draft version " + nextVersion
        );

        return versionPort.save(tenant, draft);
    }

    @Override
    public GraphVersion promote(TenantContext tenant, UUID draftVersionId, AuditedActor actor) {
        Optional<GraphVersion> draftOpt = versionPort.findById(tenant, draftVersionId);
        if (draftOpt.isEmpty()) {
            throw new IllegalArgumentException("Draft version not found: " + draftVersionId);
        }
        GraphVersion draft = draftOpt.get();
        if (!"DRAFT".equals(draft.status())) {
            // 409 at the web boundary: the usual way here is a promote (or a rollback's promote)
            // retried after the browser stopped waiting while the first attempt landed (J4-F11).
            throw new edu.upc.dtak.aran.core.service.ConflictException(
                    edu.upc.dtak.aran.core.service.ConflictException.NOT_A_DRAFT,
                    "Schema version v" + draft.versionNumber() + " is " + draft.status()
                            + ", not a draft, so there is nothing to promote — an earlier promote "
                            + "may already have landed. Reload to see the current version.");
        }

        String datasetUrl = getDatasetUrl(tenant);

        // Move the graph through the client rather than copying inside the dataset.
        //
        // This previously ran, as one update against the tenant's own dataset:
        //     CLEAR SILENT GRAPH <urn:aran:schema> ; COPY <draft> TO <urn:aran:schema>
        // When urn:aran:schema was ALREADY NON-EMPTY, that silently wrote only a prefix
        // of the draft — 62 of 123 triples in the reproduction, cut in the middle of one
        // subject — and reported success. Every nx:integration/ subject was lost, so
        // MappingCatalogOperator's QUERY 1 (`?intClass a nx:IntegratedResource`) matched
        // nothing and the whole model became unqueryable while the UI read "active".
        // Consistent with a same-dataset COPY reading and writing one quad index; the
        // identical update against a fresh dataset copies all 123. See finding F24.
        //
        // Reading into a Model first and PUTting it back makes the write independent of
        // the target's prior contents, and mirrors what replaceDraft already does.
        // Graph Store Protocol PUT replaces the graph, so no separate CLEAR is needed.
        Model draftGraph = readVersion(tenant, draftVersionId);
        long expectedSize = draftGraph.size();

        executeWithRetry(tenant, () -> {
            try (RDFConnection conn = RDFConnectionFuseki.create()
                    .destination(datasetUrl)
                    .build()) {
                conn.put("urn:aran:schema", draftGraph);
            }
        });

        // A truncated promote is worse than a failed one: it leaves a live, queryable-looking
        // schema with silently missing triples. Fail loudly instead.
        long promotedSize = query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:aran:schema> { ?s ?p ?o } }").size();
        if (promotedSize != expectedSize) {
            throw new IllegalStateException(
                    "Promotion wrote " + promotedSize + " triples into urn:aran:schema but the draft "
                    + "version " + draftVersionId + " has " + expectedSize
                    + "; the active schema graph is incomplete and was NOT promoted cleanly.");
        }

        Optional<GraphVersion> activeOpt = versionPort.findCurrentActive(tenant);
        if (activeOpt.isPresent()) {
            GraphVersion active = activeOpt.get();
            GraphVersion historical = new GraphVersion(
                    active.id(),
                    active.tenantId(),
                    active.versionNumber(),
                    "HISTORICAL",
                    active.createdAt(),
                    active.createdBy(),
                    active.promotedAt(),
                    active.notes()
            );
            versionPort.save(tenant, historical);
        }

        GraphVersion promoted = new GraphVersion(
                draft.id(),
                draft.tenantId(),
                draft.versionNumber(),
                "ACTIVE",
                draft.createdAt(),
                draft.createdBy(),
                Instant.now(),
                draft.notes()
        );

        return versionPort.save(tenant, promoted);
    }

    @Override
    public @Nullable GraphVersion currentVersion(TenantContext tenant) {
        return versionPort.findCurrentActive(tenant).orElse(null);
    }

    @Override
    public Model readVersion(TenantContext tenant, UUID versionId) {
        // Each version's triples live in their own named graph, written at draft time and
        // never mutated afterwards (promote reads this graph and PUTs it into
        // urn:aran:schema, leaving the source untouched). So the version graph is a
        // stable, immutable snapshot — which is why promote can safely read it back.
        String versionGraphUri = "urn:aran:schema:version:" + versionId;
        return query(tenant,
                "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + versionGraphUri + "> { ?s ?p ?o } }");
    }

    @Override
    public void addTriples(TenantContext tenant, String graphUri, Model triples) {
        executeWithRetry(tenant, () -> {
            String datasetUrl = getDatasetUrl(tenant);
            try (RDFConnection conn = RDFConnectionFuseki.create()
                    .destination(datasetUrl)
                    .build()) {
                conn.load(graphUri, triples);
            }
        });
    }

    @Override
    public void clearGraph(TenantContext tenant, String graphUri) {
        executeWithRetry(tenant, () -> {
            String datasetUrl = getDatasetUrl(tenant);
            try (RDFConnection conn = RDFConnectionFuseki.create()
                    .destination(datasetUrl)
                    .build()) {
                conn.update("CLEAR SILENT GRAPH <" + graphUri + ">");
            }
        });
    }
}

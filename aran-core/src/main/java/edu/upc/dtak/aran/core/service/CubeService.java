package edu.upc.dtak.aran.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.domain.cube.CubeCalendar;
import edu.upc.dtak.aran.core.domain.cube.CubeDefinition;
import edu.upc.dtak.aran.core.domain.cube.CubeDimension;
import edu.upc.dtak.aran.core.domain.cube.CubeEvidence;
import edu.upc.dtak.aran.core.domain.cube.CubeEvidence.EvidenceClass;
import edu.upc.dtak.aran.core.domain.cube.CubeEvidence.EvidenceEdge;
import edu.upc.dtak.aran.core.domain.cube.CubeEvidence.EvidenceProperty;
import edu.upc.dtak.aran.core.domain.cube.CubeMeasure;
import edu.upc.dtak.aran.core.domain.cube.CubeOql;
import edu.upc.dtak.aran.core.domain.cube.CubeQb4olap;
import edu.upc.dtak.aran.core.domain.cube.CubeRecord;
import edu.upc.dtak.aran.core.domain.cube.CubeRepository;
import edu.upc.dtak.aran.core.domain.cube.CubeYaml;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorOrchestrator;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.util.Names;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Lifecycle of cubes: create-with-validation, read, and delete — mirroring
 * {@link DataProductService}, including the part where <b>there is no update</b>. A cube is
 * validated against the evidence of one ACTIVE schema version, and re-validating it against a
 * later one is creating a new cube.
 *
 * <p>Creating a cube is one act with three artefacts, and this class is where that is true:
 * the definition row, one MATERIALIZED data product per star table (fact + each dimension,
 * created and materialized through {@link DataProductService} rather than through a second
 * materialization path), and the QB4OLAP triples in the cube's own named graph. If any of it
 * fails, none of it survives — the alternative is a cube whose star is half-built and whose
 * only repair is by hand.
 *
 * <p>The validation evidence comes from the query engine's {@code cube-evidence} operation over
 * {@link OperatorOrchestrator}, stringly typed for the same reason every call in
 * {@link DataProductService} is: the query-engine module is not on core's compile path.
 * {@link CubeEvidence} is the one place that JSON is read.
 */
@Service
@NullMarked
public class CubeService {

    private static final Logger log = LoggerFactory.getLogger(CubeService.class);

    private final CubeRepository cubes;
    private final GraphStore graphStore;
    private final OperatorOrchestrator orchestrator;
    private final DataProductService products;
    private final ObjectMapper objectMapper;

    public CubeService(CubeRepository cubes,
                       GraphStore graphStore,
                       OperatorOrchestrator orchestrator,
                       DataProductService products,
                       ObjectMapper objectMapper) {
        this.cubes = cubes;
        this.graphStore = graphStore;
        this.orchestrator = orchestrator;
        this.products = products;
        this.objectMapper = objectMapper;
    }

    /** A created cube and the materialization jobs its star tables are running. */
    public record CubeCreation(CubeDefinition cube, List<UUID> jobIds) {}

    /**
     * What the wizard suggests from: the integrated classes with their profiled properties, and
     * the referential edges with their trust tiers. A passthrough — the engine owns the evidence,
     * this owns only the parse.
     */
    public CubeEvidence evidence(TenantContext tenant) {
        OperatorRequest request = new OperatorRequest(
                tenant.value(), null, "cube-evidence", Map.of());
        OperatorResult result = orchestrator.execute("query-engine", request);
        if (!result.success()) {
            throw new IllegalStateException("Cube evidence unavailable: "
                    + (result.errorMessage() != null ? result.errorMessage() : result.errorCode()));
        }
        return CubeEvidence.parse(result.data().get("evidence"), objectMapper);
    }

    /**
     * Shape a cube over the integrated schema: validate the definition against the engine's
     * evidence, pin the ACTIVE schema version, build and materialize the star, write its QB4OLAP
     * description, and persist the definition last.
     *
     * <p>Order matters and is deliberate: everything that can be rejected is rejected before the
     * first product exists, so the ordinary failure — a bad measure, an unverified join — costs
     * nothing to recover from.
     *
     * @throws IllegalArgumentException on any validation gate, mapped to 400 at the web boundary
     */
    public CubeCreation create(TenantContext tenant, UUID projectId, String name,
                               @Nullable String description, String factClassIri,
                               List<CubeMeasure> measures, List<CubeDimension> dimensions,
                               @Nullable CubeCalendar calendar, @Nullable UUID actor) {
        CubeEvidence evidence = evidence(tenant);

        // Pre-empt the V40 unique constraint: without this the collision surfaces as a raw
        // DataIntegrityViolationException from product creation — a 500 — instead of a 400
        // the wizard can show. Listing is fine here; a project's cubes are few.
        String trimmedName = name.trim();
        boolean nameTaken = cubes.list(tenant, projectId).stream()
                .anyMatch(existing -> existing.name().equals(trimmedName));
        if (nameTaken) {
            throw new IllegalArgumentException(
                    "cube name '" + trimmedName + "' already exists in this project");
        }

        GraphVersion current = graphStore.currentVersion(tenant);
        if (current == null) {
            throw new IllegalArgumentException(
                    "No schema version to pin — promote an integrated schema first.");
        }
        if (!"ACTIVE".equals(current.status())) {
            throw new IllegalArgumentException(
                    "The current schema version is " + current.status()
                            + "; a cube pins an ACTIVE version — promote it first.");
        }

        CubeDefinition cube = new CubeDefinition(UUID.randomUUID(), UUID.fromString(tenant.value()),
                projectId, name.trim(), description, factClassIri, measures, dimensions, calendar,
                List.of(), current.id(), actor == null ? null : actor.toString(), Instant.now());
        validate(cube, evidence);
        requireStarTableNames(tenant, cube);

        // Built before anything is created: an OQL the generator cannot write (a property whose
        // local name OQL has no syntax for) must fail here, not inside a materialization job.
        String factOql = CubeOql.fact(cube, evidence);
        Map<CubeDimension, String> dimensionOql = new LinkedHashMap<>();
        for (CubeDimension dimension : cube.dimensions()) {
            dimensionOql.put(dimension, CubeOql.dimension(cube, dimension, evidence));
        }

        List<UUID> productIds = new ArrayList<>();
        List<UUID> jobIds = new ArrayList<>();
        try {
            DataProduct fact = products.create(tenant, projectId, cube.factProductName(),
                    "Fact table of cube " + cube.name(), factOql, actor);
            productIds.add(fact.id());
            jobIds.add(products.materialize(tenant, fact.id(), actor));
            for (Map.Entry<CubeDimension, String> entry : dimensionOql.entrySet()) {
                DataProduct dimensionProduct = products.create(tenant, projectId,
                        cube.dimensionProductName(entry.getKey()),
                        "Dimension " + entry.getKey().name() + " of cube " + cube.name(),
                        entry.getValue(), actor);
                productIds.add(dimensionProduct.id());
                jobIds.add(products.materialize(tenant, dimensionProduct.id(), actor));
            }

            CubeDefinition created = cube.withProductIds(productIds);
            // Graph before row, row last: the row is what makes the cube exist to every read
            // path, so it must be the final write — a crash between the two leaves at most an
            // orphaned urn:aran:cubes graph, never a cube row pointing at rolled-back products.
            writeDescription(tenant, created);
            cubes.save(tenant, toRecord(created));
            log.info("Cube {} ('{}') created with {} star products", created.id(), created.name(),
                    productIds.size());
            return new CubeCreation(created, List.copyOf(jobIds));
        } catch (RuntimeException e) {
            deleteProducts(tenant, productIds);
            clearDescriptionQuietly(tenant, cube.id());
            throw e;
        }
    }

    /** A project's cubes, newest first. */
    public List<CubeDefinition> list(TenantContext tenant, UUID projectId) {
        return cubes.list(tenant, projectId).stream().map(this::toDefinition).toList();
    }

    /** @throws ResourceNotFoundException when the cube does not exist (or is not visible) */
    public CubeDefinition get(TenantContext tenant, UUID cubeId) {
        return cubes.find(tenant, cubeId).map(this::toDefinition)
                .orElseThrow(() -> new ResourceNotFoundException("Cube not found: " + cubeId));
    }

    /**
     * The cube, when it belongs to {@code projectId} — see {@code DataProductService.get(tenant,
     * projectId, productId)} for why a by-id read under a project URL has to say so (S2-1).
     */
    public CubeDefinition get(TenantContext tenant, UUID projectId, UUID cubeId) {
        CubeDefinition cube = get(tenant, cubeId);
        if (!cube.projectId().equals(projectId)) {
            throw new ResourceNotFoundException("Cube not found: " + cubeId);
        }
        return cube;
    }

    /** The cube as {@code cube.yaml} — emit-only in v1, so there is no setter beside it. */
    public String getYaml(TenantContext tenant, UUID cubeId) {
        return CubeYaml.emit(get(tenant, cubeId));
    }

    /**
     * Delete a cube and everything creating it made: its row, its star products, its named graph.
     *
     * <p>Row first — the mirror of {@link #create}'s row-last. The row is what makes the star's
     * products members, and {@link DataProductService#delete} refuses to delete a member, so the
     * products can only go once the row has. A crash part-way therefore leaves ordinary products
     * the products screen can delete, never a cube row pointing at products that are gone.
     *
     * <p>A member product already gone is still not an error: a star built before members were
     * protected may have lost one, and refusing to clean up the rest would strand the cube.
     */
    public void delete(TenantContext tenant, UUID cubeId) {
        CubeDefinition cube = get(tenant, cubeId);
        cubes.delete(tenant, cubeId);
        deleteProducts(tenant, cube.productIds());
        graphStore.clearGraph(tenant, CubeQb4olap.graphUri(cubeId));
    }

    // ─── validation gates ─────────────────────────────────────────────────────

    private void validate(CubeDefinition cube, CubeEvidence evidence) {
        EvidenceClass fact = evidence.classOf(cube.factClassIri()).orElseThrow(
                () -> new IllegalArgumentException("fact class " + cube.factClassIri()
                        + " is not an integrated class of the ACTIVE schema"));

        for (CubeMeasure measure : cube.measures()) {
            String columnIri = measure.columnIri();
            if (columnIri == null) {
                // A RATIO measure's expr is definition-only in v1: nothing here parses it, so
                // there is nothing to check it against. See CubeMeasure's javadoc.
                continue;
            }
            EvidenceProperty column = fact.property(columnIri).orElseThrow(
                    () -> new IllegalArgumentException("measure '" + measure.name() + "': "
                            + columnIri + " is not a property of " + fact.name()));
            // COUNT is the exception on purpose — counting rows of a non-numeric column is
            // meaningful, and it is the only aggregator for which that is true.
            if (!"COUNT".equals(measure.aggregator()) && !column.numeric()) {
                throw new IllegalArgumentException("measure '" + measure.name() + "': "
                        + column.name() + " is not numeric"
                        + (column.xsd() == null ? "" : " (" + column.xsd() + ")")
                        + " — only COUNT is defined over it");
            }
        }

        for (CubeDimension dimension : cube.dimensions()) {
            EvidenceEdge edge = evidence.edgeOf(dimension.joinPropertyIri()).orElseThrow(
                    () -> new IllegalArgumentException("dimension '" + dimension.name() + "': join "
                            + dimension.joinPropertyIri()
                            + " is not a relationship of the integrated schema"));
            if (!edge.touches(cube.factClassIri())) {
                throw new IllegalArgumentException("dimension '" + dimension.name() + "': join "
                        + edge.propertyName() + " does not touch the fact class " + fact.name()
                        + " — a dimension is reached from the fact, in one hop");
            }
            String otherEnd = edge.otherEnd(cube.factClassIri());
            if (!otherEnd.equals(dimension.classIri())) {
                throw new IllegalArgumentException("dimension '" + dimension.name() + "': join "
                        + edge.propertyName() + " reaches " + Names.localName(otherEnd)
                        + ", not " + Names.localName(dimension.classIri()));
            }
            // Fan-out, refused structurally (§5.2): a join the platform cannot show to be to-one
            // multiplies fact rows and double-counts every measure sliced by this dimension. The
            // same rule AranJoinRemoveRule applies to elimination, applied to modelling.
            //
            // "To-one" is tier AND orientation (S17-1): a DECLARED foreign key read from its
            // REFERENCED end is one-to-many, and the tier alone cannot tell you that.
            if (!edge.provablyToOneFrom(cube.factClassIri()) && !dimension.allowUnverified()) {
                throw new IllegalArgumentException(EvidenceEdge.HYPOTHESIS.equals(edge.tier())
                        ? "dimension '" + dimension.name() + "': join " + edge.propertyName()
                                + " is not provably to-one (HYPOTHESIS) — set allowUnverified to insist"
                        : "dimension '" + dimension.name() + "': join " + edge.propertyName()
                                + " is not to-one from " + fact.name() + " — " + fact.name()
                                + " is the referenced side of this " + edge.tier() + " foreign key, so"
                                + " each of its rows joins to many " + Names.localName(dimension.classIri())
                                + " and every measure would be counted once per match — set"
                                + " allowUnverified to insist");
            }

            EvidenceClass dimensionClass = evidence.classOf(dimension.classIri()).orElseThrow(
                    () -> new IllegalArgumentException("dimension '" + dimension.name()
                            + "': class " + dimension.classIri()
                            + " is not an integrated class of the ACTIVE schema"));
            for (String level : dimension.hierarchy()) {
                dimensionClass.property(level).orElseThrow(
                        () -> new IllegalArgumentException("dimension '" + dimension.name()
                                + "': hierarchy level " + level + " is not a property of "
                                + dimensionClass.name()));
            }
        }

        CubeCalendar calendar = cube.calendar();
        if (calendar != null) {
            fact.property(calendar.fromColumnIri()).orElseThrow(
                    () -> new IllegalArgumentException("calendar: " + calendar.fromColumnIri()
                            + " is not a property of " + fact.name()));
        }
    }

    /**
     * Every star product must be served under exactly the name the user typed — that is the
     * promise {@code CubeNames} makes — so each one is checked against the authority that names
     * tables, {@link ProductCatalog}, before anything is created.
     *
     * <p>Tenant-wide, not project-wide (S17): {@code data_product} is unique per project, but the
     * JDBC {@code products} tree, the Iceberg catalog and MCP are one namespace per tenant. A
     * product in another project normalising to {@code <cube>_fact} used to pass a project-scoped
     * check and push the fact table to a suffixed name nobody could find again. The same check
     * also answers the same-project collision data_product's unique constraint would have raised
     * as a 500, a name the catalog would prefix to keep it off a graph-view prefix, and two star
     * products of one cube deriving the same name (cube {@code s} with a dimension {@code fact}).
     */
    private void requireStarTableNames(TenantContext tenant, CubeDefinition cube) {
        List<String> wanted = new ArrayList<>();
        wanted.add(cube.factProductName());
        cube.dimensions().forEach(dimension -> wanted.add(cube.dimensionProductName(dimension)));

        ProductCatalog catalog = ProductCatalog.of(products.listAll(tenant));
        Set<String> claimedByThisStar = new HashSet<>();
        for (String productName : wanted) {
            if (!claimedByThisStar.add(productName)) {
                throw new IllegalArgumentException("this cube would create two star products named '"
                        + productName + "' — rename the dimension");
            }
            String table = ProductCatalog.baseTableName(productName);
            if (!table.equals(productName)) {
                throw new IllegalArgumentException("star product '" + productName
                        + "' would be served as table '" + table + "', not under its own name —"
                        + " a name starting with int_, u_, src_ or w_ is reserved for the engine's"
                        + " views; pick another cube name");
            }
            catalog.byName(table).ifPresent(holder -> {
                throw new IllegalArgumentException(holder.product().projectId().equals(cube.projectId())
                        ? "a data product named '" + holder.product().name()
                                + "' already exists in this project — pick another cube or dimension name"
                        : "a data product named '" + holder.product().name() + "' in another project"
                                + " of this tenant already holds the table name '" + table
                                + "' — product tables share one namespace per tenant, so pick"
                                + " another cube or dimension name");
            });
        }
    }

    // ─── artefacts ────────────────────────────────────────────────────────────

    /**
     * Rewrite the cube's named graph. Cleared first, always: {@code addTriples} is a GSP POST and
     * therefore additive, so writing without clearing would merge a new description into an old
     * one and leave a graph describing neither.
     */
    private void writeDescription(TenantContext tenant, CubeDefinition cube) {
        String graphUri = CubeQb4olap.graphUri(cube.id());
        graphStore.clearGraph(tenant, graphUri);
        graphStore.addTriples(tenant, graphUri, CubeQb4olap.project(cube));
    }

    /** Rollback half of {@link #writeDescription}: best-effort, because it runs inside a catch
     *  that must rethrow the original failure, not a cleanup one. */
    private void clearDescriptionQuietly(TenantContext tenant, UUID cubeId) {
        try {
            graphStore.clearGraph(tenant, CubeQb4olap.graphUri(cubeId));
        } catch (RuntimeException e) {
            log.warn("Could not clear cube graph {}: {}", cubeId, e.toString());
        }
    }

    private void deleteProducts(TenantContext tenant, List<UUID> productIds) {
        for (UUID productId : productIds) {
            try {
                products.delete(tenant, productId);
            } catch (RuntimeException e) {
                log.warn("Could not delete cube member product {}: {}", productId, e.toString());
            }
        }
    }

    // ─── persistence shape ────────────────────────────────────────────────────

    private CubeRecord toRecord(CubeDefinition cube) {
        ObjectNode definition = objectMapper.createObjectNode();
        definition.set("measures", objectMapper.valueToTree(cube.measures()));
        definition.set("dimensions", objectMapper.valueToTree(cube.dimensions()));
        if (cube.calendar() != null) {
            definition.set("calendar", objectMapper.valueToTree(cube.calendar()));
        }
        return new CubeRecord(cube.id(), cube.tenantId(), cube.projectId(), cube.name(),
                cube.description(), cube.factClassIri(), definition.toString(),
                objectMapper.valueToTree(cube.productIds()).toString(), cube.schemaVersionId(),
                cube.createdBy(), cube.createdAt());
    }

    private CubeDefinition toDefinition(CubeRecord record) {
        try {
            JsonNode definition = objectMapper.readTree(record.definitionJson());
            JsonNode calendar = definition.path("calendar");
            return new CubeDefinition(record.id(), record.tenantId(), record.projectId(),
                    record.name(), record.description(), record.factClassIri(),
                    objectMapper.convertValue(definition.path("measures"),
                            new TypeReference<List<CubeMeasure>>() {}),
                    objectMapper.convertValue(definition.path("dimensions"),
                            new TypeReference<List<CubeDimension>>() {}),
                    calendar.isObject()
                            ? objectMapper.treeToValue(calendar, CubeCalendar.class) : null,
                    record.memberProductIds(),
                    record.schemaVersionId(), record.createdBy(), record.createdAt());
        } catch (Exception e) {
            // Unreadable stored JSON is a bug in this class or a hand-edited row, not user input:
            // it must not surface as a 400 telling the user their request was invalid.
            throw new IllegalStateException("Cube " + record.id() + " has an unreadable definition",
                    e);
        }
    }
}

package edu.upc.dtak.aran.app.iceberg;

import edu.upc.dtak.aran.adapter.iceberg.IcebergProductTable;
import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.core.domain.ProductTable;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
// Jackson 3, deliberately: it is what Spring MVC serializes response bodies with here, and a
// Jackson 2 JsonNode handed to it goes out as a bean ({"array":false,"nodeType":"OBJECT",…}) —
// every catalog answer was that until a real client read it (V53(c)).
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A read-only Apache Iceberg REST catalog over the tenant's MATERIALIZED data products (Gap 2,
 * produce half): an Iceberg engine that can send a bearer token on its file reads lists and
 * loads them as real Iceberg tables, authenticated with the same PAT every other API surface
 * takes. Watched working (IcebergProduceSurfaceIT): DuckDB, via {@code iceberg_scan} or
 * {@code ATTACH (TYPE ICEBERG)} plus an HTTP bearer secret, and iceberg-core's
 * {@code RESTCatalog} given a bearer-sending FileIO. PyIceberg ships no FileIO for the
 * {@code https://} locations below, so it cannot read them without a custom {@code py-io-impl}
 * that sends the bearer — none exists in this repository.
 *
 * <p>One namespace, {@code products}, holding one table per servable product under its
 * {@link edu.upc.dtak.aran.core.domain.ProductCatalog} name — the naming contract the JDBC tree
 * and the query engine already share; a third derivation here would offer a table nothing else
 * recognises.
 *
 * <p><b>Read-only is structural</b>: only the protocol's GET/HEAD read subset is mapped, so a
 * commit, a create or a drop is a 404/405 by construction rather than a checked-then-allowed path.
 *
 * <p><b>The invariant this surface was designed around</b>: every location in the served metadata
 * points back at Aran's own {@code /api/iceberg/files/…} endpoint, never at the lake. No presigned
 * URL, bucket name, or lake credential appears anywhere a client can see — the client reads bytes
 * from Aran over authenticated HTTPS, exactly as the JDBC endpoint serves frames.
 */
@RestController
@RequestMapping("/api/iceberg/v1")
@NullMarked
public class IcebergCatalogController {

    static final String NAMESPACE = "products";

    private final IcebergProductService service;
    private final CurrentUserResolver currentUser;
    private final ObjectMapper mapper;
    private final IcebergFilesBaseUrl filesBaseUrl;

    public IcebergCatalogController(IcebergProductService service, CurrentUserResolver currentUser,
                                    ObjectMapper mapper, IcebergFilesBaseUrl filesBaseUrl) {
        this.service = service;
        this.currentUser = currentUser;
        this.mapper = mapper;
        this.filesBaseUrl = filesBaseUrl;
    }

    /** The protocol's hello. No overrides, no prefix: paths continue as {@code /v1/namespaces/…}. */
    @GetMapping("/config")
    public JsonNode config() {
        currentUser.require();
        ObjectNode root = mapper.createObjectNode();
        root.putObject("defaults");
        root.putObject("overrides");
        return root;
    }

    @GetMapping("/namespaces")
    public JsonNode listNamespaces() {
        currentUser.require();
        ObjectNode root = mapper.createObjectNode();
        root.putArray("namespaces").addArray().add(NAMESPACE);
        return root;
    }

    @GetMapping("/namespaces/{namespace}")
    public ResponseEntity<JsonNode> loadNamespace(@PathVariable String namespace) {
        currentUser.require();
        if (!NAMESPACE.equals(namespace)) {
            return error(404, "NoSuchNamespaceException", "Namespace does not exist: " + namespace);
        }
        ObjectNode root = mapper.createObjectNode();
        root.putArray("namespace").add(NAMESPACE);
        root.putObject("properties");
        return ResponseEntity.ok(root);
    }

    @GetMapping("/namespaces/{namespace}/tables")
    public ResponseEntity<JsonNode> listTables(@PathVariable String namespace) {
        AuthenticatedUser caller = currentUser.require();
        if (!NAMESPACE.equals(namespace)) {
            return error(404, "NoSuchNamespaceException", "Namespace does not exist: " + namespace);
        }
        ObjectNode root = mapper.createObjectNode();
        ArrayNode identifiers = root.putArray("identifiers");
        for (ProductTable table : service.icebergTables(caller)) {
            ObjectNode ident = identifiers.addObject();
            ident.putArray("namespace").add(NAMESPACE);
            ident.put("name", table.sqlName());
        }
        return ResponseEntity.ok(root);
    }

    @RequestMapping(value = "/namespaces/{namespace}/tables/{table}",
            method = RequestMethod.HEAD)
    public ResponseEntity<Void> tableExists(@PathVariable String namespace,
                                            @PathVariable String table) {
        AuthenticatedUser caller = currentUser.require();
        boolean exists = NAMESPACE.equals(namespace)
                && service.icebergTable(caller, table).isPresent();
        return exists ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/namespaces/{namespace}/tables/{table}")
    public ResponseEntity<JsonNode> loadTable(@PathVariable String namespace,
                                              @PathVariable String table,
                                              HttpServletRequest request) {
        AuthenticatedUser caller = currentUser.require();
        if (!NAMESPACE.equals(namespace)) {
            return error(404, "NoSuchNamespaceException", "Namespace does not exist: " + namespace);
        }
        Optional<ProductTable> found = service.icebergTable(caller, table);
        if (found.isEmpty()) {
            return error(404, "NoSuchTableException", "Table does not exist: "
                    + NAMESPACE + "." + table);
        }
        IcebergProductTable.Artifacts artifacts =
                service.artifactsFor(caller, found.get(), filesBaseUrl.forRequest(request));

        ObjectNode root = mapper.createObjectNode();
        root.put("metadata-location", artifacts.metadataLocation());
        byte[] metadataJson = artifacts.files().get("metadata/" + IcebergProductTable.METADATA_FILE);
        try {
            root.set("metadata", mapper.readTree(metadataJson));
        } catch (JacksonException e) {
            throw new IllegalStateException("generated table metadata is not JSON", e);
        }
        root.putObject("config");
        return ResponseEntity.ok(root);
    }

    /** The spec's error body: {@code {"error": {"message", "type", "code"}}}. */
    private ResponseEntity<JsonNode> error(int code, String type, String message) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        return ResponseEntity.status(code).body(root);
    }
}

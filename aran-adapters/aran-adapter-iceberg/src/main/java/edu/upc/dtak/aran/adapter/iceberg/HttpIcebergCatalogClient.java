package edu.upc.dtak.aran.adapter.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * {@link IcebergCatalogClient} over plain {@code java.net.http} + Jackson — the read subset of the
 * Iceberg REST catalog protocol (Gap 2, consume half).
 *
 * <p>Protocol notes, pinned here because they are easy to get subtly wrong:
 * <ul>
 *   <li>{@code GET /v1/config} may answer with a {@code prefix} override; every later path then
 *       carries it: {@code /v1/{prefix}/namespaces/…}. The prefix is fetched once per client call
 *       chain, not cached across repositories.</li>
 *   <li>Multi-level namespaces travel in a URL path joined by the separator the catalog
 *       advertises as {@code namespace-separator} in its config, {@code %1F} (the unit separator)
 *       when it advertises none — and in the {@code parent} query parameter the same way. Not
 *       optional: the reference {@code apache/iceberg-rest-fixture} (Jetty 12) answers
 *       {@code 400 Suspicious Path Character} to a {@code %1F} in a path and advertises
 *       {@code %2E} instead, so a hard-coded unit separator cannot reach a nested namespace
 *       there.</li>
 *   <li>The table schema comes from {@code LoadTableResponse.metadata}: the schema named by
 *       {@code current-schema-id} in {@code schemas} (v2), falling back to the single
 *       {@code schema} (v1).</li>
 * </ul>
 *
 * <p><b>Credential hygiene.</b> The bearer token rides only in the {@code Authorization} header;
 * it is never logged, and error messages carry the URL path and status but never a header.
 */
@NullMarked
public class HttpIcebergCatalogClient implements IcebergCatalogClient {

    /** Bound on the namespace walk so a pathological catalog cannot make a scan unbounded. */
    private static final int MAX_NAMESPACES = 1_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** The spec's default, already percent-encoded, as it goes on the wire. */
    private static final String DEFAULT_NAMESPACE_SEPARATOR = "%1F";
    /** An advertised separator is spliced into a path verbatim, so only percent-escapes pass. */
    private static final Pattern PERCENT_ESCAPES = Pattern.compile("(%[0-9A-Fa-f]{2})+");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(java.net.ProxySelector.getDefault())
            .build();

    @Override
    public Map<String, String> config(IcebergRestRepository repository) {
        JsonNode node = get(repository, configPath(repository));
        Map<String, String> merged = new LinkedHashMap<>();
        putAll(merged, node.get("defaults"));
        putAll(merged, node.get("overrides"));
        return merged;
    }

    @Override
    public List<String> listTables(IcebergRestRepository repository) {
        Route route = routeOf(repository);
        List<String> out = new ArrayList<>();
        // Breadth-first over namespaces: list each level's tables, then descend. Bounded so a
        // catalog that answers with a cycle or an enormous tree fails loudly instead of hanging.
        Deque<List<String>> pending = new ArrayDeque<>();
        for (List<String> ns : listNamespaces(repository, route, null)) {
            pending.add(ns);
        }
        int seen = 0;
        while (!pending.isEmpty()) {
            if (++seen > MAX_NAMESPACES) {
                throw new IllegalStateException("catalog lists more than " + MAX_NAMESPACES
                        + " namespaces; refusing an unbounded scan");
            }
            List<String> ns = pending.removeFirst();
            JsonNode tables = get(repository,
                    "/v1/" + route.prefix() + "namespaces/" + route.encode(ns) + "/tables");
            JsonNode identifiers = tables.get("identifiers");
            if (identifiers != null && identifiers.isArray()) {
                for (JsonNode ident : identifiers) {
                    JsonNode name = ident.get("name");
                    if (name != null) {
                        out.add(String.join(".", ns) + "." + name.asText());
                    }
                }
            }
            for (List<String> child : listNamespaces(repository, route, ns)) {
                pending.add(child);
            }
        }
        return out;
    }

    @Override
    public IcebergTable loadTable(IcebergRestRepository repository, String tableIdentifier) {
        Route route = routeOf(repository);
        int lastDot = tableIdentifier.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == tableIdentifier.length() - 1) {
            throw new IllegalArgumentException(
                    "Iceberg table identifier must be namespace.table: '" + tableIdentifier + "'");
        }
        List<String> ns = List.of(tableIdentifier.substring(0, lastDot).split("\\."));
        String table = tableIdentifier.substring(lastDot + 1);

        JsonNode response = get(repository, "/v1/" + route.prefix() + "namespaces/"
                + route.encode(ns) + "/tables/" + urlEncode(table));
        JsonNode metadataLocation = response.get("metadata-location");
        JsonNode metadata = response.get("metadata");
        if (metadata == null) {
            throw new IllegalStateException("catalog answered without table metadata for '"
                    + tableIdentifier + "'");
        }
        String location = metadataLocation != null && !metadataLocation.isNull()
                ? metadataLocation.asText()
                : textOf(metadata, "metadata-location");
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("catalog reported no metadata location for '"
                    + tableIdentifier + "'");
        }
        return new IcebergTable(location, fieldsOf(metadata, tableIdentifier));
    }

    // --- schema extraction --------------------------------------------------

    private static List<IcebergField> fieldsOf(JsonNode metadata, String tableIdentifier) {
        JsonNode schema = currentSchema(metadata);
        if (schema == null) {
            throw new IllegalStateException("catalog metadata carries no schema for '"
                    + tableIdentifier + "'");
        }
        List<IcebergField> fields = new ArrayList<>();
        JsonNode fieldsNode = schema.get("fields");
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode f : fieldsNode) {
                JsonNode name = f.get("name");
                if (name == null) {
                    continue;
                }
                fields.add(new IcebergField(
                        name.asText(),
                        typeString(f.get("type")),
                        f.path("required").asBoolean(false)));
            }
        }
        return fields;
    }

    private static @Nullable JsonNode currentSchema(JsonNode metadata) {
        JsonNode schemas = metadata.get("schemas");
        if (schemas != null && schemas.isArray() && !schemas.isEmpty()) {
            int currentId = metadata.path("current-schema-id").asInt(-1);
            for (JsonNode s : schemas) {
                if (s.path("schema-id").asInt(-2) == currentId) {
                    return s;
                }
            }
            return schemas.get(0);
        }
        return metadata.get("schema"); // format v1
    }

    /** A primitive type is a JSON string; a nested one an object whose {@code type} names its kind. */
    private static String typeString(@Nullable JsonNode type) {
        if (type == null || type.isNull()) {
            return "string";
        }
        if (type.isTextual()) {
            return type.asText();
        }
        return type.path("type").asText("struct");
    }

    // --- protocol plumbing --------------------------------------------------

    private String configPath(IcebergRestRepository repository) {
        String warehouse = repository.warehouse();
        return "/v1/config" + (warehouse == null || warehouse.isBlank()
                ? "" : "?warehouse=" + urlEncode(warehouse));
    }

    /**
     * How this catalog wants later paths built, read from one {@code GET /v1/config}.
     *
     * @param prefix    the path prefix with a trailing slash, or the empty string
     * @param separator the percent-encoded namespace separator
     */
    private record Route(String prefix, String separator) {
        String encode(List<String> namespace) {
            List<String> encoded = new ArrayList<>(namespace.size());
            for (String segment : namespace) {
                encoded.add(urlEncode(segment));
            }
            return String.join(separator, encoded);
        }
    }

    private Route routeOf(IcebergRestRepository repository) {
        JsonNode node = get(repository, configPath(repository));
        String separator = configValue(node, "namespace-separator");
        // Anything but percent-escapes would be spliced raw into every namespace path — an
        // advertised "/" would rewrite the route — so an unusable value falls back to the
        // spec's default rather than being trusted.
        if (separator == null || !PERCENT_ESCAPES.matcher(separator).matches()) {
            separator = DEFAULT_NAMESPACE_SEPARATOR;
        }
        String prefix = configValue(node, "prefix");
        if (prefix == null || prefix.isBlank()) {
            return new Route("", separator);
        }
        // The prefix is a path fragment that may itself contain slashes; encode each segment.
        String[] segments = prefix.split("/");
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (!segment.isBlank()) {
                sb.append(urlEncode(segment)).append('/');
            }
        }
        return new Route(sb.toString(), separator);
    }

    /** A config key as the catalog resolves it: {@code overrides} win over {@code defaults}. */
    private static @Nullable String configValue(JsonNode config, String key) {
        JsonNode overrides = config.get("overrides");
        String value = overrides == null ? null : textOf(overrides, key);
        if (value == null) {
            JsonNode defaults = config.get("defaults");
            value = defaults == null ? null : textOf(defaults, key);
        }
        return value;
    }

    private List<List<String>> listNamespaces(IcebergRestRepository repository, Route route,
                                              @Nullable List<String> parent) {
        String path = "/v1/" + route.prefix() + "namespaces";
        if (parent != null) {
            path += "?parent=" + route.encode(parent);
        }
        JsonNode node = get(repository, path);
        List<List<String>> out = new ArrayList<>();
        JsonNode namespaces = node.get("namespaces");
        if (namespaces != null && namespaces.isArray()) {
            for (JsonNode ns : namespaces) {
                List<String> segments = new ArrayList<>();
                for (JsonNode segment : ns) {
                    segments.add(segment.asText());
                }
                if (!segments.isEmpty()) {
                    out.add(segments);
                }
            }
        }
        return out;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static @Nullable String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void putAll(Map<String, String> target, @Nullable JsonNode object) {
        if (object == null || !object.isObject()) {
            return;
        }
        object.properties().forEach(e -> target.put(e.getKey(), e.getValue().asText()));
    }

    private JsonNode get(IcebergRestRepository repository, String pathAndQuery) {
        String base = repository.catalogUri().replaceAll("/+$", "");
        URI uri = URI.create(base + pathAndQuery);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        String token = repository.token();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Iceberg catalog unreachable at " + uri.getHost()
                    + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the Iceberg catalog");
        }
        if (response.statusCode() / 100 != 2) {
            // The body is the catalog's own diagnostics (never a header, never the token); bound
            // it so an HTML error page does not flood a log line.
            String body = response.body() == null ? "" : response.body();
            if (body.length() > 500) {
                body = body.substring(0, 500) + "…";
            }
            throw new IllegalStateException("Iceberg catalog answered HTTP "
                    + response.statusCode() + " for " + pathAndQuery + ": " + body);
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Iceberg catalog answered non-JSON for "
                    + pathAndQuery + ": " + e.getMessage(), e);
        }
    }
}

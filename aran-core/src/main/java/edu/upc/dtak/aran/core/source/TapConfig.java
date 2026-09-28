package edu.upc.dtak.aran.core.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The one translation between a tap's settings as a map and as the JSON stored on the repository
 * row (T6).
 *
 * <p>It lives in the core because both ends need it and they are in different modules: the wire DTO
 * (aran-application) turns a request's map into the column, and {@code DataRepositoryService}
 * (aran-core) has to read the column back apart to apply the per-key update sentinel. A second copy
 * would be a place for the two to disagree about what an absent value or a non-string means, and
 * the disagreement would surface as a tap authenticating as nobody.
 */
@NullMarked
public final class TapConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TapConfig() {
    }

    /**
     * The stored JSON as an ordered map.
     *
     * <p>Insertion-ordered so a form renders the same way twice. An unreadable or non-object value
     * is an empty map rather than an exception: this is called while answering a GET, and a row
     * whose config cannot be parsed must still be listable — the repository is then visibly
     * settingless, which is the truth a user needs in order to fix it.
     */
    public static Map<String, String> toMap(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(json, new TypeReference<>() { });
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** A settings map as the JSON the row stores, and the tap's own config file will carry. */
    public static String toJson(@Nullable Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(new LinkedHashMap<>(config));
        } catch (Exception e) {
            // Every value is a String, so this cannot happen for a map that arrived over the wire.
            throw new IllegalArgumentException("tap settings could not be serialised", e);
        }
    }
}

package edu.upc.dtak.aran.operators.queryengine.oql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Every OQL statement the browser suite sends still parses — checked here, without Docker, where
 * a grammar change finds out in seconds instead of in a ten-minute {@code make e2e} run.
 *
 * <p>The statements are read out of {@code aran-web/e2e} rather than copied in, so a spec that
 * adds a query is covered without anyone remembering this file. The scan is deliberately crude —
 * string literals, with {@code +}-concatenation joined and each template {@code ${…}} replaced
 * by a class-shaped placeholder — because it only has to find strings that start with
 * {@code SELECT}. Wrapper SQL (anything over {@code ${SNAPSHOT}}) is not OQL and is skipped.
 */
class E2eOqlCorpusTest {

    /**
     * Statements a spec sends <em>in order to be refused</em>, and the text it asserts. The
     * parser must keep refusing them with that text, or the spec breaks.
     */
    private static final Map<String, String> EXPECTED_REFUSALS = Map.of(
            // v34-constraint-discovery: OQL has no column-to-column comparison.
            "WHERE Int_curated.journal_code <> Int_curated.impact_band", "Expected literal");

    @Test
    @DisplayName("every OQL statement in aran-web/e2e parses, and the deliberately bad one is refused as the spec expects")
    void everyE2eStatementParses() throws IOException {
        Map<String, Path> corpus = corpus();
        // A floor against a scan that silently found nothing and passed vacuously — not a count to
        // maintain (17 distinct statements when this was written, placeholders collapsing repeats).
        assertThat(corpus).as("OQL statements found under aran-web/e2e").hasSizeGreaterThanOrEqualTo(10);

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Path> entry : corpus.entrySet()) {
            String oql = entry.getKey();
            String refusal = EXPECTED_REFUSALS.entrySet().stream()
                    .filter(e -> oql.contains(e.getKey())).map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (refusal != null) {
                assertThatThrownBy(() -> OqlParser.parse(oql))
                        .as("%s: %s", entry.getValue().getFileName(), oql)
                        .isInstanceOf(OqlParser.OqlParseException.class)
                        .hasMessageContaining(refusal);
                continue;
            }
            try {
                OqlParser.parse(oql);
            } catch (OqlParser.OqlParseException e) {
                failures.add(entry.getValue().getFileName() + ": " + oql + "\n    -> " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            fail("e2e OQL that no longer parses:\n" + String.join("\n", failures));
        }
    }

    private static Map<String, Path> corpus() throws IOException {
        Path e2e = repoRoot().resolve("aran-web").resolve("e2e");
        assertThat(e2e).as("the browser suite's directory").isDirectory();
        Map<String, Path> statements = new LinkedHashMap<>();
        List<Path> sources;
        try (Stream<Path> tree = Files.walk(e2e)) {
            sources = tree.filter(p -> p.toString().endsWith(".ts")).sorted().toList();
        }
        for (Path source : sources) {
            // ISO-8859-1 so a stray non-UTF-8 byte (one spec carries NULs, S19-11) cannot abort the read.
            String text = Files.readString(source, StandardCharsets.ISO_8859_1);
            for (String literal : joinedStringLiterals(text)) {
                String trimmed = literal.strip();
                if (trimmed.startsWith("SELECT ") && !trimmed.contains("SNAPSHOT")) {
                    statements.putIfAbsent(placeholders(trimmed), source);
                }
            }
        }
        return statements;
    }

    /** Each template {@code ${…}} becomes a distinct class-shaped name, so the text still parses. */
    private static String placeholders(String template) {
        StringBuilder out = new StringBuilder();
        Map<String, String> names = new LinkedHashMap<>();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("${", i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            out.append(template, i, open);
            String expression = template.substring(open + 2, close);
            out.append(names.computeIfAbsent(expression, k -> "P" + names.size()));
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * The string literals of a TypeScript file, with literals joined only by {@code +} merged
     * into one — how the specs split a long query across lines. Comments are skipped, since they
     * quote OQL in backticks too.
     */
    private static List<String> joinedStringLiterals(String ts) {
        List<String> out = new ArrayList<>();
        StringBuilder current = null;
        int p = 0;
        int n = ts.length();
        while (p < n) {
            char c = ts.charAt(p);
            if (c == '/' && p + 1 < n && ts.charAt(p + 1) == '/') {
                p = ts.indexOf('\n', p);
                if (p < 0) break;
                continue;
            }
            if (c == '/' && p + 1 < n && ts.charAt(p + 1) == '*') {
                int end = ts.indexOf("*/", p + 2);
                p = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int end = p + 1;
                StringBuilder literal = new StringBuilder();
                while (end < n && ts.charAt(end) != c) {
                    if (ts.charAt(end) == '\\' && end + 1 < n) {
                        literal.append(ts.charAt(end + 1));
                        end += 2;
                        continue;
                    }
                    literal.append(ts.charAt(end++));
                }
                if (current == null) {
                    current = literal;
                } else {
                    current.append(literal);
                }
                p = end + 1;
                // Merge with the next literal only if nothing but whitespace and one '+' separate them.
                int q = skipWhitespace(ts, p);
                if (q < n && ts.charAt(q) == '+') {
                    int r = skipWhitespace(ts, q + 1);
                    if (r < n && (ts.charAt(r) == '\'' || ts.charAt(r) == '"' || ts.charAt(r) == '`')) {
                        p = r;
                        continue;
                    }
                }
                out.add(current.toString());
                current = null;
                continue;
            }
            p++;
        }
        return out;
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root above " + System.getProperty("user.dir")).isNotNull();
        return dir;
    }
}

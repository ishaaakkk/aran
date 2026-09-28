package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.util.Names;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.type.RelDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Structured description of an optimized logical plan, for assertion and for the
 * {@code planShape} payload field — the stable surface the harness and (later) the
 * plan view read, replacing regex scraping of {@link PlanExecutor#planString}.
 *
 * <p>Deliberately excludes cost estimates: statistics phases will change estimates,
 * and the harness's golden files must not churn when they do. Estimate assertions
 * belong on the {@code planString} output.
 */
public record PlanShape(Node root) {

    /** One plan operator. {@code detail} is human-oriented; {@code kind} is the contract. */
    public record Node(String kind, String detail, List<Node> inputs) {
        public Node {
            inputs = List.copyOf(inputs);
        }
    }

    public static PlanShape of(RelNode rel) {
        return of(rel, Map.of());
    }

    /**
     * @param scanAnnotations extra detail per scan, keyed by table name (e.g.
     *        {@code u_art_Artworks} → {@code access=MATERIALIZED}); appended after a comma.
     *        Scans without an entry render unannotated — absence must never fail a plan.
     */
    public static PlanShape of(RelNode rel, Map<String, String> scanAnnotations) {
        return new PlanShape(walk(rel, scanAnnotations));
    }

    /**
     * Scan annotations for every view a plan over {@code catalog} can reference: each
     * {@code u_} member view gets its dataset's access mode; each {@code int_} class view
     * gets the member consensus, or {@code MIXED} — an integrated class may genuinely mix
     * a live source with snapshot ones, and hiding that would misreport placement.
     */
    public static Map<String, String> accessAnnotations(
            MappingCatalog catalog, Function<String, String> accessByDatasetId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (MappingCatalog.IntegratedClass cls : catalog.classes().values()) {
            String consensus = null;
            boolean mixed = false;
            for (MappingCatalog.SourceView sv : cls.sources()) {
                String access = accessByDatasetId.apply(sv.datasetId());
                out.put(WrapperTable.viewName(sv, cls), "access=" + access);
                // An EXPANDED member's plan bottoms out in the raw src_ relation rather than the
                // u_ view; without this entry that leaf shape rendered unannotated — silently
                // under-reporting placement now that the mode can flip per query.
                out.put("src_" + Names.sqlName(sv.datasetId()), "access=" + access);
                if (consensus == null) {
                    consensus = access;
                } else if (!consensus.equals(access)) {
                    mixed = true;
                }
            }
            if (consensus != null) {
                out.put("int_" + Names.sqlName(cls.iri()), "access=" + (mixed ? "MIXED" : consensus));
            }
        }
        return out;
    }

    private static Node walk(RelNode rel, Map<String, String> ann) {
        List<Node> inputs = new ArrayList<>();
        for (RelNode in : rel.getInputs()) {
            inputs.add(walk(in, ann));
        }
        if (rel instanceof TableScan scan) {
            List<String> q = scan.getTable().getQualifiedName();
            String table = q.get(q.size() - 1);
            String extra = ann.get(table);
            return new Node("SCAN", extra == null ? table : table + ", " + extra, inputs);
        } else if (rel instanceof Join join) {
            return new Node("JOIN", joinDetail(join), inputs);
        } else if (rel instanceof Filter filter) {
            return new Node("FILTER",
                    readable(filter.getCondition().toString(), filter.getInput().getRowType()),
                    inputs);
        } else if (rel instanceof Project project) {
            String cols = project.getRowType().getFieldNames().stream()
                    .map(PlanShape::shorten).collect(Collectors.joining(", "));
            return new Node("PROJECT", cols, inputs);
        } else if (rel instanceof Union union) {
            return new Node("UNION", "all=" + union.all + ", inputs=" + inputs.size(), inputs);
        } else if (rel instanceof Aggregate agg) {
            String groups = agg.getGroupSet().asList().stream()
                    .map(i -> shorten(agg.getInput().getRowType().getFieldNames().get(i)))
                    .collect(Collectors.joining(", "));
            return new Node("AGGREGATE",
                    "group=[" + groups + "], aggs=" + agg.getAggCallList().size(), inputs);
        } else if (rel instanceof Sort sort) {
            String d = "keys=" + sort.getCollation().getFieldCollations().size();
            return new Node("SORT", sort.fetch != null ? d + ", fetch" : d, inputs);
        }
        return new Node("OTHER", rel.getClass().getSimpleName(), inputs);
    }

    private static String joinDetail(Join join) {
        JoinInfo info = join.analyzeCondition();
        List<String> leftNames = join.getLeft().getRowType().getFieldNames();
        List<String> rightNames = join.getRight().getRowType().getFieldNames();
        StringBuilder sb = new StringBuilder(join.getJoinType().name().toLowerCase());
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < info.leftKeys.size(); i++) {
            pairs.add(shorten(leftNames.get(info.leftKeys.get(i)))
                    + " = " + shorten(rightNames.get(info.rightKeys.get(i))));
        }
        if (!pairs.isEmpty()) {
            sb.append(", ").append(String.join(" AND ", pairs));
        }
        if (!info.isEqui()) {
            sb.append(", non-equi");
        }
        return sb.toString();
    }

    /**
     * {@code $<i>} ordinals → shortened field names of {@code rowType}, for readability.
     *
     * <p>{@code rex} is a Calcite condition rendered to text, which embeds user string literals
     * verbatim — a literal like {@code 'ref $9999999999'} matches the ordinal pattern below but
     * overflows {@code int}, so the parse is defensive and leaves that text untouched rather than
     * fail a plan over a rendering nicety. The mirror case is silent rather than a crash: a
     * literal digit run that is in range (e.g. {@code '$0'}) is indistinguishable from a real
     * ordinal and renders as a field name instead — cosmetic only, since {@code detail} is
     * human-oriented and nothing parses it back.
     */
    private static String readable(String rex, RelDataType rowType) {
        List<String> names = rowType.getFieldNames();
        Matcher m = Pattern.compile("\\$(\\d+)").matcher(rex);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name;
            try {
                int i = Integer.parseInt(m.group(1));
                name = i < names.size() ? shorten(names.get(i)) : m.group(0);
            } catch (NumberFormatException e) {
                name = m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(name));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Column names are full property IRIs; the last segment is the readable part. */
    private static String shorten(String name) {
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('#'));
        return cut >= 0 ? name.substring(cut + 1) : name;
    }

    public String toCompactString() {
        StringBuilder sb = new StringBuilder();
        append(root, 0, sb);
        return sb.toString();
    }

    private static void append(Node n, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(n.kind()).append('[').append(n.detail()).append(']').append('\n');
        for (Node in : n.inputs()) {
            append(in, depth + 1, sb);
        }
    }
}

package edu.upc.dtak.aran.operators.queryengine;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.SourceCatalog;

/**
 * The mapping catalog (§3.1 of the analysis).
 * Represents the derived GAV view definitions that the query engine unfolds against.
 */
@NullMarked
public record MappingCatalog(
        Map<String, IntegratedClass> classes,
        List<JoinProperty> joinProperties,
        List<ObjectProperty> objectProperties,
        List<ReferentialEdge> declaredEdges,
        @Nullable String schemaVersionId) {

    public MappingCatalog {
        // Defensive copy, same discipline as every other List-typed component here. Nullable
        // rather than rejected: the compact constructor also runs for callers built by
        // deserialisation or reflection that might not know about this Task-3 addition yet, and
        // "no DECLARED edges harvested" is a legitimate, common state, not an error.
        declaredEdges = declaredEdges == null ? List.of() : List.copyOf(declaredEdges);
    }

    /**
     * Convenience constructor for the pre-Task-3 shape: every caller written before Phase 4B
     * Task 3 built a catalog with no harvested {@code DECLARED} edges, so it defaults to an empty
     * list — {@link #referentialEdges()} then yields exactly the {@code HYPOTHESIS} edges it
     * always did, byte-identical to before this field existed. Dozens of tests across this module
     * construct a {@code MappingCatalog} with the 4-arg shape directly; this constructor is what
     * keeps every one of them compiling and behaving unchanged (see {@code SourceView}'s own
     * "convenience constructor for the pre-X shape" precedent, immediately below).
     */
    public MappingCatalog(Map<String, IntegratedClass> classes, List<JoinProperty> joinProperties,
                          List<ObjectProperty> objectProperties, @Nullable String schemaVersionId) {
        this(classes, joinProperties, objectProperties, List.of(), schemaVersionId);
    }

    public record IntegratedClass(String iri, List<SourceView> sources) {}

    /**
     * @param rowCount estimated total row count for this source, derived from column profiles
     *                 (cardinality / uniqueness for the most-unique column).  Zero means
     *                 "not profiled yet" and yields {@code Statistics.UNKNOWN} to Calcite.
     * @param profiles the list of column profiles (NDV, uniqueness, etc) from Freyja.
     *                 Each profile's {@code columnName} is in columnMap-VALUE namespace —
     *                 whatever names the wrapper view exposes (source-property IRIs on a real
     *                 bootstrapped catalog) — because every plan-time lookup
     *                 ({@code WrapperTable.profileAt} and its three siblings) resolves
     *                 ordinal → columnMap value → profile by name equality.
     *                 {@code MappingCatalogOperator.remapProfiles} renames freyja's raw
     *                 physical names into this namespace via {@code nx:sourceRef} at catalog
     *                 build; a profile it cannot rename stays raw and simply never matches
     *                 (no evidence, not an error).
     * @param srcClass the source class IRI this view wraps — what SourceCatalog names the
     *                 sources-tier table from. Empty string means "not recorded": the
     *                 convenience constructors default it, and a view without it simply gets
     *                 no sources-tier alias.
     * @param columnTypes global property IRI → XSD range IRI, for the columns QUERY 1 found an
     *                 {@code rdfs:range} triple for. A key missing here means "range unknown",
     *                 not "certainly untyped" — {@code XsdTypes.widenedType} folds the two the
     *                 same way (toward VARCHAR) on purpose: absence of type info must not
     *                 sharpen the type. The convenience constructors default it to {@code Map.of()}.
     * @param sourceRefs raw physical column name → source-property IRI, for the columns QUERY 1
     *                 found an {@code nx:sourceRef} triple for (the same fact
     *                 {@code MappingCatalogOperator.remapProfiles} already uses to rename freyja
     *                 profiles into columnMap-value namespace). Phase 3A's {@code SrcTable} names
     *                 a wrapper's {@code src_} relation columns from this map's keys. Empty means
     *                 "no raw names recorded" — a pre-V31 graph, or a class with no properties —
     *                 not an error. The convenience constructors default it to {@code Map.of()}.
     * @param expansionStatus {@code "EXPANDED"} once the wrapper SQL has been verified to plan
     *                 over its {@code src_} relation (Phase 3A's {@code WrapperExpansion} probe),
     *                 else {@code "OPAQUE"}. The convenience constructors — and the compact
     *                 constructor, for a canonical-constructor caller that passes {@code null} —
     *                 default it to {@code "OPAQUE"}: absence of a verified expansion must never
     *                 be mistaken for a verified one.
     */
    public record SourceView(String datasetId, String wrapperSql, Map<String, String> columnMap,
                             long rowCount, List<ColumnProfile> profiles, String srcClass,
                             Map<String, String> columnTypes, Map<String, String> sourceRefs,
                             String expansionStatus) {
        public SourceView {
            if (sourceRefs == null) {
                sourceRefs = Map.of();
            }
            if (expansionStatus == null) {
                expansionStatus = "OPAQUE";
            }
        }

        /** Convenience constructor for contexts where no profile is available (rowCount = 0). */
        public SourceView(String datasetId, String wrapperSql, Map<String, String> columnMap) {
            this(datasetId, wrapperSql, columnMap, 0L, List.of(), "");
        }

        public SourceView(String datasetId, String wrapperSql, Map<String, String> columnMap, long rowCount) {
            this(datasetId, wrapperSql, columnMap, rowCount, List.of(), "");
        }

        public SourceView(String datasetId, String wrapperSql, Map<String, String> columnMap,
                          long rowCount, List<ColumnProfile> profiles) {
            this(datasetId, wrapperSql, columnMap, rowCount, profiles, "");
        }

        /** Convenience constructor for the pre-XSD-typing shape: no ranges recorded yet. */
        public SourceView(String datasetId, String wrapperSql, Map<String, String> columnMap,
                          long rowCount, List<ColumnProfile> profiles, String srcClass) {
            this(datasetId, wrapperSql, columnMap, rowCount, profiles, srcClass, Map.of());
        }

        /** Convenience constructor for the pre-wrapper-expansion shape: no raw column refs or
         *  verified expansion status recorded yet. */
        public SourceView(String datasetId, String wrapperSql, Map<String, String> columnMap,
                          long rowCount, List<ColumnProfile> profiles, String srcClass,
                          Map<String, String> columnTypes) {
            this(datasetId, wrapperSql, columnMap, rowCount, profiles, srcClass, columnTypes,
                    Map.of(), "OPAQUE");
        }
    }

    public record JoinProperty(String iri, String leftClass, String rightClass, String leftCol, String rightCol) {}

    public record ObjectProperty(String iri, String domainClass, String rangeClass) {}

    /**
     * The trust tier of a {@link ReferentialEdge} — the spine of Phase 4B, and the fact the join
     * planner must be able to recover before it dares eliminate a join.
     *
     * <ul>
     *   <li>{@code HYPOTHESIS} — an accepted join. Join discovery found the two columns join well,
     *       but that is a guess, not a guarantee. A hypothesis edge may only <em>sharpen an
     *       estimate</em>; it must never drive join elimination (Phase 4B's binding rule).</li>
     *   <li>{@code DECLARED} — a foreign key the source schema itself declares (arrives in Task 3).</li>
     *   <li>{@code VERIFIED} — a constraint checked to actually hold against the data.</li>
     * </ul>
     *
     * <p>The ordering is deliberate — {@code HYPOTHESIS} is the least trusted. Any rule that gates
     * on trust treats an unknown or missing tier as {@code HYPOTHESIS}, so the safe default is
     * never to eliminate.
     */
    public enum Tier { HYPOTHESIS, DECLARED, VERIFIED }

    /**
     * A tier-tagged referential (FK → PK) edge between two integrated classes: every value of
     * {@code leftCols} on {@code leftClass} is asserted to appear in {@code rightCols} on
     * {@code rightClass}. Whether that assertion is a guess or a guarantee is what {@link #tier}
     * records.
     *
     * <p>{@code leftCols}/{@code rightCols} are full global property IRIs — the columns the
     * {@code int_} views expose, in the {@link JoinProperty} join-key sense — and are positionally
     * paired, so they must have equal length.
     */
    public record ReferentialEdge(String leftClass, List<String> leftCols,
                                  String rightClass, List<String> rightCols, Tier tier) {
        public ReferentialEdge {
            leftCols = List.copyOf(leftCols);
            rightCols = List.copyOf(rightCols);
            if (leftCols.size() != rightCols.size()) {
                throw new IllegalArgumentException(
                        "referential edge column arity mismatch: " + leftCols + " vs " + rightCols);
            }
        }
    }

    /**
     * The referential edges this catalog exposes to the query planner, tier-tagged.
     *
     * <p>Today: one {@link Tier#HYPOTHESIS} edge per accepted {@link JoinProperty}
     * ({@code leftClass → rightClass} on {@code [leftCol] → [rightCol]}). An accepted join is a
     * hypothesis, so the edge it yields is {@code HYPOTHESIS} — the query engine surfaces these as
     * Calcite referential constraints, but the tier keeps them estimate-only (a rule that could
     * eliminate a join must first check the tier and refuse a hypothesis).
     *
     * <p>{@code DECLARED} edges (Task 3): {@link #declaredEdges}, resolved once at catalog-build
     * time from harvested source-declared foreign keys ({@code DeclaredEdgeResolver}), concatenated
     * onto the {@code HYPOTHESIS} edges below. The list is freshly built per call.
     */
    public List<ReferentialEdge> referentialEdges() {
        List<ReferentialEdge> edges = new ArrayList<>();
        for (JoinProperty jp : joinProperties) {
            edges.add(new ReferentialEdge(
                    jp.leftClass(), List.of(jp.leftCol()),
                    jp.rightClass(), List.of(jp.rightCol()), Tier.HYPOTHESIS));
        }
        edges.addAll(declaredEdges);
        return edges;
    }

    /**
     * The sources-tier naming authority derived from this catalog: one table per
     * (dataset, source class) whose {@link SourceView} recorded its source class IRI.
     *
     * <p>Always computed over the <em>whole</em> catalog, never a pruned subset —
     * {@link SourceCatalog}'s collision suffixes depend on every claimant, so a name must not
     * change with which classes a particular statement happens to materialise.
     */
    public SourceCatalog sourceCatalog() {
        List<SourceCatalog.SourceRef> refs = new ArrayList<>();
        for (IntegratedClass cls : classes.values()) {
            for (SourceView sv : cls.sources()) {
                if (!sv.srcClass().isEmpty()) {
                    refs.add(new SourceCatalog.SourceRef(sv.datasetId(), sv.srcClass()));
                }
            }
        }
        return SourceCatalog.of(refs);
    }
}

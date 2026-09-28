package edu.upc.dtak.aran.operators.queryengine.plan;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.mapping.IntPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11 acceptance — AranStatistic returns profile-derived row counts.
 */
class AranStatisticTest {

    private static final String NS = "http://example.com/";

    private MappingCatalog.IntegratedClass classWithRowCounts(long... perSourceRowCounts) {
        List<MappingCatalog.SourceView> sources = new java.util.ArrayList<>();
        for (int i = 0; i < perSourceRowCounts.length; i++) {
            sources.add(new MappingCatalog.SourceView(
                    "ds" + i, "SELECT 1", Map.of(NS + "prop", "col"), perSourceRowCounts[i]));
        }
        return new MappingCatalog.IntegratedClass(NS + "MyClass", sources);
    }

    @Test
    void rowCountIsSumOfSourceRowCounts() {
        var cls = classWithRowCounts(500L);
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        assertThat(stat.getRowCount()).isEqualTo(500.0);
    }

    @Test
    void rowCountSumsAcrossMultipleSources() {
        // Two sources: 300 + 200 = 500 total integrated rows
        var cls = classWithRowCounts(300L, 200L);
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        assertThat(stat.getRowCount()).isEqualTo(500.0);
    }

    @Test
    void unprofiedSourceYieldsNullRowCount() {
        // rowCount=0 means "not profiled" → Calcite sees null (UNKNOWN)
        var cls = classWithRowCounts(0L);
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        assertThat(stat.getRowCount()).isNull();
    }

    @Test
    void isKeyReturnsFalseByDefault() {
        var cls = classWithRowCounts(1000L);
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isFalse();
    }

    @Test
    void unknownOrBasicStat() {
        var stat = new AranStatistic(42L, null);
        assertThat(stat.getRowCount()).isEqualTo(42.0);
        assertThat(stat.getReferentialConstraints()).isEmpty();
        assertThat(stat.getKeys()).isEmpty();
    }

    // ── Referential constraints from accepted joins (Phase 4B, Task 1) ────────

    private static final String ORDER = NS + "Order";
    private static final String CUSTOMER = NS + "Customer";
    private static final String O_REF = NS + "custRef";  // sorts after "amount" → ordinal 1
    private static final String O_AMT = NS + "amount";   // ordinal 0
    private static final String C_ID = NS + "id";        // sorts before "name" → ordinal 0
    private static final String C_NAME = NS + "name";    // ordinal 1

    /** Two single-source classes; {@code withJoin} adds the accepted Order.custRef → Customer.id join. */
    private static MappingCatalog orderCustomerCatalog(boolean withJoin) {
        var order = new MappingCatalog.IntegratedClass(ORDER, List.of(
                new MappingCatalog.SourceView("dso", "SELECT 1", Map.of(O_REF, "cref", O_AMT, "amt"))));
        var customer = new MappingCatalog.IntegratedClass(CUSTOMER, List.of(
                new MappingCatalog.SourceView("dsc", "SELECT 1", Map.of(C_ID, "cid", C_NAME, "cname"))));
        List<MappingCatalog.JoinProperty> joins = withJoin
                ? List.of(new MappingCatalog.JoinProperty(NS + "placedBy", ORDER, CUSTOMER, O_REF, C_ID))
                : List.of();
        return new MappingCatalog(Map.of(ORDER, order, CUSTOMER, customer), joins, List.of(), "v-test");
    }

    @Test
    @DisplayName("an accepted join exposes a HYPOTHESIS referential constraint on the referencing class")
    void exposesHypothesisRiForAcceptedJoin() {
        Map<String, Table> tables = AranSchema.buildTableMap(orderCustomerCatalog(true));

        assertThat(tables.get("int_Order").getStatistic().getReferentialConstraints())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getSourceQualifiedName()).containsExactly("int_Order");
                    assertThat(c.getTargetQualifiedName()).containsExactly("int_Customer");
                    // custRef is ordinal 1 in Order; id is ordinal 0 in Customer
                    assertThat(c.getColumnPairs()).containsExactly(IntPair.of(1, 0));
                    assertThat(TieredReferentialConstraint.tierOf(c))
                            .as("an accepted join is a hypothesis, never DECLARED")
                            .isEqualTo(Tier.HYPOTHESIS);
                });
    }

    @Test
    @DisplayName("the referenced (PK) side carries no constraint — the edge is directional")
    void referencedSideHasNoConstraint() {
        Map<String, Table> tables = AranSchema.buildTableMap(orderCustomerCatalog(true));
        assertThat(tables.get("int_Customer").getStatistic().getReferentialConstraints()).isEmpty();
    }

    @Test
    @DisplayName("no accepted join → no referential constraint on the class table")
    void noRiWithoutJoins() {
        Map<String, Table> tables = AranSchema.buildTableMap(orderCustomerCatalog(false));
        assertThat(tables.get("int_Order").getStatistic().getReferentialConstraints()).isEmpty();
    }

    @Test
    @DisplayName("the RI's source qualified name equals the RelOptTable's own qualified name")
    void riSourceNameMatchesRelOptTableQualifiedName() {
        // Task 4 matches an RI against the scanned table. The RI carries a single-element
        // registration name; this proves that equals what Calcite reports for the table, so the
        // match is sound rather than a lucky guess about root-schema prefixing.
        SchemaPlus schema = AranSchema.create(orderCustomerCatalog(true));
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);
        RelOptTable table = ((TableScan) builder.scan("int_Order").build()).getTable();

        assertThat(table.getReferentialConstraints())
                .singleElement()
                .satisfies(c -> assertThat(c.getSourceQualifiedName())
                        .isEqualTo(table.getQualifiedName()));
    }

    @Test
    @DisplayName("the tier survives Calcite's RelOptTable pass-through — the exact path Task 4 recovers it on")
    void tierSurvivesTheRelOptTableScanPath() {
        // The crux invariant Task 4 depends on: it recovers the tier via
        // scan.getTable().getReferentialConstraints() → TieredReferentialConstraint.tierOf(c), NOT
        // the direct statistic call every other tier test uses. This pins that the tier-carrying
        // instance survives Calcite's RelOptTable pass-through (RelOptTableImpl delegates straight to
        // the statistic) rather than being reconstructed into a plain RelReferentialConstraintImpl.
        //
        // The `isInstanceOf` assertion is the load-bearing one. tierOf() fail-safes any untagged
        // constraint to HYPOTHESIS, so asserting tierOf == HYPOTHESIS alone could NOT tell "the tier
        // survived" from "the tier was lost and defaulted" — both yield HYPOTHESIS. A Calcite bump
        // that reconstructs constraints would break the instanceof check here, catching a silent
        // downgrade that would otherwise just stop all future DECLARED-tier elimination from firing.
        SchemaPlus schema = AranSchema.create(orderCustomerCatalog(true));
        FrameworkConfig config = Frameworks.newConfigBuilder().defaultSchema(schema).build();
        RelBuilder builder = RelBuilder.create(config);
        RelOptTable table = ((TableScan) builder.scan("int_Order").build()).getTable();

        assertThat(table.getReferentialConstraints())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c)
                            .as("the tier-carrying subtype must survive the pass-through intact,"
                                    + " not downgrade to a plain RelReferentialConstraintImpl")
                            .isInstanceOf(TieredReferentialConstraint.class);
                    assertThat(TieredReferentialConstraint.tierOf(c))
                            .as("recovered on the real table path Task 4 uses")
                            .isEqualTo(Tier.HYPOTHESIS);
                });
    }

    // ── ProfileStatsSource.estimateRowCount unit tests ────────────────────────

    @Test
    void estimateRowCountFromUniqueKeyColumn() {
        // uniqueness = 1.0 → rowCount = cardinality = 1000
        ColumnProfile cp = profile(UUID.randomUUID(), "id", 1000L, 1.0);
        assertThat(ProfileStatsSource.estimateRowCount(List.of(cp))).isEqualTo(1000L);
    }

    @Test
    void estimateRowCountFromLowCardinalityColumn() {
        // cardinality=5, uniqueness=0.05 → rowCount ≈ 5/0.05 = 100
        ColumnProfile cp = profile(UUID.randomUUID(), "category", 5L, 0.05);
        assertThat(ProfileStatsSource.estimateRowCount(List.of(cp))).isEqualTo(100L);
    }

    @Test
    void estimateRowCountTakesMaxAcrossColumns() {
        UUID did = UUID.randomUUID();
        ColumnProfile key = profile(did, "id",       100L, 1.0);   // → 100
        ColumnProfile cat = profile(did, "category",  10L, 0.1);   // → 100
        ColumnProfile dup = profile(did, "constant",   1L, 0.01);  // → 100
        assertThat(ProfileStatsSource.estimateRowCount(List.of(key, cat, dup))).isEqualTo(100L);
    }

    @Test
    void estimateRowCountEmptyProfileReturnsZero() {
        assertThat(ProfileStatsSource.estimateRowCount(List.of())).isEqualTo(0L);
    }

    // ── getKeys() tests ──────────────────────────────────────────────────────

    @Test
    void getKeysReturnsUniqueColumnAsSingletonKey() {
        var cls = singleSourceClassWithProfile(1.0);
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        assertThat(stat.getKeys()).containsExactly(ImmutableBitSet.of(0));
    }

    @Test
    void getKeysEmptyWhenColumnNotUnique() {
        // uniqueness 0.5 -> not a key -> exploded/fan-out table declares no key
        var cls = singleSourceClassWithProfile(0.5);
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        assertThat(stat.getKeys()).isEmpty();
    }

    @Test
    void getKeysEmptyForMultiSourceClass() {
        // Two sources -> UNION ALL may break per-source uniqueness -> no declarable key
        ColumnProfile cp = profile(UUID.randomUUID(), "col", 1000L, 1.0);
        var s1 = new MappingCatalog.SourceView(
                "ds0", "SELECT 1", Map.of(NS + "prop", "col"), 1000L, List.of(cp));
        var s2 = new MappingCatalog.SourceView(
                "ds1", "SELECT 1", Map.of(NS + "prop", "col"), 1000L, List.of(cp));
        var cls = new MappingCatalog.IntegratedClass(NS + "MyClass", List.of(s1, s2));
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        assertThat(stat.getKeys()).isEmpty();
    }

    @Test
    void getKeysEmptyWhenNoProfiles() {
        // No profiles (e.g. federated without a snapshot) -> no key
        var cls = classWithRowCounts(1000L); // helper builds a source with empty profiles
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        assertThat(stat.getKeys()).isEmpty();
    }

    @Test
    void getKeysReturnsOnlyTheUniqueColumnOrdinalInMultiColumnClass() {
        // One source, two global properties. Sorted global-IRI order determines column
        // ordinals: NS+"idProp" ('i' < 'n') sorts before NS+"nameProp", so idProp is ordinal 0
        // and nameProp is ordinal 1. Only the idProp source column ("id_col") is unique.
        ColumnProfile idProfile   = profile(UUID.randomUUID(), "id_col",   1000L, 1.0);
        ColumnProfile nameProfile = profile(UUID.randomUUID(), "name_col", 1000L, 0.5);
        var source = new MappingCatalog.SourceView(
                "ds0", "SELECT 1",
                Map.of(NS + "idProp", "id_col", NS + "nameProp", "name_col"),
                1000L, List.of(idProfile, nameProfile));
        var cls = new MappingCatalog.IntegratedClass(NS + "MyClass", List.of(source));
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();

        // Correct ordinal SUBSET: exactly {0} (idProp), not {0,1} and not {1}.
        assertThat(stat.getKeys()).containsExactly(ImmutableBitSet.of(0));
    }

    @Test
    void getKeysAgreesWithIsKeyOnSameOrdinals() {
        var cls = singleSourceClassWithProfile(1.0);
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();
        // Every ordinal getKeys() reports as a singleton key must also satisfy isKey().
        for (ImmutableBitSet key : stat.getKeys()) {
            assertThat(stat.isKey(key))
                    .as("getKeys ordinal %s must agree with isKey", key)
                    .isTrue();
        }
    }

    // ── Phase 4B fix: HARVESTED uniqueness is approximate (pg_stats), never a verified key ──

    @Test
    @DisplayName("a HARVESTED profile at key-like uniqueness is NOT reported as a key by getKeys()/isKey()")
    void harvestedProfileAtKeyLikeUniquenessIsNotAKey() {
        // uniqueness 1.0 would satisfy the 0.9999 threshold, but pg_stats.n_distinct is an
        // ANALYZE-based estimate, not freyja's exact COUNT(DISTINCT) — must never be read as
        // "verified key" regardless of how close to 1.0 it lands.
        ColumnProfile harvested = harvestedProfile("col", 1000L, 1.0);
        var source = new MappingCatalog.SourceView(
                "ds0", "SELECT 1", Map.of(NS + "prop", "col"), 1000L, List.of(harvested));
        var cls = new MappingCatalog.IntegratedClass(NS + "MyClass", List.of(source));
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();

        assertThat(stat.getKeys()).as("HARVESTED must never surface as a declared key").isEmpty();
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isFalse();
    }

    @Test
    @DisplayName("a FEDERATED_LIVE profile at key-like uniqueness is NOT a key — profile-time truth decays on a live source")
    void federatedLiveAtKeyLikeUniquenessIsNotAKey() {
        // Gap 2 follow-up: the live-suite profile measured uniqueness 1.0 exactly, but it
        // describes the source at profile time; the source keeps committing afterwards, and join
        // elimination licensed by a stale key returns wrong rows. Same posture as HARVESTED.
        ColumnProfile sample = federatedLiveProfile("col", 1000L, 1.0);
        var source = new MappingCatalog.SourceView(
                "ds0", "SELECT 1", Map.of(NS + "prop", "col"), 1000L, List.of(sample));
        var cls = new MappingCatalog.IntegratedClass(NS + "MyClass", List.of(source));
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();

        assertThat(stat.getKeys()).as("FEDERATED_LIVE must never surface as a key").isEmpty();
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isFalse();
    }

    @Test
    @DisplayName("a SNAPSHOT profile at the same uniqueness still IS a key — no regression from the HARVESTED guard")
    void snapshotProfileAtSameUniquenessStillIsAKey() {
        var cls = singleSourceClassWithProfile(1.0); // SNAPSHOT by the convenience constructor
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();

        assertThat(stat.getKeys()).containsExactly(ImmutableBitSet.of(0));
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isTrue();
    }

    // ── T1.1 (audit PROFILING-12 + WRAPPER_VERIFY-2): row order and exactness of key evidence ──

    @Test
    @DisplayName("a HARVESTED row listed before the SNAPSHOT row for the same column must not shadow the key")
    void harvestedRowBeforeSnapshotRowDoesNotShadowTheKey() {
        // Once V36 lets SNAPSHOT and HARVESTED generations coexist for one physical column,
        // key detection must not depend on which row the list yields first: the exact SNAPSHOT
        // evidence wins whether it comes before or after the approximate HARVESTED row.
        ColumnProfile harvested = harvestedProfile("col", 1000L, 1.0);
        ColumnProfile snapshot  = profile(UUID.randomUUID(), "col", 1000L, 1.0);
        var source = new MappingCatalog.SourceView(
                "ds0", "SELECT 1", Map.of(NS + "prop", "col"), 1000L, List.of(harvested, snapshot));
        var cls = new MappingCatalog.IntegratedClass(NS + "MyClass", List.of(source));
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();

        assertThat(stat.getKeys()).containsExactly(ImmutableBitSet.of(0));
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isTrue();
    }

    @Test
    @DisplayName("near-unique is not a key: uniqueness 0.99995 (a real duplicate in 20k rows) must not authorize elimination")
    void nearUniqueSnapshotProfileIsNotAKey() {
        // freyja's uniqueness is exact cardinality/row_count; anything below 1.0 means real
        // duplicates exist and join elimination would change row multiplicity.
        var cls = singleSourceClassWithProfile(0.99995);
        var stat = (AranStatistic) new IntegratedClassTable(cls).getStatistic();

        assertThat(stat.getKeys()).isEmpty();
        assertThat(stat.isKey(ImmutableBitSet.of(0))).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ColumnProfile profile(UUID datasetId, String columnName,
                                         long cardinality, double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), datasetId, columnName, 0, "VARCHAR",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    /** A Phase 4B HARVESTED-subject profile (pg_stats-derived, approximate — see isSingleColumnKey). */
    private static ColumnProfile harvestedProfile(String columnName, long cardinality, double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 0, "UNKNOWN",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                "HARVESTED", null);
    }

    /** A profile the federated compile-tail flow persisted — bounded staged read of a live source. */
    private static ColumnProfile federatedLiveProfile(String columnName, long cardinality,
                                                        double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), columnName, 0, "VARCHAR",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                ColumnProfile.SUBJECT_FEDERATED_LIVE, NS + "MyClass");
    }

    /**
     * Single-source class with one global property whose source column has the given
     * uniqueness profile. Column ordinal 0 corresponds to {@code NS+"prop"}.
     */
    private MappingCatalog.IntegratedClass singleSourceClassWithProfile(double uniqueness) {
        ColumnProfile cp = profile(UUID.randomUUID(), "col", 1000L, uniqueness);
        var source = new MappingCatalog.SourceView(
                "ds0", "SELECT 1", Map.of(NS + "prop", "col"), 1000L, List.of(cp));
        return new MappingCatalog.IntegratedClass(NS + "MyClass", List.of(source));
    }
}

package edu.upc.dtak.aran.operators.queryengine;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKey;
import edu.upc.dtak.aran.core.domain.DeclaredForeignKeyRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.operators.queryengine.MappingCatalog.Tier;
import edu.upc.dtak.aran.operators.queryengine.plan.ProfileStatsSource;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MappingCatalogTest {

    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String INTEGRATED_RESOURCE = NS + "IntegratedResource";
    private static final String JOIN_PROPERTY = NS + "JoinProperty";
    private static final String JOIN_ON_LEFT = NS + "joinOnLeft";
    private static final String JOIN_ON_RIGHT = NS + "joinOnRight";
    private static final String PROVENANCE = NS + "provenance";
    private static final String WRAPPER_SQL = NS + "wrapperSql";
    private static final String DATASET_ID = NS + "datasetId";
    private static final String SOURCE_REF = NS + "sourceRef";
    private static final String WRAPPER_EXPANSION = NS + "wrapperExpansion";

    @Test
    void testMappingCatalogExtraction() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system")
        );

        Model m = ModelFactory.createDefaultModel();
        
        // 1. Integrated Artworks
        Resource iArtworks = m.createResource("urn:IArtworks");
        m.add(iArtworks, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iArtworks, RDF.type, RDFS.Class);

        Resource cmoaArtworks = m.createResource("urn:cmoa:Artworks");
        m.add(cmoaArtworks, RDFS.subClassOf, iArtworks);
        m.add(cmoaArtworks, m.createProperty(WRAPPER_SQL), "SELECT * FROM cmoa_artworks");
        m.add(cmoaArtworks, m.createProperty(PROVENANCE), "cmoa-dataset-1");

        Resource cmoaTitle = m.createResource("urn:cmoa:title");
        m.add(cmoaTitle, RDFS.domain, cmoaArtworks);
        m.add(cmoaTitle, RDFS.range, XSD.xstring); // leaf datatype property, as real bootstrap output has
        Resource iTitle = m.createResource("urn:ITitle");
        m.add(iTitle, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(cmoaTitle, RDFS.subPropertyOf, iTitle);

        Resource cmoaLocalProp = m.createResource("urn:cmoa:localprop");
        m.add(cmoaLocalProp, RDFS.domain, cmoaArtworks); // no subPropertyOf => source-local
        m.add(cmoaLocalProp, RDFS.range, XSD.xstring);

        // A non-string XSD range, to prove QUERY 1 carries the range through as-is rather than
        // just detecting "is this xsd:" (the FILTER already covers that) — XsdTypes (Task 2's
        // companion) is what turns this IRI into a SQL type; the catalog's job is only to keep
        // what QUERY 1 already reads.
        Resource cmoaYear = m.createResource("urn:cmoa:year");
        m.add(cmoaYear, RDFS.domain, cmoaArtworks);
        m.add(cmoaYear, RDFS.range, XSD.integer);
        Resource iYear = m.createResource("urn:IYear");
        m.add(iYear, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(cmoaYear, RDFS.subPropertyOf, iYear);

        // F13: an unaligned OBJECT property —
        // domain is this class, but range is another rdfs:Class, not an xsd:
        // datatype, exactly the shape JsonBootstrap gives a nested JSON object
        // (e.g. semantic_papers.json's `venue: {code, label}`). WrapperSqlBuilder
        // never emits a SQL column for this shape, so QUERY 1 must not surface it
        // as a mapped column either — the two would disagree about what the
        // wrapper view actually exposes, and the int_ view's UNION ALL would
        // reference a column that does not exist.
        Resource cmoaVenue = m.createResource("urn:cmoa:venue");
        m.add(cmoaVenue, RDFS.domain, cmoaArtworks);
        Resource cmoaVenueClass = m.createResource("urn:cmoa:Venue");
        m.add(cmoaVenueClass, RDF.type, RDFS.Class);
        m.add(cmoaVenue, RDFS.range, cmoaVenueClass); // object property: range is a class, not xsd:*

        // 2. Integrated Artist (3 sources for I4 merge)
        Resource iArtist = m.createResource("urn:IArtist");
        m.add(iArtist, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iArtist, RDF.type, RDFS.Class);

        Resource cmoaArtist = m.createResource("urn:cmoa:Artist");
        m.add(cmoaArtist, RDFS.subClassOf, iArtist);
        m.add(cmoaArtist, m.createProperty(WRAPPER_SQL), "SELECT * FROM cmoa_artist");

        Resource momaArtist = m.createResource("urn:moma:Artist");
        m.add(momaArtist, RDFS.subClassOf, iArtist);
        m.add(momaArtist, m.createProperty(WRAPPER_SQL), "SELECT * FROM moma_artist");

        Resource tateArtist = m.createResource("urn:tate:Artist");
        m.add(tateArtist, RDFS.subClassOf, iArtist);
        m.add(tateArtist, m.createProperty(WRAPPER_SQL), "SELECT * FROM tate_artist");

        // 3. Join in the two-node form of Flores et al. Fig. 14 (see SchemaIntegrator).
        //    P_s = hasArtist  — the join object property (Query 2 reads this, unchanged).
        //    P_l = hasArtistJoin — the datatype-property parent the two join columns
        //          become sub-properties of. P_l is an IntegratedResource, so the join
        //          columns deliberately surface as mapped columns of P_l in the int_
        //          views (Query-1 "leakage", accepted as correct: the join key is a
        //          real, queryable column).
        Resource pS = m.createResource("urn:hasArtist");
        m.add(pS, RDF.type, m.createResource(JOIN_PROPERTY));
        m.add(pS, RDFS.domain, cmoaArtworks);
        m.add(pS, RDFS.range, cmoaArtist);
        Resource artistIdCol = m.createResource("urn:cmoa:artist_id");
        Resource idCol = m.createResource("urn:cmoa:id");
        m.add(pS, m.createProperty(JOIN_ON_LEFT), artistIdCol);
        m.add(pS, m.createProperty(JOIN_ON_RIGHT), idCol);

        Resource pL = m.createResource("urn:hasArtistJoin");
        m.add(pL, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(pL, RDF.type, RDF.Property);
        m.add(pL, m.createProperty(JOIN_PROPERTY), pS);
        // The join columns are datatype properties of their source classes and are
        // sub-properties of P_l. Real bootstrap output always gives a raw leaf
        // column (even one later used as a join key) an xsd: range — see F13.
        m.add(artistIdCol, RDFS.domain, cmoaArtworks);
        m.add(artistIdCol, RDFS.range, XSD.xstring);
        m.add(artistIdCol, RDFS.subPropertyOf, pL);
        m.add(idCol, RDFS.domain, cmoaArtist);
        m.add(idCol, RDFS.range, XSD.xstring);
        m.add(idCol, RDFS.subPropertyOf, pL);

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, null);
        MappingCatalog catalog = operator.getCatalog(tenant);

        assertThat(catalog.classes()).containsKey("urn:IArtworks");
        MappingCatalog.IntegratedClass artworks = catalog.classes().get("urn:IArtworks");
        assertThat(artworks.sources()).hasSize(1);
        MappingCatalog.SourceView artworksSource = artworks.sources().get(0);
        System.out.println("DEBUG COLUMNS: " + artworksSource.columnMap());
        assertThat(artworksSource.datasetId()).isEqualTo("cmoa-dataset-1");
        // The sources tier names its tables from the source class IRI, so the catalog must
        // carry it — QUERY 1 always bound ?srcClass; the record used to discard it.
        assertThat(artworksSource.srcClass()).isEqualTo("urn:cmoa:Artworks");
        assertThat(artworksSource.wrapperSql()).isEqualTo("SELECT * FROM cmoa_artworks");
        assertThat(artworksSource.columnMap()).containsEntry("urn:ITitle", "urn:cmoa:title");
        assertThat(artworksSource.columnMap()).containsEntry("urn:cmoa:localprop", "urn:cmoa:localprop");
        // The range QUERY 1 read for a column survives into the catalog, keyed by the same
        // global property IRI columnMap uses — this is the fact Task 2 adds.
        assertThat(artworksSource.columnTypes()).containsEntry("urn:IYear", XSD.integer.getURI());
        // Accepted Query-1 leakage: the join key surfaces as a mapped column of P_l.
        assertThat(artworksSource.columnMap()).containsEntry("urn:hasArtistJoin", "urn:cmoa:artist_id");
        // F13: an object property (range is a class, not xsd:*) must NOT surface as a
        // mapped column — WrapperSqlBuilder.leafPropertiesOf never emits a SQL column
        // for it, so a columnMap entry here would make DuckDbExecutor's UNION ALL
        // projection reference a column the wrapper view does not define.
        assertThat(artworksSource.columnMap()).doesNotContainKey("urn:cmoa:venue");
        assertThat(artworksSource.columnMap()).doesNotContainValue("urn:cmoa:venue");
        // Same reasoning applies to the raw join object-property itself (`hasArtist`,
        // range = cmoaArtist, a class) — it must not leak into columnMap either; only
        // its P_l datatype sub-properties (asserted above) do.
        assertThat(artworksSource.columnMap()).doesNotContainKey("urn:hasArtist");
        assertThat(artworksSource.columnMap()).doesNotContainValue("urn:hasArtist");
        // No nx:sourceRef triple anywhere on cmoaArtworks's properties, and no
        // nx:wrapperExpansion triple on cmoaArtworks itself — absence must default rather than
        // error, same rule XsdTypes.widenedType already applies to a missing columnTypes entry.
        assertThat(artworksSource.sourceRefs()).isEmpty();
        assertThat(artworksSource.expansionStatus()).isEqualTo("OPAQUE");

        assertThat(catalog.classes()).containsKey("urn:IArtist");
        MappingCatalog.IntegratedClass artist = catalog.classes().get("urn:IArtist");
        assertThat(artist.sources()).hasSize(3);

        assertThat(catalog.joinProperties()).hasSize(1);
        MappingCatalog.JoinProperty joinProp = catalog.joinProperties().get(0);
        assertThat(joinProp.iri()).isEqualTo("urn:hasArtist");
        assertThat(joinProp.leftClass()).isEqualTo("urn:cmoa:Artworks");
        assertThat(joinProp.rightClass()).isEqualTo("urn:cmoa:Artist");
        // leftCol/rightCol must name the column the int_ views actually expose. For the
        // two-node join form that is P_l — the same IRI the columnMap above is keyed by — and
        // NOT the raw joinOnLeft/joinOnRight source properties. Asserting the source IRIs here
        // (as this test used to) contradicted the columnMap assertion three lines up and made
        // the planner look for a field the int_ view has no column for. Both join keys are
        // sub-properties of the same P_l, so each side maps its own source column into that one
        // global column and the join is left.P_l = right.P_l. Caught by SourcesToOqlEndToEndTest.
        assertThat(joinProp.leftCol()).isEqualTo("urn:hasArtistJoin");
        assertThat(joinProp.rightCol()).isEqualTo("urn:hasArtistJoin");
    }

    @Test
    @DisplayName("nx:sourceRef triples populate SourceView.sourceRefs with raw column name -> source property IRI")
    void sourceRefsCapturesRawColumnNameToPropertyIri() {
        // Phase 3A needs the raw physical column name recoverable from the catalog (SrcTable,
        // Task 2, names a wrapper's src_ relation columns from it). QUERY 1 has bound ?sourceRef
        // since V31 to remap freyja profiles; this test pins that the same fact also
        // lands on the SourceView itself, not just the ephemeral map that fed remapProfiles.
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system")
        );

        Model m = ModelFactory.createDefaultModel();

        Resource iWidget = m.createResource("urn:IWidget");
        m.add(iWidget, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iWidget, RDF.type, RDFS.Class);

        Resource srcWidget = m.createResource("urn:src:Widget");
        m.add(srcWidget, RDFS.subClassOf, iWidget);
        m.add(srcWidget, m.createProperty(WRAPPER_SQL), "SELECT * FROM widgets");

        Resource srcYear = m.createResource("urn:src:Widget/year");
        m.add(srcYear, RDFS.domain, srcWidget);
        m.add(srcYear, RDFS.range, XSD.integer);
        m.add(srcYear, m.createProperty(SOURCE_REF), "year");

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, null);
        MappingCatalog catalog = operator.getCatalog(tenant);

        MappingCatalog.SourceView source = catalog.classes().get("urn:IWidget").sources().get(0);
        assertThat(source.sourceRefs()).containsEntry("year", "urn:src:Widget/year");
    }

    @Test
    @DisplayName("nx:wrapperExpansion \"EXPANDED\" on the source class yields SourceView.expansionStatus == EXPANDED")
    void expansionStatusReflectsExplicitWrapperExpansionTriple() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system")
        );

        Model m = ModelFactory.createDefaultModel();

        Resource iWidget = m.createResource("urn:IWidget");
        m.add(iWidget, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iWidget, RDF.type, RDFS.Class);

        // wrapperExpansion is a per-source-class fact, alongside wrapperSql/datasetId/provenance
        // — it is not attached to any individual property.
        Resource srcWidget = m.createResource("urn:src:Widget");
        m.add(srcWidget, RDFS.subClassOf, iWidget);
        m.add(srcWidget, m.createProperty(WRAPPER_SQL), "SELECT * FROM widgets");
        m.add(srcWidget, m.createProperty(WRAPPER_EXPANSION), "EXPANDED");

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, null);
        MappingCatalog catalog = operator.getCatalog(tenant);

        MappingCatalog.SourceView source = catalog.classes().get("urn:IWidget").sources().get(0);
        assertThat(source.expansionStatus()).isEqualTo("EXPANDED");
    }

    @Test
    @DisplayName("columnTypes, sourceRefs and expansionStatus survive the profile-stats reconstruction, not just the initial SPARQL pass")
    void sourceMetadataSurvivesProfileStatsReconstruction() {
        // The bug this pins: MappingCatalogOperator rebuilds each SourceView a second time (to
        // carry the freshly-read row count/profiles) whenever profiling data exists for its
        // dataset — the common production case. That rebuild used to call the SourceView
        // constructor that predates columnTypes, which defaults it to empty, silently discarding
        // every XSD range QUERY 1 had just harvested. testMappingCatalogExtraction() above never
        // exercises this: it always passes profileStats = null, so the rebuild never runs there.
        // sourceRefs and expansionStatus (Task 1, phase 3A) are exposed to exactly the same risk:
        // a constructor call at the reconstruction site that doesn't thread them through would
        // silently wipe them back to Map.of()/"OPAQUE" the same way.
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system")
        );

        // ProfileStatsSource.statsFor only looks a dataset up when its id parses as a UUID (a
        // real profiled dataset's id always does) — unlike the other test's "cmoa-dataset-1".
        UUID datasetId = UUID.randomUUID();

        Model m = ModelFactory.createDefaultModel();

        Resource iWidget = m.createResource("urn:IWidget");
        m.add(iWidget, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iWidget, RDF.type, RDFS.Class);

        Resource srcWidget = m.createResource("urn:src:Widget");
        m.add(srcWidget, RDFS.subClassOf, iWidget);
        m.add(srcWidget, m.createProperty(WRAPPER_SQL), "SELECT * FROM widgets");
        m.add(srcWidget, m.createProperty(DATASET_ID), datasetId.toString());

        Resource srcYear = m.createResource("urn:src:year");
        m.add(srcYear, RDFS.domain, srcWidget);
        m.add(srcYear, RDFS.range, XSD.integer);
        Resource iYear = m.createResource("urn:IWidgetYear");
        m.add(iYear, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(srcYear, RDFS.subPropertyOf, iYear);

        // A second, unaligned property carrying nx:sourceRef, and an explicit wrapperExpansion
        // on the class — both non-default values, so a reconstruction that silently defaults
        // them (the wipe bug this test pins) is distinguishable from one that doesn't. Left off
        // srcYear/iYear on purpose: that pair stays the no-sourceRef pass-through case the
        // profiles assertion below depends on (see its comment).
        Resource srcCode = m.createResource("urn:src:code");
        m.add(srcCode, RDFS.domain, srcWidget);
        m.add(srcCode, RDFS.range, XSD.xstring);
        m.add(srcCode, m.createProperty(SOURCE_REF), "code_col");
        m.add(srcWidget, m.createProperty(WRAPPER_EXPANSION), "EXPANDED");

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        // A real ProfileStatsSource over a mocked repository — it is a concrete final class, not
        // an interface, so this mirrors ProfileStatsSourceTest's own wiring rather than mocking
        // it directly. One non-empty profile makes MappingCatalogOperator's
        // "if (!stats.isEmpty())" reconstruction branch actually run.
        DatasetRepository repo = mock(DatasetRepository.class);
        when(repo.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(
                dataset(datasetId, List.of(columnProfile("year", 500, 1.0)))));
        ProfileStatsSource profileStats = new ProfileStatsSource(repo);

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, profileStats);
        MappingCatalog catalog = operator.getCatalog(tenant);

        MappingCatalog.IntegratedClass widget = catalog.classes().get("urn:IWidget");
        assertThat(widget.sources()).hasSize(1);
        MappingCatalog.SourceView source = widget.sources().get(0);

        // Proves the reconstruction branch actually ran (not skipped): rowCount moved off QUERY
        // 1's placeholder of 0 to the profile-derived estimate (cardinality / uniqueness = 500).
        assertThat(source.rowCount()).isEqualTo(500L);
        // The regression this test exists for.
        assertThat(source.columnTypes()).containsEntry("urn:IWidgetYear", XSD.integer.getURI());
        // No nx:sourceRef on srcYear in this model, so the profile-rename pass must leave the
        // raw name untouched — no-evidence pass-through, never an error (see remapProfiles).
        assertThat(source.profiles())
                .extracting(ColumnProfile::columnName)
                .containsExactly("year");
        // The two facts this test was extended for: both survive the reconstruction rebuild
        // rather than reverting to their Map.of()/"OPAQUE" defaults.
        assertThat(source.sourceRefs()).containsEntry("code_col", "urn:src:code");
        assertThat(source.expansionStatus()).isEqualTo("EXPANDED");
    }

    @Test
    @DisplayName("profiles are remapped into columnMap-value namespace via nx:sourceRef, so plan-time NDV/key lookups resolve")
    void profilesRemapToColumnMapValueNamespaceViaSourceRef() {
        // The bug this pins: in production, columnMap values are full source-property IRIs
        // (QUERY 1 binds ?srcProp, minted by BootstrapEngine.sourcePropertyIri), while freyja
        // profiles carry the RAW physical column name. The four plan-time resolution sites
        // (WrapperTable.profileAt, IntegratedClassTable.soleSourceProfileAt,
        // WrapperStatistic/AranStatistic.isSingleColumnKey) all match
        // cp.columnName().equals(columnMap value) — so against a real bootstrapped catalog no
        // profile ever resolved and NDV/null-fraction/key evidence silently never reached the
        // planner; only rowCount (name-free) survived. Every other test in the suite uses raw
        // names as columnMap values, which is why it stayed green. The graph carries the raw
        // name as nx:sourceRef on every bootstrapped leaf property; the catalog build must use
        // it to rename each profile into whatever namespace columnMap's values are in.
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system")
        );

        UUID datasetId = UUID.randomUUID();

        Model m = ModelFactory.createDefaultModel();

        Resource iWidget = m.createResource("urn:IWidget");
        m.add(iWidget, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iWidget, RDF.type, RDFS.Class);

        Resource srcWidget = m.createResource("urn:src:Widget");
        m.add(srcWidget, RDFS.subClassOf, iWidget);
        m.add(srcWidget, m.createProperty(WRAPPER_SQL), "SELECT * FROM widgets");
        m.add(srcWidget, m.createProperty(DATASET_ID), datasetId.toString());

        // Production shape: property IRI ≠ physical column name, linked by nx:sourceRef.
        Resource srcYear = m.createResource("urn:src:Widget/year");
        m.add(srcYear, RDFS.domain, srcWidget);
        m.add(srcYear, RDFS.range, XSD.integer);
        m.add(srcYear, m.createProperty(SOURCE_REF), "year");
        // A second, unaligned property — pass-through columnMap key, same remap rule.
        Resource srcName = m.createResource("urn:src:Widget/name");
        m.add(srcName, RDFS.domain, srcWidget);
        m.add(srcName, RDFS.range, XSD.xstring);
        m.add(srcName, m.createProperty(SOURCE_REF), "name");

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        DatasetRepository repo = mock(DatasetRepository.class);
        when(repo.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(
                dataset(datasetId, List.of(
                        columnProfile("year", 500, 1.0),
                        columnProfile("name", 480, 0.96)))));
        ProfileStatsSource profileStats = new ProfileStatsSource(repo);

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, profileStats);
        MappingCatalog catalog = operator.getCatalog(tenant);

        MappingCatalog.SourceView source = catalog.classes().get("urn:IWidget").sources().get(0);
        // Both properties are unaligned, so columnMap maps each IRI to itself — the values are
        // IRIs, exactly what the wrapper view's columns are named in production.
        assertThat(source.columnMap()).containsEntry("urn:src:Widget/year", "urn:src:Widget/year");

        // The invariant the four plan-time resolution sites depend on: for every mapped global
        // column, a profile is findable under the columnMap VALUE for that column.
        assertThat(source.profiles())
                .extracting(ColumnProfile::columnName)
                .containsExactlyInAnyOrder("urn:src:Widget/year", "urn:src:Widget/name");
        // The remap must not disturb the name-free row-count estimate.
        assertThat(source.rowCount()).isEqualTo(500L);
        // And the metric payload rides along with the renamed profile.
        assertThat(source.profiles())
                .filteredOn(p -> p.columnName().equals("urn:src:Widget/year"))
                .singleElement()
                .satisfies(p -> assertThat(p.cardinality()).isEqualTo(500L));
        // Phase 3B: a rename must not reset subject/classIri to their compact-constructor
        // defaults — withColumnName copies every component, these two included.
        assertThat(source.profiles())
                .as("the rename preserves subject/classIri rather than resetting them")
                .extracting(ColumnProfile::subject, ColumnProfile::classIri)
                .containsOnly(org.assertj.core.groups.Tuple.tuple("SNAPSHOT", null));
    }

    @Test
    @DisplayName("a column with both an exact SNAPSHOT profile and a HARVESTED estimate keeps only the exact one")
    void exactProfileWinsOverAHarvestedEstimateForTheSameColumn() {
        // Reachable only since Flyway V36 (V40/V44) widened column_profile's key to include
        // `subject`: before it, a HARVESTED row and a SNAPSHOT row for one column could not
        // coexist at all, so this pair never arrived here. Every plan-time lookup below the
        // catalog resolves a column to a profile by FIRST NAME MATCH, and AranStatistic's key
        // detection returns false outright on a HARVESTED row — so keeping both would make
        // "is this column a verified key" depend on which row the list happens to hold first.
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(),
                        UUID.randomUUID(), java.time.Instant.now(), "system"));

        UUID datasetId = UUID.randomUUID();
        Model m = ModelFactory.createDefaultModel();

        Resource iWidget = m.createResource("urn:IWidget");
        m.add(iWidget, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iWidget, RDF.type, RDFS.Class);

        Resource srcWidget = m.createResource("urn:src:Widget");
        m.add(srcWidget, RDFS.subClassOf, iWidget);
        m.add(srcWidget, m.createProperty(WRAPPER_SQL), "SELECT * FROM widgets");
        m.add(srcWidget, m.createProperty(DATASET_ID), datasetId.toString());

        Resource srcId = m.createResource("urn:src:Widget/id");
        m.add(srcId, RDFS.domain, srcWidget);
        m.add(srcId, RDFS.range, XSD.integer);
        m.add(srcId, m.createProperty(SOURCE_REF), "id");
        // Harvested but never profiled by freyja — the evidence a federated table with no snapshot
        // has, and the case this drop must NOT reach.
        Resource srcRegion = m.createResource("urn:src:Widget/region");
        m.add(srcRegion, RDFS.domain, srcWidget);
        m.add(srcRegion, RDFS.range, XSD.xstring);
        m.add(srcRegion, m.createProperty(SOURCE_REF), "region");

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        DatasetRepository repo = mock(DatasetRepository.class);
        // HARVESTED first in the list on purpose: under first-name-match resolution that is the
        // order which used to lose, so this fails loudly if the preference is ever removed.
        when(repo.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(
                dataset(datasetId, List.of(
                        harvestedProfile("id", 880, 0.98),
                        columnProfile("id", 900, 1.0),
                        harvestedProfile("region", 4, 0.004)))));

        MappingCatalogOperator operator =
                new MappingCatalogOperator(graphStore, new ProfileStatsSource(repo));
        MappingCatalog.SourceView source =
                operator.getCatalog(tenant).classes().get("urn:IWidget").sources().get(0);

        assertThat(source.profiles())
                .extracting(ColumnProfile::columnName, ColumnProfile::subject, ColumnProfile::cardinality)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("urn:src:Widget/id", "SNAPSHOT", 900L),
                        org.assertj.core.groups.Tuple.tuple("urn:src:Widget/region", "HARVESTED", 4L));
    }

    private static ColumnProfile harvestedProfile(String column, long cardinality, double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "UNKNOWN",
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

    private static ColumnProfile columnProfile(String column, long cardinality, double uniqueness) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of());
    }

    private static ColumnProfile wrapperOutputProfile(String column, long cardinality, double uniqueness,
                                                       String classIri) {
        return new ColumnProfile(
                UUID.randomUUID(), UUID.randomUUID(), column, 0, "VARCHAR",
                cardinality, null, uniqueness, 0.0,
                0.0, 0L, 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0.0,
                0L, 0, 0, 0.0, 0.0,
                null, null,
                false, false,
                List.of(), List.of(),
                "WRAPPER_OUTPUT", classIri);
    }

    private static Dataset dataset(UUID id, List<ColumnProfile> columns) {
        return new Dataset(id, UUID.randomUUID(), "ds", "", null, columns,
                null, null);
    }

    // ── Phase 3B (Task 3): selection rule — OPAQUE prefers WRAPPER_OUTPUT, EXPANDED always SNAPSHOT ──

    @Test
    @DisplayName("an OPAQUE view with its own WRAPPER_OUTPUT profiles uses them as-is — no remap, rowCount from that set alone")
    void opaqueViewWithWrapperOutputProfilesUsesThemAsIs() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system"));

        UUID datasetId = UUID.randomUUID();
        Model m = ModelFactory.createDefaultModel();

        Resource iWidget = m.createResource("urn:IWidget");
        m.add(iWidget, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iWidget, RDF.type, RDFS.Class);

        Resource srcWidget = m.createResource("urn:src:Widget");
        m.add(srcWidget, RDFS.subClassOf, iWidget);
        m.add(srcWidget, m.createProperty(WRAPPER_SQL), "SELECT * FROM widgets"); // OPAQUE by default
        m.add(srcWidget, m.createProperty(DATASET_ID), datasetId.toString());

        // A sourceRef exists for "year" — if the SNAPSHOT/remap path were (wrongly) taken, a
        // profile named "year" would be renamed to this IRI. The assertion below proves it is not.
        Resource srcYear = m.createResource("urn:src:Widget/year");
        m.add(srcYear, RDFS.domain, srcWidget);
        m.add(srcYear, RDFS.range, XSD.integer);
        m.add(srcYear, m.createProperty(SOURCE_REF), "year");

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        // A stale/irrelevant SNAPSHOT profile also lives on the dataset (e.g. bootstrap-time
        // auto-profiling of the raw snapshot) alongside the class's own WRAPPER_OUTPUT evidence —
        // proves the OPAQUE-with-evidence branch prefers the latter and ignores the former.
        ColumnProfile snapshot = columnProfile("year", 999, 0.5);
        ColumnProfile wrapperOutput = wrapperOutputProfile("year", 42, 1.0, "urn:src:Widget");

        DatasetRepository repo = mock(DatasetRepository.class);
        when(repo.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(
                dataset(datasetId, List.of(snapshot, wrapperOutput))));
        ProfileStatsSource profileStats = new ProfileStatsSource(repo);

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, profileStats);
        MappingCatalog catalog = operator.getCatalog(tenant);

        MappingCatalog.SourceView source = catalog.classes().get("urn:IWidget").sources().get(0);
        assertThat(source.expansionStatus()).isEqualTo("OPAQUE");
        assertThat(source.rowCount())
                .as("cardinality 42 / uniqueness 1.0 — the WRAPPER_OUTPUT set's own estimate, not"
                        + " the SNAPSHOT profile's 999/0.5=1998")
                .isEqualTo(42L);
        assertThat(source.profiles()).containsExactly(wrapperOutput);
        assertThat(source.profiles())
                .as("no remap: 'year' stays 'year' even though it has a sourceRef entry")
                .extracting(ColumnProfile::columnName)
                .containsExactly("year");
    }

    @Test
    @DisplayName("an OPAQUE view without its own WRAPPER_OUTPUT profiles falls back to the remapped SNAPSHOT"
            + " path, ignoring another class's WRAPPER_OUTPUT rows on the same dataset")
    void opaqueViewFallsBackToSnapshotWhenNoWrapperOutputProfilesForItsOwnClass() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system"));

        UUID datasetId = UUID.randomUUID();
        Model m = ModelFactory.createDefaultModel();

        Resource iWidget = m.createResource("urn:IWidget");
        m.add(iWidget, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iWidget, RDF.type, RDFS.Class);

        Resource srcWidget = m.createResource("urn:src:Widget");
        m.add(srcWidget, RDFS.subClassOf, iWidget);
        m.add(srcWidget, m.createProperty(WRAPPER_SQL), "SELECT * FROM widgets"); // OPAQUE by default
        m.add(srcWidget, m.createProperty(DATASET_ID), datasetId.toString());

        Resource srcYear = m.createResource("urn:src:Widget/year");
        m.add(srcYear, RDFS.domain, srcWidget);
        m.add(srcYear, RDFS.range, XSD.integer);
        m.add(srcYear, m.createProperty(SOURCE_REF), "year");

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        ColumnProfile snapshot = columnProfile("year", 500, 1.0);
        // Another class's WRAPPER_OUTPUT evidence, sharing the same dataset — must not leak in.
        ColumnProfile otherClassWrapperOutput = wrapperOutputProfile("year", 42, 1.0, "urn:src:OTHER");

        DatasetRepository repo = mock(DatasetRepository.class);
        when(repo.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(
                dataset(datasetId, List.of(snapshot, otherClassWrapperOutput))));
        ProfileStatsSource profileStats = new ProfileStatsSource(repo);

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, profileStats);
        MappingCatalog catalog = operator.getCatalog(tenant);

        MappingCatalog.SourceView source = catalog.classes().get("urn:IWidget").sources().get(0);
        assertThat(source.rowCount()).isEqualTo(500L);
        assertThat(source.profiles())
                .as("remapped SNAPSHOT profile only — the other class's WRAPPER_OUTPUT row is excluded")
                .extracting(ColumnProfile::columnName)
                .containsExactly("urn:src:Widget/year");
    }

    @Test
    @DisplayName("an EXPANDED view always uses the remapped SNAPSHOT path, even if a WRAPPER_OUTPUT"
            + " profile exists for its own class")
    void expandedViewAlwaysUsesSnapshotEvenWithWrapperOutputEvidence() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system"));

        UUID datasetId = UUID.randomUUID();
        Model m = ModelFactory.createDefaultModel();

        Resource iWidget = m.createResource("urn:IWidget");
        m.add(iWidget, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iWidget, RDF.type, RDFS.Class);

        Resource srcWidget = m.createResource("urn:src:Widget");
        m.add(srcWidget, RDFS.subClassOf, iWidget);
        m.add(srcWidget, m.createProperty(WRAPPER_SQL), "SELECT * FROM widgets");
        m.add(srcWidget, m.createProperty(DATASET_ID), datasetId.toString());
        m.add(srcWidget, m.createProperty(WRAPPER_EXPANSION), "EXPANDED");

        Resource srcYear = m.createResource("urn:src:Widget/year");
        m.add(srcYear, RDFS.domain, srcWidget);
        m.add(srcYear, RDFS.range, XSD.integer);
        m.add(srcYear, m.createProperty(SOURCE_REF), "year");

        when(graphStore.query(eq(tenant), anyString())).thenReturn(m);

        ColumnProfile snapshot = columnProfile("year", 500, 1.0);
        // A stale/leftover WRAPPER_OUTPUT row for this SAME class — the ledger ruling is that an
        // EXPANDED view always uses SNAPSHOT regardless, so this must be ignored, not preferred.
        ColumnProfile wrapperOutput = wrapperOutputProfile("urn:src:Widget/year", 42, 1.0, "urn:src:Widget");

        DatasetRepository repo = mock(DatasetRepository.class);
        when(repo.findById(eq(tenant), eq(datasetId))).thenReturn(Optional.of(
                dataset(datasetId, List.of(snapshot, wrapperOutput))));
        ProfileStatsSource profileStats = new ProfileStatsSource(repo);

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, profileStats);
        MappingCatalog catalog = operator.getCatalog(tenant);

        MappingCatalog.SourceView source = catalog.classes().get("urn:IWidget").sources().get(0);
        assertThat(source.expansionStatus()).isEqualTo("EXPANDED");
        assertThat(source.rowCount()).isEqualTo(500L);
        assertThat(source.profiles())
                .as("exactly one profile — the WRAPPER_OUTPUT row must not double up alongside the"
                        + " remapped SNAPSHOT one")
                .extracting(ColumnProfile::columnName)
                .containsExactly("urn:src:Widget/year");
    }

    // ── Phase 4B (Task 3): declared FKs -> DECLARED referential edges, wired end-to-end ──────

    /** Builds an active schema with two source classes, each subClassOf its own integrated class,
     *  a foreign-key-shaped column on each side linked by nx:sourceRef — the graph shape
     *  {@code DeclaredEdgeResolver} needs {@code MappingCatalogOperator}'s QUERY 1 to have already
     *  collected ({@code srcClassByDatasetId}/{@code refsBySrcClass}). */
    private record TwoClassFixture(Model model, UUID employeesDatasetId, UUID departmentsDatasetId) {}

    private static TwoClassFixture twoClassFixture() {
        Model m = ModelFactory.createDefaultModel();
        UUID employeesDatasetId = UUID.randomUUID();
        UUID departmentsDatasetId = UUID.randomUUID();

        Resource iEmployee = m.createResource("urn:IEmployee");
        m.add(iEmployee, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iEmployee, RDF.type, RDFS.Class);
        Resource srcEmployees = m.createResource("urn:src:Employees");
        m.add(srcEmployees, RDFS.subClassOf, iEmployee);
        m.add(srcEmployees, m.createProperty(WRAPPER_SQL), "SELECT * FROM employees");
        m.add(srcEmployees, m.createProperty(DATASET_ID), employeesDatasetId.toString());
        Resource deptIdCol = m.createResource("urn:src:Employees/dept_id");
        m.add(deptIdCol, RDFS.domain, srcEmployees);
        m.add(deptIdCol, RDFS.range, XSD.integer);
        m.add(deptIdCol, m.createProperty(SOURCE_REF), "dept_id");

        Resource iDepartment = m.createResource("urn:IDepartment");
        m.add(iDepartment, RDF.type, m.createResource(INTEGRATED_RESOURCE));
        m.add(iDepartment, RDF.type, RDFS.Class);
        Resource srcDepartments = m.createResource("urn:src:Departments");
        m.add(srcDepartments, RDFS.subClassOf, iDepartment);
        m.add(srcDepartments, m.createProperty(WRAPPER_SQL), "SELECT * FROM departments");
        m.add(srcDepartments, m.createProperty(DATASET_ID), departmentsDatasetId.toString());
        Resource idCol = m.createResource("urn:src:Departments/id");
        m.add(idCol, RDFS.domain, srcDepartments);
        m.add(idCol, RDFS.range, XSD.integer);
        m.add(idCol, m.createProperty(SOURCE_REF), "id");

        return new TwoClassFixture(m, employeesDatasetId, departmentsDatasetId);
    }

    @Test
    @DisplayName("a harvested foreign key reaches the catalog as a DECLARED referential edge on the integrated classes")
    void harvestedForeignKeyBecomesDeclaredReferentialEdge() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system"));

        TwoClassFixture fixture = twoClassFixture();
        when(graphStore.query(eq(tenant), anyString())).thenReturn(fixture.model());

        DeclaredForeignKey harvestedFk = new DeclaredForeignKey(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                fixture.employeesDatasetId(), List.of("dept_id"),
                fixture.departmentsDatasetId(), List.of("id"));
        DeclaredForeignKeyRepository declaredFks = mock(DeclaredForeignKeyRepository.class);
        when(declaredFks.findAllByTenant(tenant)).thenReturn(List.of(harvestedFk));

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, null, declaredFks);
        MappingCatalog catalog = operator.getCatalog(tenant);

        assertThat(catalog.declaredEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.tier()).isEqualTo(Tier.DECLARED);
            assertThat(edge.leftClass()).isEqualTo("urn:IEmployee");
            assertThat(edge.leftCols()).containsExactly("urn:src:Employees/dept_id");
            assertThat(edge.rightClass()).isEqualTo("urn:IDepartment");
            assertThat(edge.rightCols()).containsExactly("urn:src:Departments/id");
        });
        // referentialEdges() concatenates HYPOTHESIS (none here) with DECLARED — proves the wiring
        // reaches the method Task 4's elimination rule actually reads, not just the new field.
        assertThat(catalog.referentialEdges()).containsExactly(catalog.declaredEdges().get(0));
    }

    @Test
    @DisplayName("a harvested FK whose endpoint dataset has no wrapping source class is dropped, not surfaced")
    void harvestedForeignKeyWithUnresolvableEndpointIsDropped() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system"));

        TwoClassFixture fixture = twoClassFixture();
        when(graphStore.query(eq(tenant), anyString())).thenReturn(fixture.model());

        // References a dataset id no source class in the active schema wraps.
        DeclaredForeignKey unresolvable = new DeclaredForeignKey(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), List.of("dept_id"),
                fixture.departmentsDatasetId(), List.of("id"));
        DeclaredForeignKeyRepository declaredFks = mock(DeclaredForeignKeyRepository.class);
        when(declaredFks.findAllByTenant(tenant)).thenReturn(List.of(unresolvable));

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, null, declaredFks);
        MappingCatalog catalog = operator.getCatalog(tenant);

        assertThat(catalog.declaredEdges()).as("can't place it -> dropped, not a partial/wrong edge").isEmpty();
        assertThat(catalog.referentialEdges()).isEmpty();
    }

    @Test
    @DisplayName("harvest-absent (no DeclaredForeignKeyRepository wired) is byte-identical to before Task 3")
    void harvestAbsentIsByteIdenticalToBeforeTask3() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system"));

        TwoClassFixture fixture = twoClassFixture();
        when(graphStore.query(eq(tenant), anyString())).thenReturn(fixture.model());

        // The pre-Task-3 constructor shape — exactly what every other test in this file already
        // uses. No DeclaredForeignKeyRepository exists to call, so nothing new can run.
        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, null);
        MappingCatalog catalog = operator.getCatalog(tenant);

        assertThat(catalog.declaredEdges()).isEmpty();
        assertThat(catalog.referentialEdges()).isEmpty();
    }

    @Test
    @DisplayName("a wired repository with nothing harvested yet behaves exactly like no repository at all")
    void wiredButEmptyRepositoryYieldsNoDeclaredEdges() {
        GraphStore graphStore = mock(GraphStore.class);
        TenantContext tenant = new TenantContext("test-tenant");
        UUID versionId = UUID.randomUUID();
        when(graphStore.currentVersion(tenant)).thenReturn(
                new GraphVersion(versionId, UUID.randomUUID(), 1, "PROMOTED", java.time.Instant.now(), UUID.randomUUID(), java.time.Instant.now(), "system"));

        TwoClassFixture fixture = twoClassFixture();
        when(graphStore.query(eq(tenant), anyString())).thenReturn(fixture.model());

        DeclaredForeignKeyRepository declaredFks = mock(DeclaredForeignKeyRepository.class);
        when(declaredFks.findAllByTenant(tenant)).thenReturn(List.of());

        MappingCatalogOperator operator = new MappingCatalogOperator(graphStore, null, declaredFks);
        MappingCatalog catalog = operator.getCatalog(tenant);

        assertThat(catalog.declaredEdges()).isEmpty();
        assertThat(catalog.referentialEdges()).isEmpty();
    }
}

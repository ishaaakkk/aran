package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-1: the wrapper's shape is the connector's explicit statement, and a kind the builder has
 * never heard of gets a FLAT wrapper — not a JSONPath one.
 *
 * <p>The builder used to infer the shape from {@code nx:format} through a closed allowlist
 * (CSV, PARQUET, JDBC → flat; anything else → document). V45 had already caught JDBC's table
 * name being read as a struct field; the eighth kind repeated it: an Iceberg class's iterator
 * {@code db.researchers} was split on the dot into {@code s."db"."researchers"."col"}, which
 * binds against nothing in {@code iceberg_scan}'s output, so a FEDERATED Iceberg query could not
 * bind its wrapper whether or not the extension installed. The contract kit now requires
 * {@code nx:shape}; this pins what the builder does with it, and that the Iceberg wrapper really
 * runs over a relation shaped like the scan's output.
 */
class WrapperShapeTest {

    private static final class FakeCatalog implements IcebergCatalogClient {
        @Override public Map<String, String> config(IcebergRestRepository repository) { return Map.of(); }
        @Override public List<String> listTables(IcebergRestRepository repository) { return List.of("db.researchers"); }
        @Override public IcebergTable loadTable(IcebergRestRepository repository, String tableIdentifier) {
            return new IcebergTable("s3://lake/db/researchers/metadata/00003.metadata.json",
                    List.of(new IcebergField("id", "long", true),
                            new IcebergField("name", "string", false),
                            new IcebergField("score", "double", false)));
        }
    }

    private static Model icebergGraph() throws Exception {
        var connector = new IcebergConnector(Optional.of(new FakeCatalog()), new OutboundAddressGuard(false));
        return connector.introspect(SourceDescriptor.ofIceberg("d-ice", "db.researchers", null,
                new SourceDescriptor.Iceberg("http://localhost:8181/api/catalog", "prod", "tok",
                        "db.researchers", "MINIO", "http://localhost:9000", "AKIA", "SECRET",
                        "us-east-1"))).rdfs();
    }

    private static String onlyWrapper(Model model) {
        assertThat(WrapperGenerator.attachPerClassWrappers(model)).isEqualTo(1);
        return model.listObjectsOfProperty(WrapperVocab.WRAPPER_SQL).toList().getFirst().asLiteral().getString();
    }

    @Test
    @DisplayName("an Iceberg class gets a flat wrapper: columns, not a path split on the table identifier")
    void icebergWrapperIsFlat() throws Exception {
        String sql = onlyWrapper(icebergGraph());
        assertThat(sql)
                .contains("\"name\" AS")
                .contains("\"score\" AS")
                .contains("FROM " + WrapperVocab.SNAPSHOT_PLACEHOLDER)
                .doesNotContain("s.\"db\"")
                .doesNotContain("UNNEST");
    }

    @Test
    @DisplayName("…and it executes over a relation shaped like iceberg_scan's output")
    void icebergWrapperExecutesOverTheScanShape() throws Exception {
        String sql = onlyWrapper(icebergGraph());
        // ${SNAPSHOT} binds to src_<ds> AS SELECT * FROM iceberg_scan(...) at query time: a
        // relation whose columns are the source refs. The old JSONPath wrapper failed exactly here.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE src(id BIGINT, name VARCHAR, score DOUBLE)");
            st.execute("INSERT INTO src VALUES (1, 'Ada', 0.9), (2, 'Grace', 0.8)");
            try (ResultSet rs = st.executeQuery(sql.replace(WrapperVocab.SNAPSHOT_PLACEHOLDER, "src"))) {
                int rows = 0;
                while (rs.next()) rows++;
                assertThat(rows).isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("a class whose format the builder has never seen is flat, whatever its iterator looks like")
    void unknownFormatDefaultsToFlat() {
        Model m = ModelFactory.createDefaultModel();
        Resource cls = m.createResource("http://ex/ds/Objects");
        m.add(cls, RDF.type, RDFS.Class);
        // An object-storage JSON object: format JSON, but columns from DESCRIBE — the connector
        // says FLAT, and the dotted object key must not be read as a struct path (S3-2).
        WrapperVocab.attachClassWrapper(m, cls, "ds", "", "JSON", "raw/people.json", WrapperVocab.Shape.FLAT);
        Resource p = m.createResource("http://ex/ds/Objects/name");
        m.add(p, RDFS.domain, cls);
        m.add(p, RDFS.range, XSD.xstring);
        m.add(p, WrapperVocab.SOURCE_REF, "name");
        assertThat(onlyWrapper(m))
                .isEqualTo("SELECT \"name\" AS \"http://ex/ds/Objects/name\" FROM " + WrapperVocab.SNAPSHOT_PLACEHOLDER);
    }

    @Test
    @DisplayName("a graph written before nx:shape existed still gets a document wrapper for JSON, flat otherwise")
    void legacyGraphsInferOnlyTheJsonCase() {
        for (String[] kind : List.of(new String[] {"JSON", "$[*]", "DOCUMENT"}, new String[] {"ICEBERG", "db.t", "FLAT"})) {
            Model m = ModelFactory.createDefaultModel();
            Resource cls = m.createResource("http://ex/ds/C");
            m.add(cls, RDF.type, RDFS.Class);
            m.add(cls, WrapperVocab.DATASET_ID, "ds");
            m.add(cls, WrapperVocab.SNAPSHOT_ID, "");
            m.add(cls, WrapperVocab.FORMAT, kind[0]);
            m.add(cls, WrapperVocab.ITERATOR, kind[1]);   // no nx:shape: a pre-2026-09-07 graph
            Resource p = m.createResource("http://ex/ds/C/name");
            m.add(p, RDFS.domain, cls);
            m.add(p, RDFS.range, XSD.xstring);
            m.add(p, WrapperVocab.SOURCE_REF, "name");
            String sql = onlyWrapper(m);
            if (kind[2].equals("DOCUMENT")) {
                assertThat(sql).as(kind[0]).contains("s.\"name\"");
            } else {
                assertThat(sql).as(kind[0]).doesNotContain("s.\"db\"").contains("\"name\" AS");
            }
        }
    }
}

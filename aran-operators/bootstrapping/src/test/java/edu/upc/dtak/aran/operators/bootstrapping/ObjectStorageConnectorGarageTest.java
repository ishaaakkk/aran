package edu.upc.dtak.aran.operators.bootstrapping;

import static org.assertj.core.api.Assertions.assertThat;

import edu.upc.dtak.aran.core.source.GarageContainer;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.source.SourceSchema;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.UploadObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The object-storage connector against a real S3-compatible store (Garage in Testcontainers): it
 * learns a parquet object's and a CSV object's schema over httpfs, reading no rows.
 *
 * <p>Docker-tagged so {@code validate.sh --offline} excludes it; run it with
 * {@code ./gradlew :aran-operators:bootstrapping:test} on a host with Docker.
 *
 * <p>The two objects are authored locally (parquet via a scratch DuckDB, CSV as text) and uploaded
 * with the MinIO SDK as a plain S3 client, so the only code that touches S3 over httpfs is the
 * connector under test.
 */
@Tag("docker")
@DisplayName("ObjectStorageConnector — introspect over Garage/httpfs")
class ObjectStorageConnectorGarageTest {

    private static final String BUCKET = "aran-test";
    private static final GarageContainer GARAGE = new GarageContainer().withBucket(BUCKET);

    private final ObjectStorageConnector connector = new ObjectStorageConnector(new edu.upc.dtak.aran.core.net.OutboundAddressGuard(false));

    @BeforeAll
    static void up() throws Exception {
        GARAGE.start();
        MinioClient client = MinioClient.builder()
                .endpoint(GARAGE.endpoint())
                .credentials(GARAGE.accessKey(), GARAGE.secretKey())
                .region(GARAGE.region())
                .build();

        Path parquet = Files.createTempFile("aran-garage-", ".parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement()) {
            // Four distinct DuckDB types so the XSD mapping is exercised end to end. score is cast
            // to DOUBLE on purpose: a bare 3.5 literal is DECIMAL(2,1), and this pins the
            // DOUBLE→xsd:double alignment (phase-2/3B) rather than the DECIMAL→xsd:decimal branch.
            s.execute("COPY (SELECT * FROM (VALUES "
                    + "(1, 'alice', CAST(3.5 AS DOUBLE), true), "
                    + "(2, 'bob', CAST(4.25 AS DOUBLE), false)) "
                    + "AS t(id, name, score, active)) TO '"
                    + parquet.toAbsolutePath() + "' (FORMAT parquet)");
        }
        client.uploadObject(UploadObjectArgs.builder()
                .bucket(BUCKET).object("data.parquet")
                .filename(parquet.toAbsolutePath().toString()).build());
        Files.deleteIfExists(parquet);

        byte[] csv = "id,name,score\n1,alice,3.5\n2,bob,4.25\n".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder()
                .bucket(BUCKET).object("data.csv")
                .stream(new ByteArrayInputStream(csv), csv.length, -1)
                .contentType("text/csv").build());
    }

    @AfterAll
    static void down() {
        GARAGE.stop();
    }

    private SourceDescriptor descriptorFor(String objectKey, String format) {
        return SourceDescriptor.ofObjectStorage("ds-1", "People", null,
                new SourceDescriptor.ObjectStorage(GarageContainer.PROVIDER, GARAGE.endpoint(), BUCKET,
                        objectKey, GARAGE.accessKey(), GARAGE.secretKey(), GARAGE.region(), format));
    }

    @Test
    @DisplayName("a parquet object yields one labelled class and XSD-typed columns")
    void introspectParquet() throws Exception {
        SourceSchema schema = connector.introspect(descriptorFor("data.parquet", "PARQUET"));

        Model m = schema.rdfs();
        List<Resource> classes = m.listResourcesWithProperty(RDF.type, RDFS.Class).toList();
        assertThat(classes).hasSize(1);
        assertThat(m.contains(classes.getFirst(), RDFS.label, "People")).isTrue();

        assertThat(rangeOf(m, "id")).isEqualTo(XSD.integer.getURI());
        assertThat(rangeOf(m, "name")).isEqualTo(XSD.xstring.getURI());
        assertThat(rangeOf(m, "score")).isEqualTo(XSD.xdouble.getURI());
        assertThat(rangeOf(m, "active")).isEqualTo(XSD.xboolean.getURI());

        // Wrapper metadata: format drives the resolver's reader choice; the object key locates it.
        Property format = m.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/", "format");
        assertThat(m.getProperty(classes.getFirst(), format).getString()).isEqualTo("PARQUET");
    }

    @Test
    @DisplayName("a CSV object yields sniffed XSD-typed columns")
    void introspectCsv() throws Exception {
        Model m = connector.introspect(descriptorFor("data.csv", "CSV")).rdfs();

        assertThat(m.listResourcesWithProperty(RDF.type, RDFS.Class).toList()).hasSize(1);
        assertThat(rangeOf(m, "id")).isEqualTo(XSD.integer.getURI());
        assertThat(rangeOf(m, "name")).isEqualTo(XSD.xstring.getURI());
        assertThat(rangeOf(m, "score")).isEqualTo(XSD.xdouble.getURI());
    }

    @Test
    @DisplayName("introspection reads metadata only — no data values reach the schema graph")
    void introspectIsMetadataOnly() throws Exception {
        // The connector's only statement against the object is DESCRIBE ... LIMIT 0 (parquet: the
        // footer; CSV: DuckDB's bounded sniff). The output-level proof is that no cell value — e.g.
        // the row values 'alice'/'bob' — appears anywhere in the emitted schema graph.
        Model m = connector.introspect(descriptorFor("data.parquet", "PARQUET")).rdfs();
        boolean anyRowValue = m.listObjects().toList().stream()
                .filter(RDFNode::isLiteral)
                .map(n -> n.asLiteral().getString())
                .anyMatch(v -> v.equals("alice") || v.equals("bob") || v.equals("3.5"));
        assertThat(anyRowValue)
                .describedAs("a row value in the schema graph means introspection pulled rows")
                .isFalse();
    }

    /** The rdfs:range URI of the property whose rdfs:label is {@code columnLabel}. */
    private static String rangeOf(Model m, String columnLabel) {
        Resource prop = m.listResourcesWithProperty(RDFS.label, columnLabel)
                .filterKeep(r -> m.contains(r, RDF.type, RDF.Property))
                .toList().getFirst();
        return m.getProperty(prop, RDFS.range).getObject().asResource().getURI();
    }
}

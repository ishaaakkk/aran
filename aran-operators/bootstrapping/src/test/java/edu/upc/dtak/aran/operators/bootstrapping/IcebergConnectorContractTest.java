package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceConnectorContract;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Iceberg connector, against the shared contract and then against what is peculiar to it
 * (Gap 2). The catalog under test is a fake answering the port — deliberately: the connector's
 * whole introspection is "trust the catalog's LoadTableResponse", so the fake IS the contract
 * surface. A run against a live catalog is outside this suite.
 */
class IcebergConnectorContractTest extends SourceConnectorContract {

    /** A catalog that serves one table with one of each type the mapper distinguishes. */
    private static final class FakeCatalog implements IcebergCatalogClient {
        @Override
        public Map<String, String> config(IcebergRestRepository repository) {
            return Map.of();
        }

        @Override
        public List<String> listTables(IcebergRestRepository repository) {
            return List.of("db.researchers");
        }

        @Override
        public IcebergTable loadTable(IcebergRestRepository repository, String tableIdentifier) {
            if (!"db.researchers".equals(tableIdentifier)) {
                throw new IllegalStateException("no such table: " + tableIdentifier);
            }
            return new IcebergTable(
                    "s3://lake/db/researchers/metadata/00003.metadata.json",
                    List.of(
                            new IcebergField("id", "long", true),
                            new IcebergField("name", "string", false),
                            new IcebergField("score", "double", false),
                            new IcebergField("grant", "decimal(38,9)", false),
                            new IcebergField("tenured", "boolean", false),
                            new IcebergField("hired_on", "date", false),
                            new IcebergField("seen_at", "timestamptz", false),
                            new IcebergField("tags", "list", false)));
        }
    }

    @Override
    protected SourceConnector connector() {
        return new IcebergConnector(Optional.of(new FakeCatalog()), new OutboundAddressGuard(false));
    }

    @Override
    protected SourceDescriptor validSource() {
        return SourceDescriptor.ofIceberg("d-ice", "db.researchers", null,
                new SourceDescriptor.Iceberg("http://localhost:8181/api/catalog", "prod", "tok",
                        "db.researchers", "MINIO", "http://localhost:9000", "AKIA", "SECRET",
                        "us-east-1"));
    }

    @Test
    @DisplayName("maps Iceberg types onto the same XSD range set the parquet path produces")
    void typesLandOnTheSharedXsdSet() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();

        assertThat(rangeOf(model, "id")).isEqualTo(XSD.integer.getURI());
        assertThat(rangeOf(model, "name")).isEqualTo(XSD.xstring.getURI());
        assertThat(rangeOf(model, "score")).isEqualTo(XSD.xdouble.getURI());
        assertThat(rangeOf(model, "grant")).isEqualTo(XSD.decimal.getURI());
        assertThat(rangeOf(model, "tenured")).isEqualTo(XSD.xboolean.getURI());
        assertThat(rangeOf(model, "hired_on")).isEqualTo(XSD.date.getURI());
        assertThat(rangeOf(model, "seen_at")).isEqualTo(XSD.dateTime.getURI());
        // Nested types degrade to string deliberately — see IcebergTypes.
        assertThat(rangeOf(model, "tags")).isEqualTo(XSD.xstring.getURI());
    }

    @Test
    @DisplayName("introspection is control-plane-only: no snapshot, the table identifier as iterator")
    void introspectionIsMetadataOnly() throws Exception {
        Model model = connector().introspect(validSource()).rdfs();
        Resource cls = model.listResourcesWithProperty(RDF.type, RDFS.Class).toList().getFirst();
        assertThat(model.getProperty(cls, nx("snapshotId")).getString())
                .describedAs("a federated Iceberg dataset has no lake snapshot")
                .isEmpty();
        assertThat(model.getProperty(cls, nx("iterator")).getString())
                .describedAs("the iterator is the relation to read, as for JDBC")
                .isEqualTo("db.researchers");
    }

    private static String rangeOf(Model model, String columnLabel) {
        Resource property = model.listStatements(null, RDFS.label, columnLabel)
                .toList().stream()
                .map(s -> s.getSubject())
                .filter(s -> model.contains(s, RDF.type, RDF.Property))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no property labelled " + columnLabel));
        RDFNode range = model.getProperty(property, RDFS.range).getObject();
        return range.asResource().getURI();
    }

    private static org.apache.jena.rdf.model.Property nx(String local) {
        return ResourceFactory.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/" + local);
    }
}

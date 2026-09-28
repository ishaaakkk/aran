package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.net.OutboundAddressGuard;
import edu.upc.dtak.aran.core.source.SourceConnector;
import edu.upc.dtak.aran.core.source.SourceDescriptor;
import edu.upc.dtak.aran.core.source.SourceSchema;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Iceberg REST catalog source connector (Gap 2, consume half): one table of a catalog, read live
 * via DuckDB's {@code iceberg} extension.
 *
 * <p>It claims a descriptor by the presence of {@link SourceDescriptor#iceberg()}, so it is
 * mutually exclusive with every other connector by construction. Unlike
 * {@code ObjectStorageBootstrap}, introspection opens <b>no DuckDB connection at all</b>: the
 * catalog's own {@code LoadTableResponse} carries the table's current schema as JSON, so the
 * schema is learned from the control plane alone — the data plane is not dialed until a query or a
 * copy actually reads rows. That makes this the cheapest introspection of any live kind, and the
 * only one that works without the DuckDB {@code iceberg} extension being installable.
 */
@NullMarked
@Component
public final class IcebergConnector implements SourceConnector {

    private final @Nullable IcebergCatalogClient catalog;
    private final OutboundAddressGuard addressGuard;

    public IcebergConnector(Optional<IcebergCatalogClient> catalog, OutboundAddressGuard addressGuard) {
        this.catalog = catalog.orElse(null);
        this.addressGuard = addressGuard;
    }

    @Override
    public String sourceKind() {
        return "ICEBERG";
    }

    @Override
    public boolean supports(SourceDescriptor source) {
        return source.iceberg() != null;
    }

    @Override
    public SourceSchema introspect(SourceDescriptor source) throws IOException {
        SourceDescriptor.Iceberg ice = Objects.requireNonNull(source.iceberg(),
                "the Iceberg connector requires Iceberg catalog details");
        if (catalog == null) {
            throw new IOException("this build has no Iceberg catalog client on its classpath");
        }
        // Screen the control plane before it is dialed (the F-S1-11 rule ObjectStorageConnector
        // applies to its endpoint). The data plane is not dialed here at all.
        addressGuard.checkUri(ice.catalogUri());

        IcebergCatalogClient.IcebergTable table;
        try {
            table = catalog.loadTable(toRepository(ice), ice.tableIdentifier());
        } catch (RuntimeException e) {
            throw new IOException("failed to load Iceberg table '" + ice.tableIdentifier()
                    + "' from the catalog: " + e.getMessage(), e);
        }

        Model model = ModelFactory.createDefaultModel();
        String classIri = BootstrapEngine.sourceClassIri(source.datasetId(), source.datasetName());
        Resource cls = model.createResource(classIri);
        model.add(cls, RDF.type, RDFS.Class);
        model.add(cls, RDFS.label, source.datasetName());
        // No lake snapshot — the table is read where it lives. The table identifier goes in the
        // iterator slot exactly as JdbcBootstrap puts the table name there: for a non-file source
        // the iterator is the specific relation to read.
        WrapperVocab.attachClassWrapper(model, cls, source.datasetId(), "", "ICEBERG",
                ice.tableIdentifier(), WrapperVocab.Shape.FLAT);

        for (IcebergCatalogClient.IcebergField field : table.fields()) {
            String name = field.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            String propIri = BootstrapEngine.sourcePropertyIri(
                    source.datasetId(), source.datasetName(), name);
            Resource prop = model.createResource(propIri);
            model.add(prop, RDF.type, RDF.Property);
            model.add(prop, RDFS.label, name);
            model.add(prop, RDFS.domain, cls);
            model.add(prop, RDFS.range, IcebergTypes.xsdRangeFor(field.type()));
            model.add(prop, WrapperVocab.SOURCE_REF, name);
        }
        return new SourceSchema(model);
    }

    /**
     * The client speaks in repositories; a descriptor is the connector-facing projection of one.
     * Rebuild the repository shape the client needs from the descriptor's fields (ids are not part
     * of what the client reads, so placeholders are fine).
     */
    private static IcebergRestRepository toRepository(SourceDescriptor.Iceberg ice) {
        return new IcebergRestRepository(
                new java.util.UUID(0, 0), new java.util.UUID(0, 0), new java.util.UUID(0, 0),
                "descriptor", "",
                ice.catalogUri(),
                ice.warehouse() == null ? "" : ice.warehouse(),
                ice.token() == null ? "" : ice.token(),
                ice.provider(),
                ice.endpoint() == null ? "" : ice.endpoint(),
                ice.accessKey() == null ? "" : ice.accessKey(),
                ice.secretKey() == null ? "" : ice.secretKey(),
                ice.region() == null ? "" : ice.region());
    }
}

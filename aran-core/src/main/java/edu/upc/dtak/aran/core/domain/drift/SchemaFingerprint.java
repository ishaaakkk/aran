package edu.upc.dtak.aran.core.domain.drift;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The comparable shape of one dataset's bootstrapped source schema, at a point in time.
 *
 * <p>This is a projection of the source graph — the classes, their iterators and formats, and
 * each property's column reference and declared type — reduced to exactly what a later
 * re-bootstrap could change. It is deliberately <em>not</em> the graph: wrapper SQL, labels
 * that carry no identity, and provenance triples are all excluded, because a change in them is
 * either derived from something already here or is not drift at all.
 *
 * <p><b>Identity.</b> A class is identified by its {@code iterator}, not its IRI. The IRI is
 * minted from the dataset <em>name</em> ({@code BootstrapEngine.sourcePropertyIri} interpolates
 * it), so renaming a dataset and re-bootstrapping re-mints every IRI in the graph while the
 * source is untouched. Keying on the iterator — {@code "row"} for CSV, a JSONPath for JSON —
 * lets the diff recognise that as the systematic IRI change it is, rather than reporting every
 * class and column as dropped-and-re-added. Properties are keyed on {@code sourceRef} (the real
 * column name) for the same reason.
 *
 * @param datasetId the dataset this describes
 * @param classes   the source classes, in no guaranteed order — {@link #canonical()} sorts
 */
@NullMarked
public record SchemaFingerprint(String datasetId, List<ClassShape> classes) {

    public SchemaFingerprint {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId must not be blank");
        }
        classes = List.copyOf(classes);
    }

    /**
     * One {@code rdfs:Class} of the source.
     *
     * @param iri      the minted class IRI — payload, not identity (see the class javadoc)
     * @param format   {@code CSV} | {@code JSON} | {@code PARQUET}
     * @param iterator {@code "row"} for CSV/Parquet, a JSONPath for JSON. The identity.
     */
    public record ClassShape(String iri, String format, String iterator, List<PropertyShape> properties) {
        public ClassShape {
            properties = List.copyOf(properties);
        }
    }

    /**
     * One {@code rdf:Property} of a source class.
     *
     * @param iri       the minted property IRI — what {@code ModelMapping.sourceIri} points at,
     *                  so this is the value impact analysis needs even though it is not identity
     * @param sourceRef the column name (CSV/Parquet) or key path (JSON). The identity.
     * @param range     the declared {@code rdfs:range}. CSV bootstraps everything as
     *                  {@code xsd:string}, so this only discriminates for Parquet and JSON.
     */
    public record PropertyShape(String iri, String label, String sourceRef, @Nullable String range) {}

    /**
     * A deterministic rendering, used both for {@link #hash()} and for making a failing
     * comparison readable in a test. Sorted throughout: a Jena model has no order, so two
     * fingerprints of the same graph must not differ by iteration order.
     */
    public String canonical() {
        StringBuilder sb = new StringBuilder("dataset=").append(datasetId).append('\n');
        classes.stream()
                .sorted(Comparator.comparing(ClassShape::iterator).thenComparing(ClassShape::iri))
                .forEach(c -> {
                    sb.append("class iterator=").append(c.iterator())
                            .append(" iri=").append(c.iri())
                            .append(" format=").append(c.format()).append('\n');
                    c.properties().stream()
                            .sorted(Comparator.comparing(PropertyShape::sourceRef)
                                    .thenComparing(PropertyShape::iri))
                            .forEach(p -> sb.append("  prop sourceRef=").append(p.sourceRef())
                                    .append(" iri=").append(p.iri())
                                    .append(" range=").append(p.range() == null ? "-" : p.range())
                                    .append('\n'));
                });
        return sb.toString();
    }

    /**
     * Hex SHA-256 of {@link #canonical()} — the cheap "did anything change at all?" test, so the
     * common no-drift case costs one string comparison instead of a full diff.
     */
    public String hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Total properties across all classes — the headline number for "this source has N columns". */
    public int propertyCount() {
        return classes.stream().mapToInt(c -> c.properties().size()).sum();
    }
}

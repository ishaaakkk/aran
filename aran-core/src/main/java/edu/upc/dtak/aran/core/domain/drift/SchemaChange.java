package edu.upc.dtak.aran.core.domain.drift;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One difference between two {@link SchemaFingerprint}s.
 *
 * <p>Purely descriptive: a change says what moved, never how much it matters. Severity is not a
 * property of a change — the same {@link SchemaChangeKind#COLUMN_REMOVED} is noise if nothing
 * referenced the column and breaking if a join key did — so it is assigned by
 * {@link DriftImpactAnalyzer}, which has the mappings in hand.
 *
 * @param classIri       the class the change belongs to; the <em>old</em> IRI where both exist,
 *                       since that is what existing mappings point at
 * @param propertyIri    the property affected, or null for class-level changes. For a rename this
 *                       is the <em>old</em> IRI — again, the one mappings reference.
 * @param replacementIri for {@link SchemaChangeKind#COLUMN_RENAMED} and
 *                       {@link SchemaChangeKind#CLASS_IRI_CHANGED}, the IRI that now stands in
 *                       its place. This is what a repair would rewrite mappings to.
 * @param confidence     for a rename, how strongly the evidence supports it (0–1); null otherwise.
 *                       A rename is always a proposal: rewiring a mapping to the wrong column is
 *                       worse than the dangling reference it would be fixing.
 * @param detail         a sentence a user can read without knowing the vocabulary
 */
@NullMarked
public record SchemaChange(
        SchemaChangeKind kind,
        String classIri,
        @Nullable String propertyIri,
        @Nullable String replacementIri,
        @Nullable String before,
        @Nullable String after,
        @Nullable Double confidence,
        String detail) {

    public static SchemaChange classAdded(String classIri, String iterator, int propertyCount) {
        return new SchemaChange(SchemaChangeKind.CLASS_ADDED, classIri, null, null, null, iterator, null,
                "New source class at '" + iterator + "' with " + propertyCount + " columns.");
    }

    public static SchemaChange classRemoved(String classIri, String iterator, int propertyCount) {
        return new SchemaChange(SchemaChangeKind.CLASS_REMOVED, classIri, null, null, iterator, null, null,
                "Source class at '" + iterator + "' (" + propertyCount + " columns) is gone from the source.");
    }

    public static SchemaChange classIriChanged(String oldIri, String newIri, String iterator) {
        return new SchemaChange(SchemaChangeKind.CLASS_IRI_CHANGED, oldIri, null, newIri, oldIri, newIri, null,
                "The class at '" + iterator + "' kept its shape but was re-minted under a new IRI "
                        + "— this is what renaming the dataset does.");
    }

    public static SchemaChange formatChanged(String classIri, String before, String after) {
        return new SchemaChange(SchemaChangeKind.FORMAT_CHANGED, classIri, null, null, before, after, null,
                "Source format changed from " + before + " to " + after + ".");
    }

    public static SchemaChange columnAdded(String classIri, String propertyIri, String sourceRef) {
        return new SchemaChange(SchemaChangeKind.COLUMN_ADDED, classIri, propertyIri, null, null, sourceRef, null,
                "New column '" + sourceRef + "' is available to map.");
    }

    public static SchemaChange columnRemoved(String classIri, String propertyIri, String sourceRef) {
        return new SchemaChange(SchemaChangeKind.COLUMN_REMOVED, classIri, propertyIri, null, sourceRef, null, null,
                "Column '" + sourceRef + "' is gone from the source.");
    }

    public static SchemaChange columnRenamed(String classIri, String oldPropertyIri, String newPropertyIri,
                                             String oldRef, String newRef, double confidence, String basis) {
        return new SchemaChange(SchemaChangeKind.COLUMN_RENAMED, classIri, oldPropertyIri, newPropertyIri,
                oldRef, newRef, confidence,
                "Column '" + oldRef + "' looks renamed to '" + newRef + "' (" + basis + ").");
    }

    public static SchemaChange typeChanged(String classIri, String propertyIri, String sourceRef,
                                           @Nullable String before, @Nullable String after) {
        return new SchemaChange(SchemaChangeKind.TYPE_CHANGED, classIri, propertyIri, null, before, after, null,
                "Column '" + sourceRef + "' changed type from " + shortName(before) + " to " + shortName(after)
                        + " — queries keep running, but comparisons and aggregates may mean something else.");
    }

    private static String shortName(@Nullable String iri) {
        if (iri == null) {
            return "unknown";
        }
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }
}

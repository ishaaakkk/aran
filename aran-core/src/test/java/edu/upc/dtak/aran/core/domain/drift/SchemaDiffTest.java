package edu.upc.dtak.aran.core.domain.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The diff, case by case.
 *
 * <p>Every case here is one of the scenarios the drift plan set out to catch, written against the
 * fingerprints directly so it needs neither a graph nor a database.
 */
class SchemaDiffTest {

    private static final String DS = "11111111-1111-1111-1111-111111111111";
    private static final String BASE = "http://www.essi.upc.edu/DTIM/NextiaDI/source/" + DS + "/";

    private static SchemaFingerprint csv(String className, String... columns) {
        return csvTyped(className, "http://www.w3.org/2001/XMLSchema#string", columns);
    }

    private static SchemaFingerprint csvTyped(String className, String range, String... columns) {
        String classIri = BASE + className;
        List<SchemaFingerprint.PropertyShape> props = Arrays.stream(columns)
                .map(c -> new SchemaFingerprint.PropertyShape(classIri + "/" + c, c, c, range))
                .toList();
        return new SchemaFingerprint(DS,
                List.of(new SchemaFingerprint.ClassShape(classIri, "CSV", "row", props)));
    }

    @Test
    @DisplayName("an identical fingerprint produces no changes")
    void noDrift() {
        assertThat(SchemaDiff.between(csv("papers", "title", "venue"), csv("papers", "title", "venue")))
                .isEmpty();
    }

    @Test
    @DisplayName("a new column is an addition, not a rename")
    void columnAdded() {
        List<SchemaChange> changes =
                SchemaDiff.between(csv("papers", "title"), csv("papers", "title", "doi"));

        assertThat(changes).singleElement()
                .satisfies(c -> {
                    assertThat(c.kind()).isEqualTo(SchemaChangeKind.COLUMN_ADDED);
                    assertThat(c.after()).isEqualTo("doi");
                });
    }

    @Test
    @DisplayName("one column out and one in is proposed as a rename")
    void renameProposed() {
        // The motivating case: papers.csv renames `venue` to `conference`. A drop plus an
        // add is technically what happened; a rename is what the user did.
        List<SchemaChange> changes = SchemaDiff.between(
                csv("papers", "title", "venue"), csv("papers", "title", "conference"));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(SchemaChangeKind.COLUMN_RENAMED);
            assertThat(c.before()).isEqualTo("venue");
            assertThat(c.after()).isEqualTo("conference");
            assertThat(c.propertyIri()).isEqualTo(BASE + "papers/venue");
            assertThat(c.replacementIri()).isEqualTo(BASE + "papers/conference");
            assertThat(c.confidence()).isNotNull().isGreaterThanOrEqualTo(SchemaDiff.RENAME_THRESHOLD);
        });
    }

    @Test
    @DisplayName("an ambiguous swap is NOT guessed at — both sides are reported plainly")
    void ambiguousRenamesAreNotGuessed() {
        // Two out, two in. Every signal available here is symmetric: CSV declares every column
        // xsd:string, and the candidate has no profile yet. Picking a pairing would be a coin
        // flip that silently rewires a mapping to the wrong column.
        List<SchemaChange> changes = SchemaDiff.between(
                csv("papers", "a", "b"), csv("papers", "c", "d"));

        assertThat(changes).extracting(SchemaChange::kind)
                .containsExactlyInAnyOrder(
                        SchemaChangeKind.COLUMN_REMOVED, SchemaChangeKind.COLUMN_REMOVED,
                        SchemaChangeKind.COLUMN_ADDED, SchemaChangeKind.COLUMN_ADDED);
        assertThat(changes).noneMatch(c -> c.kind() == SchemaChangeKind.COLUMN_RENAMED);
    }

    @Test
    @DisplayName("a rename with a matching type is more confident than one without")
    void typeAgreementRaisesConfidence() {
        double sameType = SchemaDiff.between(
                        csvTyped("p", "xsd:int", "title", "venue"),
                        csvTyped("p", "xsd:int", "title", "conference"))
                .get(0).confidence();

        SchemaFingerprint before = csvTyped("p", "xsd:int", "title", "venue");
        SchemaFingerprint after = csvTyped("p", "xsd:string", "title", "conference");
        // Only the renamed column differs in type here; `title` changes too, so filter to the rename.
        double differentType = SchemaDiff.between(before, after).stream()
                .filter(c -> c.kind() == SchemaChangeKind.COLUMN_RENAMED)
                .findFirst().orElseThrow().confidence();

        assertThat(sameType).isGreaterThan(differentType);
    }

    @Test
    @DisplayName("a surviving column whose type moved is a type change, not a drop and add")
    void typeChanged() {
        List<SchemaChange> changes = SchemaDiff.between(
                csvTyped("papers", "http://www.w3.org/2001/XMLSchema#int", "year"),
                csvTyped("papers", "http://www.w3.org/2001/XMLSchema#string", "year"));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(SchemaChangeKind.TYPE_CHANGED);
            assertThat(c.detail()).contains("int").contains("string");
        });
    }

    @Test
    @DisplayName("a dropped column with no arrival is a plain removal")
    void columnRemoved() {
        List<SchemaChange> changes =
                SchemaDiff.between(csv("papers", "title", "venue"), csv("papers", "title"));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(SchemaChangeKind.COLUMN_REMOVED);
            assertThat(c.propertyIri()).isEqualTo(BASE + "papers/venue");
        });
    }

    @Test
    @DisplayName("renaming the DATASET re-mints every IRI, and that is reported once, not per column")
    void datasetRenameIsOneFinding() {
        // BootstrapEngine interpolates the dataset name into every IRI, so renaming the dataset
        // and re-bootstrapping changes the whole graph while the source is untouched. Keying the
        // diff on the iterator is what keeps this from reading as "all columns dropped".
        List<SchemaChange> changes = SchemaDiff.between(
                csv("papers", "title", "venue"), csv("publications", "title", "venue"));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(SchemaChangeKind.CLASS_IRI_CHANGED);
            assertThat(c.classIri()).isEqualTo(BASE + "papers");
            assertThat(c.replacementIri()).isEqualTo(BASE + "publications");
        });
    }

    @Test
    @DisplayName("a class that disappears is reported as removed")
    void classRemoved() {
        SchemaFingerprint before = new SchemaFingerprint(DS, List.of(
                new SchemaFingerprint.ClassShape(BASE + "p", "JSON", "$", List.of()),
                new SchemaFingerprint.ClassShape(BASE + "p.authors", "JSON", "$.authors[*]", List.of())));
        SchemaFingerprint after = new SchemaFingerprint(DS, List.of(
                new SchemaFingerprint.ClassShape(BASE + "p", "JSON", "$", List.of())));

        assertThat(SchemaDiff.between(before, after)).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(SchemaChangeKind.CLASS_REMOVED);
            assertThat(c.before()).isEqualTo("$.authors[*]");
        });
    }

    @Test
    @DisplayName("a format change is reported without disturbing the columns")
    void formatChanged() {
        SchemaFingerprint before = csv("papers", "title");
        SchemaFingerprint after = new SchemaFingerprint(DS, List.of(
                new SchemaFingerprint.ClassShape(BASE + "papers", "PARQUET", "row",
                        before.classes().get(0).properties())));

        assertThat(SchemaDiff.between(before, after)).singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo(SchemaChangeKind.FORMAT_CHANGED));
    }

    @Test
    @DisplayName("the change order is stable across runs")
    void deterministicOrder() {
        SchemaFingerprint before = csv("papers", "a", "b", "c");
        SchemaFingerprint after = csv("papers", "a", "x", "y");
        assertThat(SchemaDiff.between(before, after))
                .isEqualTo(SchemaDiff.between(before, after));
    }

    @Test
    @DisplayName("name similarity is bounded and case-insensitive")
    void nameSimilarityBounds() {
        assertThat(SchemaDiff.nameSimilarity("venue", "VENUE")).isEqualTo(1.0);
        assertThat(SchemaDiff.nameSimilarity("venue", "conference")).isBetween(0.0, 1.0);
        assertThat(SchemaDiff.nameSimilarity("year", "year_published")).isGreaterThan(0.0);
    }
}

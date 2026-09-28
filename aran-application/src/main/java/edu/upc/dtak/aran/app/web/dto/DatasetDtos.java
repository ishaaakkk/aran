package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.ColumnProfile;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRegistration;
import edu.upc.dtak.aran.core.domain.DatasetStatus;
import edu.upc.dtak.aran.core.source.DatasetAccessMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class DatasetDtos {

    private DatasetDtos() {
    }

    public record DatasetRequest(
            @NotBlank(message = "dataset name is required")
            @Size(max = 256, message = "dataset name must be at most 256 characters")
            String name,

            @Size(max = 4000, message = "description must be at most 4000 characters")
            String description
    ) {
        public Dataset toDomain(UUID id, UUID repositoryId) {
            return new Dataset(
                id, 
                repositoryId, 
                name.trim(), 
                description == null ? "" : description.trim(),
                null,
                List.of(),
                null,
                null
            );
        }
    }

    /** Body of the import-from-table request: which scanned table to snapshot. */
    public record ImportTableRequest(
            @NotBlank(message = "tableName is required")
            @Size(max = 256, message = "tableName must be at most 256 characters")
            String tableName
    ) {}

    /** Body of the import-from-stream request (T6): which of a tap's streams to pull. */
    public record ImportStreamRequest(
            @NotBlank(message = "stream is required")
            @Size(max = 256, message = "stream must be at most 256 characters")
            String stream
    ) {}

    /** Body of the import-from-Iceberg request (Gap 2): which {@code namespace.table} to copy. */
    public record ImportIcebergTableRequest(
            @NotBlank(message = "tableIdentifier is required")
            @Size(max = 512, message = "tableIdentifier must be at most 512 characters")
            String tableIdentifier
    ) {}

    /**
     * @param accessMode how queries read this dataset — from its snapshot, live, or live with a
     *                   lake copy (J2-F3). Computed per response from the repository, never
     *                   stored: it follows the repository's federation setting and the
     *                   dataset's own copy, both of which can change.
     */
    public record DatasetResponse(
            UUID id,
            UUID repositoryId,
            String name,
            String description,
            String snapshotId,
            DatasetStatus bootstrapStatus,
            DatasetAccessMode accessMode
    ) {
        public static DatasetResponse from(Dataset d, DatasetAccessMode accessMode) {
            return new DatasetResponse(
                d.id(),
                d.repositoryId(),
                d.name(),
                d.description(),
                d.snapshotId(),
                d.bootstrapStatus(),
                accessMode
            );
        }
    }

    /**
     * The stored column profiles of one dataset (J3-F1), one group per thing profiled.
     *
     * <p>A dataset can carry several generations at once (V36): freyja over the raw snapshot
     * ({@code SNAPSHOT}), over one source class's wrapper output ({@code WRAPPER_OUTPUT}) or live
     * binding ({@code FEDERATED_LIVE}), and the relational catalog's own statistics ({@code
     * HARVESTED}). They measure different things under the same column names, so they are never
     * merged: the tab shows which one it is reading, and whether the planner may take it as key
     * evidence ({@link ColumnProfile#admissibleAsKeyEvidence()} — the planner's own rule).
     */
    public record DatasetProfilesResponse(UUID datasetId, List<ProfileGroup> groups) {

        /** Snapshot first — what join discovery reads — then the per-class generations, then estimates. */
        private static final List<String> SUBJECT_ORDER = List.of(
                ColumnProfile.SUBJECT_SNAPSHOT, "WRAPPER_OUTPUT",
                ColumnProfile.SUBJECT_FEDERATED_LIVE, ColumnProfile.SUBJECT_HARVESTED);

        public static DatasetProfilesResponse from(UUID datasetId, List<ColumnProfile> profiles) {
            Map<List<@Nullable String>, List<ColumnProfile>> bySubject = new LinkedHashMap<>();
            for (ColumnProfile p : profiles) {
                bySubject.computeIfAbsent(Arrays.asList(p.subject(), p.classIri()),
                        k -> new ArrayList<>()).add(p);
            }
            List<ProfileGroup> groups = new ArrayList<>();
            bySubject.forEach((key, rows) -> {
                ColumnProfile first = rows.get(0);
                groups.add(new ProfileGroup(
                        first.subject(),
                        first.classIri(),
                        first.admissibleAsKeyEvidence(),
                        rows.stream()
                                .sorted(Comparator.comparingInt(ColumnProfile::columnIndex)
                                        .thenComparing(ColumnProfile::columnName))
                                .map(StoredColumnProfile::from)
                                .toList()));
            });
            groups.sort(Comparator
                    .comparingInt((ProfileGroup g) -> subjectRank(g.subject()))
                    .thenComparing(ProfileGroup::subject)
                    .thenComparing(g -> Objects.requireNonNullElse(g.classIri(), "")));
            return new DatasetProfilesResponse(datasetId, List.copyOf(groups));
        }

        private static int subjectRank(String subject) {
            int i = SUBJECT_ORDER.indexOf(subject);
            return i < 0 ? SUBJECT_ORDER.size() : i;
        }
    }

    /**
     * @param keyEvidence whether the planner may read these rows as proof that a column is unique
     *                    — false for {@code HARVESTED} (a catalog estimate) and {@code
     *                    FEDERATED_LIVE} (exact when taken, decaying after)
     */
    public record ProfileGroup(
            String subject,
            @Nullable String classIri,
            boolean keyEvidence,
            List<StoredColumnProfile> columns) {}

    /**
     * One stored column profile, as the Profile tab renders it.
     *
     * <p>{@code valueHashSample} is left out on purpose: it is freyja's bottom-k of value hashes,
     * input to the exact join score and meaningless to read. {@code emptyColumn}/{@code
     * binaryColumn} are {@code ColumnProfile.isEmpty}/{@code isBinary}, renamed because a record
     * component called {@code isEmpty} is also a bean-style getter for a property called {@code
     * empty}, and a wire name should not depend on which of the two the serializer picks.
     */
    public record StoredColumnProfile(
            String columnName,
            int columnIndex,
            String sourceType,
            long cardinality,
            @Nullable Double entropy,
            double uniqueness,
            double incompleteness,
            double frequencyAvg,
            long frequencyMin,
            long frequencyMax,
            double frequencySd,
            double frequencyIqr,
            double valPctMin,
            double valPctMax,
            double valPctStd,
            double constancy,
            int lenMaxWord,
            int lenMinWord,
            double lenAvgWord,
            long numberWords,
            int wordsCntMax,
            int wordsCntMin,
            double wordsCntAvg,
            double wordsCntSd,
            @Nullable String firstWord,
            @Nullable String lastWord,
            boolean emptyColumn,
            boolean binaryColumn,
            List<String> commonValues,
            List<String> commonValuesSoundex) {

        static StoredColumnProfile from(ColumnProfile c) {
            return new StoredColumnProfile(
                    c.columnName(), c.columnIndex(), c.sourceType(),
                    c.cardinality(), c.entropy(), c.uniqueness(), c.incompleteness(),
                    c.frequencyAvg(), c.frequencyMin(), c.frequencyMax(), c.frequencySd(), c.frequencyIqr(),
                    c.valPctMin(), c.valPctMax(), c.valPctStd(), c.constancy(),
                    c.lenMaxWord(), c.lenMinWord(), c.lenAvgWord(),
                    c.numberWords(), c.wordsCntMax(), c.wordsCntMin(), c.wordsCntAvg(), c.wordsCntSd(),
                    c.firstWord(), c.lastWord(),
                    c.isEmpty(), c.isBinary(),
                    c.commonValues(), c.commonValuesSoundex());
        }
    }

    /**
     * What a create-with-file answers: the dataset as it stands — {@code PENDING},
     * because bootstrapping now runs as a job — plus the id of that job, so the
     * caller can watch the real thing instead of guessing when it is done.
     *
     * <p>Same field names as {@link DatasetResponse} plus {@code bootstrapJobId},
     * so a client that ignores the job id still reads it as a plain dataset.
     */
    public record DatasetCreatedResponse(
            UUID id,
            UUID repositoryId,
            String name,
            String description,
            String snapshotId,
            DatasetStatus bootstrapStatus,
            UUID bootstrapJobId
    ) {
        public static DatasetCreatedResponse from(DatasetRegistration registration) {
            Dataset d = registration.dataset();
            return new DatasetCreatedResponse(
                d.id(),
                d.repositoryId(),
                d.name(),
                d.description(),
                d.snapshotId(),
                d.bootstrapStatus(),
                registration.bootstrapJobId()
            );
        }
    }
}

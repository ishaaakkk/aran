package edu.upc.dtak.aran.core.domain;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import edu.upc.dtak.aran.core.domain.DatasetStatus;

/**
 * A dataset is a logical artifact belonging to a data repository that provides data.
 *
 * <p>There is deliberately no {@code wrapper} component. One existed until 2026-08-16, holding
 * the flat SELECT {@code WrapperGenerator} produced at bootstrap, and nothing ever executed it:
 * the query engine runs {@code nx:wrapperSql} from the schema graph, and a user edits
 * {@code ModelSource.wrapperSql}. It was deprecated rather than deleted because it was
 * simultaneously a JPA column and a wire field; pre-release, with no consumer to keep
 * compatible, that reason expired. Of the three fields once called wrapper, two remain.
 *
 * <p>There is likewise no {@code denialConstraints} component (dropped 2026-08-26, Flyway V35,
 * on the same precedent). A row-level LIMA modal wrote it and nothing read it: enforcement
 * compiles {@code ModelConstraint} rows into the {@code u_}/{@code int_} views, so a constraint
 * that landed here was reported saved and then silently ignored. Denial constraints belong to an
 * integrated class, not to a dataset — which is the shape {@code ModelConstraint} already has.
 */
@NullMarked
public record Dataset(
        UUID id,
        UUID repositoryId,
        String name,
        String description,
        @Nullable String snapshotId,
        List<ColumnProfile> columns,
        @Nullable String graphJson,
        @Nullable DatasetStatus bootstrapStatus
) {
    public Dataset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("dataset name must not be blank");
        }
        if (description == null) {
            throw new IllegalArgumentException("dataset description must not be null (use \"\")");
        }
        columns = List.copyOf(columns);
    }

    public Dataset(UUID id, UUID repositoryId, String name, String description) {
        this(id, repositoryId, name, description, null, List.of(), null, null);
    }

    public Dataset(UUID id, UUID repositoryId, String name, String description, @Nullable String snapshotId) {
        this(id, repositoryId, name, description, snapshotId, List.of(), null, null);
    }

    public Dataset(UUID id, UUID repositoryId, String name, String description, @Nullable String snapshotId, List<ColumnProfile> columns) {
        this(id, repositoryId, name, description, snapshotId, columns, null, null);
    }

    public Dataset(UUID id, UUID repositoryId, String name, String description, @Nullable String snapshotId, List<ColumnProfile> columns, @Nullable String graphJson) {
        this(id, repositoryId, name, description, snapshotId, columns, graphJson, null);
    }
}

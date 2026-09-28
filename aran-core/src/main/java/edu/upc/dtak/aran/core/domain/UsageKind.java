package edu.upc.dtak.aran.core.domain;

import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * What kind of work a {@link UsageEvent} records.
 *
 * <p>Stored as a string, so adding a kind is an application change rather than a migration.
 */
@NullMarked
public enum UsageKind {
    /** An OQL query run from the workbench. Rows, but no bytes — nothing leaves the server. */
    QUERY(Direction.NONE),
    /** An uncapped export — the Python SDK's read path. */
    EXPORT(Direction.EGRESS),
    /** A statement executed through the Avatica JDBC endpoint. */
    JDBC(Direction.EGRESS),
    /**
     * A product's parquet served to an external engine over the Iceberg produce catalog.
     *
     * <p>Counted per response rather than per file: a reader fetches a footer, then column chunks,
     * as separate {@code Range} requests, so the bytes that left are the ones written to that
     * response — not the size of the file they came from.
     */
    ICEBERG(Direction.EGRESS),
    /** A data product materialised to the lake. */
    MATERIALIZE(Direction.STORED),
    /** Bytes uploaded as a dataset. */
    UPLOAD(Direction.INGRESS);

    /**
     * Which way the bytes went — the distinction that makes a byte count mean something.
     *
     * <p>Summing every kind's {@code byte_count} into one "data moved" figure was the first
     * version of the admin screen, and it was worse than showing nothing: a tenant that uploaded
     * 5 GB read identically to one that exported 5 GB, and only the second of those costs egress.
     */
    public enum Direction {
        /** Arrived from a client. */
        INGRESS,
        /** Left to a client. This is the one a cloud bill charges for. */
        EGRESS,
        /** Written to the lake by Aran itself — not movement, but the thing that grows storage. */
        STORED,
        /** No bytes crossed anything. */
        NONE
    }

    private final Direction direction;

    UsageKind(Direction direction) {
        this.direction = direction;
    }

    public Direction direction() {
        return direction;
    }

    /**
     * The kind names in a direction, for the aggregate queries.
     *
     * <p>Returned as strings because {@code usage_event.kind} is a varchar, and passed as a query
     * parameter rather than inlined into the SQL: the classification then has exactly one home,
     * and a new kind cannot be counted in the wrong column by a query nobody remembered to edit.
     */
    public static List<String> namesWithDirection(Direction direction) {
        return Arrays.stream(values())
                .filter(k -> k.direction == direction)
                .map(Enum::name)
                .toList();
    }
}

package edu.upc.dtak.aran.core.domain;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Represents a lightweight grid/tabular preview of a dataset.
 */
@NullMarked
public record DatasetPreview(
        List<String> headers,
        List<List<Object>> rows
) {
    public DatasetPreview {
        if (headers == null) {
            throw new IllegalArgumentException("headers must not be null");
        }
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
    }
}

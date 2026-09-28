package edu.upc.dtak.aran.core.domain;

import org.jspecify.annotations.NullMarked;

@NullMarked
public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

package edu.upc.dtak.aran.core.operator;

import org.jspecify.annotations.NullMarked;

/**
 * The channel an operator uses to say where it has got to.
 *
 * <p>Stages are the real phase boundaries of the work — "downloading snapshot",
 * "building lattice" — not a decorative countdown. An operator that cannot say
 * anything true about its progress reports nothing, and the UI shows an honest
 * spinner instead of a fabricated bar.
 *
 * <p>Implementations are supplied by {@code JobService}, which persists each
 * update onto the job row and pushes it to any SSE listeners.
 */
@NullMarked
public interface ProgressReporter {

    /** Report the phase the operator has just entered. */
    void stage(String stage);

    /** Report completion percentage, 0..100. Optional — only call it if it is real. */
    void percent(int percent);

    /**
     * Discards progress. The default for the synchronous {@code execute(request)}
     * path, where there is no job row to write to.
     */
    ProgressReporter NOOP = new ProgressReporter() {
        @Override
        public void stage(String stage) {
        }

        @Override
        public void percent(int percent) {
        }
    };
}

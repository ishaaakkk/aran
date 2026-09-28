package edu.upc.dtak.aran.core.service;

import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * A promote refused because the tenant's current state makes it unsafe, with {@link #classIris()}
 * naming the classes at stake (empty when the refusal is about the run, not the schema).
 */
@NullMarked
public class PromoteConflictException extends StateConflictException {

    /** UI-OD2: the draft would remove classes another project's integration model promoted. */
    public static final String REMOVES_OTHER_MODELS_CLASSES = "PROMOTE_REMOVES_OTHER_MODELS_CLASSES";
    /** J4-F2: a newer compile of the same model retired this draft; its mappings are PENDING again. */
    public static final String DRAFT_SUPERSEDED = "DRAFT_SUPERSEDED";
    /** The run is not a promotable DRAFT (already promoted, failed, or still running). */
    public static final String RUN_NOT_DRAFT = "RUN_NOT_DRAFT";

    private static final long serialVersionUID = 1L;

    private final transient List<String> classIris;
    private final transient List<String> ownerProjects;

    public PromoteConflictException(String errorCode, String message, List<String> classIris) {
        this(errorCode, message, classIris, List.of());
    }

    /**
     * @param ownerProjects the names of the projects that own {@code classIris} — only those the
     *                      promoter may see; one they may not is never named
     */
    public PromoteConflictException(String errorCode, String message, List<String> classIris,
                                    List<String> ownerProjects) {
        super(errorCode, message);
        this.classIris = List.copyOf(classIris);
        this.ownerProjects = List.copyOf(ownerProjects);
    }

    public List<String> classIris() {
        return classIris;
    }

    public List<String> ownerProjects() {
        return ownerProjects;
    }
}

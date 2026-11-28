package procomp.core.git;

/**
 * Observed installation condition of a single mandatory Git hook in a Repository.
 */
public enum HookState {
    INSTALLED,
    MISSING,
    OUTDATED,
    NOT_EXECUTABLE
}

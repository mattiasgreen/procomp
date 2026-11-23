package procomp.core.build;

/**
 * Explicit display state for a Service's build readiness across CLI and Web UI (no cryptic placeholders).
 */
public enum BuildDisplayState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    ARTIFACT_PRESENT,
    NOT_BUILT,
    NO_BUILDER
}

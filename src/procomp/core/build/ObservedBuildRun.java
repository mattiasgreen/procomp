package procomp.core.build;

import java.util.Objects;
import java.util.Optional;

/**
 * Purely derived read-only observation of a Service's build readiness and latest Build Run.
 */
public record ObservedBuildRun(
    BuildDisplayState displayState,
    Optional<BuildStatus> status,
    Optional<BuildRunRecord> record
) {
    public ObservedBuildRun {
        Objects.requireNonNull(displayState, "displayState must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(record, "record must not be null");
    }
}

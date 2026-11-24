package procomp.core.git;

import java.util.Objects;

/**
 * Effective observed state of a {@code Repository}'s latest {@code Git Run} after PID liveness evaluation.
 */
public record ObservedGitRun(GitRunStatus effectiveStatus, GitRunRecord record) {

    public ObservedGitRun {
        Objects.requireNonNull(effectiveStatus, "effectiveStatus must not be null");
        Objects.requireNonNull(record, "record must not be null");
    }
}

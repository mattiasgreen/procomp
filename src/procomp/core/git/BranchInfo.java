package procomp.core.git;

import java.time.Instant;
import java.util.Objects;

/**
 * Deduplicated local/remote branch entry ordered by committer date descending.
 */
public record BranchInfo(
    String name,
    RefOrigin origin,
    Instant lastCommitAt,
    String shortSubject,
    boolean current
) {
    public enum RefOrigin {
        LOCAL,
        REMOTE,
        LOCAL_AND_REMOTE
    }

    public BranchInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("BranchInfo name must be non-blank");
        }
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(lastCommitAt, "lastCommitAt must not be null");
        Objects.requireNonNull(shortSubject, "shortSubject must not be null");
    }
}

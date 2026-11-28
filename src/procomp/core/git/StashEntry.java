package procomp.core.git;

import java.util.Objects;

/**
 * Single entry from {@code git stash list}.
 */
public record StashEntry(String ref, String message) {
    public StashEntry {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("StashEntry ref must be non-blank");
        }
        Objects.requireNonNull(message, "StashEntry message must not be null");
    }
}

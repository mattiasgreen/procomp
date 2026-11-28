package procomp.core.git;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolved mandatory Git hook specification with its source file path and SHA-256 hex digest.
 */
public record HookSpec(String name, Path sourcePath, String sourceDigest) {

    public HookSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("HookSpec name must be non-blank");
        }
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        if (sourceDigest == null || sourceDigest.isBlank()) {
            throw new IllegalArgumentException("sourceDigest must be non-blank");
        }
    }
}

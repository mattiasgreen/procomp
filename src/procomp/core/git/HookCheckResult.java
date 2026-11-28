package procomp.core.git;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Evaluation result for a single mandatory Git hook in a Repository.
 */
public record HookCheckResult(
    String hookName,
    Path sourcePath,
    Path targetPath,
    HookState state
) {
    public HookCheckResult {
        if (hookName == null || hookName.isBlank()) {
            throw new IllegalArgumentException("hookName must be non-blank");
        }
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(targetPath, "targetPath must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }

    public boolean needsAction() {
        return state != HookState.INSTALLED;
    }
}

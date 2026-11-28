package procomp.core.git;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Outcome of installing or upgrading a single mandatory Git hook in a Repository.
 */
public record HookInstallOutcome(
    String hookName,
    Path targetPath,
    HookInstallAction action
) {
    public HookInstallOutcome {
        if (hookName == null || hookName.isBlank()) {
            throw new IllegalArgumentException("hookName must be non-blank");
        }
        Objects.requireNonNull(targetPath, "targetPath must not be null");
        Objects.requireNonNull(action, "action must not be null");
    }
}

package procomp.core.project;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolved configuration for mandatory Git hooks declared in {@code procomp.json}.
 */
public record GitHooksConfig(
    Optional<Path> sourceDir,
    List<String> requiredHooks,
    Map<String, Path> explicitFiles,
    GitHookInstallMode mode
) {
    public GitHooksConfig {
        Objects.requireNonNull(sourceDir, "sourceDir must not be null");
        requiredHooks = List.copyOf(requiredHooks);
        explicitFiles = Collections.unmodifiableMap(new LinkedHashMap<>(explicitFiles));
        Objects.requireNonNull(mode, "mode must not be null");
        if (sourceDir.isEmpty() && explicitFiles.isEmpty()) {
            throw new IllegalArgumentException("GitHooksConfig requires at least sourceDir or explicitFiles");
        }
    }
}

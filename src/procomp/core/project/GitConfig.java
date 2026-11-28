package procomp.core.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolved top-level Git settings for a Project (mandatory hooks and optional extra Repositories).
 */
public record GitConfig(
    Optional<GitHooksConfig> hooks,
    List<Path> extraRepositories
) {
    public GitConfig {
        Objects.requireNonNull(hooks, "hooks must not be null");
        extraRepositories = List.copyOf(extraRepositories);
    }
}

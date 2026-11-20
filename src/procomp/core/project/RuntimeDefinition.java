package procomp.core.project;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reusable execution template defining how a class of Services runs locally.
 */
public record RuntimeDefinition(
    String name,
    List<String> command,
    Optional<String> healthPath,
    Optional<String> artifactGlob,
    Optional<String> artifactExclude,
    Optional<String> workingDir,
    SecretEnv env
) {
    public RuntimeDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RuntimeDefinition name must be non-blank");
        }
        command = List.copyOf(command);
        Objects.requireNonNull(healthPath);
        Objects.requireNonNull(artifactGlob);
        Objects.requireNonNull(artifactExclude);
        Objects.requireNonNull(workingDir);
        Objects.requireNonNull(env);
    }
}

package procomp.core.project;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reusable build template defining how a class of Services is compiled or packaged into an Artifact.
 */
public record BuilderDefinition(
    String name,
    List<String> command,
    Optional<String> workingDir,
    SecretEnv env
) {
    public BuilderDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("BuilderDefinition name must be non-blank");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("BuilderDefinition command must not be empty");
        }
        command = List.copyOf(command);
        Objects.requireNonNull(workingDir);
        Objects.requireNonNull(env);
    }
}

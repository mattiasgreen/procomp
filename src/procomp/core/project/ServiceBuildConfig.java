package procomp.core.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Resolved build configuration for a Service referencing a declared Builder.
 */
public record ServiceBuildConfig(
    String builder,
    List<String> command,
    Path workingDir,
    SecretEnv env
) {
    public ServiceBuildConfig {
        if (builder == null || builder.isBlank()) {
            throw new IllegalArgumentException("ServiceBuildConfig builder must be non-blank");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("ServiceBuildConfig command must not be empty");
        }
        command = List.copyOf(command);
        Objects.requireNonNull(workingDir);
        Objects.requireNonNull(env);
    }
}

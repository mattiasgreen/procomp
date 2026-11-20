package procomp.core.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Local reverse-proxy Gateway definition (WireMock in v1).
 */
public record GatewayDefinition(
    String type,
    int port,
    Optional<String> healthPath,
    List<String> command,
    Optional<Path> workingDir,
    SecretEnv env
) {
    public GatewayDefinition {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("GatewayDefinition type must be non-blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("GatewayDefinition port out of range: " + port);
        }
        Objects.requireNonNull(healthPath);
        command = List.copyOf(command);
        Objects.requireNonNull(workingDir);
        Objects.requireNonNull(env);
    }

    public String adminUrl() {
        return "http://127.0.0.1:" + port;
    }
}

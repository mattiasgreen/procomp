package procomp.core.process;

import procomp.core.project.GatewayDefinition;
import procomp.core.project.ServiceDefinition;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure target descriptor for Service or Gateway Discovery and Inspection (excludes env secrets).
 */
public record ProcessTarget(
    String name,
    Optional<Integer> port,
    Optional<String> healthPath,
    Path stdoutLog,
    Path stderrLog
) {
    public ProcessTarget {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ProcessTarget name must be non-blank");
        }
        Objects.requireNonNull(port, "port must not be null");
        Objects.requireNonNull(healthPath, "healthPath must not be null");
        Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
        Objects.requireNonNull(stderrLog, "stderrLog must not be null");
    }

    public static ProcessTarget forService(ServiceDefinition service, Path stdoutLog, Path stderrLog) {
        Objects.requireNonNull(service, "service must not be null");
        return new ProcessTarget(service.name(), service.port(), service.healthPath(), stdoutLog, stderrLog);
    }

    public static ProcessTarget forGateway(GatewayDefinition gateway, Path stdoutLog, Path stderrLog) {
        Objects.requireNonNull(gateway, "gateway must not be null");
        return new ProcessTarget("gateway", Optional.of(gateway.port()), gateway.healthPath(), stdoutLog, stderrLog);
    }

    public Optional<String> healthEndpoint() {
        if (port.isPresent() && healthPath.isPresent() && !healthPath.get().isBlank()) {
            String rawPath = healthPath.get().trim();
            String normalizedPath = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
            return Optional.of("http://127.0.0.1:" + port.get() + normalizedPath);
        }
        return Optional.empty();
    }
}

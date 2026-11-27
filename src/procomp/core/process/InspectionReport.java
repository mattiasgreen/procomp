package procomp.core.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deep read-only Inspection diagnostics for a Service's local Process (never contains env values).
 */
public record InspectionReport(
    String service,
    Optional<Integer> port,
    Optional<String> desiredRoute,
    Optional<String> liveGatewayRoute,
    Ownership ownership,
    Optional<String> provenance,
    HealthState health,
    Optional<Long> pid,
    Optional<Duration> uptime,
    Optional<String> commandLine,
    List<String> arguments,
    List<ParentProcess> parentChain,
    Optional<Long> rssBytes,
    Optional<String> healthEndpoint,
    String healthResponse,
    Path outLogPath,
    Path errLogPath,
    boolean stale,
    List<DiagnosticCommand> diagnosticCommands
) {
    public InspectionReport(
        String service,
        Optional<Integer> port,
        Optional<String> desiredRoute,
        Optional<String> liveGatewayRoute,
        Ownership ownership,
        Optional<String> provenance,
        HealthState health,
        Optional<Long> pid,
        Optional<Duration> uptime,
        Optional<String> commandLine,
        List<String> arguments,
        List<ParentProcess> parentChain,
        Optional<Long> rssBytes,
        Optional<String> healthEndpoint,
        String healthResponse,
        Path outLogPath,
        Path errLogPath,
        boolean stale
    ) {
        this(
            service, port, desiredRoute, liveGatewayRoute, ownership, provenance, health, pid, uptime,
            commandLine, arguments, parentChain, rssBytes, healthEndpoint, healthResponse, outLogPath, errLogPath,
            stale, List.of()
        );
    }

    public InspectionReport {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service must be non-blank");
        }
        Objects.requireNonNull(port, "port must not be null");
        Objects.requireNonNull(desiredRoute, "desiredRoute must not be null");
        Objects.requireNonNull(liveGatewayRoute, "liveGatewayRoute must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        Objects.requireNonNull(health, "health must not be null");
        Objects.requireNonNull(pid, "pid must not be null");
        Objects.requireNonNull(uptime, "uptime must not be null");
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        arguments = List.copyOf(arguments);
        parentChain = List.copyOf(parentChain);
        Objects.requireNonNull(rssBytes, "rssBytes must not be null");
        Objects.requireNonNull(healthEndpoint, "healthEndpoint must not be null");
        Objects.requireNonNull(healthResponse, "healthResponse must not be null");
        Objects.requireNonNull(outLogPath, "outLogPath must not be null");
        Objects.requireNonNull(errLogPath, "errLogPath must not be null");
        diagnosticCommands = List.copyOf(diagnosticCommands);
    }
}

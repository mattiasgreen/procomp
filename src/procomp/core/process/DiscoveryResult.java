package procomp.core.process;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure read-only Discovery classification result for a Service or Gateway.
 */
public record DiscoveryResult(
    String target,
    Ownership ownership,
    Optional<String> provenance,
    HealthState health,
    Optional<Long> pid,
    Optional<Instant> startedAt,
    boolean stale,
    Optional<String> healthEndpoint,
    String healthResponse,
    Optional<ObservedProcess> process
) {
    public DiscoveryResult {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must be non-blank");
        }
        Objects.requireNonNull(ownership, "ownership must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        Objects.requireNonNull(health, "health must not be null");
        Objects.requireNonNull(pid, "pid must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(healthEndpoint, "healthEndpoint must not be null");
        Objects.requireNonNull(healthResponse, "healthResponse must not be null");
        Objects.requireNonNull(process, "process must not be null");
    }

    /**
     * Returns elapsed uptime relative to {@code now} when the Process is active, or empty when STOPPED/CRASHED.
     */
    public Optional<Duration> uptime(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (health == HealthState.STOPPED || health == HealthState.CRASHED || startedAt.isEmpty()) {
            return Optional.empty();
        }
        Duration elapsed = Duration.between(startedAt.get(), now);
        return Optional.of(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }
}

package procomp.core.process;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure read-only Discovery classification result for a Service or Gateway,
 * backed by a sealed {@link ProcessState} so illegal ownership/health combinations are unrepresentable.
 */
public record DiscoveryResult(
    String target,
    ProcessState state,
    Optional<String> healthEndpoint,
    String healthResponse
) {
    public DiscoveryResult {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must be non-blank");
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(healthEndpoint, "healthEndpoint must not be null");
        Objects.requireNonNull(healthResponse, "healthResponse must not be null");
    }

    public Ownership ownership() {
        return state.ownership();
    }

    public Optional<String> provenance() {
        return state.provenance();
    }

    public HealthState health() {
        return state.health();
    }

    public Optional<Long> pid() {
        return state.pid();
    }

    public Optional<Instant> startedAt() {
        return state.startedAt();
    }

    public boolean stale() {
        return state.stale();
    }

    public Optional<ObservedProcess> process() {
        return state.process();
    }

    /**
     * Returns elapsed uptime relative to {@code now} when the Process is active, or empty when STOPPED/CRASHED.
     */
    public Optional<Duration> uptime(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (health() == HealthState.STOPPED || health() == HealthState.CRASHED || startedAt().isEmpty()) {
            return Optional.empty();
        }
        Duration elapsed = Duration.between(startedAt().get(), now);
        return Optional.of(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }
}

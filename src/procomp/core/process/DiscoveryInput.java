package procomp.core.process;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete immutable snapshot of inputs gathered by shell for pure Discovery classification.
 */
public record DiscoveryInput(
    ProcessTarget target,
    Optional<ManagedProcessRecord> managedRecord,
    Optional<PortBinding> portListener,
    Optional<ObservedProcess> process,
    HealthCheck healthCheck,
    Optional<Instant> latestArtifactTimestamp
) {
    public DiscoveryInput {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(managedRecord, "managedRecord must not be null");
        Objects.requireNonNull(portListener, "portListener must not be null");
        Objects.requireNonNull(process, "process must not be null");
        Objects.requireNonNull(healthCheck, "healthCheck must not be null");
        Objects.requireNonNull(latestArtifactTimestamp, "latestArtifactTimestamp must not be null");
    }
}

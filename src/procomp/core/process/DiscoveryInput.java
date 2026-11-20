package procomp.core.process;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete immutable snapshot of inputs gathered by shell for pure Discovery classification.
 */
public record DiscoveryInput(
    ProcessTarget target,
    Optional<ManagedRecordView> managedRecord,
    Optional<PortBinding> portListener,
    Optional<ObservedProcess> process,
    HealthProbe healthProbe,
    Optional<Instant> latestArtifactTimestamp
) {
    public DiscoveryInput {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(managedRecord, "managedRecord must not be null");
        Objects.requireNonNull(portListener, "portListener must not be null");
        Objects.requireNonNull(process, "process must not be null");
        Objects.requireNonNull(healthProbe, "healthProbe must not be null");
        Objects.requireNonNull(latestArtifactTimestamp, "latestArtifactTimestamp must not be null");
    }
}

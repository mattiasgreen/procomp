package procomp.core.process;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted metadata for a Managed local OS Process (never stores environment secret values).
 */
public record ManagedProcessRecord(
    long pid,
    Instant startedAt,
    List<String> commandSummary,
    Optional<Instant> artifactTimestamp,
    Path stdoutLog,
    Path stderrLog
) {
    public ManagedProcessRecord {
        if (pid <= 0) {
            throw new IllegalArgumentException("ManagedProcessRecord pid must be positive: " + pid);
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        commandSummary = List.copyOf(commandSummary);
        Objects.requireNonNull(artifactTimestamp, "artifactTimestamp must not be null");
        Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
        Objects.requireNonNull(stderrLog, "stderrLog must not be null");
    }
}

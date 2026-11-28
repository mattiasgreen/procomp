package procomp.core.process;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted lifecycle metadata for a Managed local OS Process (never stores environment secret values).
 * Modeled as a sealed ADT so stop-in-progress metadata is only present in the {@link Stopping} state.
 */
public sealed interface ManagedProcessRecord
    permits ManagedProcessRecord.Running, ManagedProcessRecord.Stopping {

    long pid();

    Instant startedAt();

    List<String> commandSummary();

    Optional<Instant> artifactTimestamp();

    Path stdoutLog();

    Path stderrLog();

    /**
     * Transitions this record to {@link Stopping} with the instant stop was requested.
     */
    default Stopping requestStop(Instant stopRequestedAt) {
        return new Stopping(
            pid(),
            startedAt(),
            stopRequestedAt,
            commandSummary(),
            artifactTimestamp(),
            stdoutLog(),
            stderrLog()
        );
    }

    /**
     * Transitions this record back to {@link Running} (for example, if termination failed).
     */
    default Running abortStop() {
        return new Running(
            pid(),
            startedAt(),
            commandSummary(),
            artifactTimestamp(),
            stdoutLog(),
            stderrLog()
        );
    }

    /**
     * Normal managed process record (not currently stopping).
     */
    record Running(
        long pid,
        Instant startedAt,
        List<String> commandSummary,
        Optional<Instant> artifactTimestamp,
        Path stdoutLog,
        Path stderrLog
    ) implements ManagedProcessRecord {
        public Running {
            if (pid <= 0) {
                throw new IllegalArgumentException("ManagedProcessRecord.Running pid must be positive: " + pid);
            }
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            commandSummary = List.copyOf(commandSummary);
            Objects.requireNonNull(artifactTimestamp, "artifactTimestamp must not be null");
            Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
            Objects.requireNonNull(stderrLog, "stderrLog must not be null");
        }
    }

    /**
     * Managed process record for which graceful/forced termination has been initiated.
     */
    record Stopping(
        long pid,
        Instant startedAt,
        Instant stopRequestedAt,
        List<String> commandSummary,
        Optional<Instant> artifactTimestamp,
        Path stdoutLog,
        Path stderrLog
    ) implements ManagedProcessRecord {
        public Stopping {
            if (pid <= 0) {
                throw new IllegalArgumentException("ManagedProcessRecord.Stopping pid must be positive: " + pid);
            }
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            Objects.requireNonNull(stopRequestedAt, "stopRequestedAt must not be null");
            commandSummary = List.copyOf(commandSummary);
            Objects.requireNonNull(artifactTimestamp, "artifactTimestamp must not be null");
            Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
            Objects.requireNonNull(stderrLog, "stderrLog must not be null");
        }
    }
}

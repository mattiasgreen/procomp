package procomp.core.build;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted metadata for the latest Build Run of a Service.
 * Each variant carries only the non-null fields valid in that state.
 */
public sealed interface BuildRunRecord
    permits BuildRunRecord.Running, BuildRunRecord.Succeeded, BuildRunRecord.Failed {

    long pid();

    Instant startedAt();

    List<String> commandSummary();

    Path stdoutLog();

    Path stderrLog();

    record Running(
        long pid,
        Instant startedAt,
        List<String> commandSummary,
        Path stdoutLog,
        Path stderrLog
    ) implements BuildRunRecord {
        public Running {
            if (pid <= 0) {
                throw new IllegalArgumentException("BuildRunRecord.Running pid must be positive");
            }
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            commandSummary = List.copyOf(commandSummary);
            Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
            Objects.requireNonNull(stderrLog, "stderrLog must not be null");
        }
    }

    record Succeeded(
        long pid,
        Instant startedAt,
        Instant finishedAt,
        List<String> commandSummary,
        Path stdoutLog,
        Path stderrLog
    ) implements BuildRunRecord {
        public Succeeded {
            if (pid <= 0) {
                throw new IllegalArgumentException("BuildRunRecord.Succeeded pid must be positive");
            }
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            Objects.requireNonNull(finishedAt, "finishedAt must not be null");
            commandSummary = List.copyOf(commandSummary);
            Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
            Objects.requireNonNull(stderrLog, "stderrLog must not be null");
        }
    }

    record Failed(
        long pid,
        Instant startedAt,
        Instant finishedAt,
        Optional<Integer> exitCode,
        String reason,
        List<String> commandSummary,
        Path stdoutLog,
        Path stderrLog
    ) implements BuildRunRecord {
        public Failed {
            if (pid <= 0) {
                throw new IllegalArgumentException("BuildRunRecord.Failed pid must be positive");
            }
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            Objects.requireNonNull(finishedAt, "finishedAt must not be null");
            Objects.requireNonNull(exitCode, "exitCode must not be null");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("BuildRunRecord.Failed reason must be non-blank");
            }
            commandSummary = List.copyOf(commandSummary);
            Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
            Objects.requireNonNull(stderrLog, "stderrLog must not be null");
        }
    }
}

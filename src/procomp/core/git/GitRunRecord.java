package procomp.core.git;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted metadata for the latest tracked {@code Git Run} of a {@code Repository}.
 * Modeled as a closed sum type so illegal states (such as {@code RUNNING} with an exit code
 * or {@code SUCCEEDED} without a finish timestamp) are unrepresentable.
 */
public sealed interface GitRunRecord
    permits GitRunRecord.Running, GitRunRecord.Succeeded, GitRunRecord.Failed {

    String repositoryName();

    GitOperation operation();

    long pid();

    Instant startedAt();

    String commandLine();

    Path stdoutLog();

    Path stderrLog();

    default GitRunStatus status() {
        return switch (this) {
            case Running _ -> GitRunStatus.RUNNING;
            case Succeeded _ -> GitRunStatus.SUCCEEDED;
            case Failed _ -> GitRunStatus.FAILED;
        };
    }

    default Optional<Instant> finishedAt() {
        return switch (this) {
            case Running _ -> Optional.empty();
            case Succeeded s -> Optional.of(s.finishedAtInstant());
            case Failed f -> Optional.of(f.finishedAtInstant());
        };
    }

    default Optional<Integer> exitCode() {
        return switch (this) {
            case Running _ -> Optional.empty();
            case Succeeded _ -> Optional.of(0);
            case Failed f -> f.exitCodeValue();
        };
    }

    default Optional<String> reason() {
        return switch (this) {
            case Running _, Succeeded _ -> Optional.empty();
            case Failed f -> Optional.of(f.reasonText());
        };
    }

    record Running(
        String repositoryName,
        GitOperation operation,
        long pid,
        Instant startedAt,
        String commandLine,
        Path stdoutLog,
        Path stderrLog
    ) implements GitRunRecord {
        public Running {
            validateCommon(repositoryName, operation, pid, startedAt, commandLine, stdoutLog, stderrLog);
        }

        public Succeeded succeeded(Instant finishedAt) {
            return new Succeeded(repositoryName, operation, pid, startedAt, finishedAt, commandLine, stdoutLog, stderrLog);
        }

        public Failed failed(Instant finishedAt, Optional<Integer> exitCode, String reason) {
            return new Failed(repositoryName, operation, pid, startedAt, finishedAt, exitCode, reason, commandLine, stdoutLog, stderrLog);
        }
    }

    record Succeeded(
        String repositoryName,
        GitOperation operation,
        long pid,
        Instant startedAt,
        Instant finishedAtInstant,
        String commandLine,
        Path stdoutLog,
        Path stderrLog
    ) implements GitRunRecord {
        public Succeeded {
            validateCommon(repositoryName, operation, pid, startedAt, commandLine, stdoutLog, stderrLog);
            Objects.requireNonNull(finishedAtInstant, "finishedAt must not be null");
        }
    }

    record Failed(
        String repositoryName,
        GitOperation operation,
        long pid,
        Instant startedAt,
        Instant finishedAtInstant,
        Optional<Integer> exitCodeValue,
        String reasonText,
        String commandLine,
        Path stdoutLog,
        Path stderrLog
    ) implements GitRunRecord {
        public Failed {
            validateCommon(repositoryName, operation, pid, startedAt, commandLine, stdoutLog, stderrLog);
            Objects.requireNonNull(finishedAtInstant, "finishedAt must not be null");
            Objects.requireNonNull(exitCodeValue, "exitCode must not be null");
            if (reasonText == null || reasonText.isBlank()) {
                throw new IllegalArgumentException("reason must be non-blank");
            }
        }
    }

    private static void validateCommon(
        String repositoryName,
        GitOperation operation,
        long pid,
        Instant startedAt,
        String commandLine,
        Path stdoutLog,
        Path stderrLog
    ) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalArgumentException("repositoryName must be non-blank");
        }
        Objects.requireNonNull(operation, "operation must not be null");
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
        Objects.requireNonNull(stderrLog, "stderrLog must not be null");
    }
}

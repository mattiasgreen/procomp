package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * OS process execution and inspection port.
 */
public interface ProcessRunner {

    Result<SpawnedProcess, DomainError> spawnDetached(SpawnRequest request);

    Result<Optional<ProcessSnapshot>, DomainError> inspect(long pid);

    boolean isAlive(long pid);

    Result<Unit, DomainError> stopTree(long pid, Duration gracefulTimeout);

    record SpawnRequest(
        List<String> command,
        Path workingDir,
        Map<String, String> envOverrides,
        Path stdoutLog,
        Path stderrLog
    ) {
        public SpawnRequest {
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("SpawnRequest command must not be empty");
            }
            command = List.copyOf(command);
            Objects.requireNonNull(workingDir, "workingDir must not be null");
            envOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(envOverrides));
            Objects.requireNonNull(stdoutLog, "stdoutLog must not be null");
            Objects.requireNonNull(stderrLog, "stderrLog must not be null");
        }

        @Override
        public String toString() {
            return "SpawnRequest[command=" + command + ", workingDir=" + workingDir
                + ", envKeys=" + envOverrides.keySet() + ", stdoutLog=" + stdoutLog + ", stderrLog=" + stderrLog + "]";
        }
    }

    record SpawnedProcess(long pid, Instant startedAt, String commandLine) {
        public SpawnedProcess {
            if (pid <= 0) {
                throw new IllegalArgumentException("pid must be positive");
            }
            Objects.requireNonNull(startedAt);
            Objects.requireNonNull(commandLine);
        }
    }

    record ParentProcessInfo(long pid, Optional<String> commandLine, Optional<String> executable) {
        public ParentProcessInfo {
            Objects.requireNonNull(commandLine);
            Objects.requireNonNull(executable);
        }
    }

    record ProcessSnapshot(
        long pid,
        boolean alive,
        Optional<Instant> startTime,
        Optional<String> commandLine,
        List<String> arguments,
        List<ParentProcessInfo> parentChain,
        Optional<Long> rssBytes,
        Optional<String> executable
    ) {
        public ProcessSnapshot {
            Objects.requireNonNull(startTime);
            Objects.requireNonNull(commandLine);
            arguments = List.copyOf(arguments);
            parentChain = List.copyOf(parentChain);
            Objects.requireNonNull(rssBytes);
            Objects.requireNonNull(executable);
        }
    }
}

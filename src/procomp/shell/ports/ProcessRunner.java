package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.process.ObservedProcess;

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

    Result<Optional<ObservedProcess>, DomainError> inspect(long pid);

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
}

package e2e.scenarios.inmemory;

import e2e.scenarios.inmemory.VirtualOsState.ProcessKind;
import e2e.scenarios.inmemory.VirtualOsState.VirtualProcess;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.process.ObservedProcess;
import procomp.shell.ports.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Stateful in-memory implementation of {@link ProcessRunner} backed by {@link VirtualOsState}.
 */
public final class SimulatedProcessRunner implements ProcessRunner {

    private final VirtualOsState os;

    SimulatedProcessRunner(VirtualOsState os) {
        this.os = Objects.requireNonNull(os, "os must not be null");
    }

    @Override
    public Result<SpawnedProcess, DomainError> spawnDetached(SpawnRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        os.applyLagIfEnabled();

        String targetName = extractTargetName(request.stdoutLog());
        OptionalInt port = extractPort(request.command());
        ProcessKind kind = "gateway".equals(targetName)
            ? ProcessKind.MANAGED_GATEWAY
            : ProcessKind.MANAGED_SERVICE;

        if (port.isPresent() && os.aliveListenerOnPort(port.getAsInt()).isPresent()) {
            return Result.err(DomainError.of(
                "PORT_ALREADY_IN_USE",
                "Cannot spawn " + targetName + ": port " + port.getAsInt() + " is already bound"
            ));
        }

        VirtualProcess proc = os.registerManagedProcess(
            targetName,
            kind,
            port,
            request.command(),
            request.stdoutLog(),
            request.stderrLog()
        );
        return Result.ok(new SpawnedProcess(proc.pid, proc.startedAt, proc.commandLine));
    }

    @Override
    public Result<Optional<ObservedProcess>, DomainError> inspect(long pid) {
        return Result.ok(os.processByPid(pid).map(VirtualProcess::toObservedProcess));
    }

    @Override
    public boolean isAlive(long pid) {
        return os.processByPid(pid).map(p -> p.alive).orElse(false);
    }

    @Override
    public Result<Unit, DomainError> stopTree(long pid, Duration gracefulTimeout) {
        os.stopProcess(pid);
        return Result.ok(Unit.VALUE);
    }

    private static String extractTargetName(Path stdoutLog) {
        Path fileName = stdoutLog.getFileName();
        if (fileName == null) {
            return "unknown";
        }
        String name = fileName.toString();
        if (name.endsWith(".out.log")) {
            return name.substring(0, name.length() - ".out.log".length());
        }
        if (name.endsWith(".err.log")) {
            return name.substring(0, name.length() - ".err.log".length());
        }
        return name;
    }

    private static OptionalInt extractPort(List<String> command) {
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg.startsWith("-Dserver.port=")) {
                return parsePort(arg.substring("-Dserver.port=".length()));
            }
            if (arg.startsWith("-Dbroker.port=")) {
                return parsePort(arg.substring("-Dbroker.port=".length()));
            }
            if ("--port".equals(arg) && i + 1 < command.size()) {
                return parsePort(command.get(i + 1));
            }
        }
        return OptionalInt.empty();
    }

    private static OptionalInt parsePort(String raw) {
        try {
            int p = Integer.parseInt(raw.trim());
            return (p >= 1 && p <= 65535) ? OptionalInt.of(p) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}

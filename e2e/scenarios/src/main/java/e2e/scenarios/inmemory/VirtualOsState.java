package e2e.scenarios.inmemory;

import e2e.scenarios.engine.LagProfile;
import procomp.core.process.ObservedProcess;
import procomp.core.process.ParentProcess;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Shared virtual OS process and TCP port table used by {@link SimulatedProcessRunner},
 * {@link SimulatedPortScanner}, and {@link SimulatedHttpClient}.
 */
final class VirtualOsState {

    enum ProcessKind {
        MANAGED_SERVICE,
        UNMANAGED_SERVICE,
        CONFLICT,
        MANAGED_GATEWAY,
        UNMANAGED_GATEWAY
    }

    static final class VirtualProcess {
        final long pid;
        final String name;
        final ProcessKind kind;
        final OptionalInt port;
        final Instant startedAt;
        final String commandLine;
        final List<String> arguments;
        final List<ParentProcess> parentChain;
        final long rssBytes;
        final String executable;
        final Optional<Path> stdoutLog;
        final Optional<Path> stderrLog;
        boolean alive;

        VirtualProcess(
            long pid,
            String name,
            ProcessKind kind,
            OptionalInt port,
            Instant startedAt,
            String commandLine,
            List<String> arguments,
            List<ParentProcess> parentChain,
            long rssBytes,
            String executable,
            Optional<Path> stdoutLog,
            Optional<Path> stderrLog
        ) {
            this.pid = pid;
            this.name = name;
            this.kind = kind;
            this.port = port;
            this.startedAt = startedAt;
            this.commandLine = commandLine;
            this.arguments = List.copyOf(arguments);
            this.parentChain = List.copyOf(parentChain);
            this.rssBytes = rssBytes;
            this.executable = executable;
            this.stdoutLog = stdoutLog;
            this.stderrLog = stderrLog;
            this.alive = true;
        }

        ObservedProcess toObservedProcess() {
            return new ObservedProcess(
                pid,
                alive,
                Optional.of(startedAt),
                Optional.of(commandLine),
                arguments,
                parentChain,
                Optional.of(rssBytes),
                Optional.of(executable)
            );
        }
    }

    private static final long INITIAL_PID = 10_000L;

    private final FakeClock clock;
    private final InMemoryStateStore stateStore;
    private final Map<Long, VirtualProcess> processesByPid = new LinkedHashMap<>();
    private final Map<Integer, Long> listenerPidByPort = new LinkedHashMap<>();
    private long nextPid = INITIAL_PID;
    private LagProfile lag = LagProfile.NONE;
    private Runnable onGatewayStateChanged = () -> {};

    VirtualOsState(FakeClock clock, InMemoryStateStore stateStore) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
    }

    void setOnGatewayStateChanged(Runnable callback) {
        this.onGatewayStateChanged = Objects.requireNonNull(callback, "callback must not be null");
    }

    void reset() {
        processesByPid.clear();
        listenerPidByPort.clear();
        nextPid = INITIAL_PID;
    }

    void setLag(LagProfile lag) {
        this.lag = Objects.requireNonNull(lag, "lag must not be null");
    }

    LagProfile lag() {
        return lag;
    }

    FakeClock clock() {
        return clock;
    }

    InMemoryStateStore stateStore() {
        return stateStore;
    }

    void applyLagIfEnabled() {
        applyDelayMillis(lag.delayMillis());
    }

    void applyProbeLagIfEnabled() {
        long baseMs = lag.delayMillis();
        if (baseMs > 0) {
            applyDelayMillis(Math.max(1L, baseMs / 4));
        }
    }

    private void applyDelayMillis(long delayMs) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            clock.advanceMillis(delayMs);
        }
    }

    VirtualProcess registerManagedProcess(
        String name,
        ProcessKind kind,
        OptionalInt port,
        List<String> command,
        Path stdoutLog,
        Path stderrLog
    ) {
        long pid = nextPid++;
        Instant startedAt = clock.now();
        String cmdLine = String.join(" ", command);
        String executable = command.get(0);
        List<String> args = command.size() > 1 ? command.subList(1, command.size()) : List.of();
        List<ParentProcess> parents = List.of(
            new ParentProcess(9000L, Optional.of("procomp"), Optional.of("java"))
        );

        VirtualProcess proc = new VirtualProcess(
            pid,
            name,
            kind,
            port,
            startedAt,
            cmdLine,
            args,
            parents,
            kind == ProcessKind.MANAGED_GATEWAY ? 96L * 1024 * 1024 : 128L * 1024 * 1024,
            executable,
            Optional.of(stdoutLog),
            Optional.of(stderrLog)
        );
        processesByPid.put(pid, proc);
        port.ifPresent(p -> listenerPidByPort.put(p, pid));

        stateStore.ensureLogFile(stdoutLog);
        stateStore.ensureLogFile(stderrLog);
        stateStore.appendLog(stdoutLog, "[" + startedAt + "] INFO  Starting " + name
            + (port.isPresent() ? " on port " + port.getAsInt() : "") + " (PID " + pid + ")");
        stateStore.appendLog(stdoutLog, "[" + startedAt + "] INFO  " + name + " is ready");

        if (kind == ProcessKind.MANAGED_GATEWAY) {
            onGatewayStateChanged.run();
        }
        return proc;
    }

    VirtualProcess spawnUnmanagedService(String serviceName, int port, boolean ideAncestor) {
        if (listenerPidByPort.containsKey(port)) {
            throw new IllegalStateException("Port " + port + " is already bound by PID " + listenerPidByPort.get(port));
        }
        long pid = nextPid++;
        Instant startedAt = clock.now();
        List<String> args = List.of("-Dserver.port=" + port, "-jar", serviceName + ".jar");
        String cmdLine = "java " + String.join(" ", args);
        List<ParentProcess> parents = ideAncestor
            ? List.of(new ParentProcess(
                9001L,
                Optional.of("C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe"),
                Optional.of("idea64.exe")
            ))
            : List.of(new ParentProcess(9002L, Optional.of("pwsh.exe"), Optional.of("pwsh.exe")));

        VirtualProcess proc = new VirtualProcess(
            pid,
            serviceName,
            ProcessKind.UNMANAGED_SERVICE,
            OptionalInt.of(port),
            startedAt,
            cmdLine,
            args,
            parents,
            142L * 1024 * 1024,
            "java",
            Optional.empty(),
            Optional.empty()
        );
        processesByPid.put(pid, proc);
        listenerPidByPort.put(port, pid);
        return proc;
    }

    VirtualProcess spawnConflict(String serviceName, int port) {
        if (listenerPidByPort.containsKey(port)) {
            throw new IllegalStateException("Port " + port + " is already bound by PID " + listenerPidByPort.get(port));
        }
        long pid = nextPid++;
        Instant startedAt = clock.now();
        List<String> args = List.of("-m", "http.server", Integer.toString(port));
        String cmdLine = "python3 -m http.server " + port;
        List<ParentProcess> parents = List.of(
            new ParentProcess(9003L, Optional.of("/bin/bash"), Optional.of("bash"))
        );

        VirtualProcess proc = new VirtualProcess(
            pid,
            "conflict-" + serviceName,
            ProcessKind.CONFLICT,
            OptionalInt.of(port),
            startedAt,
            cmdLine,
            args,
            parents,
            24L * 1024 * 1024,
            "python3",
            Optional.empty(),
            Optional.empty()
        );
        processesByPid.put(pid, proc);
        listenerPidByPort.put(port, pid);
        return proc;
    }

    VirtualProcess spawnUnmanagedGateway(int gatewayPort) {
        if (listenerPidByPort.containsKey(gatewayPort)) {
            throw new IllegalStateException(
                "Gateway port " + gatewayPort + " is already bound by PID " + listenerPidByPort.get(gatewayPort)
            );
        }
        long pid = nextPid++;
        Instant startedAt = clock.now();
        List<String> args = List.of("-jar", "wiremock-standalone.jar", "--port", Integer.toString(gatewayPort));
        String cmdLine = "java " + String.join(" ", args);
        List<ParentProcess> parents = List.of(
            new ParentProcess(9002L, Optional.of("pwsh.exe"), Optional.of("pwsh.exe"))
        );

        VirtualProcess proc = new VirtualProcess(
            pid,
            "gateway",
            ProcessKind.UNMANAGED_GATEWAY,
            OptionalInt.of(gatewayPort),
            startedAt,
            cmdLine,
            args,
            parents,
            104L * 1024 * 1024,
            "java",
            Optional.empty(),
            Optional.empty()
        );
        processesByPid.put(pid, proc);
        listenerPidByPort.put(gatewayPort, pid);
        onGatewayStateChanged.run();
        return proc;
    }

    void killManagedService(String serviceName) {
        VirtualProcess target = null;
        for (VirtualProcess proc : processesByPid.values()) {
            if (proc.alive && proc.kind == ProcessKind.MANAGED_SERVICE && proc.name.equals(serviceName)) {
                target = proc;
            }
        }
        if (target == null) {
            throw new IllegalStateException("No alive Managed process found for service '" + serviceName + "'");
        }
        long targetPid = target.pid;
        target.alive = false;
        target.port.ifPresent(p -> {
            Long owner = listenerPidByPort.get(p);
            if (owner != null && owner == targetPid) {
                listenerPidByPort.remove(p);
            }
        });
        target.stderrLog.ifPresent(errPath -> stateStore.appendLog(
            errPath,
            "[" + clock.now() + "] ERROR " + serviceName + " terminated unexpectedly (SIGKILL, PID " + targetPid + ")"
        ));
    }

    void stopProcess(long pid) {
        VirtualProcess proc = processesByPid.get(pid);
        if (proc == null || !proc.alive) {
            return;
        }
        proc.alive = false;
        proc.port.ifPresent(p -> {
            Long owner = listenerPidByPort.get(p);
            if (owner != null && owner == pid) {
                listenerPidByPort.remove(p);
            }
        });
        if (proc.kind == ProcessKind.MANAGED_GATEWAY || proc.kind == ProcessKind.UNMANAGED_GATEWAY) {
            onGatewayStateChanged.run();
        }
    }

    Optional<VirtualProcess> processByPid(long pid) {
        return Optional.ofNullable(processesByPid.get(pid));
    }

    Optional<VirtualProcess> aliveListenerOnPort(int port) {
        Long pid = listenerPidByPort.get(port);
        if (pid == null) {
            return Optional.empty();
        }
        VirtualProcess proc = processesByPid.get(pid);
        if (proc == null || !proc.alive) {
            listenerPidByPort.remove(port);
            return Optional.empty();
        }
        return Optional.of(proc);
    }

    boolean isServiceAlive(String serviceName) {
        for (VirtualProcess proc : processesByPid.values()) {
            if (proc.alive
                && (proc.kind == ProcessKind.MANAGED_SERVICE || proc.kind == ProcessKind.UNMANAGED_SERVICE)
                && proc.name.equals(serviceName)) {
                return true;
            }
        }
        return false;
    }
}

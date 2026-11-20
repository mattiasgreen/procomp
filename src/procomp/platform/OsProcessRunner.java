package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.shell.ports.ProcessRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cross-platform OS {@link ProcessRunner} adapter backed by {@link ProcessBuilder} and {@link ProcessHandle}.
 * Note: {@code rssBytes} is reported as {@link Optional#empty()} because {@link ProcessHandle} does not
 * expose resident memory size portably across OSes without external per-process tool shelling.
 */
public final class OsProcessRunner implements ProcessRunner {

    private static final int MAX_PARENT_DEPTH = 64;
    private static final Duration FORCE_KILL_WAIT = Duration.ofSeconds(5);

    @Override
    public Result<SpawnedProcess, DomainError> spawnDetached(SpawnRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!Files.isDirectory(request.workingDir())) {
            return Result.err(DomainError.of(
                "WORKING_DIR_NOT_FOUND",
                "Working directory does not exist: " + request.workingDir(),
                Map.of("workingDir", request.workingDir().toString())
            ));
        }

        List<String> effectiveCommand = new ArrayList<>(request.command());
        effectiveCommand.set(0, resolveExecutable(request.command().get(0), request.workingDir()));

        try {
            ensureParentDir(request.stdoutLog());
            ensureParentDir(request.stderrLog());

            ProcessBuilder pb = new ProcessBuilder(effectiveCommand);
            pb.directory(request.workingDir().toFile());
            if (!request.envOverrides().isEmpty()) {
                pb.environment().putAll(request.envOverrides());
            }
            pb.redirectInput(ProcessBuilder.Redirect.from(nullDeviceFile()));
            pb.redirectOutput(ProcessBuilder.Redirect.to(request.stdoutLog().toFile()));
            pb.redirectError(ProcessBuilder.Redirect.to(request.stderrLog().toFile()));

            Process process = pb.start();
            ProcessHandle.Info info = process.toHandle().info();
            Instant startedAt = info.startInstant().orElseGet(Instant::now);
            String commandLine = info.commandLine().orElseGet(() -> String.join(" ", effectiveCommand));
            return Result.ok(new SpawnedProcess(process.pid(), startedAt, commandLine));
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "PROCESS_SPAWN_ERROR",
                "Failed to spawn process '" + effectiveCommand.get(0) + "': " + e.getMessage(),
                Map.of("executable", effectiveCommand.get(0), "workingDir", request.workingDir().toString())
            ));
        }
    }

    @Override
    public Result<Optional<ProcessSnapshot>, DomainError> inspect(long pid) {
        if (pid <= 0) {
            return Result.ok(Optional.empty());
        }
        Optional<ProcessHandle> handleOpt = ProcessHandle.of(pid);
        if (handleOpt.isEmpty() || !handleOpt.get().isAlive()) {
            return Result.ok(Optional.empty());
        }
        ProcessHandle handle = handleOpt.get();
        ProcessHandle.Info info = handle.info();
        List<String> arguments = info.arguments().map(Arrays::asList).orElse(List.of());
        Optional<String> executable = info.command();
        Optional<String> commandLine = info.commandLine().or(() -> executable.map(exe ->
            arguments.isEmpty() ? exe : exe + " " + String.join(" ", arguments)
        ));

        List<ParentProcessInfo> parentChain = collectParents(handle);
        return Result.ok(Optional.of(new ProcessSnapshot(
            handle.pid(),
            true,
            info.startInstant(),
            commandLine,
            arguments,
            parentChain,
            Optional.empty(),
            executable
        )));
    }

    @Override
    public boolean isAlive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Override
    public Result<Unit, DomainError> stopTree(long pid, Duration gracefulTimeout) {
        Objects.requireNonNull(gracefulTimeout, "gracefulTimeout must not be null");
        if (pid <= 0) {
            return Result.ok(Unit.VALUE);
        }
        Optional<ProcessHandle> rootOpt = ProcessHandle.of(pid);
        if (rootOpt.isEmpty() || !rootOpt.get().isAlive()) {
            return Result.ok(Unit.VALUE);
        }
        ProcessHandle root = rootOpt.get();
        List<ProcessHandle> descendants = new ArrayList<>(root.descendants().toList());
        Collections.reverse(descendants);

        for (ProcessHandle child : descendants) {
            if (child.isAlive()) {
                child.destroy();
            }
        }
        root.destroy();

        List<ProcessHandle> allHandles = new ArrayList<>(descendants.size() + 1);
        allHandles.addAll(descendants);
        allHandles.add(root);

        if (!waitForAllExit(allHandles, gracefulTimeout)) {
            if (Thread.currentThread().isInterrupted()) {
                return Result.err(DomainError.of(
                    "PROCESS_STOP_INTERRUPTED",
                    "Interrupted while stopping process PID " + pid,
                    Map.of("pid", Long.toString(pid))
                ));
            }
            for (ProcessHandle handle : allHandles) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            }
            waitForAllExit(allHandles, FORCE_KILL_WAIT);
            if (Thread.currentThread().isInterrupted()) {
                return Result.err(DomainError.of(
                    "PROCESS_STOP_INTERRUPTED",
                    "Interrupted while force-stopping process PID " + pid,
                    Map.of("pid", Long.toString(pid))
                ));
            }
        }

        if (root.isAlive()) {
            return Result.err(DomainError.of(
                "PROCESS_STOP_FAILED",
                "Process PID " + pid + " remained alive after termination attempt",
                Map.of("pid", Long.toString(pid))
            ));
        }
        return Result.ok(Unit.VALUE);
    }

    private static List<ParentProcessInfo> collectParents(ProcessHandle handle) {
        List<ParentProcessInfo> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(handle.pid());
        Optional<ProcessHandle> cursor = handle.parent();
        while (cursor.isPresent() && chain.size() < MAX_PARENT_DEPTH) {
            ProcessHandle parent = cursor.get();
            if (!visited.add(parent.pid())) {
                break;
            }
            ProcessHandle.Info pInfo = parent.info();
            chain.add(new ParentProcessInfo(parent.pid(), pInfo.commandLine(), pInfo.command()));
            cursor = parent.parent();
        }
        return chain;
    }

    private static boolean waitForAllExit(List<ProcessHandle> handles, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            return handles.stream().noneMatch(ProcessHandle::isAlive);
        }
        List<CompletableFuture<ProcessHandle>> futures = handles.stream()
            .filter(ProcessHandle::isAlive)
            .map(ProcessHandle::onExit)
            .toList();
        if (futures.isEmpty()) {
            return true;
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return handles.stream().noneMatch(ProcessHandle::isAlive);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return handles.stream().noneMatch(ProcessHandle::isAlive);
        }
    }

    private static String resolveExecutable(String rawExe, Path workingDir) {
        if (Path.of(rawExe).isAbsolute()) {
            return rawExe;
        }
        if (rawExe.contains("/") || rawExe.contains("\\")) {
            return workingDir.resolve(rawExe).normalize().toString();
        }
        Path candidateInWorkingDir = workingDir.resolve(rawExe).normalize();
        if (Files.isRegularFile(candidateInWorkingDir)) {
            return candidateInWorkingDir.toString();
        }
        return rawExe;
    }

    private static void ensureParentDir(Path file) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static File nullDeviceFile() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return new File(os.contains("win") ? "NUL" : "/dev/null");
    }
}

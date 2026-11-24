package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.process.ObservedProcess;
import procomp.core.process.ParentProcess;
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
 * Cross-platform OS {@link ProcessRunner} adapter backed by {@link ProcessBuilder} and {@link ProcessHandle},
 * with best-effort batch RSS snapshots via {@code tasklist /FO CSV /NH} (Windows) or {@code ps -eo pid=,rss=} (POSIX).
 */
public final class OsProcessRunner implements ProcessRunner {

    private static final int MAX_PARENT_DEPTH = 64;
    private static final Duration FORCE_KILL_WAIT = Duration.ofSeconds(5);
    private static final long RSS_COMMAND_TIMEOUT_SECONDS = 5L;

    @Override
    public Result<SpawnedProcess, DomainError> spawnDetached(SpawnRequest request) {
        return startProcess(request).map(started ->
            new SpawnedProcess(started.process().pid(), started.startedAt(), started.commandLine())
        );
    }

    @Override
    public Result<RunningProcess, DomainError> spawnAttached(SpawnRequest request) {
        return startProcess(request).map(started -> new OsRunningProcess(
            started.process(), started.startedAt(), started.commandLine()
        ));
    }

    private record StartedProcess(Process process, Instant startedAt, String commandLine) {}

    private static final class OsRunningProcess implements RunningProcess {
        private final Process process;
        private final Instant startedAt;
        private final String commandLine;

        OsRunningProcess(Process process, Instant startedAt, String commandLine) {
            this.process = process;
            this.startedAt = startedAt;
            this.commandLine = commandLine;
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public Instant startedAt() {
            return startedAt;
        }

        @Override
        public String commandLine() {
            return commandLine;
        }

        @Override
        public ExitOutcome awaitExit(Optional<Duration> timeout) {
            Objects.requireNonNull(timeout, "timeout must not be null");
            try {
                if (timeout.isEmpty()) {
                    int code = process.waitFor();
                    return new ExitOutcome.Exited(code);
                }
                long millis = Math.max(0L, timeout.get().toMillis());
                boolean exited = process.waitFor(millis, TimeUnit.MILLISECONDS);
                if (!exited) {
                    return ExitOutcome.TimedOut.INSTANCE;
                }
                return new ExitOutcome.Exited(process.exitValue());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ExitOutcome.Interrupted.INSTANCE;
            }
        }
    }

    private Result<StartedProcess, DomainError> startProcess(SpawnRequest request) {
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
            return Result.ok(new StartedProcess(process, startedAt, commandLine));
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "PROCESS_SPAWN_ERROR",
                "Failed to spawn process '" + effectiveCommand.get(0) + "': " + e.getMessage(),
                Map.of("executable", effectiveCommand.get(0), "workingDir", request.workingDir().toString())
            ));
        }
    }

    @Override
    public Result<Optional<ObservedProcess>, DomainError> inspect(long pid) {
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

        List<ParentProcess> parentChain = collectParents(handle);
        return Result.ok(Optional.of(new ObservedProcess(
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
    public Map<Long, Long> snapshotRssBytes() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmd = isWindows
            ? List.of("tasklist", "/FO", "CSV", "/NH")
            : List.of("ps", "-eo", "pid=,rss=");
        Optional<String> out = runCommandQuiet(cmd);
        if (out.isEmpty()) {
            return Map.of();
        }
        return isWindows
            ? OsRssParser.parseWindowsTasklistCsv(out.get())
            : OsRssParser.parsePosixPsRss(out.get());
    }

    private static Optional<String> runCommandQuiet(List<String> command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            Process proc = process;
            CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return proc.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                    return new byte[0];
                }
            });
            boolean finished = process.waitFor(RSS_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            byte[] bytes = outputFuture.get(1, TimeUnit.SECONDS);
            return Optional.of(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return Optional.empty();
        } catch (IOException | ExecutionException | TimeoutException ignored) {
            if (process != null) {
                process.destroyForcibly();
            }
            return Optional.empty();
        }
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
            List<ProcessHandle> latestDescendants = new ArrayList<>(root.descendants().toList());
            Collections.reverse(latestDescendants);
            Set<Long> seenPids = new HashSet<>();
            for (ProcessHandle h : allHandles) {
                seenPids.add(h.pid());
            }
            for (ProcessHandle child : latestDescendants) {
                if (seenPids.add(child.pid())) {
                    allHandles.add(child);
                }
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

        List<String> survivingPids = allHandles.stream()
            .filter(ProcessHandle::isAlive)
            .map(h -> Long.toString(h.pid()))
            .toList();
        if (!survivingPids.isEmpty()) {
            return Result.err(DomainError.of(
                "PROCESS_STOP_FAILED",
                "Process PID " + pid + " (or descendant) remained alive after termination attempt: " + String.join(", ", survivingPids),
                Map.of("pid", Long.toString(pid), "survivingPids", String.join(",", survivingPids))
            ));
        }
        return Result.ok(Unit.VALUE);
    }

    private static List<ParentProcess> collectParents(ProcessHandle handle) {
        List<ParentProcess> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(handle.pid());
        Optional<ProcessHandle> cursor = handle.parent();
        while (cursor.isPresent() && chain.size() < MAX_PARENT_DEPTH) {
            ProcessHandle parent = cursor.get();
            if (!visited.add(parent.pid())) {
                break;
            }
            ProcessHandle.Info pInfo = parent.info();
            chain.add(new ParentProcess(parent.pid(), pInfo.commandLine(), pInfo.command()));
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
                .get(Math.max(1L, timeout.toNanos()), TimeUnit.NANOSECONDS);
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
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (Path.of(rawExe).isAbsolute()) {
            return resolveWindowsExt(Path.of(rawExe), isWindows).map(Path::toString).orElse(rawExe);
        }
        if (rawExe.contains("/") || rawExe.contains("\\")) {
            Path resolved = workingDir.resolve(rawExe).normalize();
            return resolveWindowsExt(resolved, isWindows).map(Path::toString).orElse(resolved.toString());
        }
        Path candidateInWorkingDir = workingDir.resolve(rawExe).normalize();
        Optional<Path> found = resolveWindowsExt(candidateInWorkingDir, isWindows);
        if (found.isPresent()) {
            return found.get().toString();
        }
        return rawExe;
    }

    static Optional<Path> resolveWindowsExt(Path base, boolean isWindows) {
        if (!isWindows) {
            return Files.isRegularFile(base) ? Optional.of(base) : Optional.empty();
        }
        String fileName = base.getFileName() != null ? base.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        boolean hasWinExecExt = fileName.endsWith(".exe") || fileName.endsWith(".cmd")
            || fileName.endsWith(".bat") || fileName.endsWith(".com");
        if (hasWinExecExt && Files.isRegularFile(base)) {
            return Optional.of(base);
        }
        if (!hasWinExecExt) {
            for (String ext : List.of(".exe", ".cmd", ".bat", ".com")) {
                Path withExt = base.resolveSibling(base.getFileName() + ext);
                if (Files.isRegularFile(withExt)) {
                    return Optional.of(withExt);
                }
            }
        }
        return Files.isRegularFile(base) ? Optional.of(base) : Optional.empty();
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

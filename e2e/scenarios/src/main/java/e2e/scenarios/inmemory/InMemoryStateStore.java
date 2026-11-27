package e2e.scenarios.inmemory;

import procomp.core.build.BuildRunRecord;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.git.GitRunRecord;
import procomp.core.process.LogTailer;
import procomp.core.process.ManagedProcessRecord;
import procomp.shell.ports.LogChunk;
import procomp.shell.ports.LogPage;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.StateStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * In-memory implementation of {@link StateStore} storing project routing tables,
 * managed process records, and raw UTF-8 stdout/stderr log buffers.
 */
public final class InMemoryStateStore implements StateStore {

    private final Path rootStateDir;
    private final Map<String, Map<String, String>> routingByProject = new LinkedHashMap<>();
    private final Map<String, Map<ProcessKey, ManagedProcessRecord>> managedByProject = new LinkedHashMap<>();
    private final Map<String, Map<String, BuildRunRecord>> buildRunsByProject = new LinkedHashMap<>();
    private final Map<String, Map<String, GitRunRecord>> gitRunsByProject = new LinkedHashMap<>();
    private final Map<Path, StringBuilder> logs = new LinkedHashMap<>();

    public InMemoryStateStore() {
        this(Path.of("/in-memory-state").toAbsolutePath().normalize());
    }

    public InMemoryStateStore(Path rootStateDir) {
        this.rootStateDir = Objects.requireNonNull(rootStateDir, "rootStateDir must not be null")
            .toAbsolutePath().normalize();
    }

    public synchronized void clear() {
        routingByProject.clear();
        managedByProject.clear();
        buildRunsByProject.clear();
        gitRunsByProject.clear();
        logs.clear();
    }

    public synchronized void ensureLogFile(Path logPath) {
        logs.computeIfAbsent(normalize(logPath), _ -> new StringBuilder());
    }

    public synchronized void appendLog(Path logPath, String line) {
        Objects.requireNonNull(line, "line must not be null");
        StringBuilder sb = logs.computeIfAbsent(normalize(logPath), _ -> new StringBuilder());
        sb.append(line);
        if (!line.endsWith("\n")) {
            sb.append('\n');
        }
    }

    @Override
    public Path projectStateDir(String project) {
        return rootStateDir.resolve(project).normalize();
    }

    @Override
    public synchronized Result<Optional<Map<String, String>>, DomainError> readRouting(String project) {
        Map<String, String> routes = routingByProject.get(project);
        if (routes == null) {
            return Result.ok(Optional.empty());
        }
        return Result.ok(Optional.of(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(routes))));
    }

    @Override
    public synchronized Result<Unit, DomainError> writeRouting(String project, Map<String, String> routes) {
        routingByProject.put(project, new LinkedHashMap<>(routes));
        return Result.ok(Unit.VALUE);
    }

    @Override
    public synchronized Result<Optional<ManagedProcessRecord>, DomainError> readManagedProcess(String project, ProcessKey key) {
        Map<ProcessKey, ManagedProcessRecord> map = managedByProject.get(project);
        if (map == null) {
            return Result.ok(Optional.empty());
        }
        return Result.ok(Optional.ofNullable(map.get(key)));
    }

    @Override
    public synchronized Result<Unit, DomainError> writeManagedProcess(String project, ProcessKey key, ManagedProcessRecord record) {
        managedByProject.computeIfAbsent(project, _ -> new LinkedHashMap<>()).put(key, record);
        return Result.ok(Unit.VALUE);
    }

    @Override
    public synchronized Result<Unit, DomainError> deleteManagedProcess(String project, ProcessKey key) {
        Map<ProcessKey, ManagedProcessRecord> map = managedByProject.get(project);
        if (map != null) {
            map.remove(key);
        }
        return Result.ok(Unit.VALUE);
    }

    @Override
    public synchronized Result<Optional<BuildRunRecord>, DomainError> readBuildRun(String project, String serviceName) {
        Map<String, BuildRunRecord> map = buildRunsByProject.get(project);
        if (map == null) {
            return Result.ok(Optional.empty());
        }
        return Result.ok(Optional.ofNullable(map.get(serviceName)));
    }

    @Override
    public synchronized Result<Unit, DomainError> writeBuildRun(String project, String serviceName, BuildRunRecord record) {
        buildRunsByProject.computeIfAbsent(project, _ -> new LinkedHashMap<>()).put(serviceName, record);
        return Result.ok(Unit.VALUE);
    }

    @Override
    public synchronized Result<Optional<GitRunRecord>, DomainError> readGitRun(String project, String repositoryName) {
        Map<String, GitRunRecord> map = gitRunsByProject.get(project);
        if (map == null) {
            return Result.ok(Optional.empty());
        }
        return Result.ok(Optional.ofNullable(map.get(repositoryName)));
    }

    @Override
    public synchronized Result<Unit, DomainError> writeGitRun(String project, String repositoryName, GitRunRecord record) {
        gitRunsByProject.computeIfAbsent(project, _ -> new LinkedHashMap<>()).put(repositoryName, record);
        return Result.ok(Unit.VALUE);
    }

    @Override
    public Path logPath(String project, ProcessKey key, boolean stderr) {
        String baseName = switch (key) {
            case ProcessKey.Service(String svc) -> svc;
            case ProcessKey.Gateway _ -> "gateway";
            case ProcessKey.Build(String svc) -> svc + ".build";
            case ProcessKey.Git(String repo) -> repo + ".git";
        };
        String suffix = stderr ? ".err.log" : ".out.log";
        return projectStateDir(project).resolve("logs").resolve(baseName + suffix).normalize();
    }

    @Override
    public synchronized Result<String, DomainError> readLogText(Path logPath) {
        StringBuilder sb = logs.get(normalize(logPath));
        if (sb == null) {
            return Result.err(DomainError.of(
                "LOG_NOT_FOUND",
                "Log file not found: " + logPath,
                Map.of("path", logPath.toString())
            ));
        }
        return Result.ok(sb.toString());
    }

    @Override
    public synchronized Result<LogChunk, DomainError> readLogChunk(Path logPath, long byteOffset) {
        StringBuilder sb = logs.get(normalize(logPath));
        if (sb == null) {
            return Result.err(DomainError.of(
                "LOG_NOT_FOUND",
                "Log file not found: " + logPath,
                Map.of("path", logPath.toString())
            ));
        }
        byte[] allBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        int size = allBytes.length;
        int start = byteOffset > size ? 0 : (int) Math.max(0L, byteOffset);
        if (start >= size) {
            return Result.ok(new LogChunk("", size));
        }
        String slice = new String(allBytes, start, size - start, StandardCharsets.UTF_8);
        return Result.ok(new LogChunk(slice, size));
    }

    @Override
    public synchronized Result<LogPage, DomainError> readLogPageBackward(
        Path logPath,
        OptionalLong beforeOffset,
        int maxLines,
        int maxBytes
    ) {
        if (maxLines < 0) {
            throw new IllegalArgumentException("maxLines must be non-negative: " + maxLines);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (beforeOffset.isPresent() && beforeOffset.getAsLong() < 0) {
            throw new IllegalArgumentException("beforeOffset must be non-negative: " + beforeOffset.getAsLong());
        }
        StringBuilder sb = logs.get(normalize(logPath));
        if (sb == null) {
            return Result.err(DomainError.of(
                "LOG_NOT_FOUND",
                "Log file not found: " + logPath,
                Map.of("path", logPath.toString())
            ));
        }
        byte[] allBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        long size = allBytes.length;
        long endOffset;
        if (beforeOffset.isPresent()) {
            long requested = beforeOffset.getAsLong();
            if (requested > size) {
                return Result.ok(new LogPage("", 0L, 0L, false, true));
            }
            endOffset = requested;
        } else {
            endOffset = size;
        }
        if (endOffset == 0L || maxLines == 0) {
            return Result.ok(new LogPage("", endOffset, endOffset, endOffset > 0L, false));
        }
        long windowStart = Math.max(0L, endOffset - maxBytes);
        byte[] sliceBytes = Arrays.copyOfRange(allBytes, (int) windowStart, (int) endOffset);
        LogTailer.BackwardSlice slice = LogTailer.sliceBackward(sliceBytes, windowStart, maxLines);
        return Result.ok(new LogPage(slice.text(), slice.startOffset(), slice.endOffset(), slice.hasOlder(), false));
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path must not be null").toAbsolutePath().normalize();
    }
}

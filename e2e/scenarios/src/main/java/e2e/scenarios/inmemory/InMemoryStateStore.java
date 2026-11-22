package e2e.scenarios.inmemory;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.process.ManagedProcessRecord;
import procomp.shell.ports.LogChunk;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.StateStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory implementation of {@link StateStore} storing project routing tables,
 * managed process records, and raw UTF-8 stdout/stderr log buffers.
 */
public final class InMemoryStateStore implements StateStore {

    private final Path rootStateDir;
    private final Map<String, Map<String, String>> routingByProject = new LinkedHashMap<>();
    private final Map<String, Map<ProcessKey, ManagedProcessRecord>> managedByProject = new LinkedHashMap<>();
    private final Map<Path, StringBuilder> logs = new LinkedHashMap<>();

    public InMemoryStateStore() {
        this(Path.of("/in-memory-state").toAbsolutePath().normalize());
    }

    public InMemoryStateStore(Path rootStateDir) {
        this.rootStateDir = Objects.requireNonNull(rootStateDir, "rootStateDir must not be null")
            .toAbsolutePath().normalize();
    }

    public void clear() {
        routingByProject.clear();
        managedByProject.clear();
        logs.clear();
    }

    public void ensureLogFile(Path logPath) {
        logs.computeIfAbsent(normalize(logPath), _ -> new StringBuilder());
    }

    public void appendLog(Path logPath, String line) {
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
    public Result<Optional<Map<String, String>>, DomainError> readRouting(String project) {
        Map<String, String> routes = routingByProject.get(project);
        if (routes == null) {
            return Result.ok(Optional.empty());
        }
        return Result.ok(Optional.of(Map.copyOf(routes)));
    }

    @Override
    public Result<Unit, DomainError> writeRouting(String project, Map<String, String> routes) {
        routingByProject.put(project, new LinkedHashMap<>(routes));
        return Result.ok(Unit.VALUE);
    }

    @Override
    public Result<Optional<ManagedProcessRecord>, DomainError> readManagedProcess(String project, ProcessKey key) {
        Map<ProcessKey, ManagedProcessRecord> map = managedByProject.get(project);
        if (map == null) {
            return Result.ok(Optional.empty());
        }
        return Result.ok(Optional.ofNullable(map.get(key)));
    }

    @Override
    public Result<Unit, DomainError> writeManagedProcess(String project, ProcessKey key, ManagedProcessRecord record) {
        managedByProject.computeIfAbsent(project, _ -> new LinkedHashMap<>()).put(key, record);
        return Result.ok(Unit.VALUE);
    }

    @Override
    public Result<Unit, DomainError> deleteManagedProcess(String project, ProcessKey key) {
        Map<ProcessKey, ManagedProcessRecord> map = managedByProject.get(project);
        if (map != null) {
            map.remove(key);
        }
        return Result.ok(Unit.VALUE);
    }

    @Override
    public Path logPath(String project, ProcessKey key, boolean stderr) {
        String baseName = switch (key) {
            case ProcessKey.Service(String svc) -> svc;
            case ProcessKey.Gateway _ -> "gateway";
        };
        String suffix = stderr ? ".err.log" : ".out.log";
        return projectStateDir(project).resolve("logs").resolve(baseName + suffix).normalize();
    }

    @Override
    public Result<String, DomainError> readLogText(Path logPath) {
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
    public Result<LogChunk, DomainError> readLogChunk(Path logPath, long byteOffset) {
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

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path must not be null").toAbsolutePath().normalize();
    }
}

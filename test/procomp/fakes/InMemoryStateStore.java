package procomp.fakes;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.shell.ports.LogChunk;
import procomp.shell.ports.ManagedProcessRecord;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.StateStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryStateStore implements StateStore {

    private final Path baseStateDir;
    private final Map<String, Map<String, String>> routingByProject = new LinkedHashMap<>();
    private final Map<String, Map<String, ManagedProcessRecord>> serviceProcesses = new LinkedHashMap<>();
    private final Map<String, ManagedProcessRecord> gatewayProcesses = new LinkedHashMap<>();
    private final Map<Path, byte[]> logs = new LinkedHashMap<>();

    public InMemoryStateStore() {
        this(Path.of("/in-memory-state").toAbsolutePath().normalize());
    }

    public InMemoryStateStore(Path baseStateDir) {
        this.baseStateDir = baseStateDir.toAbsolutePath().normalize();
    }

    @Override
    public Path projectStateDir(String project) {
        return baseStateDir.resolve(project).normalize();
    }

    @Override
    public Result<Optional<Map<String, String>>, DomainError> readRouting(String project) {
        Map<String, String> existing = routingByProject.get(project);
        if (existing == null) {
            return Result.ok(Optional.empty());
        }
        return Result.ok(Optional.of(Collections.unmodifiableMap(new LinkedHashMap<>(existing))));
    }

    @Override
    public Result<Unit, DomainError> writeRouting(String project, Map<String, String> routes) {
        routingByProject.put(project, new LinkedHashMap<>(routes));
        return Result.ok(Unit.VALUE);
    }

    @Override
    public Result<Optional<ManagedProcessRecord>, DomainError> readManagedProcess(String project, ProcessKey key) {
        return switch (key) {
            case ProcessKey.Service(String svc) -> Result.ok(
                Optional.ofNullable(serviceProcesses.getOrDefault(project, Map.of()).get(svc))
            );
            case ProcessKey.Gateway _ -> Result.ok(Optional.ofNullable(gatewayProcesses.get(project)));
        };
    }

    @Override
    public Result<Unit, DomainError> writeManagedProcess(String project, ProcessKey key, ManagedProcessRecord record) {
        switch (key) {
            case ProcessKey.Service(String svc) ->
                serviceProcesses.computeIfAbsent(project, _ -> new LinkedHashMap<>()).put(svc, record);
            case ProcessKey.Gateway _ -> gatewayProcesses.put(project, record);
        }
        return Result.ok(Unit.VALUE);
    }

    @Override
    public Result<Unit, DomainError> deleteManagedProcess(String project, ProcessKey key) {
        switch (key) {
            case ProcessKey.Service(String svc) -> {
                Map<String, ManagedProcessRecord> map = serviceProcesses.get(project);
                if (map != null) {
                    map.remove(svc);
                }
            }
            case ProcessKey.Gateway _ -> gatewayProcesses.remove(project);
        }
        return Result.ok(Unit.VALUE);
    }

    @Override
    public Result<Map<String, ManagedProcessRecord>, DomainError> listManagedServiceProcesses(String project) {
        return Result.ok(Collections.unmodifiableMap(new LinkedHashMap<>(
            serviceProcesses.getOrDefault(project, Map.of())
        )));
    }

    @Override
    public Path logPath(String project, ProcessKey key, boolean stderr) {
        String baseName = switch (key) {
            case ProcessKey.Service(String svc) -> svc;
            case ProcessKey.Gateway _ -> "gateway";
        };
        String suffix = stderr ? ".err.log" : ".out.log";
        return projectStateDir(project).resolve("logs").resolve(baseName + suffix);
    }

    public InMemoryStateStore appendLog(Path logPath, String text) {
        Path norm = logPath.toAbsolutePath().normalize();
        byte[] prev = logs.getOrDefault(norm, new byte[0]);
        byte[] added = text.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[prev.length + added.length];
        System.arraycopy(prev, 0, combined, 0, prev.length);
        System.arraycopy(added, 0, combined, prev.length, added.length);
        logs.put(norm, combined);
        return this;
    }

    public InMemoryStateStore replaceLog(Path logPath, String text) {
        Path norm = logPath.toAbsolutePath().normalize();
        logs.put(norm, text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public boolean logExists(Path logPath) {
        return logs.containsKey(logPath.toAbsolutePath().normalize());
    }

    @Override
    public Result<String, DomainError> readLogText(Path logPath) {
        byte[] data = logs.get(logPath.toAbsolutePath().normalize());
        if (data == null) {
            return Result.err(DomainError.of("LOG_NOT_FOUND", "Log file not found: " + logPath, Map.of("path", logPath.toString())));
        }
        return Result.ok(new String(data, StandardCharsets.UTF_8));
    }

    @Override
    public Result<LogChunk, DomainError> readLogChunk(Path logPath, long byteOffset) {
        byte[] data = logs.get(logPath.toAbsolutePath().normalize());
        if (data == null) {
            return Result.err(DomainError.of("LOG_NOT_FOUND", "Log file not found: " + logPath, Map.of("path", logPath.toString())));
        }
        int start = byteOffset > data.length ? 0 : (int) Math.max(0, byteOffset);
        String text = new String(data, start, data.length - start, StandardCharsets.UTF_8);
        return Result.ok(new LogChunk(text, data.length));
    }
}

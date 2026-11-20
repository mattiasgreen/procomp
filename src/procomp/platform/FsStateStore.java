package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.shell.ports.LogChunk;
import procomp.shell.ports.ManagedProcessRecord;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.StateStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * Cross-platform filesystem implementation of {@link StateStore}.
 * Scopes state per Project under PROCOMP_STATE_DIR, %LOCALAPPDATA%\procomp\<project>,
 * or $XDG_STATE_HOME / ~/.local/state/procomp/<project>.
 */
public final class FsStateStore implements StateStore {

    private final String osName;
    private final Map<String, String> env;
    private final Path userHome;

    public FsStateStore() {
        this(
            System.getProperty("os.name", ""),
            System.getenv(),
            Path.of(System.getProperty("user.home", "."))
        );
    }

    public FsStateStore(String osName, Map<String, String> env, Path userHome) {
        this.osName = osName == null ? "" : osName;
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.userHome = userHome;
    }

    @Override
    public Path projectStateDir(String project) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project must be non-blank");
        }
        String override = nonBlankEnv("PROCOMP_STATE_DIR");
        if (override != null) {
            return Path.of(override).resolve(project).normalize();
        }
        if (isWindows()) {
            String localAppData = nonBlankEnv("LOCALAPPDATA");
            Path base = localAppData != null
                ? Path.of(localAppData)
                : userHome.resolve("AppData").resolve("Local");
            return base.resolve("procomp").resolve(project).normalize();
        }
        String xdgState = nonBlankEnv("XDG_STATE_HOME");
        Path base = xdgState != null
            ? Path.of(xdgState)
            : userHome.resolve(".local").resolve("state");
        return base.resolve("procomp").resolve(project).normalize();
    }

    @Override
    public Result<Optional<Map<String, String>>, DomainError> readRouting(String project) {
        Path file = projectStateDir(project).resolve("routing.json");
        if (!Files.isRegularFile(file)) {
            return Result.ok(Optional.empty());
        }
        return readJsonObject(file).flatMap(obj -> {
            Optional<JsonValue> routesVal = obj.get("routes");
            if (routesVal.isEmpty() || !(routesVal.get() instanceof JsonValue.JsonObject routesObj)) {
                return Result.err(corruptState(file, "Missing or invalid 'routes' object"));
            }
            Map<String, String> routes = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> entry : routesObj.fields().entrySet()) {
                if (!(entry.getValue() instanceof JsonValue.JsonString(String route))) {
                    return Result.err(corruptState(file, "Route value for '" + entry.getKey() + "' must be a string"));
                }
                routes.put(entry.getKey(), route);
            }
            return Result.ok(Optional.of(routes));
        });
    }

    @Override
    public Result<Unit, DomainError> writeRouting(String project, Map<String, String> routes) {
        Path file = projectStateDir(project).resolve("routing.json");
        Map<String, JsonValue> routeNodes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : routes.entrySet()) {
            routeNodes.put(entry.getKey(), Json.str(entry.getValue()));
        }
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("routes", Json.obj(routeNodes));
        return writeAtomically(file, Json.stringifyPretty(Json.obj(root)) + "\n");
    }

    @Override
    public Result<Optional<ManagedProcessRecord>, DomainError> readManagedProcess(String project, ProcessKey key) {
        Path file = processFilePath(project, key);
        if (!Files.isRegularFile(file)) {
            return Result.ok(Optional.empty());
        }
        return readManagedProcessFile(file).map(Optional::of);
    }

    @Override
    public Result<Unit, DomainError> writeManagedProcess(String project, ProcessKey key, ManagedProcessRecord record) {
        Path file = processFilePath(project, key);
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("pid", Json.num(record.pid()));
        root.put("startedAt", Json.str(record.startedAt().toString()));
        List<JsonValue> cmdNodes = new ArrayList<>(record.commandSummary().size());
        for (String arg : record.commandSummary()) {
            cmdNodes.add(Json.str(arg));
        }
        root.put("commandSummary", Json.arr(cmdNodes));
        root.put("artifactTimestamp", record.artifactTimestamp().map(t -> (JsonValue) Json.str(t.toString())).orElse(Json.nil()));
        root.put("stdoutLog", Json.str(record.stdoutLog().toString()));
        root.put("stderrLog", Json.str(record.stderrLog().toString()));
        return writeAtomically(file, Json.stringifyPretty(Json.obj(root)) + "\n");
    }

    @Override
    public Result<Unit, DomainError> deleteManagedProcess(String project, ProcessKey key) {
        Path file = processFilePath(project, key);
        try {
            Files.deleteIfExists(file);
            return Result.ok(Unit.VALUE);
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "STATE_IO_ERROR",
                "Failed to delete managed process record '" + file + "': " + e.getMessage(),
                Map.of("path", file.toString())
            ));
        }
    }

    @Override
    public Result<Map<String, ManagedProcessRecord>, DomainError> listManagedServiceProcesses(String project) {
        Path processesDir = projectStateDir(project).resolve("processes");
        if (!Files.isDirectory(processesDir)) {
            return Result.ok(Map.of());
        }
        Map<String, ManagedProcessRecord> result = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> stream = Files.list(processesDir)) {
            files = stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "STATE_IO_ERROR",
                "Failed to list process records under '" + processesDir + "': " + e.getMessage(),
                Map.of("path", processesDir.toString())
            ));
        }
        for (Path file : files) {
            String filename = file.getFileName().toString();
            String serviceName = filename.substring(0, filename.length() - ".json".length());
            Result<ManagedProcessRecord, DomainError> recRes = readManagedProcessFile(file);
            if (recRes.isErr()) {
                return recRes.fold(ignored -> { throw new IllegalStateException(); }, Result::err);
            }
            result.put(serviceName, ((Result.Ok<ManagedProcessRecord, DomainError>) recRes).value());
        }
        return Result.ok(result);
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
    public boolean logExists(Path logPath) {
        return Files.isRegularFile(logPath);
    }

    @Override
    public Result<String, DomainError> readLogText(Path logPath) {
        if (!Files.isRegularFile(logPath)) {
            return Result.err(DomainError.of("LOG_NOT_FOUND", "Log file not found: " + logPath, Map.of("path", logPath.toString())));
        }
        try {
            return Result.ok(Files.readString(logPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.err(DomainError.of("STATE_IO_ERROR", "Failed to read log '" + logPath + "': " + e.getMessage(), Map.of("path", logPath.toString())));
        }
    }

    @Override
    public Result<LogChunk, DomainError> readLogChunk(Path logPath, long byteOffset) {
        if (!Files.isRegularFile(logPath)) {
            return Result.err(DomainError.of("LOG_NOT_FOUND", "Log file not found: " + logPath, Map.of("path", logPath.toString())));
        }
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ)) {
            long size = channel.size();
            long start = byteOffset > size ? 0 : Math.max(0, byteOffset);
            long remaining = size - start;
            if (remaining <= 0) {
                return Result.ok(new LogChunk("", size));
            }
            if (remaining > Integer.MAX_VALUE - 8) {
                remaining = Integer.MAX_VALUE - 8;
            }
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) remaining);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return Result.ok(new LogChunk(new String(bytes, StandardCharsets.UTF_8), start + bytes.length));
        } catch (IOException e) {
            return Result.err(DomainError.of("STATE_IO_ERROR", "Failed to read log chunk '" + logPath + "': " + e.getMessage(), Map.of("path", logPath.toString())));
        }
    }

    private Path processFilePath(String project, ProcessKey key) {
        Path dir = projectStateDir(project);
        return switch (key) {
            case ProcessKey.Service(String svc) -> dir.resolve("processes").resolve(svc + ".json");
            case ProcessKey.Gateway _ -> dir.resolve("gateway.json");
        };
    }

    private Result<ManagedProcessRecord, DomainError> readManagedProcessFile(Path file) {
        return readJsonObject(file).flatMap(obj -> {
            Optional<JsonValue> pidVal = obj.get("pid");
            if (pidVal.isEmpty() || !(pidVal.get() instanceof JsonValue.JsonNumber num) || !num.isInteger()) {
                return Result.err(corruptState(file, "Invalid or missing 'pid'"));
            }
            OptionalLong pidOpt = num.asLong();
            if (pidOpt.isEmpty() || pidOpt.getAsLong() <= 0) {
                return Result.err(corruptState(file, "Invalid non-positive 'pid'"));
            }

            Result<Instant, DomainError> startedRes = requireInstant(obj, "startedAt", file);
            if (startedRes.isErr()) return castErr(startedRes);

            Optional<JsonValue> cmdVal = obj.get("commandSummary");
            if (cmdVal.isEmpty() || !(cmdVal.get() instanceof JsonValue.JsonArray(List<JsonValue> elems))) {
                return Result.err(corruptState(file, "Invalid or missing 'commandSummary'"));
            }
            List<String> commandSummary = new ArrayList<>(elems.size());
            for (JsonValue el : elems) {
                if (!(el instanceof JsonValue.JsonString(String s))) {
                    return Result.err(corruptState(file, "Non-string element in 'commandSummary'"));
                }
                commandSummary.add(s);
            }

            Optional<Instant> artifactTimestamp = Optional.empty();
            Optional<JsonValue> artVal = obj.get("artifactTimestamp");
            if (artVal.isPresent() && !(artVal.get() instanceof JsonValue.JsonNull)) {
                if (!(artVal.get() instanceof JsonValue.JsonString(String ts))) {
                    return Result.err(corruptState(file, "'artifactTimestamp' must be an ISO-8601 string or null"));
                }
                try {
                    artifactTimestamp = Optional.of(Instant.parse(ts));
                } catch (DateTimeParseException e) {
                    return Result.err(corruptState(file, "Invalid 'artifactTimestamp': " + ts));
                }
            }

            Result<String, DomainError> stdoutRes = requireString(obj, "stdoutLog", file);
            if (stdoutRes.isErr()) return castErr(stdoutRes);
            Result<String, DomainError> stderrRes = requireString(obj, "stderrLog", file);
            if (stderrRes.isErr()) return castErr(stderrRes);

            return Result.ok(new ManagedProcessRecord(
                pidOpt.getAsLong(),
                okVal(startedRes),
                commandSummary,
                artifactTimestamp,
                Path.of(okVal(stdoutRes)),
                Path.of(okVal(stderrRes))
            ));
        });
    }

    private Result<JsonValue.JsonObject, DomainError> readJsonObject(Path file) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Result.err(DomainError.of("STATE_IO_ERROR", "Failed to read '" + file + "': " + e.getMessage(), Map.of("path", file.toString())));
        }
        return Json.parse(raw).flatMap(val -> {
            if (val instanceof JsonValue.JsonObject obj) {
                return Result.ok(obj);
            }
            return Result.err(corruptState(file, "Expected top-level JSON object"));
        });
    }

    private Result<Unit, DomainError> writeAtomically(Path target, String content) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return Result.ok(Unit.VALUE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "STATE_IO_ERROR",
                "Failed to write '" + target + "': " + e.getMessage(),
                Map.of("path", target.toString())
            ));
        }
    }

    private static Result<Instant, DomainError> requireInstant(JsonValue.JsonObject obj, String key, Path file) {
        Result<String, DomainError> sRes = requireString(obj, key, file);
        if (sRes.isErr()) return castErr(sRes);
        try {
            return Result.ok(Instant.parse(okVal(sRes)));
        } catch (DateTimeParseException e) {
            return Result.err(corruptState(file, "Invalid ISO-8601 timestamp for '" + key + "'"));
        }
    }

    private static Result<String, DomainError> requireString(JsonValue.JsonObject obj, String key, Path file) {
        Optional<JsonValue> v = obj.get(key);
        if (v.isEmpty() || !(v.get() instanceof JsonValue.JsonString(String s))) {
            return Result.err(corruptState(file, "Missing or non-string '" + key + "'"));
        }
        return Result.ok(s);
    }

    private static DomainError corruptState(Path file, String detail) {
        return DomainError.of("STATE_CORRUPT", "Corrupt state file '" + file + "': " + detail, Map.of("path", file.toString()));
    }

    private String nonBlankEnv(String key) {
        String val = env.get(key);
        return (val != null && !val.isBlank()) ? val : null;
    }

    private boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static <T, U> Result<U, DomainError> castErr(Result<T, DomainError> err) {
        return err.fold(ignored -> { throw new IllegalStateException(); }, Result::err);
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}

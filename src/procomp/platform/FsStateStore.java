package procomp.platform;

import procomp.core.build.BuildRunRecord;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.core.process.LogTailer;
import procomp.core.process.ManagedProcessRecord;
import procomp.shell.ports.LogChunk;
import procomp.shell.ports.LogPage;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.StateStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Cross-platform filesystem implementation of {@link StateStore}.
 * Scopes state per Project under PROCOMP_STATE_DIR, %LOCALAPPDATA%\procomp\<project>,
 * or $XDG_STATE_HOME / ~/.local/state/procomp/<project>.
 */
public final class FsStateStore implements StateStore {

    private static final int MAX_LOG_CHUNK_BYTES = 1024 * 1024;
    private static final int BACKWARD_READ_STEP_BYTES = 64 * 1024;
    private static final long MAX_LOG_TEXT_BYTES = 16L * 1024 * 1024;
    private static final int WINDOWS_MOVE_MAX_ATTEMPTS = 5;

    private final String osName;
    private final Map<String, String> env;
    private final Path userHome;
    private final ConcurrentHashMap<Path, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();

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
        requireSafeSegment(project, "project");
        Optional<String> override = nonBlankEnv("PROCOMP_STATE_DIR");
        Path root;
        if (override.isPresent()) {
            root = Path.of(override.get()).normalize();
        } else if (isWindows()) {
            Path base = nonBlankEnv("LOCALAPPDATA")
                .map(Path::of)
                .orElseGet(() -> userHome.resolve("AppData").resolve("Local"));
            root = base.resolve("procomp").normalize();
        } else {
            Path base = nonBlankEnv("XDG_STATE_HOME")
                .map(Path::of)
                .orElseGet(() -> userHome.resolve(".local").resolve("state"));
            root = base.resolve("procomp").normalize();
        }
        return resolveContained(root, project, "project");
    }

    @Override
    public Result<Optional<Map<String, String>>, DomainError> readRouting(String project) {
        Path file = projectStateDir(project).resolve("routing.json");
        ReentrantReadWriteLock lock = lockFor(file);
        lock.readLock().lock();
        try {
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
        } finally {
            lock.readLock().unlock();
        }
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
        ReentrantReadWriteLock lock = lockFor(file);
        lock.writeLock().lock();
        try {
            return writeAtomically(file, Json.stringifyPretty(Json.obj(root)) + "\n");
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Result<Optional<ManagedProcessRecord>, DomainError> readManagedProcess(String project, ProcessKey key) {
        Path file = processFilePath(project, key);
        ReentrantReadWriteLock lock = lockFor(file);
        lock.readLock().lock();
        try {
            if (!Files.isRegularFile(file)) {
                return Result.ok(Optional.empty());
            }
            return readManagedProcessFile(file).map(Optional::of);
        } finally {
            lock.readLock().unlock();
        }
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
        ReentrantReadWriteLock lock = lockFor(file);
        lock.writeLock().lock();
        try {
            return writeAtomically(file, Json.stringifyPretty(Json.obj(root)) + "\n");
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Result<Unit, DomainError> deleteManagedProcess(String project, ProcessKey key) {
        Path file = processFilePath(project, key);
        ReentrantReadWriteLock lock = lockFor(file);
        lock.writeLock().lock();
        try {
            Files.deleteIfExists(file);
            return Result.ok(Unit.VALUE);
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "STATE_IO_ERROR",
                "Failed to delete managed process record '" + file + "': " + e.getMessage(),
                Map.of("path", file.toString())
            ));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Result<Optional<BuildRunRecord>, DomainError> readBuildRun(String project, String serviceName) {
        Path file = buildRunFilePath(project, serviceName);
        ReentrantReadWriteLock lock = lockFor(file);
        lock.readLock().lock();
        try {
            if (!Files.isRegularFile(file)) {
                return Result.ok(Optional.empty());
            }
            return readBuildRunFile(file).map(Optional::of);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Result<Unit, DomainError> writeBuildRun(String project, String serviceName, BuildRunRecord record) {
        Path file = buildRunFilePath(project, serviceName);
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("pid", Json.num(record.pid()));
        root.put("startedAt", Json.str(record.startedAt().toString()));
        switch (record) {
            case BuildRunRecord.Running _ -> root.put("status", Json.str("RUNNING"));
            case BuildRunRecord.Succeeded s -> {
                root.put("status", Json.str("SUCCEEDED"));
                root.put("finishedAt", Json.str(s.finishedAt().toString()));
            }
            case BuildRunRecord.Failed f -> {
                root.put("status", Json.str("FAILED"));
                root.put("finishedAt", Json.str(f.finishedAt().toString()));
                root.put("exitCode", f.exitCode().map(c -> (JsonValue) Json.num(c)).orElse(Json.nil()));
                root.put("reason", Json.str(f.reason()));
            }
        }
        List<JsonValue> cmdNodes = new ArrayList<>(record.commandSummary().size());
        for (String arg : record.commandSummary()) {
            cmdNodes.add(Json.str(arg));
        }
        root.put("commandSummary", Json.arr(cmdNodes));
        root.put("stdoutLog", Json.str(record.stdoutLog().toString()));
        root.put("stderrLog", Json.str(record.stderrLog().toString()));
        ReentrantReadWriteLock lock = lockFor(file);
        lock.writeLock().lock();
        try {
            return writeAtomically(file, Json.stringifyPretty(Json.obj(root)) + "\n");
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Path logPath(String project, ProcessKey key, boolean stderr) {
        String baseName = switch (key) {
            case ProcessKey.Service(String svc) -> {
                requireSafeSegment(svc, "service");
                yield svc;
            }
            case ProcessKey.Gateway _ -> "gateway";
            case ProcessKey.Build(String svc) -> {
                requireSafeSegment(svc, "service");
                yield svc + ".build";
            }
        };
        String suffix = stderr ? ".err.log" : ".out.log";
        Path logsDir = projectStateDir(project).resolve("logs").normalize();
        return resolveContained(logsDir, baseName + suffix, "log");
    }

    @Override
    public Result<String, DomainError> readLogText(Path logPath) {
        if (!Files.isRegularFile(logPath)) {
            return Result.err(DomainError.of("LOG_NOT_FOUND", "Log file not found: " + logPath, Map.of("path", logPath.toString())));
        }
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size <= MAX_LOG_TEXT_BYTES) {
                return Result.ok(Files.readString(logPath, StandardCharsets.UTF_8));
            }
            long start = size - MAX_LOG_TEXT_BYTES;
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) MAX_LOG_TEXT_BYTES);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            int offset = skipLeadingUtf8ContinuationBytes(bytes);
            return Result.ok(new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8));
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
            int toRead = (int) Math.min(remaining, MAX_LOG_CHUNK_BYTES);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(toRead);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            int completeLen = completeUtf8PrefixLength(bytes);
            if (completeLen == 0 && bytes.length > 0) {
                // Only incomplete trailing bytes exist; wait for writer to finish codepoint
                return Result.ok(new LogChunk("", start));
            }
            return Result.ok(new LogChunk(new String(bytes, 0, completeLen, StandardCharsets.UTF_8), start + completeLen));
        } catch (IOException e) {
            return Result.err(DomainError.of("STATE_IO_ERROR", "Failed to read log chunk '" + logPath + "': " + e.getMessage(), Map.of("path", logPath.toString())));
        }
    }

    @Override
    public Result<LogPage, DomainError> readLogPageBackward(
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
        if (!Files.isRegularFile(logPath)) {
            return Result.err(DomainError.of("LOG_NOT_FOUND", "Log file not found: " + logPath, Map.of("path", logPath.toString())));
        }
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ)) {
            long size = channel.size();
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

            long minWindowStart = Math.max(0L, endOffset - maxBytes);
            long cursor = endOffset;
            int seenNewlines = 0;
            boolean trailingNewlineChecked = false;
            List<byte[]> chunks = new ArrayList<>();
            int totalRead = 0;

            while (cursor > minWindowStart && seenNewlines < maxLines) {
                long nextStart = Math.max(minWindowStart, cursor - BACKWARD_READ_STEP_BYTES);
                int toRead = (int) (cursor - nextStart);
                channel.position(nextStart);
                ByteBuffer buffer = ByteBuffer.allocate(toRead);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        break;
                    }
                }
                buffer.flip();
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                if (chunk.length == 0) {
                    break;
                }
                chunks.add(chunk);
                totalRead += chunk.length;

                int scanEnd = chunk.length - 1;
                if (!trailingNewlineChecked) {
                    trailingNewlineChecked = true;
                    if (chunk[scanEnd] == '\n') {
                        scanEnd--;
                    }
                }
                for (int i = scanEnd; i >= 0; i--) {
                    if (chunk[i] == '\n') {
                        seenNewlines++;
                        if (seenNewlines >= maxLines) {
                            break;
                        }
                    }
                }
                cursor = nextStart;
            }

            byte[] bytes = new byte[totalRead];
            int destPos = 0;
            for (int i = chunks.size() - 1; i >= 0; i--) {
                byte[] chunk = chunks.get(i);
                System.arraycopy(chunk, 0, bytes, destPos, chunk.length);
                destPos += chunk.length;
            }

            LogTailer.BackwardSlice slice = LogTailer.sliceBackward(bytes, cursor, maxLines);
            return Result.ok(new LogPage(slice.text(), slice.startOffset(), slice.endOffset(), slice.hasOlder(), false));
        } catch (IOException e) {
            return Result.err(DomainError.of("STATE_IO_ERROR", "Failed to read log page '" + logPath + "': " + e.getMessage(), Map.of("path", logPath.toString())));
        }
    }

    private Path buildRunFilePath(String project, String serviceName) {
        requireSafeSegment(serviceName, "service");
        Path dir = projectStateDir(project).resolve("builds").normalize();
        return resolveContained(dir, serviceName + ".json", "service");
    }

    private Path processFilePath(String project, ProcessKey key) {
        Path dir = projectStateDir(project);
        return switch (key) {
            case ProcessKey.Service(String svc) -> {
                requireSafeSegment(svc, "service");
                yield resolveContained(dir.resolve("processes").normalize(), svc + ".json", "service");
            }
            case ProcessKey.Gateway _ -> dir.resolve("gateway.json");
            case ProcessKey.Build _ -> throw new IllegalArgumentException(
                "ProcessKey.Build must use readBuildRun/writeBuildRun/deleteBuildRun"
            );
        };
    }

    private Result<BuildRunRecord, DomainError> readBuildRunFile(Path file) {
        return readJsonObject(file).flatMap(obj -> {
            Optional<JsonValue> pidVal = obj.get("pid");
            if (pidVal.isEmpty() || !(pidVal.get() instanceof JsonValue.JsonNumber num) || !num.isInteger()) {
                return Result.err(corruptState(file, "Invalid or missing 'pid'"));
            }
            OptionalLong pidOpt = num.asLong();
            if (pidOpt.isEmpty() || pidOpt.getAsLong() <= 0) {
                return Result.err(corruptState(file, "Invalid non-positive 'pid'"));
            }

            Result<String, DomainError> statusRes = requireString(obj, "status", file);
            if (statusRes.isErr()) return statusRes.castError();
            String status = okVal(statusRes);

            Result<Instant, DomainError> startedRes = requireInstant(obj, "startedAt", file);
            if (startedRes.isErr()) return startedRes.castError();

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

            Result<String, DomainError> stdoutRes = requireString(obj, "stdoutLog", file);
            if (stdoutRes.isErr()) return stdoutRes.castError();
            Result<String, DomainError> stderrRes = requireString(obj, "stderrLog", file);
            if (stderrRes.isErr()) return stderrRes.castError();

            Path stdoutPath;
            Path stderrPath;
            try {
                stdoutPath = Path.of(okVal(stdoutRes));
                stderrPath = Path.of(okVal(stderrRes));
            } catch (InvalidPathException e) {
                return Result.err(corruptState(file, "Invalid log path: " + e.getMessage()));
            }

            return switch (status) {
                case "RUNNING" -> Result.ok(new BuildRunRecord.Running(
                    pidOpt.getAsLong(), okVal(startedRes), commandSummary, stdoutPath, stderrPath
                ));
                case "SUCCEEDED" -> {
                    Result<Instant, DomainError> finishedRes = requireInstant(obj, "finishedAt", file);
                    if (finishedRes.isErr()) yield finishedRes.castError();
                    yield Result.ok(new BuildRunRecord.Succeeded(
                        pidOpt.getAsLong(), okVal(startedRes), okVal(finishedRes), commandSummary, stdoutPath, stderrPath
                    ));
                }
                case "FAILED" -> {
                    Result<Instant, DomainError> finishedRes = requireInstant(obj, "finishedAt", file);
                    if (finishedRes.isErr()) yield finishedRes.castError();
                    Optional<Integer> exitCode = Optional.empty();
                    Optional<JsonValue> exitVal = obj.get("exitCode");
                    if (exitVal.isPresent() && !(exitVal.get() instanceof JsonValue.JsonNull)) {
                        if (!(exitVal.get() instanceof JsonValue.JsonNumber exitNum) || !exitNum.isInteger() || exitNum.asInt().isEmpty()) {
                            yield Result.err(corruptState(file, "'exitCode' must be an integer or null"));
                        }
                        exitCode = Optional.of(exitNum.asInt().getAsInt());
                    }
                    Result<String, DomainError> reasonRes = requireString(obj, "reason", file);
                    if (reasonRes.isErr()) yield reasonRes.castError();
                    if (okVal(reasonRes).isBlank()) {
                        yield Result.err(corruptState(file, "'reason' must not be blank"));
                    }
                    yield Result.ok(new BuildRunRecord.Failed(
                        pidOpt.getAsLong(),
                        okVal(startedRes),
                        okVal(finishedRes),
                        exitCode,
                        okVal(reasonRes),
                        commandSummary,
                        stdoutPath,
                        stderrPath
                    ));
                }
                default -> Result.err(corruptState(file, "Unknown build run status '" + status + "'"));
            };
        });
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
            if (startedRes.isErr()) return startedRes.castError();

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
            if (stdoutRes.isErr()) return stdoutRes.castError();
            Result<String, DomainError> stderrRes = requireString(obj, "stderrLog", file);
            if (stderrRes.isErr()) return stderrRes.castError();

            Path stdoutPath;
            Path stderrPath;
            try {
                stdoutPath = Path.of(okVal(stdoutRes));
                stderrPath = Path.of(okVal(stderrRes));
            } catch (InvalidPathException e) {
                return Result.err(corruptState(file, "Invalid log path: " + e.getMessage()));
            }
            return Result.ok(new ManagedProcessRecord(
                pidOpt.getAsLong(),
                okVal(startedRes),
                commandSummary,
                artifactTimestamp,
                stdoutPath,
                stderrPath
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
            Path normalized = target.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IOException("Target path has no parent directory: " + normalized);
            }
            Files.createDirectories(parent);
            Path fileName = normalized.getFileName();
            String prefix = fileName != null ? fileName.toString() : "procomp-state";
            Path tmp = Files.createTempFile(parent, prefix, ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                moveWithRetry(tmp, normalized);
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

    private void moveWithRetry(Path tmp, Path normalized) throws IOException {
        IOException lastFailure = null;
        long backoffMs = 15L;
        for (int attempt = 1; attempt <= WINDOWS_MOVE_MAX_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                lastFailure = e;
                if (!isWindows() || attempt == WINDOWS_MOVE_MAX_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoffMs *= 2;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
    }

    private ReentrantReadWriteLock lockFor(Path file) {
        return fileLocks.computeIfAbsent(file.toAbsolutePath().normalize(), ignored -> new ReentrantReadWriteLock());
    }

    private static void requireSafeSegment(String value, String label) {
        if (value == null
            || value.isBlank()
            || value.equals(".")
            || value.equals("..")
            || value.contains("/")
            || value.contains("\\")
            || value.contains("\0")) {
            throw new IllegalArgumentException(label + " must be a single path-safe segment");
        }
    }

    private static Path resolveContained(Path root, String child, String label) {
        Path normalizedRoot = root.normalize();
        Path resolved = normalizedRoot.resolve(child).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Resolved " + label + " path escapes root: " + child);
        }
        return resolved;
    }

    private static int skipLeadingUtf8ContinuationBytes(byte[] bytes) {
        int i = 0;
        while (i < bytes.length && (bytes[i] & 0b1100_0000) == 0b1000_0000) {
            i++;
        }
        return i;
    }

    private static int completeUtf8PrefixLength(byte[] bytes) {
        int len = bytes.length;
        if (len == 0) {
            return 0;
        }
        // Inspect up to the last 3 bytes for a truncated multi-byte UTF-8 sequence.
        int start = Math.max(0, len - 3);
        for (int i = len - 1; i >= start; i--) {
            int b = bytes[i] & 0xFF;
            if ((b & 0b1000_0000) == 0) {
                return len; // ASCII terminates cleanly
            }
            if ((b & 0b1100_0000) != 0b1000_0000) {
                // Found leading byte of last multi-byte sequence
                int expected;
                if ((b & 0b1110_0000) == 0b1100_0000) {
                    expected = 2;
                } else if ((b & 0b1111_0000) == 0b1110_0000) {
                    expected = 3;
                } else if ((b & 0b1111_1000) == 0b1111_0000) {
                    expected = 4;
                } else {
                    return len; // invalid byte, let decoder handle it
                }
                int available = len - i;
                return available < expected ? i : len;
            }
        }
        return len;
    }

    private static Result<Instant, DomainError> requireInstant(JsonValue.JsonObject obj, String key, Path file) {
        Result<String, DomainError> sRes = requireString(obj, key, file);
        if (sRes.isErr()) return sRes.castError();
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

    private Optional<String> nonBlankEnv(String key) {
        String val = env.get(key);
        return (val != null && !val.isBlank()) ? Optional.of(val) : Optional.empty();
    }

    private boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}

package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.LogTailer;
import procomp.core.project.Project;
import procomp.shell.ports.Clock;
import procomp.shell.ports.LogChunk;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.StateStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Use-case orchestrator for minimal raw Service log path resolution, tailing, and following.
 */
public final class LogsShell {

    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);

    private final StateStore stateStore;
    private final Clock clock;
    private final ProgressObserver observer;

    public LogsShell(StateStore stateStore, Clock clock, ProgressObserver observer) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    public Result<LogsResult, DomainError> run(Project project, LogsRequest request) {
        return run(project, request, DEFAULT_POLL_INTERVAL, () -> !Thread.currentThread().isInterrupted());
    }

    public Result<LogsResult, DomainError> run(
        Project project,
        LogsRequest request,
        Duration pollInterval,
        BooleanSupplier keepFollowing
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        Objects.requireNonNull(keepFollowing, "keepFollowing must not be null");

        if (project.service(request.serviceName()).isEmpty()) {
            return Result.err(DomainError.of(
                "UNKNOWN_SERVICE",
                "Unknown service '" + request.serviceName() + "' in project '" + project.name() + "'",
                Map.of("service", request.serviceName(), "project", project.name())
            ));
        }

        ProcessKey key = request.build()
            ? ProcessKey.build(request.serviceName())
            : ProcessKey.service(request.serviceName());
        Path logPath = stateStore.logPath(project.name(), key, request.stderr());
        if (request.pathOnly()) {
            return Result.ok(new LogsResult.ResolvedPath(logPath));
        }

        if (!request.follow()) {
            return stateStore.readLogText(logPath)
                .map(text -> new LogsResult.RawTail(logPath, LogTailer.tailLines(text, request.lines())));
        }

        return follow(logPath, request, pollInterval, keepFollowing);
    }

    private Result<LogsResult, DomainError> follow(
        Path logPath,
        LogsRequest request,
        Duration pollInterval,
        BooleanSupplier keepFollowing
    ) {
        Result<LogChunk, DomainError> initialRes = stateStore.readLogChunk(logPath, 0L);
        if (initialRes instanceof Result.Err<LogChunk, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        LogChunk initial = ((Result.Ok<LogChunk, DomainError>) initialRes).value();
        String initialTail = LogTailer.tailLines(initial.text(), request.lines());
        emitLines(request.serviceName(), request.stderr(), initialTail);

        long offset = initial.nextOffset();
        while (keepFollowing.getAsBoolean() && clock.sleep(pollInterval)) {
            Result<LogChunk, DomainError> chunkRes = stateStore.readLogChunk(logPath, offset);
            if (chunkRes instanceof Result.Err<LogChunk, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            LogChunk chunk = ((Result.Ok<LogChunk, DomainError>) chunkRes).value();
            if (!chunk.text().isEmpty()) {
                emitLines(request.serviceName(), request.stderr(), chunk.text());
            }
            offset = chunk.nextOffset();
        }
        return Result.ok(new LogsResult.Followed(logPath, offset));
    }

    private void emitLines(String serviceName, boolean stderr, String rawText) {
        for (String line : LogTailer.splitRawLines(rawText)) {
            observer.onEvent(new ProgressEvent.LogLine(serviceName, stderr, line));
        }
    }

    public record LogsRequest(
        String serviceName,
        boolean stderr,
        boolean pathOnly,
        int lines,
        boolean follow,
        boolean build
    ) {
        public static final int DEFAULT_LINES = 100;

        public LogsRequest(
            String serviceName,
            boolean stderr,
            boolean pathOnly,
            int lines,
            boolean follow
        ) {
            this(serviceName, stderr, pathOnly, lines, follow, false);
        }

        public LogsRequest {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("serviceName must be non-blank");
            }
            if (lines < 0) {
                throw new IllegalArgumentException("lines must be non-negative: " + lines);
            }
        }
    }

    public sealed interface LogsResult permits LogsResult.ResolvedPath, LogsResult.RawTail, LogsResult.Followed {

        record ResolvedPath(Path path) implements LogsResult {
            public ResolvedPath {
                Objects.requireNonNull(path, "path must not be null");
            }
        }

        record RawTail(Path path, String text) implements LogsResult {
            public RawTail {
                Objects.requireNonNull(path, "path must not be null");
                Objects.requireNonNull(text, "text must not be null");
            }
        }

        record Followed(Path path, long finalOffset) implements LogsResult {
            public Followed {
                Objects.requireNonNull(path, "path must not be null");
                if (finalOffset < 0) {
                    throw new IllegalArgumentException("finalOffset must be non-negative: " + finalOffset);
                }
            }
        }
    }
}

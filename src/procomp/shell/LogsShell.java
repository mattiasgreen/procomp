package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.process.LogTailer;
import procomp.core.project.Project;
import procomp.shell.ports.Clock;
import procomp.shell.ports.LogChunk;
import procomp.shell.ports.LogPage;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.ProgressEvent;
import procomp.shell.ports.StateStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Use-case orchestrator for minimal raw Service log path resolution, tailing, backward paging, and following.
 */
public final class LogsShell {

    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);
    public static final int DEFAULT_PAGE_MAX_BYTES = 1024 * 1024;

    private static void noopPageListener(LogPage ignored) {}

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
        return run(project, request, pollInterval, keepFollowing, LogsShell::noopPageListener);
    }

    public Result<LogsResult, DomainError> run(
        Project project,
        LogsRequest request,
        Duration pollInterval,
        BooleanSupplier keepFollowing,
        Consumer<LogPage> onInitialPage
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        Objects.requireNonNull(keepFollowing, "keepFollowing must not be null");
        Objects.requireNonNull(onInitialPage, "onInitialPage must not be null");

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

        return follow(
            logPath,
            request.serviceName(),
            request.stderr(),
            request.lines(),
            pollInterval,
            keepFollowing,
            onInitialPage
        );
    }

    public Result<LogPageResult, DomainError> readPage(Project project, LogsPageRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");
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
        return stateStore.readLogPageBackward(logPath, request.beforeOffset(), request.lines(), request.maxBytes())
            .map(page -> new LogPageResult(logPath, page));
    }

    public Result<LogsResult, DomainError> runGateway(Project project, GatewayLogsRequest request) {
        return runGateway(project, request, DEFAULT_POLL_INTERVAL, () -> !Thread.currentThread().isInterrupted());
    }

    public Result<LogsResult, DomainError> runGateway(
        Project project,
        GatewayLogsRequest request,
        Duration pollInterval,
        BooleanSupplier keepFollowing
    ) {
        return runGateway(project, request, pollInterval, keepFollowing, LogsShell::noopPageListener);
    }

    public Result<LogsResult, DomainError> runGateway(
        Project project,
        GatewayLogsRequest request,
        Duration pollInterval,
        BooleanSupplier keepFollowing,
        Consumer<LogPage> onInitialPage
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        Objects.requireNonNull(keepFollowing, "keepFollowing must not be null");
        Objects.requireNonNull(onInitialPage, "onInitialPage must not be null");

        if (project.gateway().isEmpty()) {
            return Result.err(DomainError.of(
                "GATEWAY_NOT_CONFIGURED",
                "No Gateway is configured in project '" + project.name() + "'",
                Map.of("project", project.name())
            ));
        }

        Path logPath = stateStore.logPath(project.name(), ProcessKey.gateway(), request.stderr());
        if (request.pathOnly()) {
            return Result.ok(new LogsResult.ResolvedPath(logPath));
        }

        if (!request.follow()) {
            return stateStore.readLogText(logPath)
                .map(text -> new LogsResult.RawTail(logPath, LogTailer.tailLines(text, request.lines())));
        }

        return follow(logPath, "gateway", request.stderr(), request.lines(), pollInterval, keepFollowing, onInitialPage);
    }

    public Result<LogPageResult, DomainError> readGatewayPage(Project project, GatewayLogsPageRequest request) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (project.gateway().isEmpty()) {
            return Result.err(DomainError.of(
                "GATEWAY_NOT_CONFIGURED",
                "No Gateway is configured in project '" + project.name() + "'",
                Map.of("project", project.name())
            ));
        }
        Path logPath = stateStore.logPath(project.name(), ProcessKey.gateway(), request.stderr());
        return stateStore.readLogPageBackward(logPath, request.beforeOffset(), request.lines(), request.maxBytes())
            .map(page -> new LogPageResult(logPath, page));
    }

    private Result<LogsResult, DomainError> follow(
        Path logPath,
        String targetName,
        boolean stderr,
        int lines,
        Duration pollInterval,
        BooleanSupplier keepFollowing,
        Consumer<LogPage> onInitialPage
    ) {
        Result<LogPage, DomainError> initialRes = stateStore.readLogPageBackward(
            logPath,
            OptionalLong.empty(),
            lines,
            DEFAULT_PAGE_MAX_BYTES
        );
        if (initialRes instanceof Result.Err<LogPage, DomainError>(DomainError err)) {
            return Result.err(err);
        }
        LogPage initial = ((Result.Ok<LogPage, DomainError>) initialRes).value();
        onInitialPage.accept(initial);
        emitLines(targetName, stderr, initial.text());

        long offset = initial.endOffset();
        while (keepFollowing.getAsBoolean() && clock.sleep(pollInterval)) {
            Result<LogChunk, DomainError> chunkRes = stateStore.readLogChunk(logPath, offset);
            if (chunkRes instanceof Result.Err<LogChunk, DomainError>(DomainError err)) {
                return Result.err(err);
            }
            LogChunk chunk = ((Result.Ok<LogChunk, DomainError>) chunkRes).value();
            if (!chunk.text().isEmpty()) {
                emitLines(targetName, stderr, chunk.text());
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

    public record GatewayLogsRequest(
        boolean stderr,
        boolean pathOnly,
        int lines,
        boolean follow
    ) {
        public GatewayLogsRequest {
            if (lines < 0) {
                throw new IllegalArgumentException("lines must be non-negative: " + lines);
            }
        }
    }

    public record LogsPageRequest(
        String serviceName,
        boolean stderr,
        boolean build,
        OptionalLong beforeOffset,
        int lines,
        int maxBytes
    ) {
        public LogsPageRequest(
            String serviceName,
            boolean stderr,
            boolean build,
            OptionalLong beforeOffset,
            int lines
        ) {
            this(serviceName, stderr, build, beforeOffset, lines, DEFAULT_PAGE_MAX_BYTES);
        }

        public LogsPageRequest {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("serviceName must be non-blank");
            }
            Objects.requireNonNull(beforeOffset, "beforeOffset must not be null");
            if (beforeOffset.isPresent() && beforeOffset.getAsLong() < 0) {
                throw new IllegalArgumentException("beforeOffset must be non-negative: " + beforeOffset.getAsLong());
            }
            if (lines < 0) {
                throw new IllegalArgumentException("lines must be non-negative: " + lines);
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
            }
        }
    }

    public record GatewayLogsPageRequest(
        boolean stderr,
        OptionalLong beforeOffset,
        int lines,
        int maxBytes
    ) {
        public GatewayLogsPageRequest(boolean stderr, OptionalLong beforeOffset, int lines) {
            this(stderr, beforeOffset, lines, DEFAULT_PAGE_MAX_BYTES);
        }

        public GatewayLogsPageRequest {
            Objects.requireNonNull(beforeOffset, "beforeOffset must not be null");
            if (beforeOffset.isPresent() && beforeOffset.getAsLong() < 0) {
                throw new IllegalArgumentException("beforeOffset must be non-negative: " + beforeOffset.getAsLong());
            }
            if (lines < 0) {
                throw new IllegalArgumentException("lines must be non-negative: " + lines);
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
            }
        }
    }

    public record LogPageResult(Path path, LogPage page) {
        public LogPageResult {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(page, "page must not be null");
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

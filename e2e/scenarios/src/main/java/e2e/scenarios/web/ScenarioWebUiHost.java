package e2e.scenarios.web;

import e2e.scenarios.inmemory.InMemoryWorld;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ProcompRuntime;
import procomp.shell.ProjectLoader;
import procomp.web.LiveProjectHolder;
import procomp.web.StaticAssets;
import procomp.web.WebUi;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * In-process host running the real {@link WebUi} (JDK 25 {@code HttpServer} + Vanilla ES6
 * {@code web/} assets) directly on top of an {@link InMemoryWorld}'s {@link ProcompRuntime}.
 *
 * <p>Used by both Playwright browser tests ({@code :e2e:web-ui}) and the interactive
 * {@code :e2e:scenarios:ui} DevEx launcher.</p>
 */
public final class ScenarioWebUiHost implements AutoCloseable {

    private static final Duration FAST_LOGS_POLL = Duration.ofMillis(50);
    private static final Duration FAST_SSE_HEARTBEAT = Duration.ofMillis(500);

    private final WebUi webUi;
    private final int boundPort;

    private ScenarioWebUiHost(WebUi webUi, int boundPort) {
        this.webUi = webUi;
        this.boundPort = boundPort;
    }

    public static ScenarioWebUiHost start(InMemoryWorld world, Path webDir) {
        return start(world, webDir, 0);
    }

    public static ScenarioWebUiHost start(InMemoryWorld world, Path webDir, int requestedPort) {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(webDir, "webDir must not be null");

        ProcompRuntime runtime = world.procompRuntime();
        Result<ProjectLoader, DomainError> loaderRes = runtime.projectLoader();
        if (loaderRes instanceof Result.Err<ProjectLoader, DomainError>(DomainError err)) {
            throw new IllegalStateException("InMemoryWorld ProcompRuntime missing ProjectLoader: " + err.format());
        }
        ProjectLoader loader = ((Result.Ok<ProjectLoader, DomainError>) loaderRes).value();
        LiveProjectHolder holder = new LiveProjectHolder(loader, Optional.empty());
        StaticAssets assets = StaticAssets.fromDirectory(webDir);

        WebUi webUi = new WebUi(runtime, holder, assets, requestedPort, FAST_LOGS_POLL, FAST_SSE_HEARTBEAT);
        Result<Integer, DomainError> startRes = webUi.start();
        if (startRes instanceof Result.Err<Integer, DomainError>(DomainError err)) {
            webUi.close();
            throw new IllegalStateException("Failed to start ScenarioWebUiHost: " + err.format());
        }
        int boundPort = ((Result.Ok<Integer, DomainError>) startRes).value();
        return new ScenarioWebUiHost(webUi, boundPort);
    }

    public int boundPort() {
        return boundPort;
    }

    public String baseUrl() {
        return "http://" + WebUi.BIND_HOST + ":" + boundPort;
    }

    @Override
    public void close() {
        webUi.close();
    }
}

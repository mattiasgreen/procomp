package procomp.web;

import procomp.Assert;
import procomp.core.build.BuildRunRecord;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.shell.ProcompRuntime;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner.ExitOutcome;
import procomp.shell.ports.ProgressObserver;
import procomp.web.ApiRouter.ApiRequest;
import procomp.web.ApiRouter.ApiResponse;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BuildApiRouterTest {

    private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

    private static Map<String, String> csrfHeaders() {
        return Map.of(
            "Host", "127.0.0.1:9090",
            "Origin", "http://127.0.0.1:9090",
            "X-Procomp-Request", "1"
        );
    }

    private static Map<String, String> getHeaders() {
        return Map.of("Host", "127.0.0.1:9090");
    }

    public static void testBuildCancelAndBuildLogsEndpoints() {
        Path configPath = Path.of("C:/dev/acme/procomp.json").toAbsolutePath().normalize();
        String configJson = """
            {
              "project": "acme",
              "builders": {
                "gradle": { "command": ["./gradlew", ":{{service}}:assemble"] }
              },
              "runtimes": {
                "java": { "command": ["java", "-jar", "app.jar"] }
              },
              "services": {
                "orders": { "port": 9001, "runtime": "java", "builder": "gradle", "workingDir": "orders" },
                "static-app": { "port": 9002, "runtime": "java", "workingDir": "static" }
              }
            }
            """;

        InMemoryConfigSource configSource = new InMemoryConfigSource(configPath.getParent())
            .withFile(configPath, configJson);
        InMemoryStateStore stateStore = new InMemoryStateStore();
        FakeClock clock = new FakeClock(T0);
        FakeProcessRunner runner = new FakeProcessRunner(clock).onAttachedExit(req -> {
            stateStore.replaceLog(req.stdoutLog(), "Web build ok\n");
            stateStore.replaceLog(req.stderrLog(), "");
            return new ExitOutcome.Exited(0);
        });
        ProcompRuntime runtime = ProcompRuntime.builder()
            .configSource(configSource)
            .stateStore(stateStore)
            .processRunner(runner)
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(new FakeProjectFiles())
            .clock(clock)
            .observer(ProgressObserver.noop())
            .build();
        LiveProjectHolder holder = new LiveProjectHolder(
            Assert.assertOk(runtime.projectLoader(), "projectLoader"),
            Optional.of(configPath)
        );
        // Direct executor runs the background build synchronously for deterministic testing
        ApiRouter router = new ApiRouter(runtime, holder, 9090, Duration.ofMillis(10), Runnable::run);

        // 1. Building service without Builder returns 400 NO_BUILDER_CONFIGURED
        ApiResponse noBuilderRes = router.handle(
            ApiRequest.of("POST", "/api/services/static-app/build", csrfHeaders(), "{}")
        );
        Assert.assertEquals(400, noBuilderRes.statusCode(), "400 for service without builder");
        Assert.assertContains(noBuilderRes.body(), "NO_BUILDER_CONFIGURED", "error code");

        // 2. Building orders returns 202 Accepted and persists SUCCEEDED
        ApiResponse buildRes = router.handle(
            ApiRequest.of("POST", "/api/services/orders/build", csrfHeaders(), "{}")
        );
        Assert.assertEquals(202, buildRes.statusCode(), "202 Accepted for build");
        Assert.assertContains(buildRes.body(), "procomp", "cliCommand echo");

        // 3. GET /api/status reports SUCCEEDED for orders and NO_BUILDER for static-app
        ApiResponse statusRes = router.handle(ApiRequest.of("GET", "/api/status", getHeaders(), ""));
        Assert.assertEquals(200, statusRes.statusCode(), "200 status");
        Assert.assertContains(statusRes.body(), "\"displayState\":\"SUCCEEDED\"", "orders SUCCEEDED");
        Assert.assertContains(statusRes.body(), "\"displayState\":\"NO_BUILDER\"", "static-app NO_BUILDER");

        // 4. GET /api/services/orders/logs?build=true returns build stdout
        ApiResponse logsRes = router.handle(
            ApiRequest.of("GET", "/api/services/orders/logs", "build=true&lines=50", getHeaders(), "")
        );
        Assert.assertEquals(200, logsRes.statusCode(), "200 build logs");
        Assert.assertContains(logsRes.body(), "Web build ok", "build log text");
        Assert.assertContains(logsRes.body(), "--build", "cliCommand includes --build");

        // 5. UNKNOWN state returns 409 unless force=true, and cancel clears it
        Path out = stateStore.logPath("acme", ProcessKey.build("orders"), false);
        Path err = stateStore.logPath("acme", ProcessKey.build("orders"), true);
        Assert.assertOk(
            stateStore.writeBuildRun("acme", "orders", new BuildRunRecord.Running(888L, T0, List.of("./gradlew"), out, err)),
            "seed dead RUNNING record"
        );
        ApiResponse unknownRes = router.handle(
            ApiRequest.of("POST", "/api/services/orders/build", csrfHeaders(), "{}")
        );
        Assert.assertEquals(409, unknownRes.statusCode(), "409 for UNKNOWN build run without force");
        Assert.assertContains(unknownRes.body(), "BUILD_RUN_UNKNOWN", "UNKNOWN error code");

        ApiResponse cancelRes = router.handle(
            ApiRequest.of("POST", "/api/services/orders/build/cancel", csrfHeaders(), "")
        );
        Assert.assertEquals(200, cancelRes.statusCode(), "200 cancel clears UNKNOWN");
        JsonValue parsed = Assert.assertOk(Json.parse(cancelRes.body()), "parse cancel response");
        Assert.assertTrue(parsed instanceof JsonValue.JsonObject, "json object");
        Assert.assertContains(cancelRes.body(), "CLEARED_UNKNOWN", "CLEARED_UNKNOWN disposition");
    }
}

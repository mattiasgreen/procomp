package procomp.web;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.fakes.FakeClock;
import procomp.fakes.FakeGitHooksStore;
import procomp.fakes.FakeHttpClient;
import procomp.fakes.FakePortScanner;
import procomp.fakes.FakeProcessRunner;
import procomp.fakes.FakeProjectFiles;
import procomp.fakes.InMemoryConfigSource;
import procomp.fakes.InMemoryStateStore;
import procomp.shell.ProcompRuntime;
import procomp.web.ApiRouter.ApiRequest;
import procomp.web.ApiRouter.ApiResponse;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public final class GitHooksApiRouterTest {

    public static void testStatusIncludesGitHooksAndInstallEndpointUpdatesState() {
        Path root = Path.of("C:/work/acme").toAbsolutePath().normalize();
        Path reposRoot = Path.of("C:/work/repos").toAbsolutePath().normalize();
        Path ordersRepo = reposRoot.resolve("orders");

        InMemoryConfigSource config = new InMemoryConfigSource(root).withFile(root.resolve("procomp.json"), """
            {
              "project": "acme",
              "repositoriesRoot": "../repos",
              "runtimes": { "java": { "command": ["java", "-jar", "app.jar"] } },
              "services": {
                "orders": { "runtime": "java", "port": 9001, "repositoryUrl": "https://git.example/orders.git" }
              },
              "git": {
                "hooks": {
                  "sourceDir": "{{configDir}}/hooks",
                  "required": ["pre-commit"]
                }
              }
            }
            """);

        FakeGitHooksStore gitHooksStore = new FakeGitHooksStore()
            .withSourceFile(root.resolve("hooks/pre-commit"), "#!/bin/sh\necho ok\n")
            .withGitRepository(ordersRepo);

        FakeClock clock = new FakeClock(Instant.parse("2026-11-25T05:00:00Z"));
        ProcompRuntime runtime = ProcompRuntime.builder()
            .configSource(config)
            .stateStore(new InMemoryStateStore())
            .processRunner(new FakeProcessRunner(clock))
            .portScanner(new FakePortScanner())
            .httpClient(new FakeHttpClient())
            .projectFiles(new FakeProjectFiles().withDirectory(ordersRepo))
            .gitHooksStore(gitHooksStore)
            .clock(clock)
            .build();

        LiveProjectHolder holder = new LiveProjectHolder(
            Assert.assertOk(runtime.projectLoader(), "projectLoader"),
            Optional.empty()
        );
        ApiRouter router = new ApiRouter(runtime, holder, 9090);

        Map<String, String> getHeaders = Map.of("Host", "127.0.0.1:9090");
        Map<String, String> postHeaders = Map.of(
            "Host", "127.0.0.1:9090",
            "Origin", "http://127.0.0.1:9090",
            "X-Procomp-Request", "1"
        );

        // 1. GET /api/status includes gitHooks with hasWarnings: true
        ApiResponse statusBefore = router.handle(ApiRequest.of("GET", "/api/status", "", getHeaders, ""));
        Assert.assertEquals(200, statusBefore.statusCode(), "GET /api/status 200");
        JsonValue.JsonObject beforeJson = (JsonValue.JsonObject) Assert.assertOk(Json.parse(statusBefore.body()), "parse status");
        JsonValue.JsonObject dataObj = (JsonValue.JsonObject) beforeJson.get("data").orElseThrow();
        JsonValue.JsonObject gitHooksObj = (JsonValue.JsonObject) dataObj.get("gitHooks").orElseThrow();
        Assert.assertEquals(true, ((JsonValue.JsonBoolean) gitHooksObj.get("hasWarnings").orElseThrow()).value(), "hasWarnings true");

        // 2. GET /api/git/hooks returns report and verbatim CLI command
        ApiResponse hooksResp = router.handle(ApiRequest.of("GET", "/api/git/hooks", "", getHeaders, ""));
        Assert.assertEquals(200, hooksResp.statusCode(), "GET /api/git/hooks 200");
        JsonValue.JsonObject hooksJson = (JsonValue.JsonObject) Assert.assertOk(Json.parse(hooksResp.body()), "parse hooks");
        Assert.assertEquals("procomp git hooks status", ((JsonValue.JsonString) hooksJson.get("cliCommand").orElseThrow()).value(), "cli echo");

        // 3. POST /api/git/hooks/install without CSRF header is rejected (403)
        ApiResponse noCsrf = router.handle(ApiRequest.of("POST", "/api/git/hooks/install", "", getHeaders, "{}"));
        Assert.assertEquals(403, noCsrf.statusCode(), "CSRF enforced on POST /api/git/hooks/install");

        // 4. POST /api/git/hooks/install installs missing hook and echoes CLI command
        ApiResponse installResp = router.handle(ApiRequest.of(
            "POST", "/api/git/hooks/install", "", postHeaders, "{\"repositories\":[\"orders\"]}"
        ));
        Assert.assertEquals(200, installResp.statusCode(), "POST /api/git/hooks/install 200");
        JsonValue.JsonObject installJson = (JsonValue.JsonObject) Assert.assertOk(Json.parse(installResp.body()), "parse install");
        Assert.assertEquals("procomp git hooks install orders", ((JsonValue.JsonString) installJson.get("cliCommand").orElseThrow()).value(), "install cli echo");
        JsonValue.JsonObject installData = (JsonValue.JsonObject) installJson.get("data").orElseThrow();
        Assert.assertEquals(1L, ((JsonValue.JsonNumber) installData.get("totalInstalled").orElseThrow()).asLong().orElseThrow(), "1 installed");

        // 5. GET /api/status now reports hasWarnings: false
        ApiResponse statusAfter = router.handle(ApiRequest.of("GET", "/api/status", "", getHeaders, ""));
        Assert.assertEquals(200, statusAfter.statusCode(), "GET /api/status after 200");
        JsonValue.JsonObject afterJson = (JsonValue.JsonObject) Assert.assertOk(Json.parse(statusAfter.body()), "parse status after");
        JsonValue.JsonObject afterData = (JsonValue.JsonObject) afterJson.get("data").orElseThrow();
        JsonValue.JsonObject afterHooks = (JsonValue.JsonObject) afterData.get("gitHooks").orElseThrow();
        Assert.assertEquals(false, ((JsonValue.JsonBoolean) afterHooks.get("hasWarnings").orElseThrow()).value(), "hasWarnings false after install");
    }
}

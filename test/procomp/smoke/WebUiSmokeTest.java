package procomp.smoke;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.platform.FsConfigSource;
import procomp.platform.FsProjectFiles;
import procomp.platform.FsStateStore;
import procomp.platform.JdkHttpClient;
import procomp.platform.OsPortScanner;
import procomp.platform.OsProcessRunner;
import procomp.platform.SystemClock;
import procomp.shell.ProcompRuntime;
import procomp.shell.ProjectLoader;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProgressObserver;
import procomp.web.LiveProjectHolder;
import procomp.web.StaticAssets;
import procomp.web.WebUi;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class WebUiSmokeTest {

    public static void testWebUiStaticAssetsApiCsrfLiveSseReloadAndStopPreservesManagedProcess() throws Exception {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();

        Path tmpDir = Files.createTempDirectory("procomp-webui-smoke");
        long spawnedPid = -1L;
        WebUi webUi = null;

        try {
            int servicePort;
            try (ServerSocket socket = new ServerSocket(0)) {
                servicePort = socket.getLocalPort();
            }

            Path serviceDir = tmpDir.resolve("orders-svc");
            Files.createDirectories(serviceDir);
            Files.writeString(serviceDir.resolve("MiniService.java"), """
                public class MiniService {
                    public static void main(String[] args) throws Exception {
                        System.out.println("MINI_WEBUI_SERVICE_STARTED");
                        System.out.flush();
                        Thread.sleep(60000);
                    }
                }
                """, StandardCharsets.UTF_8);

            Path configFile = tmpDir.resolve("procomp.json");
            Path stateDir = tmpDir.resolve("state");
            Files.writeString(configFile, projectConfigJson(javaExe, servicePort, false), StandardCharsets.UTF_8);

            FsStateStore stateStore = new FsStateStore(
                System.getProperty("os.name", ""),
                Map.of("PROCOMP_STATE_DIR", stateDir.toString()),
                tmpDir
            );
            ProcompRuntime runtime = ProcompRuntime.builder()
                .configSource(new FsConfigSource())
                .stateStore(stateStore)
                .processRunner(new OsProcessRunner())
                .portScanner(new OsPortScanner())
                .httpClient(new JdkHttpClient())
                .projectFiles(new FsProjectFiles())
                .clock(new SystemClock())
                .observer(ProgressObserver.noop())
                .build();

            ProjectLoader loader = Assert.assertOk(runtime.projectLoader(), "projectLoader");
            LiveProjectHolder projectHolder = new LiveProjectHolder(loader, Optional.of(configFile));
            StaticAssets staticAssets = Assert.assertOk(StaticAssets.discoverDefault(), "discover web/");

            webUi = new WebUi(
                runtime,
                projectHolder,
                staticAssets,
                0,
                Duration.ofMillis(40),
                Duration.ofMillis(100)
            );
            int boundPort = Assert.assertOk(webUi.start(), "WebUi.start()");
            Assert.assertTrue(boundPort > 0, "ephemeral port assigned");
            String baseUrl = webUi.baseUrl();
            Assert.assertEquals("http://127.0.0.1:" + boundPort, baseUrl, "baseUrl matches 127.0.0.1:<port>");

            try (HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {

                // 1. Static ES6 serving: / -> index.html, /app.js -> text/javascript
                HttpResponse<String> indexRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                Assert.assertEquals(200, indexRes.statusCode(), "GET / returns 200");
                Assert.assertContains(
                    indexRes.headers().firstValue("Content-Type").orElse(""),
                    "text/html",
                    "GET / serves text/html"
                );
                Assert.assertEquals(
                    "no-cache",
                    indexRes.headers().firstValue("Cache-Control").orElse(""),
                    "GET / sets Cache-Control: no-cache"
                );
                Assert.assertContains(indexRes.body(), "<script type=\"module\"", "index.html loads ES6 module");

                HttpResponse<String> appJsRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/app.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                Assert.assertEquals(200, appJsRes.statusCode(), "GET /app.js returns 200");
                Assert.assertContains(
                    appJsRes.headers().firstValue("Content-Type").orElse(""),
                    "text/javascript",
                    "GET /app.js serves text/javascript"
                );

                // 2. GET /api/status
                HttpResponse<String> statusRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                Assert.assertEquals(200, statusRes.statusCode(), "GET /api/status returns 200");
                JsonValue.JsonObject statusJson = (JsonValue.JsonObject) Assert.assertOk(
                    Json.parse(statusRes.body()),
                    "valid /api/status JSON"
                );
                JsonValue.JsonObject statusData = (JsonValue.JsonObject) statusJson.get("data").orElseThrow();
                Assert.assertEquals("webui-smoke", ((JsonValue.JsonString) statusData.get("project").orElseThrow()).value(), "project name");
                Assert.assertEquals(1, ((JsonValue.JsonArray) statusData.get("services").orElseThrow()).elements().size(), "1 service initially");

                // 3. PUT /api/routes: CSRF rejection on bad Origin + happy path
                HttpResponse<String> badOriginRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/routes"))
                        .header("Content-Type", "application/json")
                        .header("Origin", "http://evil.example")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"services\":[\"orders\"],\"route\":\"LOCAL\"}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                Assert.assertEquals(403, badOriginRes.statusCode(), "bad Origin rejected with 403");
                Assert.assertContains(badOriginRes.body(), "FORBIDDEN_ORIGIN", "403 body contains FORBIDDEN_ORIGIN");

                // 3b. Cross-origin GET /api/services/start/stream rejected with HTTP 403 BEFORE 200 SSE headers
                HttpResponse<String> badSseOriginRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/services/start/stream?services=orders"))
                        .header("Origin", "http://evil.example")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                Assert.assertEquals(403, badSseOriginRes.statusCode(), "cross-origin SSE start stream rejected with 403");
                Assert.assertContains(badSseOriginRes.body(), "FORBIDDEN_ORIGIN", "403 SSE body contains FORBIDDEN_ORIGIN");

                HttpResponse<String> routeOkRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/routes"))
                        .header("Content-Type", "application/json")
                        .header("Origin", baseUrl)
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"services\":[\"orders\"],\"route\":\"LOCAL\"}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                Assert.assertEquals(200, routeOkRes.statusCode(), "valid PUT /api/routes returns 200; body=" + routeOkRes.body());
                Assert.assertContains(routeOkRes.body(), "\"LOCAL\"", "orders route updated to LOCAL");

                // 4. Live SSE GET /api/services/orders/logs/stream + clean disconnect cleanup
                Path logPath = stateStore.logPath("webui-smoke", ProcessKey.service("orders"), false);
                Files.createDirectories(logPath.getParent());
                Files.writeString(logPath, "boot-line-1\n", StandardCharsets.UTF_8);

                try (HttpClient sseClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()) {
                    HttpResponse<InputStream> sseRes = sseClient.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/api/services/orders/logs/stream?lines=10"))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofInputStream()
                    );
                    Assert.assertEquals(200, sseRes.statusCode(), "SSE logs stream returns 200");
                    Assert.assertContains(
                        sseRes.headers().firstValue("Content-Type").orElse(""),
                        "text/event-stream",
                        "SSE content type"
                    );

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(sseRes.body(), StandardCharsets.UTF_8))) {
                        StringBuilder seen = new StringBuilder();
                        boolean sawInitial = false;
                        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (System.nanoTime() < deadline) {
                            String line = reader.readLine();
                            if (line == null) {
                                break;
                            }
                            seen.append(line).append('\n');
                            if (line.contains("boot-line-1")) {
                                sawInitial = true;
                                break;
                            }
                        }
                        Assert.assertTrue(sawInitial, "SSE delivered initial log line; seen=" + seen);

                        Files.writeString(logPath, "appended-line-2\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

                        boolean sawAppended = false;
                        long appendDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (System.nanoTime() < appendDeadline) {
                            String line = reader.readLine();
                            if (line == null) {
                                break;
                            }
                            seen.append(line).append('\n');
                            if (line.contains("appended-line-2")) {
                                sawAppended = true;
                                break;
                            }
                        }
                        Assert.assertTrue(sawAppended, "SSE delivered appended log line; seen=" + seen);
                    }
                }

                // Wait for server-side SSE handler to detect disconnect and clean up
                long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (webUi.activeSseStreamCount() > 0 && System.nanoTime() < cleanupDeadline) {
                    Thread.sleep(50);
                }
                Assert.assertEquals(0, webUi.activeSseStreamCount(), "SSE stream cleaned up after client disconnect");

                // 5. procomp.json live reload on edit
                Files.writeString(configFile, projectConfigJson(javaExe, servicePort, true), StandardCharsets.UTF_8);
                // Ensure mtime advances even on coarse filesystem timestamps
                Files.setLastModifiedTime(configFile, FileTime.from(Instant.now().plusSeconds(2)));

                HttpResponse<String> reloadedRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                Assert.assertEquals(200, reloadedRes.statusCode(), "reloaded /api/status returns 200");
                Assert.assertContains(reloadedRes.body(), "\"billing\"", "live reload picks up newly added billing service");

                // 6. Start a real Managed process, stop WebUi, and verify Managed process remains alive
                HttpResponse<String> startRes = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/services/start"))
                        .header("Content-Type", "application/json")
                        .header("Origin", baseUrl)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"services\":[\"orders\"],\"detach\":true}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                Assert.assertEquals(200, startRes.statusCode(), "POST /api/services/start returns 200; body=" + startRes.body());
                JsonValue.JsonObject startJson = (JsonValue.JsonObject) Assert.assertOk(Json.parse(startRes.body()), "start json");
                JsonValue.JsonObject startData = (JsonValue.JsonObject) startJson.get("data").orElseThrow();
                JsonValue.JsonArray outcomes = (JsonValue.JsonArray) startData.get("outcomes").orElseThrow();
                JsonValue.JsonObject firstOutcome = (JsonValue.JsonObject) outcomes.elements().get(0);
                spawnedPid = ((JsonValue.JsonNumber) firstOutcome.get("pid").orElseThrow()).asLong().orElseThrow();
                Assert.assertTrue(
                    ProcessHandle.of(spawnedPid).map(ProcessHandle::isAlive).orElse(false),
                    "started Managed process is alive"
                );
            }

            webUi.stop();
            Assert.assertFalse(webUi.isRunning(), "WebUi reports stopped");
            Assert.assertTrue(
                ProcessHandle.of(spawnedPid).map(ProcessHandle::isAlive).orElse(false),
                "WebUi.stop() must leave running Managed process alive"
            );
        } finally {
            if (webUi != null) {
                webUi.stop();
            }
            if (spawnedPid > 0) {
                ProcessHandle.of(spawnedPid).ifPresent(ProcessHandle::destroyForcibly);
            }
            deleteRecursively(tmpDir);
        }
    }

    private static String projectConfigJson(String javaExe, int servicePort, boolean includeBilling) {
        String escapedJava = javaExe.replace("\\", "\\\\");
        String billingEntry = includeBilling
            ? ",\n    \"billing\": { \"runtime\": \"java-src\", \"port\": " + (servicePort + 1) + ", \"workingDir\": \"orders-svc\" }"
            : "";
        return """
            {
              "project": "webui-smoke",
              "runtimes": {
                "java-src": {
                  "command": ["%s", "MiniService.java"]
                }
              },
              "services": {
                "orders": {
                  "runtime": "java-src",
                  "port": %d,
                  "workingDir": "orders-svc"
                }%s
              }
            }
            """.formatted(escapedJava, servicePort, billingEntry);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}

package e2e.runner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * True end-to-end integration test exercising procomp (via JEP 458) against:
 * - Real WireMock standalone as the managed Gateway
 * - Real WireMock standalone as the simulated Remote upstream
 * - Dedicated embedded ActiveMQ Artemis broker (TCP-only health check)
 * - 3 real Spring Boot services (orders, pricing, notifications) built by Gradle
 */
final class ProcompTopologyE2ETest {

    private static final String SECRET_VALUE = "e2e-artemis-top-secret-9988";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Test
    void hybridLocalRemoteRoutingWithSpringBootArtemisAndRealWireMock() throws Exception {
        Path repoRoot = Path.of(requireProp("e2e.repoRoot")).toAbsolutePath().normalize();
        Path wiremockJar = Path.of(requireProp("e2e.wiremockJar")).toAbsolutePath().normalize();
        String javaHome = requireProp("e2e.javaHome");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();

        assertTrue(Files.isRegularFile(wiremockJar), "WireMock standalone JAR must exist: " + wiremockJar);

        int gwPort = freePort();
        int remotePort = freePort();
        int brokerPort = freePort();
        int ordersPort = freePort();
        int pricingPort = freePort();
        int notificationsPort = freePort();

        Path tmpDir = Files.createTempDirectory("procomp-real-e2e");
        Path stateDir = tmpDir.resolve("state");
        Path configFile = tmpDir.resolve("procomp.json");
        Process remoteWireMock = null;

        try {
            // 1. Start external Simulated Remote (real WireMock standalone, not managed by procomp)
            remoteWireMock = startExternalRemoteWireMock(javaExe, wiremockJar, remotePort, tmpDir);
            waitForHttp200("http://127.0.0.1:" + remotePort + "/__admin/health", Duration.ofSeconds(30));
            registerRemoteStub(remotePort, "/pricing/quote", """
                {"price": 99, "servedBy": "remote-wiremock-pricing"}
                """);

            // 2. Write .env, service-index.json (Catalog), and procomp.json
            Files.writeString(tmpDir.resolve(".env"), "E2E_DEMO_SECRET=" + SECRET_VALUE + "\n", StandardCharsets.UTF_8);

            Files.writeString(tmpDir.resolve("service-index.json"), """
                {
                  "apps": [
                    { "servicename": "broker",        "localport": %d, "type": "artemis-broker", "subproject": "e2e/broker" },
                    { "servicename": "orders",        "localport": %d, "type": "spring-boot",    "subproject": "e2e/orders" },
                    { "servicename": "pricing",       "localport": %d, "type": "spring-boot",    "subproject": "e2e/pricing" },
                    { "servicename": "notifications", "localport": %d, "type": "spring-boot",    "subproject": "e2e/notifications" }
                  ]
                }
                """.formatted(brokerPort, ordersPort, pricingPort, notificationsPort), StandardCharsets.UTF_8);

            Files.writeString(configFile, """
                {
                  "project": "real-e2e",
                  "repositoriesRoot": "%s",
                  "envFiles": [".env"],
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "http://127.0.0.1:%d/{{service}}",
                    "workingDir": "{{repositoriesRoot}}",
                    "vars": {
                      "jvmArgs": ["-Xmx256m"]
                    },
                    "env": {
                      "E2E_GATEWAY_URL": "http://127.0.0.1:%d",
                      "SPRING_ARTEMIS_MODE": "native",
                      "SPRING_ARTEMIS_BROKER_URL": "tcp://127.0.0.1:%d"
                    }
                  },
                  "runtimes": {
                    "spring-boot": {
                      "command": ["%s", "{{jvmArgs}}", "-Dserver.port={{port}}", "-jar", "{{artifact}}"],
                      "healthPath": "/actuator/health",
                      "artifactGlob": "{{subproject}}/build/libs/*.jar",
                      "artifactExclude": "*-plain.jar"
                    },
                    "artemis-broker": {
                      "command": ["%s", "{{jvmArgs}}", "-Dbroker.port={{port}}", "-jar", "{{artifact}}"],
                      "artifactGlob": "{{subproject}}/build/libs/*.jar",
                      "artifactExclude": "*-plain.jar"
                    }
                  },
                  "gateway": {
                    "type": "wiremock",
                    "port": %d,
                    "healthPath": "/__admin/health",
                    "command": ["%s", "-Xmx192m", "-jar", "%s", "--port", "%d", "--bind-address", "127.0.0.1", "--enable-stub-cors"],
                    "workingDir": "%s"
                  },
                  "catalog": {
                    "path": "service-index.json",
                    "itemsPath": "apps",
                    "map": {
                      "name": "servicename",
                      "port": "localport",
                      "runtime": "type",
                      "vars.subproject": "subproject"
                    }
                  },
                  "profiles": {
                    "orders-local": {
                      "local": ["broker", "orders", "notifications"]
                    }
                  }
                }
                """.formatted(
                escapeJson(repoRoot.toString()),
                remotePort,
                gwPort,
                brokerPort,
                escapeJson(javaExe),
                escapeJson(javaExe),
                gwPort,
                escapeJson(javaExe),
                escapeJson(wiremockJar.toString()),
                gwPort,
                escapeJson(tmpDir.toString())
            ), StandardCharsets.UTF_8);

            // 3. Apply Profile -> start Gateway -> reconcile -> start LOCAL slice (broker, orders, notifications)
            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "profile", "use", "orders-local"), "profile use");
            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "gateway", "start", "--timeout", "30"), "gateway start");
            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "gateway", "reconcile"), "gateway reconcile");
            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "start", "--timeout", "60"), "start local slice");

            // Verify status --json & secret hygiene
            CliResult status1 = assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "status", "--json"), "status --json");
            assertFalse(status1.stdout().contains(SECRET_VALUE), "status --json must never leak secrets");
            assertTrue(status1.stdout().contains("\"syncStatus\":\"IN_SYNC\""), "Gateway must be IN_SYNC: " + status1.stdout());

            // 4. Scenario 1: Hybrid flow (orders LOCAL -> pricing REMOTE via Gateway -> Artemis JMS -> notifications LOCAL)
            HttpResponse<String> orderResp1 = post("http://127.0.0.1:" + gwPort + "/orders/create");
            assertEquals(200, orderResp1.statusCode(), "POST /orders/create via Gateway failed: " + orderResp1.body());
            assertTrue(orderResp1.body().contains("\"servedBy\":\"local-orders\""), "Expected local orders: " + orderResp1.body());
            assertTrue(orderResp1.body().contains("\"pricedBy\":\"remote-wiremock-pricing\""), "Expected remote pricing: " + orderResp1.body());
            assertTrue(orderResp1.body().contains("\"price\":99"), "Expected remote price 99: " + orderResp1.body());

            String orderId1 = extractJsonString(orderResp1.body(), "orderId");
            assertNotNull(orderId1, "orderId must be present in response: " + orderResp1.body());

            // Verify async JMS delivery through ActiveMQ Artemis to notifications
            String notifBody1 = pollHttp200("http://127.0.0.1:" + gwPort + "/notifications/" + orderId1, Duration.ofSeconds(15));
            assertTrue(notifBody1.contains("\"pricedBy\":\"remote-wiremock-pricing\""), "Notification should carry remote pricing: " + notifBody1);
            assertTrue(notifBody1.contains("\"servedBy\":\"local-notifications\""), "Expected local notifications: " + notifBody1);

            // Verify Remote WireMock actually received /pricing/quote
            HttpResponse<String> remoteReqs = get("http://127.0.0.1:" + remotePort + "/__admin/requests");
            assertEquals(200, remoteReqs.statusCode());
            assertTrue(remoteReqs.body().contains("/pricing/quote"), "Remote WireMock journal should contain /pricing/quote");

            // 5. Scenario 2: Start pricing + flip Route to LOCAL -> prove UNIX separation (still remote until reconcile) -> reconcile -> hits local
            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "start", "pricing", "--timeout", "60"), "start pricing");
            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "route", "pricing", "local"), "route pricing local");

            CliResult drifted = assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "status", "--json"), "status drifted");
            assertTrue(drifted.stdout().contains("\"syncStatus\":\"DRIFTED\""), "Gateway must be DRIFTED before reconcile: " + drifted.stdout());

            HttpResponse<String> preReconcileOrder = post("http://127.0.0.1:" + gwPort + "/orders/create");
            assertEquals(200, preReconcileOrder.statusCode());
            assertTrue(preReconcileOrder.body().contains("\"pricedBy\":\"remote-wiremock-pricing\""),
                "Before reconcile, Gateway must still route pricing REMOTE: " + preReconcileOrder.body());

            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "gateway", "reconcile"), "gateway reconcile local");

            HttpResponse<String> orderResp2 = post("http://127.0.0.1:" + gwPort + "/orders/create");
            assertEquals(200, orderResp2.statusCode(), "POST /orders/create after reconcile failed: " + orderResp2.body());
            assertTrue(orderResp2.body().contains("\"pricedBy\":\"local-pricing\""),
                "After reconcile, orders must hit local pricing without restart: " + orderResp2.body());
            assertTrue(orderResp2.body().contains("\"price\":42"), "Expected local price 42: " + orderResp2.body());

            String orderId2 = extractJsonString(orderResp2.body(), "orderId");
            String notifBody2 = pollHttp200("http://127.0.0.1:" + gwPort + "/notifications/" + orderId2, Duration.ofSeconds(15));
            assertTrue(notifBody2.contains("\"pricedBy\":\"local-pricing\""), "Notification should reflect local pricing: " + notifBody2);

            // 6. Scenario 3: Fail-fast when LOCAL target is stopped (no silent fallback to REMOTE)
            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "stop", "pricing"), "stop pricing");
            HttpResponse<String> failedPricing = get("http://127.0.0.1:" + gwPort + "/pricing/quote");
            assertTrue(failedPricing.statusCode() >= 500,
                "Stopped LOCAL target must fail fast (5xx), not fall back to REMOTE; got " + failedPricing.statusCode() + ": " + failedPricing.body());
            assertFalse(failedPricing.body().contains("remote-wiremock-pricing"),
                "Stopped LOCAL target must never fall back to REMOTE");

            // 7. Scenario 4: Inspect secret hygiene + stop --all clean teardown
            CliResult inspectOrders = assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "inspect", "orders", "--json"), "inspect orders");
            assertFalse(inspectOrders.stdout().contains(SECRET_VALUE), "inspect --json must never leak secrets");

            assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "stop", "--all"), "stop --all");
            CliResult finalStatus = assertCliOk(runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "status", "--json"), "final status");
            assertTrue(finalStatus.stdout().contains("\"syncStatus\":\"OFFLINE\""), "Gateway must be OFFLINE after stop --all: " + finalStatus.stdout());
        } finally {
            // Best-effort cleanup of any remaining Managed processes via procomp stop --all
            try {
                if (Files.isRegularFile(configFile)) {
                    runProcomp(repoRoot, javaExe, javaHome, stateDir, configFile, "stop", "--all");
                }
            } catch (Exception ignored) {
            }
            if (remoteWireMock != null) {
                destroyTree(remoteWireMock.toHandle());
            }
            deleteRecursively(tmpDir);
        }
    }

    private static Process startExternalRemoteWireMock(String javaExe, Path wiremockJar, int port, Path workDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            javaExe, "-Xmx192m", "-jar", wiremockJar.toString(),
            "--port", Integer.toString(port),
            "--bind-address", "127.0.0.1"
        );
        pb.directory(workDir.toFile());
        pb.redirectOutput(workDir.resolve("remote-wiremock.out.log").toFile());
        pb.redirectError(workDir.resolve("remote-wiremock.err.log").toFile());
        return pb.start();
    }

    private static void registerRemoteStub(int remotePort, String urlPath, String responseJson) throws IOException, InterruptedException {
        String mapping = """
            {
              "request": { "method": "GET", "urlPath": "%s" },
              "response": {
                "status": 200,
                "headers": { "Content-Type": "application/json" },
                "body": %s
              }
            }
            """.formatted(urlPath, quoteJsonString(responseJson.trim()));

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + remotePort + "/__admin/mappings"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapping, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(201, resp.statusCode(), "Failed to register remote WireMock stub: " + resp.body());
    }

    private static CliResult runProcomp(
        Path repoRoot,
        String javaExe,
        String javaHome,
        Path stateDir,
        Path configFile,
        String... args
    ) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add(repoRoot.resolve("src/procomp/cli/Procomp.java").toString());
        cmd.add("--config");
        cmd.add(configFile.toString());
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoRoot.toFile());
        pb.environment().put("JAVA_HOME", javaHome);
        pb.environment().put("PROCOMP_STATE_DIR", stateDir.toString());
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            destroyTree(p.toHandle());
            throw new AssertionError("CLI timed out: " + cmd);
        }
        return new CliResult(p.exitValue(), stdout, stderr);
    }

    private static CliResult assertCliOk(CliResult res, String label) {
        assertEquals(0, res.exitCode(), label + " failed (exit " + res.exitCode() + ")\nSTDOUT:\n" + res.stdout() + "\nSTDERR:\n" + res.stderr());
        return res;
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void waitForHttp200(String url, Duration timeout) throws InterruptedException {
        pollHttp200(url, timeout);
    }

    private static String pollHttp200(String url, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String lastError = "no attempt";
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> resp = get(url);
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
                lastError = "HTTP " + resp.statusCode() + ": " + resp.body();
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for HTTP 200 from " + url + " (last: " + lastError + ")");
    }

    private static String extractJsonString(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String quoteJsonString(String raw) {
        return "\"" + escapeJson(raw) + "\"";
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String requireProp(String key) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + key);
        }
        return val;
    }

    private static void destroyTree(ProcessHandle handle) {
        List<ProcessHandle> all = new ArrayList<>(handle.descendants().toList());
        all.add(handle);
        for (ProcessHandle h : all) {
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        }
        for (ProcessHandle h : all) {
            try {
                h.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
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

    private record CliResult(int exitCode, String stdout, String stderr) {}
}

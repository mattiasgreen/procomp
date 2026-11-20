package procomp.smoke;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class EndToEndCliWorkflowSmokeTest {

    public static void testFullDailyCliWorkflowAcrossShimAndJep458WithRealProcessesAndWireMockStub() throws Exception {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();

        Path tmpDir = Files.createTempDirectory("procomp-e2e-workflow");
        Path stateDir = tmpDir.resolve("state");
        long gwPid = -1;
        long ordersPid = -1;

        try {
            int gwPort = freePort();
            int ordersPort = freePort();
            int billingPort = freePort();

            writeMiniWireMockGateway(tmpDir.resolve("wiremock"));
            writeMiniHealthService(tmpDir.resolve("orders-svc"), "orders");
            writeMiniHealthService(tmpDir.resolve("billing-svc"), "billing");

            Path configFile = tmpDir.resolve("procomp.json");
            Files.writeString(configFile, """
                {
                  "project": "e2e-acme",
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}",
                    "env": {
                      "E2E_SECRET": "e2e-top-secret-token"
                    }
                  },
                  "runtimes": {
                    "java-src": {
                      "command": ["%s", "MiniHealthService.java", "{{port}}"],
                      "healthPath": "/actuator/health"
                    }
                  },
                  "gateway": {
                    "type": "wiremock",
                    "port": %d,
                    "healthPath": "/__admin/health",
                    "command": ["%s", "MiniWireMockGateway.java", "%d"],
                    "workingDir": "wiremock"
                  },
                  "services": {
                    "orders": { "runtime": "java-src", "port": %d, "workingDir": "orders-svc" },
                    "billing": { "runtime": "java-src", "port": %d, "workingDir": "billing-svc" }
                  },
                  "profiles": {
                    "orders-local": {
                      "local": ["orders"]
                    }
                  }
                }
                """.formatted(
                javaExe.replace("\\", "\\\\"),
                gwPort,
                javaExe.replace("\\", "\\\\"),
                gwPort,
                ordersPort,
                billingPort
            ), StandardCharsets.UTF_8);

            // 1. profile use orders-local (via OS shim procomp.cmd / ./procomp)
            ProcessResult profileRes = runShim(isWindows, javaHome, stateDir,
                "--config", configFile.toString(), "profile", "use", "orders-local");
            Assert.assertEquals(0, profileRes.exitCode(), "profile use exits 0; stderr=" + profileRes.stderr());
            Assert.assertContains(profileRes.stdout(), "orders-local", "profile use reports profile");

            // 2. gateway start (via JEP 458 java src/procomp/cli/Procomp.java) -> spawns detached WireMock stub
            ProcessResult gwStartRes = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "gateway", "start", "--timeout", "20");
            Assert.assertEquals(0, gwStartRes.exitCode(), "gateway start exits 0; stderr=" + gwStartRes.stderr());
            Assert.assertContains(gwStartRes.stdout(), "MANAGED", "gateway start reports MANAGED");
            Assert.assertContains(gwStartRes.stdout(), "HEALTHY", "gateway start reports HEALTHY");

            // Verify status before reconcile shows DRIFTED (2)
            ProcessResult preReconcileStatus = runShim(isWindows, javaHome, stateDir,
                "--config", configFile.toString(), "status");
            Assert.assertEquals(0, preReconcileStatus.exitCode(), "status pre-reconcile exits 0; stderr=" + preReconcileStatus.stderr());
            Assert.assertContains(preReconcileStatus.stdout(), "DRIFTED (2)", "status shows DRIFTED (2) before reconcile");

            // 3. gateway reconcile -> IN_SYNC
            ProcessResult reconcile1 = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "gateway", "reconcile");
            Assert.assertEquals(0, reconcile1.exitCode(), "gateway reconcile exits 0; stderr=" + reconcile1.stderr());
            Assert.assertContains(reconcile1.stdout(), "IN_SYNC", "reconcile reports IN_SYNC");

            // 4. Bare start -> spawns ONLY LOCAL-routed orders, leaves REMOTE billing stopped
            ProcessResult startRes = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "start", "--timeout", "20");
            Assert.assertEquals(0, startRes.exitCode(), "bare start exits 0; stderr=" + startRes.stderr());
            Assert.assertContains(startRes.stdout(), "orders", "bare start launches orders");
            Assert.assertNotContains(startRes.stdout(), "billing", "bare start skips REMOTE billing");

            // 5. status (table via OS shim) & status --json (via JEP 458)
            ProcessResult statusText = runShim(isWindows, javaHome, stateDir,
                "--config", configFile.toString(), "status");
            Assert.assertEquals(0, statusText.exitCode(), "status table exits 0; stderr=" + statusText.stderr());
            for (String header : List.of("SERVICE", "ROUTE", "OWNER", "HEALTH", "PORT", "PID", "UPTIME", "STALE")) {
                Assert.assertContains(statusText.stdout(), header, "status table header " + header);
            }
            Assert.assertContains(statusText.stdout(), "IN SYNC", "status table Gateway summary IN SYNC");
            Assert.assertNotContains(statusText.stdout(), "e2e-top-secret-token", "status table never leaks secrets");

            ProcessResult statusJson = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "status", "--json");
            Assert.assertEquals(0, statusJson.exitCode(), "status --json exits 0; stderr=" + statusJson.stderr());
            Assert.assertNotContains(statusJson.stdout(), "e2e-top-secret-token", "status --json never leaks secrets");

            JsonValue.JsonObject statusObj = asObj(statusJson.stdout());
            Assert.assertEquals("e2e-acme", str(statusObj, "project"), "status json project");
            JsonValue.JsonObject gwObj = obj(statusObj, "gateway");
            Assert.assertTrue(bool(gwObj, "configured"), "gateway configured");
            Assert.assertEquals("MANAGED", str(gwObj, "owner"), "gateway owner MANAGED");
            Assert.assertEquals("HEALTHY", str(gwObj, "health"), "gateway health HEALTHY");
            Assert.assertEquals("IN_SYNC", str(gwObj, "syncStatus"), "gateway IN_SYNC");
            gwPid = numLong(gwObj, "pid");
            Assert.assertTrue(isAlive(gwPid), "gateway child JVM is alive across CLI exits");

            List<JsonValue> services = arr(statusObj, "services");
            Assert.assertEquals(2, services.size(), "2 services in status json");
            JsonValue.JsonObject ordersJson = (JsonValue.JsonObject) services.get(0);
            Assert.assertEquals("orders", str(ordersJson, "service"), "orders first");
            Assert.assertEquals("LOCAL", str(ordersJson, "route"), "orders route LOCAL");
            Assert.assertEquals("MANAGED", str(ordersJson, "owner"), "orders owner MANAGED");
            Assert.assertEquals("HEALTHY", str(ordersJson, "health"), "orders health HEALTHY");
            Assert.assertEquals((long) ordersPort, numLong(ordersJson, "port"), "orders port");
            ordersPid = numLong(ordersJson, "pid");
            Assert.assertTrue(isAlive(ordersPid), "orders child JVM is alive across CLI exits");
            Assert.assertFalse(bool(ordersJson, "stale"), "orders not stale");

            JsonValue.JsonObject billingJson = (JsonValue.JsonObject) services.get(1);
            Assert.assertEquals("billing", str(billingJson, "service"), "billing second");
            Assert.assertEquals("REMOTE", str(billingJson, "route"), "billing route REMOTE");
            Assert.assertEquals("NONE", str(billingJson, "owner"), "billing owner NONE");
            Assert.assertEquals("STOPPED", str(billingJson, "health"), "billing STOPPED");

            // 6. inspect orders --json
            ProcessResult inspectRes = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "inspect", "orders", "--json");
            Assert.assertEquals(0, inspectRes.exitCode(), "inspect orders --json exits 0; stderr=" + inspectRes.stderr());
            Assert.assertNotContains(inspectRes.stdout(), "e2e-top-secret-token", "inspect never leaks secrets");
            JsonValue.JsonObject inspectObj = asObj(inspectRes.stdout());
            Assert.assertEquals("MANAGED", str(inspectObj, "owner"), "inspect owner");
            Assert.assertEquals("HEALTHY", str(inspectObj, "health"), "inspect health");
            Assert.assertEquals("LOCAL", str(inspectObj, "desiredRoute"), "inspect desiredRoute");
            Assert.assertEquals(ordersPid, numLong(inspectObj, "pid"), "inspect pid matches");

            // 7. logs orders -n 5 -> raw untouched .out.log line
            ProcessResult logsRes = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "logs", "orders", "-n", "5");
            Assert.assertEquals(0, logsRes.exitCode(), "logs orders exits 0; stderr=" + logsRes.stderr());
            Assert.assertContains(logsRes.stdout(), "MINI_HEALTH_STARTED:orders:" + ordersPort, "logs reads raw stdout log");

            // 8. route orders remote -> leaves orders process alive & turns Gateway DRIFTED -> reconcile converges
            ProcessResult routeRemote = runShim(isWindows, javaHome, stateDir,
                "--config", configFile.toString(), "route", "orders", "remote");
            Assert.assertEquals(0, routeRemote.exitCode(), "route orders remote exits 0; stderr=" + routeRemote.stderr());
            Assert.assertTrue(isAlive(ordersPid), "route never kills running Managed process");

            ProcessResult driftedStatus = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "status", "--json");
            Assert.assertEquals(0, driftedStatus.exitCode(), "status --json after route flip exits 0");
            JsonValue.JsonObject driftedObj = asObj(driftedStatus.stdout());
            JsonValue.JsonObject driftedGw = obj(driftedObj, "gateway");
            Assert.assertEquals("DRIFTED", str(driftedGw, "syncStatus"), "gateway DRIFTED after route flip");
            Assert.assertEquals(1, arr(driftedGw, "drifted").size(), "1 drifted service");
            JsonValue.JsonObject ordersAfterFlip = (JsonValue.JsonObject) arr(driftedObj, "services").get(0);
            Assert.assertEquals("REMOTE", str(ordersAfterFlip, "route"), "orders route updated to REMOTE");
            Assert.assertEquals("HEALTHY", str(ordersAfterFlip, "health"), "orders process still HEALTHY");

            ProcessResult reconcileRemote = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "gateway", "reconcile");
            Assert.assertEquals(0, reconcileRemote.exitCode(), "reconcile after remote flip exits 0");
            Assert.assertContains(fetchMappingsJson(gwPort), "https://shared-dev.mycorp/orders", "WireMock live mapping updated to REMOTE URL");

            // Flip back to local -> DRIFTED -> reconcile back to LOCAL
            Assert.assertEquals(0, runShim(isWindows, javaHome, stateDir,
                "--config", configFile.toString(), "route", "orders", "local").exitCode(), "route orders local");
            JsonValue.JsonObject flipBackObj = asObj(runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "status", "--json").stdout());
            Assert.assertEquals("DRIFTED", str(obj(flipBackObj, "gateway"), "syncStatus"), "gateway DRIFTED on flip back to local");
            Assert.assertEquals(0, runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "gateway", "reconcile").exitCode(), "reconcile local");
            Assert.assertContains(fetchMappingsJson(gwPort), "http://127.0.0.1:" + ordersPort, "WireMock live mapping restored to local port");

            // 9. Bare stop -> stops Managed orders, leaves Managed Gateway running, keeps Routing Table intact
            ProcessResult stopServices = runShim(isWindows, javaHome, stateDir,
                "--config", configFile.toString(), "stop");
            Assert.assertEquals(0, stopServices.exitCode(), "bare stop exits 0; stderr=" + stopServices.stderr());
            Assert.assertFalse(isAlive(ordersPid), "orders JVM stopped by bare stop");
            ordersPid = -1;
            Assert.assertTrue(isAlive(gwPid), "Managed Gateway left running by bare stop");

            JsonValue.JsonObject afterStopObj = asObj(runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "status", "--json").stdout());
            Assert.assertEquals("HEALTHY", str(obj(afterStopObj, "gateway"), "health"), "gateway still HEALTHY");
            Assert.assertEquals("IN_SYNC", str(obj(afterStopObj, "gateway"), "syncStatus"), "gateway still IN_SYNC");
            JsonValue.JsonObject ordersAfterStop = (JsonValue.JsonObject) arr(afterStopObj, "services").get(0);
            Assert.assertEquals("LOCAL", str(ordersAfterStop, "route"), "stop never mutates Routing Table");
            Assert.assertEquals("NONE", str(ordersAfterStop, "owner"), "orders owner NONE after stop");
            Assert.assertEquals("STOPPED", str(ordersAfterStop, "health"), "orders STOPPED after stop");

            // 10. gateway stop -> stops Managed Gateway and status reports OFFLINE
            ProcessResult gwStop = runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "gateway", "stop");
            Assert.assertEquals(0, gwStop.exitCode(), "gateway stop exits 0; stderr=" + gwStop.stderr());
            Assert.assertFalse(isAlive(gwPid), "gateway JVM stopped by gateway stop");
            gwPid = -1;

            JsonValue.JsonObject finalStatus = asObj(runJep458(javaExe, javaHome, stateDir,
                "--config", configFile.toString(), "status", "--json").stdout());
            Assert.assertEquals("NONE", str(obj(finalStatus, "gateway"), "owner"), "gateway owner NONE");
            Assert.assertEquals("STOPPED", str(obj(finalStatus, "gateway"), "health"), "gateway STOPPED");
            Assert.assertEquals("OFFLINE", str(obj(finalStatus, "gateway"), "syncStatus"), "gateway OFFLINE");
        } finally {
            killTrackedOrPersistedProcesses(ordersPid, gwPid, stateDir.resolve("e2e-acme"));
            deleteRecursively(tmpDir);
        }
    }

    private static void writeMiniHealthService(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MiniHealthService.java"), """
            import com.sun.net.httpserver.HttpServer;
            import java.io.OutputStream;
            import java.net.InetAddress;
            import java.net.InetSocketAddress;
            import java.nio.charset.StandardCharsets;

            public class MiniHealthService {
                public static void main(String[] args) throws Exception {
                    int port = Integer.parseInt(args[0]);
                    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
                    server.createContext("/actuator/health", ex -> {
                        byte[] body = "{\\"status\\":\\"UP\\"}".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(200, body.length);
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(body);
                        }
                    });
                    server.start();
                    System.out.println("MINI_HEALTH_STARTED:%s:" + port);
                    System.out.flush();
                    Thread.sleep(300000);
                }
            }
            """.formatted(name), StandardCharsets.UTF_8);
    }

    private static void writeMiniWireMockGateway(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MiniWireMockGateway.java"), """
            import com.sun.net.httpserver.HttpExchange;
            import com.sun.net.httpserver.HttpServer;
            import java.io.IOException;
            import java.io.OutputStream;
            import java.net.InetAddress;
            import java.net.InetSocketAddress;
            import java.nio.charset.StandardCharsets;
            import java.util.ArrayList;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.regex.Matcher;
            import java.util.regex.Pattern;

            public class MiniWireMockGateway {
                private static final Map<String, String> MAPPINGS = new LinkedHashMap<>();

                public static void main(String[] args) throws Exception {
                    int port = Integer.parseInt(args[0]);
                    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
                    server.createContext("/__admin/health", ex -> send(ex, 200, "{\\"status\\":\\"healthy\\"}"));
                    server.createContext("/__admin/mappings", MiniWireMockGateway::handleMappings);
                    server.start();
                    System.out.println("MINI_WIREMOCK_STARTED:" + port);
                    System.out.flush();
                    Thread.sleep(300000);
                }

                private static synchronized void handleMappings(HttpExchange ex) throws IOException {
                    String method = ex.getRequestMethod();
                    String path = ex.getRequestURI().getPath();
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                    if ("GET".equals(method) && "/__admin/mappings".equals(path)) {
                        send(ex, 200, "{\\"mappings\\":[" + String.join(",", MAPPINGS.values()) + "]}");
                        return;
                    }
                    if ("POST".equals(method) && "/__admin/mappings/import".equals(path)) {
                        List<String> objs = extractMappingObjects(body);
                        for (String obj : objs) {
                            MAPPINGS.put(extractId(obj), obj);
                        }
                        send(ex, 200, "{}");
                        return;
                    }
                    if ("POST".equals(method) && "/__admin/mappings".equals(path)) {
                        MAPPINGS.put(extractId(body), body);
                        send(ex, 201, "{}");
                        return;
                    }
                    if ("DELETE".equals(method) && path.startsWith("/__admin/mappings/")) {
                        String id = path.substring("/__admin/mappings/".length());
                        MAPPINGS.remove(id);
                        send(ex, 200, "{}");
                        return;
                    }
                    send(ex, 404, "{}");
                }

                private static List<String> extractMappingObjects(String payload) {
                    int arrStart = payload.indexOf("[");
                    if (arrStart < 0) return List.of();
                    List<String> result = new ArrayList<>();
                    int depth = 0;
                    int objStart = -1;
                    boolean inStr = false;
                    boolean esc = false;
                    for (int i = arrStart + 1; i < payload.length(); i++) {
                        char c = payload.charAt(i);
                        if (esc) { esc = false; continue; }
                        if (c == '\\\\' && inStr) { esc = true; continue; }
                        if (c == '"') { inStr = !inStr; continue; }
                        if (inStr) continue;
                        if (c == '{') {
                            if (depth == 0) objStart = i;
                            depth++;
                        } else if (c == '}') {
                            depth--;
                            if (depth == 0 && objStart >= 0) {
                                result.add(payload.substring(objStart, i + 1));
                                objStart = -1;
                            }
                        } else if (c == ']' && depth == 0) {
                            break;
                        }
                    }
                    return result;
                }

                private static String extractId(String mappingJson) {
                    Matcher m = Pattern.compile("\\"id\\"\\s*:\\s*\\"([^\\"]+)\\"").matcher(mappingJson);
                    return m.find() ? m.group(1) : Integer.toString(mappingJson.hashCode());
                }

                private static void send(HttpExchange ex, int status, String body) throws IOException {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(status, bytes.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            }
            """, StandardCharsets.UTF_8);
    }

    private static String fetchMappingsJson(int gwPort) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gwPort + "/__admin/mappings"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Assert.assertEquals(200, resp.statusCode(), "GET /__admin/mappings returns 200");
        return resp.body();
    }

    private static ProcessResult runShim(boolean isWindows, String javaHome, Path stateDir, String... args)
        throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        if (isWindows) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("procomp.cmd");
        } else {
            cmd.add("sh");
            cmd.add("./procomp");
        }
        cmd.addAll(List.of(args));
        return runCli(cmd, javaHome, stateDir);
    }

    private static ProcessResult runJep458(String javaExe, String javaHome, Path stateDir, String... args)
        throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("src/procomp/cli/Procomp.java");
        cmd.addAll(List.of(args));
        return runCli(cmd, javaHome, stateDir);
    }

    private static ProcessResult runCli(List<String> command, String javaHome, Path stateDir)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("JAVA_HOME", javaHome);
        pb.environment().put("PROCOMP_STATE_DIR", stateDir.toString());
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new AssertionError("CLI command timed out: " + command);
        }
        return new ProcessResult(p.exitValue(), stdout, stderr);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean isAlive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void killTrackedOrPersistedProcesses(long ordersPid, long gwPid, Path projectStateDir) {
        killTree(ordersPid);
        killTree(gwPid);
        if (Files.isDirectory(projectStateDir)) {
            try (Stream<Path> walk = Files.walk(projectStateDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            String raw = Files.readString(p, StandardCharsets.UTF_8);
                            if (Json.parse(raw) instanceof procomp.core.common.Result.Ok<JsonValue, procomp.core.common.DomainError>(JsonValue val)
                                && val instanceof JsonValue.JsonObject obj
                                && obj.get("pid").orElse(null) instanceof JsonValue.JsonNumber num) {
                                num.asLong().ifPresent(EndToEndCliWorkflowSmokeTest::killTree);
                            }
                        } catch (Exception ignored) {
                        }
                    });
            } catch (Exception ignored) {
            }
        }
    }

    private static void killTree(long pid) {
        if (pid <= 0) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(handle -> {
            List<ProcessHandle> handles = new ArrayList<>(handle.descendants().toList());
            handles.add(handle);
            for (ProcessHandle h : handles) {
                if (h.isAlive()) {
                    h.destroyForcibly();
                }
            }
            for (ProcessHandle h : handles) {
                try {
                    h.onExit().get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static JsonValue.JsonObject asObj(String json) {
        return (JsonValue.JsonObject) Assert.assertOk(Json.parse(json.trim()), "valid JSON: " + json);
    }

    private static JsonValue.JsonObject obj(JsonValue.JsonObject parent, String key) {
        return (JsonValue.JsonObject) parent.get(key).orElseThrow();
    }

    private static List<JsonValue> arr(JsonValue.JsonObject parent, String key) {
        return ((JsonValue.JsonArray) parent.get(key).orElseThrow()).elements();
    }

    private static String str(JsonValue.JsonObject parent, String key) {
        return ((JsonValue.JsonString) parent.get(key).orElseThrow()).value();
    }

    private static boolean bool(JsonValue.JsonObject parent, String key) {
        return ((JsonValue.JsonBoolean) parent.get(key).orElseThrow()).value();
    }

    private static long numLong(JsonValue.JsonObject parent, String key) {
        return ((JsonValue.JsonNumber) parent.get(key).orElseThrow()).asLong().orElseThrow();
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

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}

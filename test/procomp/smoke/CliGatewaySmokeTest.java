package procomp.smoke;

import procomp.Assert;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class CliGatewaySmokeTest {

    public static void testJep458GatewayStartReconcileStatusJsonAndStopCleanly() throws Exception {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();

        Path tmpDir = Files.createTempDirectory("procomp-gateway-smoke");
        long spawnedPid = -1;
        try {
            int gwPort;
            try (ServerSocket s = new ServerSocket(0)) {
                gwPort = s.getLocalPort();
            }

            Path gwDir = tmpDir.resolve("wiremock");
            Files.createDirectories(gwDir);
            Files.writeString(gwDir.resolve("MiniWireMockGateway.java"), """
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
                                String id = extractId(obj);
                                MAPPINGS.put(id, obj);
                            }
                            send(ex, 200, "{}");
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

            Path configFile = tmpDir.resolve("procomp.json");
            Path stateDir = tmpDir.resolve("state");
            Files.writeString(configFile, """
                {
                  "project": "smoke-gw",
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "https://shared-dev.mycorp/{{service}}"
                  },
                  "runtimes": {
                    "java": { "command": ["%s", "-version"] }
                  },
                  "gateway": {
                    "type": "wiremock",
                    "port": %d,
                    "healthPath": "/__admin/health",
                    "command": ["%s", "MiniWireMockGateway.java", "%d"],
                    "workingDir": "wiremock",
                    "env": {
                      "GW_SECRET": "top-secret-gw-smoke"
                    }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "." }
                  }
                }
                """.formatted(
                javaExe.replace("\\", "\\\\"),
                gwPort,
                javaExe.replace("\\", "\\\\"),
                gwPort
            ), StandardCharsets.UTF_8);

            // 1. Start Managed Gateway via real JEP 458 CLI subprocess and wait for HEALTHY
            ProcessResult startRes = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "gateway", "start", "--timeout", "15"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, startRes.exitCode(), "gateway start exits 0; stderr=" + startRes.stderr());
            Assert.assertContains(startRes.stdout(), "MANAGED", "gateway start reports MANAGED");
            Assert.assertContains(startRes.stdout(), "HEALTHY", "gateway start waits until HEALTHY");

            // 2. Check status --json -> MANAGED, HEALTHY, DRIFTED; verify live PID survives CLI exit and no secret leaks
            ProcessResult statusBefore = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "gateway", "status", "--json"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, statusBefore.exitCode(), "gateway status --json exits 0; stderr=" + statusBefore.stderr());
            JsonValue.JsonObject beforeObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(statusBefore.stdout().trim()), "valid json");
            Assert.assertEquals("MANAGED", ((JsonValue.JsonString) beforeObj.get("owner").orElseThrow()).value(), "owner MANAGED");
            Assert.assertEquals("HEALTHY", ((JsonValue.JsonString) beforeObj.get("health").orElseThrow()).value(), "health HEALTHY");
            Assert.assertEquals("DRIFTED", ((JsonValue.JsonString) beforeObj.get("syncStatus").orElseThrow()).value(), "syncStatus DRIFTED");
            spawnedPid = ((JsonValue.JsonNumber) beforeObj.get("pid").orElseThrow()).asLong().orElseThrow();
            Assert.assertTrue(ProcessHandle.of(spawnedPid).map(ProcessHandle::isAlive).orElse(false), "gateway JVM survived CLI exit");
            Assert.assertNotContains(statusBefore.stdout(), "top-secret-gw-smoke", "gateway status never leaks secrets");

            // 3. Re-running gateway start reuses without spawning a second process
            ProcessResult reuseRes = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "gateway", "start"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, reuseRes.exitCode(), "second gateway start exits 0");
            Assert.assertContains(reuseRes.stdout(), "reused", "second gateway start reuses Managed Gateway");

            // 4. Route orders local -> reconcile -> gateway status --json reports IN_SYNC
            Assert.assertEquals(0, runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "route", "orders", "local"),
                javaHome,
                stateDir
            ).exitCode(), "route orders local");

            ProcessResult reconcileRes = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "gateway", "reconcile"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, reconcileRes.exitCode(), "gateway reconcile exits 0; stderr=" + reconcileRes.stderr());
            Assert.assertContains(reconcileRes.stdout(), "IN_SYNC", "reconcile outputs IN_SYNC");

            ProcessResult statusAfter = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "gateway", "status", "--json"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, statusAfter.exitCode(), "gateway status --json after reconcile exits 0");
            JsonValue.JsonObject afterObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(statusAfter.stdout().trim()), "valid json");
            Assert.assertEquals("IN_SYNC", ((JsonValue.JsonString) afterObj.get("syncStatus").orElseThrow()).value(), "syncStatus IN_SYNC");

            // 5. Stop Managed Gateway cleanly via CLI and verify process exited & status is OFFLINE
            ProcessResult stopRes = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "gateway", "stop"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, stopRes.exitCode(), "gateway stop exits 0; stderr=" + stopRes.stderr());
            Assert.assertContains(stopRes.stdout(), "stopped Managed Gateway", "stop actually terminated live Managed Gateway");
            Assert.assertFalse(ProcessHandle.of(spawnedPid).map(ProcessHandle::isAlive).orElse(false), "gateway JVM stopped");
            spawnedPid = -1;

            ProcessResult statusStopped = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "gateway", "status", "--json"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, statusStopped.exitCode(), "gateway status --json when stopped exits 0");
            JsonValue.JsonObject stoppedObj = (JsonValue.JsonObject) Assert.assertOk(Json.parse(statusStopped.stdout().trim()), "valid json");
            Assert.assertEquals("OFFLINE", ((JsonValue.JsonString) stoppedObj.get("syncStatus").orElseThrow()).value(), "syncStatus OFFLINE after stop");
        } finally {
            if (spawnedPid > 0) {
                ProcessHandle.of(spawnedPid).ifPresent(ProcessHandle::destroyForcibly);
            }
            deleteRecursively(tmpDir);
        }
    }

    private static ProcessResult runCli(List<String> command, String javaHome, Path stateDir)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("JAVA_HOME", javaHome);
        pb.environment().put("PROCOMP_STATE_DIR", stateDir.toString());
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new AssertionError("CLI command timed out: " + command);
        }
        return new ProcessResult(p.exitValue(), stdout, stderr);
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

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

public final class CliProcessSmokeTest {

    public static void testJep458StartDetachedSurvivesCliExitInspectJsonReusesAndStopsCleanly() throws Exception {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();

        Path tmpDir = Files.createTempDirectory("procomp-process-smoke");
        long spawnedPid = -1;
        try {
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }

            Path serviceDir = tmpDir.resolve("orders-svc");
            Files.createDirectories(serviceDir);
            Files.writeString(serviceDir.resolve("MiniHealthService.java"), """
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
                        System.out.println("MINI_HEALTH_STARTED:" + port);
                        System.out.flush();
                        Thread.sleep(60000);
                    }
                }
                """, StandardCharsets.UTF_8);

            Path configFile = tmpDir.resolve("procomp.json");
            Path stateDir = tmpDir.resolve("state");
            Files.writeString(configFile, """
                {
                  "project": "smoke-proc",
                  "defaults": {
                    "env": {
                      "TOP_SECRET": "do-not-print-me"
                    }
                  },
                  "runtimes": {
                    "java-src": {
                      "command": ["%s", "MiniHealthService.java", "{{port}}"],
                      "healthPath": "/actuator/health"
                    }
                  },
                  "services": {
                    "orders": {
                      "runtime": "java-src",
                      "port": %d,
                      "workingDir": "orders-svc"
                    }
                  }
                }
                """.formatted(javaExe.replace("\\", "\\\\"), port), StandardCharsets.UTF_8);

            // 1. Start detached via real JEP 458 CLI subprocess -> CLI exits 0 while child JVM survives
            ProcessResult startDetached = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "start", "orders", "-d"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, startDetached.exitCode(), "start -d exits 0; stderr=" + startDetached.stderr());

            // Wait for child JVM to finish compiling/booting and become HEALTHY
            ProcessResult startWait = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "start", "orders", "--timeout", "15"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, startWait.exitCode(), "second start reuses and waits for HEALTHY; stderr=" + startWait.stderr());
            Assert.assertContains(startWait.stdout(), "reused", "second start reuses existing detached Managed process");

            // 2. Inspect --json via real CLI subprocess -> verify live child survived across multiple CLI JVMs
            ProcessResult inspectRes = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "inspect", "orders", "--json"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, inspectRes.exitCode(), "inspect --json exits 0; stderr=" + inspectRes.stderr());
            JsonValue.JsonObject json = (JsonValue.JsonObject) Assert.assertOk(Json.parse(inspectRes.stdout().trim()), "valid inspect json");
            Assert.assertEquals("MANAGED", ((JsonValue.JsonString) json.get("owner").orElseThrow()).value(), "owner MANAGED");
            Assert.assertEquals("HEALTHY", ((JsonValue.JsonString) json.get("health").orElseThrow()).value(), "health HEALTHY");
            spawnedPid = ((JsonValue.JsonNumber) json.get("pid").orElseThrow()).asLong().orElseThrow();
            Assert.assertTrue(ProcessHandle.of(spawnedPid).map(ProcessHandle::isAlive).orElse(false), "child JVM survived CLI exits");
            Assert.assertNotContains(inspectRes.stdout(), "do-not-print-me", "inspect --json never leaks secrets");

            // 3. Stop via real CLI subprocess -> terminates child JVM cleanly
            ProcessResult stopRes = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "stop", "orders"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, stopRes.exitCode(), "stop orders exits 0; stderr=" + stopRes.stderr());
            Assert.assertFalse(ProcessHandle.of(spawnedPid).map(ProcessHandle::isAlive).orElse(false), "child JVM terminated by stop");
            spawnedPid = -1;
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

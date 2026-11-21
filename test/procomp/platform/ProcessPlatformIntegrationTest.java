package procomp.platform;

import com.sun.net.httpserver.HttpServer;
import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.HttpClient;
import procomp.core.process.ObservedProcess;
import procomp.core.process.PortBinding;
import procomp.shell.ports.ProcessRunner.SpawnRequest;
import procomp.shell.ports.ProcessRunner.SpawnedProcess;
import procomp.shell.ports.ProjectFiles.ArtifactInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class ProcessPlatformIntegrationTest {

    public static void testFsProjectFilesFindsNewestNonExcludedArtifactOnDisk() throws Exception {
        Path tmp = Files.createTempDirectory("procomp-ws-files");
        try {
            FsProjectFiles files = new FsProjectFiles();
            Assert.assertTrue(files.directoryExists(tmp), "temp dir exists");
            Assert.assertFalse(files.directoryExists(tmp.resolve("missing")), "missing dir does not exist");

            Path libs = tmp.resolve("svc1-mod").resolve("build").resolve("libs");
            Files.createDirectories(libs);
            Path oldJar = libs.resolve("svc1-1.0.jar");
            Path newJar = libs.resolve("svc1-2.0.jar");
            Path plainJar = libs.resolve("svc1-2.0-plain.jar");

            Files.writeString(oldJar, "old", StandardCharsets.UTF_8);
            Files.writeString(newJar, "new", StandardCharsets.UTF_8);
            Files.writeString(plainJar, "plain", StandardCharsets.UTF_8);

            Files.setLastModifiedTime(oldJar, FileTime.from(Instant.parse("2026-11-20T10:00:00Z")));
            Files.setLastModifiedTime(newJar, FileTime.from(Instant.parse("2026-11-20T10:05:00Z")));
            Files.setLastModifiedTime(plainJar, FileTime.from(Instant.parse("2026-11-20T10:10:00Z")));

            Optional<ArtifactInfo> found = Assert.assertOk(
                files.findNewestArtifact(tmp, "svc1-mod/build/libs/*.jar", Optional.of("*-plain.jar")),
                "findNewestArtifact succeeds"
            );
            Assert.assertTrue(found.isPresent(), "found matching jar");
            Assert.assertEquals(newJar.toAbsolutePath().normalize(), found.get().path(), "excluded *-plain.jar and picked newest fat jar");
        } finally {
            deleteRecursively(tmp);
        }
    }

    public static void testJdkHttpClientInteractsWithRealHttpServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/actuator/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/echo", exchange -> {
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            byte[] resp = (exchange.getRequestMethod() + ":" + new String(reqBody, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            JdkHttpClient client = new JdkHttpClient();
            var okResp = Assert.assertOk(
                client.get(URI.create("http://127.0.0.1:" + port + "/actuator/health"), Duration.ofSeconds(5)),
                "GET /actuator/health succeeds"
            );
            Assert.assertEquals(200, okResp.statusCode(), "status 200");
            Assert.assertTrue(okResp.is2xx(), "is2xx true");
            Assert.assertContains(okResp.body(), "UP", "body contains UP");

            var postResp = Assert.assertOk(
                client.send(new HttpClient.HttpRequestData(
                    "POST",
                    URI.create("http://127.0.0.1:" + port + "/echo"),
                    Map.of("Content-Type", "text/plain"),
                    "hello",
                    Duration.ofSeconds(5)
                )),
                "POST /echo succeeds"
            );
            Assert.assertEquals(201, postResp.statusCode(), "status 201");
            Assert.assertEquals("POST:hello", postResp.body(), "echoed method and body");

            int closedPort = findUnusedPort();
            Result<HttpClient.HttpResponseData, DomainError> refused =
                client.get(URI.create("http://127.0.0.1:" + closedPort + "/actuator/health"), Duration.ofSeconds(1));
            Assert.assertErr(refused, "connection refused returns typed Err");
        } finally {
            server.stop(0);
        }
    }

    public static void testOsPortScannerDetectsRealServerSocketsAndParsesCrossPlatformOutputs() throws Exception {
        OsPortScanner scanner = new OsPortScanner();
        long currentPid = ProcessHandle.current().pid();

        // 1. Real IPv4 loopback ServerSocket
        try (ServerSocket ipv4Socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int port = ipv4Socket.getLocalPort();
            Assert.assertTrue(scanner.isTcpListening(port, Duration.ofSeconds(1)), "IPv4 socket is listening");
            PortBinding listener = Assert.assertOk(scanner.findListener(port), "findListener IPv4").orElseThrow();
            Assert.assertEquals(port, listener.port(), "listener port");
            Assert.assertEquals(Optional.of(currentPid), listener.pid(), "listener pid matches current JVM");
        }

        // 2. Real wildcard ServerSocket (on Windows dual-stack binds [::]:port)
        try (ServerSocket wildcardSocket = new ServerSocket(0)) {
            int port = wildcardSocket.getLocalPort();
            Assert.assertTrue(scanner.isTcpListening(port, Duration.ofSeconds(1)), "wildcard socket is listening");
            PortBinding listener = Assert.assertOk(scanner.findListener(port), "findListener wildcard").orElseThrow();
            Assert.assertEquals(Optional.of(currentPid), listener.pid(), "wildcard listener pid matches current JVM");
        }

        // 3. Closed port returns empty
        int closedPort = findUnusedPort();
        Assert.assertFalse(scanner.isTcpListening(closedPort, Duration.ofMillis(200)), "closed port not listening");
        Assert.assertEquals(Optional.empty(), Assert.assertOk(scanner.findListener(closedPort), "closed port empty"), "no listener");

        // 4. Pure parser checks for Windows netstat, Linux ss, and POSIX lsof
        String winNetstatSample = """
            Active Connections
              Proto  Local Address          Foreign Address        State           PID
              TCP    0.0.0.0:9001           0.0.0.0:0              LISTENING       4321
              TCP    [::1]:9002             [::]:0                 LISTENING       5432
              TCP    127.0.0.1:9003         127.0.0.1:51000        ESTABLISHED     9999
            """;
        Assert.assertEquals(Optional.of(4321L), OsPortScanner.parseWindowsNetstat(winNetstatSample, 9001).flatMap(PortBinding::pid), "parse Windows IPv4");
        Assert.assertEquals(Optional.of(5432L), OsPortScanner.parseWindowsNetstat(winNetstatSample, 9002).flatMap(PortBinding::pid), "parse Windows IPv6");
        Assert.assertEquals(Optional.empty(), OsPortScanner.parseWindowsNetstat(winNetstatSample, 9003), "ignore ESTABLISHED");

        String ssSample = """
            State   Recv-Q Send-Q Local Address:Port Peer Address:Port Process
            LISTEN  0      128    127.0.0.1:9001     0.0.0.0:*         users:(("java",pid=8123,fd=42))
            LISTEN  0      128         [::]:9002        [::]:*         users:(("node",pid=9123,fd=19))
            """;
        Assert.assertEquals(Optional.of(8123L), OsPortScanner.parseLinuxSs(ssSample, 9001).flatMap(PortBinding::pid), "parse Linux ss IPv4");
        Assert.assertEquals(Optional.of(9123L), OsPortScanner.parseLinuxSs(ssSample, 9002).flatMap(PortBinding::pid), "parse Linux ss IPv6");

        String lsofSample = """
            COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
            java    7771 dev   42u  IPv6 0x1234      0t0  TCP *:9001 (LISTEN)
            """;
        Assert.assertEquals(Optional.of(7771L), OsPortScanner.parsePosixLsof(lsofSample, 9001).flatMap(PortBinding::pid), "parse POSIX lsof");
    }

    public static void testOsProcessRunnerSpawnsInspectsParentChainAndStopsTree() throws Exception {
        Path tmp = Files.createTempDirectory("procomp-proc-runner");
        OsProcessRunner runner = new OsProcessRunner();
        long childPid = -1;
        try {
            Path script = tmp.resolve("ChildLoop.java");
            Files.writeString(script, """
                public class ChildLoop {
                    public static void main(String[] args) throws Exception {
                        System.out.println("CHILD_OUT:" + System.getenv("PROCOMP_TEST_ENV"));
                        System.err.println("CHILD_ERR:ok");
                        System.out.flush();
                        System.err.flush();
                        Thread.sleep(60000);
                    }
                }
                """, StandardCharsets.UTF_8);

            Path outLog = tmp.resolve("child.out.log");
            Path errLog = tmp.resolve("child.err.log");
            String javaExe = currentJavaExe();

            SpawnedProcess spawned = Assert.assertOk(runner.spawnDetached(new SpawnRequest(
                List.of(javaExe, "ChildLoop.java"),
                tmp,
                Map.of("PROCOMP_TEST_ENV", "injected-value"),
                outLog,
                errLog
            )), "spawnDetached succeeds");
            childPid = spawned.pid();
            Assert.assertTrue(runner.isAlive(childPid), "spawned child is alive");

            // Wait briefly for child to emit output
            waitForFileContent(outLog, "CHILD_OUT:injected-value", Duration.ofSeconds(10));
            waitForFileContent(errLog, "CHILD_ERR:ok", Duration.ofSeconds(10));

            ObservedProcess snap = Assert.assertOk(runner.inspect(childPid), "inspect child succeeds").orElseThrow();
            Assert.assertTrue(snap.alive(), "snapshot alive");
            Assert.assertTrue(snap.commandLine().isPresent() || snap.executable().isPresent(), "snapshot includes cmdline or executable");
            long myPid = ProcessHandle.current().pid();
            boolean parentChainHasCurrentJvm = snap.parentChain().stream().anyMatch(p -> p.pid() == myPid);
            Assert.assertTrue(parentChainHasCurrentJvm, "parentChain contains test JVM PID " + myPid);

            Assert.assertOk(runner.stopTree(childPid, Duration.ofSeconds(5)), "stopTree terminates child cleanly");
            Assert.assertFalse(runner.isAlive(childPid), "child no longer alive after stopTree");
            childPid = -1;
        } finally {
            if (childPid > 0) {
                runner.stopTree(childPid, Duration.ofSeconds(2));
            }
            deleteRecursively(tmp);
        }
    }

    private static void waitForFileContent(Path file, String expectedSubstring, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.contains(expectedSubstring)) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        String actual = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "<missing>";
        Assert.fail("Timed out waiting for '" + expectedSubstring + "' in " + file + "; actual: " + actual);
    }

    private static int findUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String currentJavaExe() {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();
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

package procomp.platform;

import procomp.Assert;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.project.Project;
import procomp.core.routing.Route;
import procomp.core.routing.RoutingTable;
import procomp.shell.ProjectLoader;
import procomp.shell.RoutingShell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class FsRoutingPersistenceTest {

    public static void testFsStateStoreRoundTripsRoutingJsonAcrossTempProcompStateDir() throws Exception {
        Path tempRoot = Files.createTempDirectory("procomp-routing-l3");
        try {
            Path projectDir = tempRoot.resolve("project");
            Path stateDir = tempRoot.resolve("state");
            Files.createDirectories(projectDir);
            Files.createDirectories(stateDir);

            Path configPath = projectDir.resolve("procomp.json");
            Files.writeString(configPath, """
                {
                  "project": "acme",
                  "defaults": {
                    "proxyPathTemplate": "/{{service}}",
                    "remoteUrlTemplate": "https://shared.corp/{{service}}"
                  },
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "services": {
                    "service1": { "runtime": "java", "port": 9001, "workingDir": "." },
                    "service2": { "runtime": "java", "port": 9002, "workingDir": "." },
                    "service3": { "runtime": "java", "port": 9003, "workingDir": "." }
                  },
                  "profiles": {
                    "two-local": { "local": ["service2", "service3"] }
                  }
                }
                """, StandardCharsets.UTF_8);

            Project project = Assert.assertOk(
                new ProjectLoader(new FsConfigSource()).load(Optional.of(configPath)),
                "project config loads from disk"
            );

            Map<String, String> env = Map.of("PROCOMP_STATE_DIR", stateDir.toString());
            FsStateStore store1 = new FsStateStore(System.getProperty("os.name"), env, tempRoot);
            RoutingShell shell1 = new RoutingShell(store1);

            Path routingFile = stateDir.resolve("acme").resolve("routing.json");
            RoutingTable initial = Assert.assertOk(shell1.showRoutes(project), "initial showRoutes");
            Assert.assertEquals(List.of(), initial.localServices(), "fresh project has no LOCAL routes");
            Assert.assertFalse(Files.exists(routingFile), "read-only showRoutes must not create routing.json");

            Assert.assertOk(shell1.updateRoutes(project, List.of("service1"), Route.LOCAL), "persist service1 LOCAL");
            Assert.assertTrue(Files.isRegularFile(routingFile), "routing.json written under PROCOMP_STATE_DIR/acme");

            FsStateStore store2 = new FsStateStore(System.getProperty("os.name"), env, tempRoot);
            RoutingTable reloaded = Assert.assertOk(new RoutingShell(store2).showRoutes(project), "reload with fresh FsStateStore");
            Assert.assertEquals(Route.LOCAL, reloaded.routeOf("service1").orElseThrow(), "service1 persisted as LOCAL");
            Assert.assertEquals(Route.REMOTE, reloaded.routeOf("service2").orElseThrow(), "service2 persisted as REMOTE");
            Assert.assertEquals(Route.REMOTE, reloaded.routeOf("service3").orElseThrow(), "service3 persisted as REMOTE");

            Assert.assertOk(new RoutingShell(store2).useProfile(project, "two-local"), "apply profile to routing.json");
            String persistedBytesBeforeErr = Files.readString(routingFile, StandardCharsets.UTF_8);

            DomainError unknownSvc = Assert.assertErr(
                new RoutingShell(store2).updateRoutes(project, List.of("ghost"), Route.LOCAL),
                "unknown service fails fast"
            );
            Assert.assertEquals("UNKNOWN_SERVICE", unknownSvc.code(), "unknown service error code");
            DomainError unknownProf = Assert.assertErr(
                new RoutingShell(store2).useProfile(project, "ghost-profile"),
                "unknown profile fails fast"
            );
            Assert.assertEquals("UNKNOWN_PROFILE", unknownProf.code(), "unknown profile error code");
            Assert.assertEquals(
                persistedBytesBeforeErr,
                Files.readString(routingFile, StandardCharsets.UTF_8),
                "failed route/profile operations must not modify routing.json on disk"
            );

            // Real JEP 458 CLI subprocess check across separate invocations using PROCOMP_STATE_DIR
            String javaHome = System.getProperty("java.home");
            boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            String javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();

            ProcessResult cliUpdate = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configPath.toString(), "route", "service1", "local"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, cliUpdate.exitCode(), "CLI route update exits 0; stderr=" + cliUpdate.stderr());

            ProcessResult cliJson = runCli(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configPath.toString(), "route", "--json"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, cliJson.exitCode(), "CLI route --json exits 0; stderr=" + cliJson.stderr());
            JsonValue.JsonObject jsonRoot = (JsonValue.JsonObject) Assert.assertOk(
                Json.parse(cliJson.stdout().trim()),
                "CLI route --json outputs valid JSON"
            );
            JsonValue.JsonArray jsonRoutes = (JsonValue.JsonArray) jsonRoot.get("routes").orElseThrow();
            Assert.assertEquals("LOCAL", ((JsonValue.JsonString) ((JsonValue.JsonObject) jsonRoutes.elements().get(0)).get("route").orElseThrow()).value(), "service1 LOCAL across JEP 458 invocations");
            Assert.assertEquals("LOCAL", ((JsonValue.JsonString) ((JsonValue.JsonObject) jsonRoutes.elements().get(1)).get("route").orElseThrow()).value(), "service2 remains LOCAL from profile");
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private static ProcessResult runCli(List<String> command, String javaHome, Path stateDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("JAVA_HOME", javaHome);
        pb.environment().put("PROCOMP_STATE_DIR", stateDir.toString());
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("CLI subprocess timed out: " + command);
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

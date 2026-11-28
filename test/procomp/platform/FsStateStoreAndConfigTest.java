package procomp.platform;

import procomp.Assert;
import procomp.core.project.Project;
import procomp.shell.ProjectLoader;
import procomp.shell.ports.LogChunk;
import procomp.core.process.ManagedProcessRecord;
import procomp.shell.ports.ProcessKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class FsStateStoreAndConfigTest {

    public static void testResolvesStateDirsAcrossOsAndOverrides() {
        Path home = Path.of("C:/Users/dev").toAbsolutePath().normalize();

        FsStateStore overrideStore = new FsStateStore(
            "Windows 11",
            Map.of("PROCOMP_STATE_DIR", "C:/custom-state", "LOCALAPPDATA", "C:/ignored"),
            home
        );
        Assert.assertEquals(
            Path.of("C:/custom-state/acme").toAbsolutePath().normalize(),
            overrideStore.projectStateDir("acme"),
            "PROCOMP_STATE_DIR takes precedence over OS state dirs"
        );

        FsStateStore winStore = new FsStateStore(
            "Windows 11",
            Map.of("LOCALAPPDATA", "C:/Users/dev/AppData/Local"),
            home
        );
        Assert.assertEquals(
            Path.of("C:/Users/dev/AppData/Local/procomp/acme").toAbsolutePath().normalize(),
            winStore.projectStateDir("acme"),
            "Windows defaults to %LOCALAPPDATA%\\procomp\\<project>"
        );

        FsStateStore xdgStore = new FsStateStore(
            "Linux",
            Map.of("XDG_STATE_HOME", "/var/tmp/xdg"),
            Path.of("/home/dev")
        );
        Assert.assertEquals(
            Path.of("/var/tmp/xdg/procomp/acme").normalize(),
            xdgStore.projectStateDir("acme"),
            "POSIX honors XDG_STATE_HOME"
        );

        FsStateStore posixFallbackStore = new FsStateStore("Mac OS X", Map.of(), Path.of("/Users/dev"));
        Assert.assertEquals(
            Path.of("/Users/dev/.local/state/procomp/acme").normalize(),
            posixFallbackStore.projectStateDir("acme"),
            "POSIX falls back to ~/.local/state/procomp/<project>"
        );
    }

    public static void testRoundTripsRoutingManagedProcessAndLogsOnRealFilesystem() throws Exception {
        Path tempRoot = Files.createTempDirectory("procomp-fs-test");
        try {
            FsStateStore store = new FsStateStore(
                System.getProperty("os.name"),
                Map.of("PROCOMP_STATE_DIR", tempRoot.toString()),
                tempRoot
            );

            Assert.assertEquals(
                Optional.empty(),
                Assert.assertOk(store.readRouting("acme"), "missing routing.json returns empty"),
                "initial routing empty"
            );

            Map<String, String> routes = new LinkedHashMap<>();
            routes.put("service1", "LOCAL");
            routes.put("service2", "REMOTE");
            Assert.assertOk(store.writeRouting("acme", routes), "writeRouting succeeds");
            Assert.assertEquals(
                Optional.of(routes),
                Assert.assertOk(store.readRouting("acme"), "readRouting succeeds"),
                "routing round-trip matches"
            );

            Path outLog = store.logPath("acme", ProcessKey.service("service1"), false);
            Path errLog = store.logPath("acme", ProcessKey.service("service1"), true);
            ManagedProcessRecord rec = new ManagedProcessRecord.Running(
                4242L,
                Instant.parse("2026-11-20T12:00:00Z"),
                List.of("java", "-Xmx512m", "-jar", "service1.jar"),
                Optional.of(Instant.parse("2026-11-20T11:55:00Z")),
                outLog,
                errLog
            );
            Assert.assertOk(store.writeManagedProcess("acme", ProcessKey.service("service1"), rec), "write service record");
            Assert.assertEquals(
                Optional.of(rec),
                Assert.assertOk(store.readManagedProcess("acme", ProcessKey.service("service1")), "read service record"),
                "service ManagedProcessRecord.Running round-trips"
            );

            ManagedProcessRecord.Stopping stoppingRec = rec.requestStop(Instant.parse("2026-11-20T12:02:00Z"));
            Assert.assertOk(store.writeManagedProcess("acme", ProcessKey.service("service1"), stoppingRec), "write stopping record");
            Assert.assertEquals(
                Optional.of(stoppingRec),
                Assert.assertOk(store.readManagedProcess("acme", ProcessKey.service("service1")), "read stopping record"),
                "service ManagedProcessRecord.Stopping round-trips"
            );

            // Legacy JSON file without stopRequestedAt still parses as ManagedProcessRecord.Running
            Path legacyFile = tempRoot.resolve("acme").resolve("processes").resolve("service1.json");
            String legacyJson = """
                {
                  "pid": 4242,
                  "startedAt": "2026-11-20T12:00:00Z",
                  "commandSummary": ["java", "-Xmx512m", "-jar", "service1.jar"],
                  "artifactTimestamp": "2026-11-20T11:55:00Z",
                  "stdoutLog": "%s",
                  "stderrLog": "%s"
                }
                """.formatted(
                    outLog.toString().replace("\\", "\\\\"),
                    errLog.toString().replace("\\", "\\\\")
                );
            Files.writeString(legacyFile, legacyJson);
            Assert.assertEquals(
                Optional.of(rec),
                Assert.assertOk(store.readManagedProcess("acme", ProcessKey.service("service1")), "read legacy record"),
                "legacy JSON without stopRequestedAt parses as Running"
            );

            ManagedProcessRecord gwRec = new ManagedProcessRecord.Running(
                5252L,
                Instant.parse("2026-11-20T12:01:00Z"),
                List.of("java", "-jar", "wiremock.jar"),
                Optional.empty(),
                store.logPath("acme", ProcessKey.gateway(), false),
                store.logPath("acme", ProcessKey.gateway(), true)
            );
            Assert.assertOk(store.writeManagedProcess("acme", ProcessKey.gateway(), gwRec), "write gateway record");
            Assert.assertEquals(
                Optional.of(gwRec),
                Assert.assertOk(store.readManagedProcess("acme", ProcessKey.gateway()), "read gateway record"),
                "gateway ManagedProcessRecord round-trips"
            );

            Assert.assertOk(store.deleteManagedProcess("acme", ProcessKey.service("service1")), "delete service record");
            Assert.assertEquals(
                Optional.empty(),
                Assert.assertOk(store.readManagedProcess("acme", ProcessKey.service("service1")), "deleted record is absent"),
                "deleted record absent"
            );
            Assert.assertEquals(
                Optional.of(gwRec),
                Assert.assertOk(store.readManagedProcess("acme", ProcessKey.gateway()), "gateway record preserved"),
                "gateway record is stored separately from services"
            );

            Files.createDirectories(outLog.getParent());
            Files.writeString(outLog, "line1\n", StandardCharsets.UTF_8);
            LogChunk first = Assert.assertOk(store.readLogChunk(outLog, 0), "read initial log chunk");
            Assert.assertEquals("line1\n", first.text(), "initial log chunk text");

            Files.writeString(outLog, "line1\nline2\n", StandardCharsets.UTF_8);
            LogChunk second = Assert.assertOk(store.readLogChunk(outLog, first.nextOffset()), "read appended log chunk");
            Assert.assertEquals("line2\n", second.text(), "incremental log chunk reads only newly appended bytes");
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    public static void testLoadsRealExampleConfigAndServiceIndexFromDisk() {
        Path exampleConfig = Path.of("examples/using-service-index.procomp.json").toAbsolutePath().normalize();
        ProjectLoader loader = new ProjectLoader(new FsConfigSource());
        Project project = Assert.assertOk(
            loader.load(Optional.of(exampleConfig)),
            "examples/using-service-index.procomp.json should load cleanly"
        );
        Assert.assertEquals("acme", project.name(), "example project name");
        Assert.assertEquals(3, project.services().size(), "example loads 3 services from service-index.json");
        Assert.assertEquals(
            List.of("java", "-Xmx512m", "-Dserver.port=9001", "-jar", "{{artifact}}"),
            project.service("service1").orElseThrow().command(),
            "example service1 overrides jvmArgs"
        );
        Assert.assertEquals(
            List.of("java", "-Xmx384m", "-Dserver.port=9002", "-jar", "{{artifact}}"),
            project.service("service2").orElseThrow().command(),
            "example service2 inherits default jvmArgs"
        );
    }

    public static void testFsConfigSourceAtomicWriteRoundTrip() throws Exception {
        Path tmp = Files.createTempDirectory("procomp-cfg-write-test");
        try {
            Path configPath = tmp.resolve("procomp.json");
            String initial = """
                {
                  "project": "disk-test",
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "." }
                  }
                }
                """;
            Files.writeString(configPath, initial, StandardCharsets.UTF_8);

            FsConfigSource fs = new FsConfigSource();
            String updated = initial.replace("  \"services\":", "  \"profiles\": {\"p1\": {\"local\": [\"orders\"]}},\n  \"services\":");
            Assert.assertOk(fs.writeFile(configPath, updated), "atomic writeFile succeeds");
            Assert.assertEquals(updated, Assert.assertOk(fs.readFile(configPath), "read back"), "persisted content matches");
        } finally {
            deleteRecursively(tmp);
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
}

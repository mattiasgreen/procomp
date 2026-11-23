package procomp.platform;

import procomp.Assert;
import procomp.core.build.BuildRunRecord;
import procomp.core.common.DomainError;
import procomp.shell.ports.ProcessKey;
import procomp.shell.ports.ProcessRunner.ExitOutcome;
import procomp.shell.ports.ProcessRunner.RunningProcess;
import procomp.shell.ports.ProcessRunner.SpawnRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BuildPlatformIntegrationTest {

    public static void testFsStateStoreBuildRunRoundTripAndCorruptRejection() throws IOException {
        Path tempRoot = Files.createTempDirectory("procomp-build-state");
        try {
            FsStateStore store = new FsStateStore("Linux", Map.of("PROCOMP_STATE_DIR", tempRoot.toString()), tempRoot);
            Path outLog = store.logPath("acme", ProcessKey.build("orders"), false);
            Path errLog = store.logPath("acme", ProcessKey.build("orders"), true);
            Assert.assertTrue(outLog.getFileName().toString().equals("orders.build.out.log"), "build stdout log filename");
            Assert.assertTrue(errLog.getFileName().toString().equals("orders.build.err.log"), "build stderr log filename");

            Instant t0 = Instant.parse("2026-11-23T10:00:00Z");
            Instant t1 = Instant.parse("2026-11-23T10:00:05Z");

            // Running
            BuildRunRecord running = new BuildRunRecord.Running(1234L, t0, List.of("./gradlew", "assemble"), outLog, errLog);
            Assert.assertOk(store.writeBuildRun("acme", "orders", running), "write Running");
            Assert.assertEquals(Optional.of(running), Assert.assertOk(store.readBuildRun("acme", "orders"), "read Running"), "round-trip Running");

            // Succeeded
            BuildRunRecord succeeded = new BuildRunRecord.Succeeded(1234L, t0, t1, List.of("./gradlew", "assemble"), outLog, errLog);
            Assert.assertOk(store.writeBuildRun("acme", "orders", succeeded), "write Succeeded");
            Assert.assertEquals(Optional.of(succeeded), Assert.assertOk(store.readBuildRun("acme", "orders"), "read Succeeded"), "round-trip Succeeded");

            // Failed
            BuildRunRecord failed = new BuildRunRecord.Failed(1234L, t0, t1, Optional.of(2), "Build exited with code 2", List.of("./gradlew"), outLog, errLog);
            Assert.assertOk(store.writeBuildRun("acme", "orders", failed), "write Failed");
            Assert.assertEquals(Optional.of(failed), Assert.assertOk(store.readBuildRun("acme", "orders"), "read Failed"), "round-trip Failed");

            // Corrupt JSON fails cleanly with STATE_CORRUPT
            Path buildFile = store.projectStateDir("acme").resolve("builds").resolve("orders.json");
            Files.writeString(buildFile, "{\"pid\":1,\"status\":\"BOGUS\"}", StandardCharsets.UTF_8);
            DomainError corrupt = Assert.assertErr(store.readBuildRun("acme", "orders"), "corrupt status must fail");
            Assert.assertEquals("STATE_CORRUPT", corrupt.code(), "STATE_CORRUPT code");
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    public static void testOsProcessRunnerSpawnAttachedCapturesExitCodeLogsAndTimeout() throws IOException {
        Path tempDir = Files.createTempDirectory("procomp-build-os");
        try {
            OsProcessRunner runner = new OsProcessRunner();
            String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Path outLog = tempDir.resolve("build.out.log");
            Path errLog = tempDir.resolve("build.err.log");

            // 1. Exit 0 (java -version writes to stderr and exits 0)
            RunningProcess okProc = Assert.assertOk(
                runner.spawnAttached(new SpawnRequest(List.of(javaExe, "-version"), tempDir, Map.of(), outLog, errLog)),
                "spawn java -version"
            );
            ExitOutcome okOutcome = okProc.awaitExit(Optional.of(Duration.ofSeconds(15)));
            Assert.assertEquals(new ExitOutcome.Exited(0), okOutcome, "exit code 0");

            // 2. Non-zero exit code (java with bogus flag exits non-zero)
            RunningProcess failProc = Assert.assertOk(
                runner.spawnAttached(new SpawnRequest(List.of(javaExe, "--nonexistent-procomp-flag"), tempDir, Map.of(), outLog, errLog)),
                "spawn failing java command"
            );
            ExitOutcome failOutcome = failProc.awaitExit(Optional.of(Duration.ofSeconds(15)));
            Assert.assertTrue(
                failOutcome instanceof ExitOutcome.Exited(int code) && code != 0,
                "expected non-zero exit code, got: " + failOutcome
            );
            String errText = Files.readString(errLog, StandardCharsets.UTF_8);
            Assert.assertTrue(!errText.isBlank(), "stderr log should capture failing command output");

            // 3. Timeout + graceful stopTree on a long-running Java process
            Path sleeperSrc = tempDir.resolve("Sleeper.java");
            Files.writeString(
                sleeperSrc,
                "public class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(30_000); } }\n",
                StandardCharsets.UTF_8
            );
            RunningProcess slowProc = Assert.assertOk(
                runner.spawnAttached(new SpawnRequest(List.of(javaExe, sleeperSrc.toString()), tempDir, Map.of(), outLog, errLog)),
                "spawn sleeping java process"
            );
            try {
                ExitOutcome timeoutOutcome = slowProc.awaitExit(Optional.of(Duration.ofMillis(300)));
                Assert.assertEquals(ExitOutcome.TimedOut.INSTANCE, timeoutOutcome, "expected TimedOut");
                Assert.assertTrue(runner.isAlive(slowProc.pid()), "process still alive on timeout before stopTree");
            } finally {
                Assert.assertOk(runner.stopTree(slowProc.pid(), Duration.ofSeconds(5)), "stopTree on timed-out process");
            }
            Assert.assertFalse(runner.isAlive(slowProc.pid()), "process dead after stopTree");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Windows may hold redirected log handles briefly after process termination
                }
            }
        } catch (IOException ignored) {
            // Best-effort temp directory cleanup
        }
    }
}

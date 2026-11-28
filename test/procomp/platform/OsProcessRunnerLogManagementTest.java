package procomp.platform;

import procomp.Assert;
import procomp.shell.ports.ProcessRunner.ExitOutcome;
import procomp.shell.ports.ProcessRunner.RunningProcess;
import procomp.shell.ports.ProcessRunner.SpawnRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class OsProcessRunnerLogManagementTest {

    public static void testAppendPreservesRawLogsAcrossRestartsAndRotatesAtThreshold() throws Exception {
        Path tempRoot = Files.createTempDirectory("procomp-log-mgmt-test");
        try {
            Path emitterScript = tempRoot.resolve("Emit.java");
            Files.writeString(emitterScript, """
                public final class Emit {
                    public static void main(String[] args) {
                        System.out.println(args[0]);
                        System.err.println("ERR:" + args[0]);
                    }
                }
                """, StandardCharsets.UTF_8);

            Path outLog = tempRoot.resolve("logs").resolve("svc.out.log");
            Path errLog = tempRoot.resolve("logs").resolve("svc.err.log");
            String javaExe = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();

            // 1. Append preserves raw logs across restarts with zero injected lines
            OsProcessRunner appendRunner = new OsProcessRunner(1024 * 1024, 3);
            runEmit(appendRunner, tempRoot, javaExe, emitterScript, outLog, errLog, "run-1");
            runEmit(appendRunner, tempRoot, javaExe, emitterScript, outLog, errLog, "run-2");

            String outText = Files.readString(outLog, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String errText = Files.readString(errLog, StandardCharsets.UTF_8).replace("\r\n", "\n");
            Assert.assertEquals("run-1\nrun-2\n", outText, "stdout appends raw output across restarts with zero separators");
            Assert.assertEquals("ERR:run-1\nERR:run-2\n", errText, "stderr appends raw output across restarts with zero separators");

            // 2. Size-threshold rotation shifts .1 -> .2 -> .3 and prunes older generations
            OsProcessRunner rotatingRunner = new OsProcessRunner(10L, 3);
            // Active outLog is currently 12 bytes ("run-1\nrun-2\n") >= 10 -> rotates to .1 before writing "gen-A\n" (6 bytes)
            runEmit(rotatingRunner, tempRoot, javaExe, emitterScript, outLog, errLog, "gen-A-12345");
            Assert.assertTrue(Files.exists(outLog.resolveSibling("svc.out.log.1")), "backup .1 created on first rotation");
            Assert.assertEquals(
                "run-1\nrun-2\n",
                Files.readString(outLog.resolveSibling("svc.out.log.1"), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "backup .1 holds prior active content"
            );
            Assert.assertEquals(
                "gen-A-12345\n",
                Files.readString(outLog, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "fresh active file receives new run output"
            );

            runEmit(rotatingRunner, tempRoot, javaExe, emitterScript, outLog, errLog, "gen-B-12345");
            runEmit(rotatingRunner, tempRoot, javaExe, emitterScript, outLog, errLog, "gen-C-12345");
            runEmit(rotatingRunner, tempRoot, javaExe, emitterScript, outLog, errLog, "gen-D-12345");

            Assert.assertEquals(
                "gen-D-12345\n",
                Files.readString(outLog, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "active log has latest generation"
            );
            Assert.assertEquals(
                "gen-C-12345\n",
                Files.readString(outLog.resolveSibling("svc.out.log.1"), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "backup .1 has gen-C"
            );
            Assert.assertEquals(
                "gen-B-12345\n",
                Files.readString(outLog.resolveSibling("svc.out.log.2"), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "backup .2 has gen-B"
            );
            Assert.assertEquals(
                "gen-A-12345\n",
                Files.readString(outLog.resolveSibling("svc.out.log.3"), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "backup .3 has gen-A"
            );
            Assert.assertFalse(
                Files.exists(outLog.resolveSibling("svc.out.log.4")),
                "backup .4 is never created (oldest pruned at maxBackups=3)"
            );

            // 3. Best-effort fallback when rotation cannot delete/rename (blocked by non-empty directory at max backup .3)
            Path lockedOut = tempRoot.resolve("locked").resolve("svc.out.log");
            Path lockedErr = tempRoot.resolve("locked").resolve("svc.err.log");
            Files.createDirectories(lockedOut.getParent());
            Files.writeString(lockedOut, "existing-content-over-threshold\n", StandardCharsets.UTF_8);
            Path blockingDir = lockedOut.resolveSibling("svc.out.log.3");
            Files.createDirectories(blockingDir);
            Files.writeString(blockingDir.resolve("blocker.txt"), "keep", StandardCharsets.UTF_8);

            runEmit(rotatingRunner, tempRoot, javaExe, emitterScript, lockedOut, lockedErr, "appended-despite-lock");
            String lockedText = Files.readString(lockedOut, StandardCharsets.UTF_8).replace("\r\n", "\n");
            Assert.assertEquals(
                "existing-content-over-threshold\nappended-despite-lock\n",
                lockedText,
                "blocked rotation falls back to appending without failing Process startup"
            );
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private static void runEmit(
        OsProcessRunner runner,
        Path workingDir,
        String javaExe,
        Path emitterScript,
        Path outLog,
        Path errLog,
        String payload
    ) {
        RunningProcess proc = Assert.assertOk(
            runner.spawnAttached(new SpawnRequest(
                List.of(javaExe, emitterScript.toString(), payload),
                workingDir,
                Map.of(),
                outLog,
                errLog
            )),
            "spawn emitter for " + payload
        );
        ExitOutcome outcome = proc.awaitExit(Optional.of(Duration.ofSeconds(15)));
        Assert.assertEquals(new ExitOutcome.Exited(0), outcome, "emitter exited 0 for " + payload);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
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

package procomp.smoke;

import procomp.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class CliLogsSmokeTest {

    public static void testJep458LogsPathAndRawTailSmokeCheck() throws Exception {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();

        Path tmpDir = Files.createTempDirectory("procomp-logs-smoke");
        try {
            Path configFile = tmpDir.resolve("procomp.json");
            Path stateDir = tmpDir.resolve("state");
            Files.writeString(configFile, """
                {
                  "project": "acme",
                  "runtimes": {
                    "java": { "command": ["java", "-jar", "app.jar"] }
                  },
                  "services": {
                    "orders": { "runtime": "java", "port": 9001, "workingDir": "." }
                  }
                }
                """, StandardCharsets.UTF_8);

            ProcessResult pathRes = runCommand(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "logs", "orders", "--path"),
                javaHome,
                stateDir
            );
            Path expectedLog = stateDir.resolve("acme").resolve("logs").resolve("orders.out.log").normalize();
            Assert.assertEquals(0, pathRes.exitCode(), "logs --path should exit 0; stderr: " + pathRes.stderr());
            Assert.assertEquals(expectedLog.toString(), pathRes.stdout().trim(), "logs --path prints resolved path");

            Files.createDirectories(expectedLog.getParent());
            Files.writeString(expectedLog, "s1\ns2\ns3\n", StandardCharsets.UTF_8);

            ProcessResult tailRes = runCommand(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", configFile.toString(), "logs", "orders", "-n", "2"),
                javaHome,
                stateDir
            );
            Assert.assertEquals(0, tailRes.exitCode(), "logs -n 2 should exit 0; stderr: " + tailRes.stderr());
            Assert.assertEquals("s2\ns3\n", tailRes.stdout(), "logs -n 2 outputs raw untouched tail");
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private static ProcessResult runCommand(List<String> command, String javaHome, Path stateDir)
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
            throw new AssertionError("Command timed out: " + command);
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

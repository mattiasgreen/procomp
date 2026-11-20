package procomp.smoke;

import procomp.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class CliBootstrapSmokeTest {

    public static void testDirectJep458SourceLaunchSupportsHelpAndRejectsMalformedConfig() throws Exception {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String javaExe = Path.of(javaHome, "bin", isWindows ? "java.exe" : "java").toString();

        ProcessResult helpRes = runCommand(List.of(javaExe, "src/procomp/cli/Procomp.java", "--help"), javaHome);
        Assert.assertEquals(0, helpRes.exitCode(), "direct JEP 458 --help should exit 0; stderr: " + helpRes.stderr());
        Assert.assertContains(helpRes.stdout(), "Usage: procomp", "JEP 458 --help prints usage");

        Path tmpDir = Files.createTempDirectory("procomp-cli-smoke");
        try {
            Path badConfig = tmpDir.resolve("bad.json");
            Files.writeString(badConfig, "{\"project\": \"acme\", \"runtimes\": {}, \"unexpected\": 1}", StandardCharsets.UTF_8);

            ProcessResult badRes = runCommand(
                List.of(javaExe, "src/procomp/cli/Procomp.java", "--config", badConfig.toString(), "status"),
                javaHome
            );
            Assert.assertEquals(1, badRes.exitCode(), "malformed config should exit 1");
            Assert.assertContains(badRes.stderr(), "CONFIG_UNRECOGNIZED_FIELD", "stderr includes error code");
        } finally {
            Files.deleteIfExists(tmpDir.resolve("bad.json"));
            Files.deleteIfExists(tmpDir);
        }
    }

    public static void testOsLauncherShimExecutesHelp() throws Exception {
        String javaHome = System.getProperty("java.home");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        List<String> cmd = isWindows
            ? List.of("cmd.exe", "/c", "procomp.cmd", "--help")
            : List.of("sh", "./procomp", "--help");

        ProcessResult res = runCommand(cmd, javaHome);
        Assert.assertEquals(0, res.exitCode(), "launcher shim --help should exit 0; stderr: " + res.stderr());
        Assert.assertContains(res.stdout(), "Usage: procomp", "launcher shim --help prints usage");
    }

    private static ProcessResult runCommand(List<String> command, String javaHome) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("JAVA_HOME", javaHome);
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

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}

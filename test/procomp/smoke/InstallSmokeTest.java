package procomp.smoke;

import procomp.Assert;
import procomp.cli.ProcompVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Level 3 integration smoke tests for the pure-Bash {@code install.sh} recursive source installer
 * and generated {@code uninstall.sh}.
 */
public final class InstallSmokeTest {

    public static void testInstallUpgradeAndUninstallPrintOnlyLifecycle() throws Exception {
        String bash = resolveBashExecutable();
        String javaHome = System.getProperty("java.home");
        boolean isWindows = isWindows();

        Path sandbox = Files.createTempDirectory("procomp-install-smoke");
        try {
            Path fakeHome = sandbox.resolve("home");
            Path installDir = sandbox.resolve("install-dir");
            Path binDir = sandbox.resolve("bin-dir");
            Files.createDirectories(fakeHome);

            // 1. Fresh non-interactive install
            ProcessResult installRes = runBash(
                bash,
                List.of("./install.sh", "--yes", "--dir", toBashPath(installDir, isWindows), "--bin-dir", toBashPath(binDir, isWindows)),
                Map.of("HOME", toBashPath(fakeHome, isWindows), "JAVA_HOME", javaHome)
            );
            Assert.assertEquals(0, installRes.exitCode(), "fresh install should exit 0; stdout=" + installRes.stdout() + " stderr=" + installRes.stderr());
            Assert.assertTrue(Files.isDirectory(installDir.resolve("src")), "install copied src/");
            Assert.assertTrue(Files.isDirectory(installDir.resolve("web")), "install copied web/");
            Assert.assertTrue(Files.isRegularFile(installDir.resolve("VERSION")), "install stamped VERSION");
            Assert.assertTrue(Files.isRegularFile(installDir.resolve(".procomp-install")), "install wrote .procomp-install marker");
            Assert.assertTrue(Files.isRegularFile(installDir.resolve("uninstall.sh")), "install wrote standalone uninstall.sh");
            Assert.assertTrue(Files.isRegularFile(binDir.resolve("procomp")), "install wrote POSIX procomp shim");
            if (isWindows) {
                Assert.assertTrue(Files.isRegularFile(binDir.resolve("procomp.cmd")), "install wrote Windows procomp.cmd shim on Git Bash");
            }
            Assert.assertContains(installRes.stdout(), "export PATH=", "install prints PATH guidance when bin-dir is not on PATH");

            // 2. Installed shim executes --version and reads stamped VERSION
            String stampedVersion = Files.readString(installDir.resolve("VERSION"), StandardCharsets.UTF_8).trim();
            ProcessResult versionRes = runBash(
                bash,
                List.of(toBashPath(binDir.resolve("procomp"), isWindows), "--version"),
                Map.of("HOME", toBashPath(fakeHome, isWindows), "JAVA_HOME", javaHome)
            );
            Assert.assertEquals(0, versionRes.exitCode(), "installed shim --version should exit 0; stderr=" + versionRes.stderr());
            Assert.assertEquals("procomp " + stampedVersion, versionRes.stdout().trim(), "installed shim prints stamped VERSION");

            if (isWindows) {
                ProcessResult cmdVersionRes = runProcess(
                    List.of("cmd.exe", "/c", binDir.resolve("procomp.cmd").toString(), "--version"),
                    Map.of("JAVA_HOME", javaHome)
                );
                Assert.assertEquals(0, cmdVersionRes.exitCode(), "Windows procomp.cmd shim --version should exit 0; stderr=" + cmdVersionRes.stderr());
                Assert.assertEquals("procomp " + stampedVersion, cmdVersionRes.stdout().trim(), "procomp.cmd shim prints stamped VERSION");
            }

            // 2b. Installed copy discovers its own web/ assets from an unrelated working directory
            Path unrelatedCwd = sandbox.resolve("unrelated-project");
            Files.createDirectories(unrelatedCwd);
            Files.writeString(
                unrelatedCwd.resolve("procomp.json"),
                "{\"project\": \"smoke\", \"runtimes\": {}}\n",
                StandardCharsets.UTF_8
            );
            verifyInstalledWebUiServesAssets(bash, binDir.resolve("procomp"), unrelatedCwd, fakeHome, javaHome, isWindows);

            // 3. Upgrade removes stale .java file, moves old install to .bak-<timestamp>, and prints rm -rf without deleting backup
            Path staleFile = installDir.resolve("src/procomp/cli/StaleDeletedFile.java");
            Files.writeString(staleFile, "// stale file from older version\n", StandardCharsets.UTF_8);
            ProcessResult upgradeRes = runBash(
                bash,
                List.of("./install.sh", "--yes", "--dir", toBashPath(installDir, isWindows), "--bin-dir", toBashPath(binDir, isWindows)),
                Map.of("HOME", toBashPath(fakeHome, isWindows), "JAVA_HOME", javaHome)
            );
            Assert.assertEquals(0, upgradeRes.exitCode(), "upgrade should exit 0; stderr=" + upgradeRes.stderr());
            Assert.assertFalse(Files.exists(staleFile), "upgrade should remove stale .java file from active install");
            Assert.assertContains(upgradeRes.stdout(), "rm -rf", "upgrade prints rm -rf cleanup command for backup dir");

            Path backupDir = findBackupDir(sandbox, "install-dir.bak-")
                .orElseThrow(() -> new AssertionError("Expected backup directory install-dir.bak-* under " + sandbox));
            Assert.assertTrue(Files.exists(backupDir.resolve("src/procomp/cli/StaleDeletedFile.java")), "backup preserves previous install untouched");

            // 4. Standalone uninstall.sh and ./install.sh --uninstall print exact rm commands and delete nothing
            ProcessResult uninstallRes = runBash(
                bash,
                List.of(toBashPath(installDir.resolve("uninstall.sh"), isWindows)),
                Map.of("HOME", toBashPath(fakeHome, isWindows))
            );
            Assert.assertEquals(0, uninstallRes.exitCode(), "uninstall.sh should exit 0; stderr=" + uninstallRes.stderr());
            Assert.assertContains(uninstallRes.stdout(), "rm -f", "uninstall prints rm -f for shims");
            Assert.assertContains(uninstallRes.stdout(), "rm -rf", "uninstall prints rm -rf for install dir");
            Assert.assertTrue(Files.exists(installDir), "uninstall must NOT delete install dir automatically");
            Assert.assertTrue(Files.exists(binDir.resolve("procomp")), "uninstall must NOT delete shim automatically");

            ProcessResult flagUninstallRes = runBash(
                bash,
                List.of("./install.sh", "--uninstall", "--dir", toBashPath(installDir, isWindows)),
                Map.of("HOME", toBashPath(fakeHome, isWindows))
            );
            Assert.assertEquals(0, flagUninstallRes.exitCode(), "./install.sh --uninstall should exit 0; stderr=" + flagUninstallRes.stderr());
            Assert.assertContains(flagUninstallRes.stdout(), "rm -rf", "--uninstall prints rm -rf for install dir");

            // 5. Refuses to overwrite a non-empty unmarked directory
            Path foreignDir = sandbox.resolve("foreign-dir");
            Files.createDirectories(foreignDir);
            Files.writeString(foreignDir.resolve("important.txt"), "do not touch\n", StandardCharsets.UTF_8);
            ProcessResult foreignRes = runBash(
                bash,
                List.of("./install.sh", "--yes", "--dir", toBashPath(foreignDir, isWindows), "--bin-dir", toBashPath(binDir, isWindows)),
                Map.of("HOME", toBashPath(fakeHome, isWindows), "JAVA_HOME", javaHome)
            );
            Assert.assertTrue(foreignRes.exitCode() != 0, "install must fail on non-empty unmarked directory");
            Assert.assertTrue(Files.exists(foreignDir.resolve("important.txt")), "unmarked directory contents remain untouched");
        } finally {
            deleteRecursively(sandbox);
        }
    }

    public static void testVersionResolutionReadsSiblingVersionFileAndFallsBackToDev() throws Exception {
        Path tmpRoot = Files.createTempDirectory("procomp-version-test");
        try {
            Path nested = tmpRoot.resolve("install/src/procomp/cli");
            Files.createDirectories(nested);
            Files.writeString(
                tmpRoot.resolve("install/VERSION"),
                "v0.9.0 (abc1234, installed 2026-11-24T06:00:00Z)\n",
                StandardCharsets.UTF_8
            );
            Assert.assertEquals(
                "v0.9.0 (abc1234, installed 2026-11-24T06:00:00Z)",
                ProcompVersion.resolveFrom(nested),
                "should walk up from search root and read trimmed VERSION file"
            );

            Path blankDir = tmpRoot.resolve("blank");
            Files.createDirectories(blankDir);
            Files.writeString(blankDir.resolve("VERSION"), "   \n  ", StandardCharsets.UTF_8);
            Assert.assertEquals(
                "dev",
                ProcompVersion.resolveFrom(blankDir),
                "blank VERSION file should fall back to dev"
            );

            Path emptyDir = tmpRoot.resolve("empty");
            Files.createDirectories(emptyDir);
            Assert.assertEquals(
                "dev",
                ProcompVersion.resolveFrom(emptyDir),
                "missing VERSION file should fall back to dev"
            );
        } finally {
            deleteRecursively(tmpRoot);
        }
    }

    private static void verifyInstalledWebUiServesAssets(
        String bash,
        Path shimPath,
        Path workingDir,
        Path fakeHome,
        String javaHome,
        boolean isWindows
    ) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            bash,
            toBashPath(shimPath, isWindows),
            "ui",
            "--port",
            "0"
        );
        pb.directory(workingDir.toFile());
        pb.environment().put("HOME", toBashPath(fakeHome, isWindows));
        pb.environment().put("JAVA_HOME", javaHome);
        Process p = pb.start();
        try {
            String urlLine;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                urlLine = reader.readLine();
            }
            Assert.assertTrue(
                urlLine != null && urlLine.startsWith("http://127.0.0.1:"),
                "installed procomp ui should print localhost URL; got: " + urlLine
            );

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(urlLine + "/")).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            Assert.assertEquals(200, resp.statusCode(), "installed procomp ui should serve index.html with HTTP 200");
            Assert.assertContains(resp.body().toLowerCase(Locale.ROOT), "<!doctype html>", "response body should be HTML");
        } finally {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static void testInstallRejectsJavaOlderThan25() throws Exception {
        String bash = resolveBashExecutable();
        boolean isWindows = isWindows();

        Path sandbox = Files.createTempDirectory("procomp-install-jdk-check");
        try {
            Path fakeJavaHome = sandbox.resolve("fake-jdk21");
            Path fakeBin = fakeJavaHome.resolve("bin");
            Files.createDirectories(fakeBin);

            String fakeJavaScript = "#!/usr/bin/env sh\necho 'openjdk version \"21.0.4\" 2024-07-16' >&2\nexit 0\n";
            Path fakeJava = fakeBin.resolve("java");
            Files.writeString(fakeJava, fakeJavaScript, StandardCharsets.UTF_8);
            fakeJava.toFile().setExecutable(true);

            Path installDir = sandbox.resolve("install-dir");
            Path binDir = sandbox.resolve("bin-dir");
            ProcessResult res = runBash(
                bash,
                List.of("./install.sh", "--yes", "--dir", toBashPath(installDir, isWindows), "--bin-dir", toBashPath(binDir, isWindows)),
                Map.of("JAVA_HOME", toBashPath(fakeJavaHome, isWindows))
            );
            Assert.assertTrue(res.exitCode() != 0, "install must reject JDK 21; stdout=" + res.stdout() + " stderr=" + res.stderr());
            Assert.assertContains(res.stderr(), "25", "error message mentions required JDK 25");
            Assert.assertFalse(Files.exists(installDir), "install must not create install dir when JDK check fails");
        } finally {
            deleteRecursively(sandbox);
        }
    }

    public static void testRelativeInstallPathsResolveToAbsoluteShims() throws Exception {
        String bash = resolveBashExecutable();
        String javaHome = System.getProperty("java.home");
        boolean isWindows = isWindows();

        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        Path buildDir = repoRoot.resolve("build");
        Files.createDirectories(buildDir);
        Path sandbox = Files.createTempDirectory(buildDir, "procomp-rel-install");
        try {
            Path fakeHome = sandbox.resolve("home");
            Files.createDirectories(fakeHome);

            String relDir = "./" + repoRoot.relativize(sandbox.resolve("rel-install")).toString().replace('\\', '/');
            String relBin = "./" + repoRoot.relativize(sandbox.resolve("rel-bin")).toString().replace('\\', '/');

            ProcessResult res = runBash(
                bash,
                List.of("./install.sh", "--yes", "--dir", relDir, "--bin-dir", relBin),
                Map.of("HOME", toBashPath(fakeHome, isWindows), "JAVA_HOME", javaHome)
            );
            Assert.assertEquals(0, res.exitCode(), "relative install should exit 0; stderr=" + res.stderr());

            String shimContent = Files.readString(sandbox.resolve("rel-bin/procomp"), StandardCharsets.UTF_8);
            Assert.assertFalse(shimContent.contains("exec \"./"), "shim must contain absolute path, not relative './'");

            ProcessResult uninstallRes = runBash(
                bash,
                List.of(toBashPath(sandbox.resolve("rel-install/uninstall.sh"), isWindows)),
                Map.of("HOME", toBashPath(fakeHome, isWindows))
            );
            Assert.assertEquals(0, uninstallRes.exitCode(), "uninstall.sh should exit 0; stderr=" + uninstallRes.stderr());
            Assert.assertContains(uninstallRes.stdout(), "rm -f", "uninstall.sh must still match shim after relative install");
        } finally {
            deleteRecursively(sandbox);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String resolveBashExecutable() {
        if (isWindows()) {
            List<Path> candidates = List.of(
                Path.of("C:/Program Files/Git/bin/bash.exe"),
                Path.of("C:/Program Files/Git/usr/bin/bash.exe"),
                Path.of("C:/Program Files (x86)/Git/bin/bash.exe")
            );
            for (Path candidate : candidates) {
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return "bash";
    }

    private static String toBashPath(Path path, boolean isWindows) {
        String normalized = path.toAbsolutePath().normalize().toString();
        if (!isWindows) {
            return normalized;
        }
        String forward = normalized.replace('\\', '/');
        if (forward.length() >= 2 && forward.charAt(1) == ':') {
            char drive = Character.toLowerCase(forward.charAt(0));
            return "/" + drive + forward.substring(2);
        }
        return forward;
    }

    private static Optional<Path> findBackupDir(Path parent, String prefix) throws IOException {
        try (Stream<Path> entries = Files.list(parent)) {
            return entries
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .findFirst();
        }
    }

    private static ProcessResult runBash(String bash, List<String> scriptAndArgs, Map<String, String> extraEnv)
        throws IOException, InterruptedException {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add(bash);
        cmd.addAll(scriptAndArgs);
        return runProcess(cmd, extraEnv);
    }

    private static ProcessResult runProcess(List<String> cmd, Map<String, String> extraEnv)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(extraEnv);
        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = p.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new AssertionError("Command timed out: " + cmd);
        }
        return new ProcessResult(p.exitValue(), stdout, stderr);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}

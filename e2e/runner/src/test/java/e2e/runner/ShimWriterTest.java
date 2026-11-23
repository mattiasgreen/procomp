package e2e.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShimWriterTest {

    @Test
    void writesWindowsCmdAndPosixShimsPreWiredWithConfigAndStateDir(@TempDir Path tmpDir) throws Exception {
        Path sandboxDir = tmpDir.resolve("sandbox");
        Path repoRoot = tmpDir.resolve("repo");
        Path configFile = sandboxDir.resolve("procomp.json");
        Path stateDir = sandboxDir.resolve("state");
        String javaExe = "C:\\Program Files\\Java\\jdk-25\\bin\\java.exe";

        ShimWriter.ShimPaths paths = ShimWriter.writeShims(sandboxDir, repoRoot, configFile, stateDir, javaExe);

        assertTrue(Files.isRegularFile(paths.windowsCmd()), "p.cmd must be created");
        assertTrue(Files.isRegularFile(paths.posixSh()), "p must be created");

        String cmdContent = Files.readString(paths.windowsCmd(), StandardCharsets.UTF_8);
        assertTrue(cmdContent.contains("PROCOMP_STATE_DIR=" + stateDir), "p.cmd must set PROCOMP_STATE_DIR: " + cmdContent);
        assertTrue(cmdContent.contains(javaExe), "p.cmd must invoke javaExe: " + cmdContent);
        assertTrue(cmdContent.contains("Procomp.java"), "p.cmd must invoke Procomp.java: " + cmdContent);
        assertTrue(cmdContent.contains("--config"), "p.cmd must pass --config: " + cmdContent);
        assertTrue(cmdContent.contains(configFile.toString()), "p.cmd must pass configFile: " + cmdContent);
        assertTrue(cmdContent.contains("%*"), "p.cmd must forward CLI args: " + cmdContent);

        String shContent = Files.readString(paths.posixSh(), StandardCharsets.UTF_8);
        assertTrue(shContent.startsWith("#!/usr/bin/env sh\n"), "POSIX shim must start with shebang and LF");
        assertFalse(shContent.contains("\r"), "POSIX shim must use LF line endings only");
        assertTrue(shContent.contains("PROCOMP_STATE_DIR="), "POSIX shim must export PROCOMP_STATE_DIR: " + shContent);
        assertTrue(shContent.contains("--config"), "POSIX shim must pass --config: " + shContent);
        assertTrue(shContent.contains("\"$@\""), "POSIX shim must forward CLI args: " + shContent);
    }
}

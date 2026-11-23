package e2e.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

/**
 * Generates local {@code p.cmd} (Windows) and {@code p} (POSIX) CLI wrapper shims inside
 * a {@link RealTopologyWorld} sandbox directory, pre-wired with {@code --config} and
 * {@code PROCOMP_STATE_DIR}.
 */
public final class ShimWriter {

    public record ShimPaths(Path windowsCmd, Path posixSh) {
        public ShimPaths {
            Objects.requireNonNull(windowsCmd, "windowsCmd must not be null");
            Objects.requireNonNull(posixSh, "posixSh must not be null");
        }
    }

    private ShimWriter() {}

    public static ShimPaths writeShims(
        Path sandboxDir,
        Path repoRoot,
        Path configFile,
        Path stateDir,
        String javaExe
    ) throws IOException {
        Objects.requireNonNull(sandboxDir, "sandboxDir must not be null");
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(configFile, "configFile must not be null");
        Objects.requireNonNull(stateDir, "stateDir must not be null");
        if (javaExe == null || javaExe.isBlank()) {
            throw new IllegalArgumentException("javaExe must be non-blank");
        }

        Files.createDirectories(sandboxDir);
        Path procompEntrypoint = repoRoot.toAbsolutePath().normalize()
            .resolve("src/procomp/cli/Procomp.java");
        Path absConfig = configFile.toAbsolutePath().normalize();
        Path absStateDir = stateDir.toAbsolutePath().normalize();

        Path cmdFile = sandboxDir.resolve("p.cmd");
        String cmdScript = "@echo off\r\n"
            + "setlocal\r\n"
            + "set \"PROCOMP_STATE_DIR=" + absStateDir + "\"\r\n"
            + "\"" + javaExe + "\" \"" + procompEntrypoint + "\" --config \"" + absConfig + "\" %*\r\n"
            + "set \"EXIT_CODE=%ERRORLEVEL%\"\r\n"
            + "endlocal & exit /b %EXIT_CODE%\r\n";
        Files.writeString(cmdFile, cmdScript, StandardCharsets.UTF_8);

        Path shFile = sandboxDir.resolve("p");
        String shScript = "#!/usr/bin/env sh\n"
            + "set -eu\n"
            + "export PROCOMP_STATE_DIR=" + singleQuotePosix(absStateDir.toString()) + "\n"
            + "exec " + singleQuotePosix(javaExe)
            + " " + singleQuotePosix(procompEntrypoint.toString())
            + " --config " + singleQuotePosix(absConfig.toString())
            + " \"$@\"\n";
        Files.writeString(shFile, shScript, StandardCharsets.UTF_8);
        trySetExecutable(shFile);

        return new ShimPaths(cmdFile, shFile);
    }

    private static String singleQuotePosix(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void trySetExecutable(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException | IOException ignored) {
            file.toFile().setExecutable(true, false);
        }
    }
}

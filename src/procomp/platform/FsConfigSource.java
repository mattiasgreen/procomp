package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.ConfigSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Real filesystem and host environment adapter for {@link ConfigSource}.
 */
public final class FsConfigSource implements ConfigSource {

    @Override
    public Path currentWorkingDirectory() {
        return Path.of("").toAbsolutePath().normalize();
    }

    @Override
    public Optional<String> envVar(String name) {
        return Optional.ofNullable(System.getenv(name));
    }

    @Override
    public Map<String, String> allEnvVars() {
        return System.getenv();
    }

    @Override
    public boolean isRegularFile(Path path) {
        return Files.isRegularFile(path);
    }

    @Override
    public Result<String, DomainError> readFile(Path path) {
        try {
            return Result.ok(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "IO_READ_ERROR",
                "Failed to read file '" + path + "': " + e.getMessage(),
                Map.of("path", path.toString())
            ));
        }
    }
}

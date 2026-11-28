package procomp.platform;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;
import procomp.shell.ports.ConfigSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Real filesystem and host environment adapter for {@link ConfigSource}.
 */
public final class FsConfigSource implements ConfigSource {

    private static final int WINDOWS_MOVE_MAX_ATTEMPTS = 5;

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
    public Optional<String> currentUser() {
        String user = System.getProperty("user.name");
        return (user != null && !user.isBlank()) ? Optional.of(user) : Optional.empty();
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

    @Override
    public Result<Unit, DomainError> writeFile(Path path, String content) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IOException("Target path has no parent directory: " + normalized);
            }
            Files.createDirectories(parent);
            Path fileName = normalized.getFileName();
            String prefix = fileName != null ? fileName.toString() : "procomp-config";
            Path tmp = Files.createTempFile(parent, prefix, ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                moveWithRetry(tmp, normalized);
                return Result.ok(Unit.VALUE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            return Result.err(DomainError.of(
                "IO_WRITE_ERROR",
                "Failed to write file '" + path + "': " + e.getMessage(),
                Map.of("path", path.toString())
            ));
        }
    }

    private static void moveWithRetry(Path tmp, Path target) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        IOException lastFailure = null;
        long backoffMs = 15L;
        for (int attempt = 1; attempt <= WINDOWS_MOVE_MAX_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                lastFailure = e;
                if (!windows || attempt == WINDOWS_MOVE_MAX_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoffMs *= 2;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
    }
}

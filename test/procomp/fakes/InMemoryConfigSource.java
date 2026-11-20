package procomp.fakes;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.shell.ports.ConfigSource;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryConfigSource implements ConfigSource {

    private final Path cwd;
    private final Map<Path, String> files = new LinkedHashMap<>();
    private final Map<String, String> envVars = new LinkedHashMap<>();

    public InMemoryConfigSource(Path cwd) {
        this.cwd = cwd.toAbsolutePath().normalize();
    }

    public InMemoryConfigSource withFile(Path path, String content) {
        files.put(cwd.resolve(path).normalize(), content);
        return this;
    }

    public InMemoryConfigSource withEnv(String name, String value) {
        envVars.put(name, value);
        return this;
    }

    @Override
    public Path currentWorkingDirectory() {
        return cwd;
    }

    @Override
    public Optional<String> envVar(String name) {
        return Optional.ofNullable(envVars.get(name));
    }

    @Override
    public Map<String, String> allEnvVars() {
        return Collections.unmodifiableMap(envVars);
    }

    @Override
    public boolean isRegularFile(Path path) {
        return files.containsKey(cwd.resolve(path).normalize());
    }

    @Override
    public Result<String, DomainError> readFile(Path path) {
        Path normalized = cwd.resolve(path).normalize();
        String content = files.get(normalized);
        if (content == null) {
            return Result.err(DomainError.of("IO_READ_ERROR", "File not found: " + normalized, Map.of("path", normalized.toString())));
        }
        return Result.ok(content);
    }
}

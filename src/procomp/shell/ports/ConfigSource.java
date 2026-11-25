package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.common.Unit;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Port for discovering, reading, and atomically updating project config files alongside
 * catalog, env files, and host environment.
 */
public interface ConfigSource {

    Path currentWorkingDirectory();

    Optional<String> envVar(String name);

    Map<String, String> allEnvVars();

    default Optional<String> currentUser() {
        return Optional.empty();
    }

    boolean isRegularFile(Path path);

    Result<String, DomainError> readFile(Path path);

    default Result<Unit, DomainError> writeFile(Path path, String content) {
        return Result.err(DomainError.of(
            "IO_WRITE_UNSUPPORTED",
            "ConfigSource does not support writing '" + path + "'",
            Map.of("path", path.toString())
        ));
    }
}

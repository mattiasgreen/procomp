package procomp.shell.ports;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only port for discovering and reading project config, catalog, env files, and host environment.
 */
public interface ConfigSource {

    Path currentWorkingDirectory();

    Optional<String> envVar(String name);

    Map<String, String> allEnvVars();

    boolean isRegularFile(Path path);

    Result<String, DomainError> readFile(Path path);
}

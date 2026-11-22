package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.shell.ports.ConfigSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Use-case orchestrator that discovers procomp.json and delegates parsing, Catalog/envFile loading,
 * validation, and resolution to {@link ProjectResolver}.
 */
public final class ProjectLoader {

    private static final String CONFIG_FILENAME = "procomp.json";

    public record LoadedProject(Path configPath, Project project) {
        public LoadedProject {
            Objects.requireNonNull(configPath, "configPath must not be null");
            Objects.requireNonNull(project, "project must not be null");
        }
    }

    private final ConfigSource configSource;

    public ProjectLoader(ConfigSource configSource) {
        this.configSource = configSource;
    }

    public Result<Project, DomainError> load(Optional<Path> configOverride) {
        return loadWithPath(configOverride).map(LoadedProject::project);
    }

    public Result<LoadedProject, DomainError> loadWithPath(Optional<Path> configOverride) {
        Result<Path, DomainError> pathRes = discoverConfigPath(configOverride);
        if (pathRes.isErr()) {
            return pathRes.castError();
        }
        Path configPath = okVal(pathRes);

        Result<String, DomainError> rawJsonRes = configSource.readFile(configPath);
        if (rawJsonRes.isErr()) {
            return rawJsonRes.castError();
        }

        return ProjectResolver.resolve(
            configPath,
            okVal(rawJsonRes),
            this::readOptionalFile,
            configSource.allEnvVars()
        ).map(project -> new LoadedProject(configPath, project));
    }

    private Result<Optional<String>, DomainError> readOptionalFile(Path path) {
        if (!configSource.isRegularFile(path)) {
            return Result.ok(Optional.empty());
        }
        return configSource.readFile(path).map(Optional::of);
    }

    private Result<Path, DomainError> discoverConfigPath(Optional<Path> configOverride) {
        Path cwd = configSource.currentWorkingDirectory().normalize();

        if (configOverride.isPresent()) {
            Path resolved = cwd.resolve(configOverride.get()).normalize();
            if (!configSource.isRegularFile(resolved)) {
                return Result.err(DomainError.of(
                    "CONFIG_NOT_FOUND",
                    "Config file not found at explicit override path '" + resolved + "'",
                    Map.of("path", resolved.toString())
                ));
            }
            return Result.ok(resolved);
        }

        Optional<String> envConfig = configSource.envVar("PROCOMP_CONFIG").filter(s -> !s.isBlank());
        if (envConfig.isPresent()) {
            Path resolved = cwd.resolve(envConfig.get()).normalize();
            if (!configSource.isRegularFile(resolved)) {
                return Result.err(DomainError.of(
                    "CONFIG_NOT_FOUND",
                    "Config file not found at PROCOMP_CONFIG path '" + resolved + "'",
                    Map.of("path", resolved.toString())
                ));
            }
            return Result.ok(resolved);
        }

        Path cursor = cwd;
        while (cursor != null) {
            Path candidate = cursor.resolve(CONFIG_FILENAME).normalize();
            if (configSource.isRegularFile(candidate)) {
                return Result.ok(candidate);
            }
            cursor = cursor.getParent();
        }

        return Result.err(DomainError.of(
            "CONFIG_NOT_FOUND",
            "No " + CONFIG_FILENAME + " found in '" + cwd + "' or any parent directory",
            Map.of("startDir", cwd.toString())
        ));
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}

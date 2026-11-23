package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.Project;
import procomp.core.project.ProjectResolver;
import procomp.shell.ports.ConfigSource;
import procomp.shell.ports.ProgressObserver;
import procomp.shell.ports.ProgressObserver.DiagnosticCategory;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Use-case orchestrator that discovers procomp.json and delegates parsing, Catalog/envFile loading,
 * validation, and resolution to {@link ProjectResolver}.
 */
public final class ProjectLoader {

    private static final String CONFIG_FILENAME = "procomp.json";

    public record LoadedProject(Path configPath, Project project, List<Path> watchedPaths) {
        public LoadedProject {
            Objects.requireNonNull(configPath, "configPath must not be null");
            Objects.requireNonNull(project, "project must not be null");
            watchedPaths = List.copyOf(watchedPaths);
        }

        public LoadedProject(Path configPath, Project project) {
            this(configPath, project, List.of(configPath));
        }
    }

    private final ConfigSource configSource;
    private final ProgressObserver observer;

    public ProjectLoader(ConfigSource configSource) {
        this(configSource, ProgressObserver.noop());
    }

    public ProjectLoader(ConfigSource configSource, ProgressObserver observer) {
        this.configSource = Objects.requireNonNull(configSource, "configSource must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    public Result<Project, DomainError> load(Optional<Path> configOverride) {
        return loadWithPath(configOverride).map(LoadedProject::project);
    }

    public Result<LoadedProject, DomainError> loadWithPath(Optional<Path> configOverride) {
        Objects.requireNonNull(configOverride, "configOverride must not be null");
        Result<Path, DomainError> pathRes = discoverConfigPath(configOverride);
        if (pathRes.isErr()) {
            return pathRes.castError();
        }
        Path configPath = okVal(pathRes);
        observer.diagnostic(DiagnosticCategory.CONFIG, "Loading procomp.json: " + configPath);

        Result<String, DomainError> rawJsonRes = configSource.readFile(configPath);
        if (rawJsonRes.isErr()) {
            return rawJsonRes.castError();
        }

        Set<Path> watched = new LinkedHashSet<>();
        watched.add(configPath);
        return ProjectResolver.resolve(
            configPath,
            okVal(rawJsonRes),
            path -> {
                watched.add(path.normalize());
                return readOptionalFile(path);
            },
            configSource.allEnvVars()
        ).map(project -> {
            observer.diagnostic(
                DiagnosticCategory.CONFIG,
                "Resolved Project '" + project.name() + "' (" + project.services().size()
                    + " service(s), gateway=" + (project.gateway().isPresent() ? "configured" : "none") + ")"
            );
            return new LoadedProject(configPath, project, new ArrayList<>(watched));
        });
    }

    private Result<Optional<String>, DomainError> readOptionalFile(Path path) {
        if (!configSource.isRegularFile(path)) {
            return Result.ok(Optional.empty());
        }
        observer.diagnostic(DiagnosticCategory.CONFIG, "Reading referenced file: " + path);
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
            Path resolved;
            try {
                resolved = cwd.resolve(envConfig.get()).normalize();
            } catch (InvalidPathException e) {
                return Result.err(DomainError.of(
                    "CONFIG_INVALID_VALUE",
                    "Invalid PROCOMP_CONFIG path '" + envConfig.get() + "': " + e.getMessage(),
                    Map.of("field", "PROCOMP_CONFIG", "value", envConfig.get())
                ));
            }
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

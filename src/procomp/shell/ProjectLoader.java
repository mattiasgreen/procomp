package procomp.shell;

import procomp.core.common.DomainError;
import procomp.core.common.Result;
import procomp.core.project.CatalogParser;
import procomp.core.project.Project;
import procomp.core.project.ProjectConfigParser;
import procomp.core.project.ProjectResolver;
import procomp.core.project.RawProjectConfig;
import procomp.shell.ports.ConfigSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Use-case orchestrator that discovers procomp.json, loads external Catalog and envFiles via ConfigSource,
 * and delegates validation and resolution to pure core functions.
 */
public final class ProjectLoader {

    private static final String CONFIG_FILENAME = "procomp.json";

    private final ConfigSource configSource;

    public ProjectLoader(ConfigSource configSource) {
        this.configSource = configSource;
    }

    public Result<Project, DomainError> load(Optional<Path> configOverride) {
        Result<Path, DomainError> pathRes = discoverConfigPath(configOverride);
        if (pathRes.isErr()) {
            return castErr(pathRes);
        }
        Path configPath = okVal(pathRes);

        Result<String, DomainError> rawJsonRes = configSource.readFile(configPath);
        if (rawJsonRes.isErr()) {
            return castErr(rawJsonRes);
        }

        Result<RawProjectConfig, DomainError> rawCfgRes = ProjectConfigParser.parse(okVal(rawJsonRes));
        if (rawCfgRes.isErr()) {
            return castErr(rawCfgRes);
        }
        RawProjectConfig rawConfig = okVal(rawCfgRes);

        Path configDir = configPath.getParent();
        Map<String, RawProjectConfig.RawServiceConfig> catalogServices = Map.of();
        if (rawConfig.catalog().isPresent()) {
            Result<Optional<Path>, DomainError> catPathRes = ProjectResolver.resolveCatalogPath(configDir, rawConfig);
            if (catPathRes.isErr()) {
                return castErr(catPathRes);
            }
            Path catalogPath = okVal(catPathRes).orElseThrow();
            if (!configSource.isRegularFile(catalogPath)) {
                return Result.err(DomainError.of(
                    "CATALOG_NOT_FOUND",
                    "Catalog file not found at '" + catalogPath + "'",
                    Map.of("path", catalogPath.toString())
                ));
            }
            Result<String, DomainError> catJsonRes = configSource.readFile(catalogPath);
            if (catJsonRes.isErr()) {
                return castErr(catJsonRes);
            }
            Result<Map<String, RawProjectConfig.RawServiceConfig>, DomainError> parsedCatRes =
                CatalogParser.parse(rawConfig.catalog().get(), okVal(catJsonRes));
            if (parsedCatRes.isErr()) {
                return castErr(parsedCatRes);
            }
            catalogServices = okVal(parsedCatRes);
        }

        Result<ProjectResolver.ResolutionPlan, DomainError> planRes =
            ProjectResolver.planResolution(configPath, rawConfig, catalogServices);
        if (planRes.isErr()) {
            return castErr(planRes);
        }
        ProjectResolver.ResolutionPlan plan = okVal(planRes);

        Map<Path, String> loadedEnvFiles = new LinkedHashMap<>();
        for (ProjectResolver.EnvFileTarget target : plan.allEnvFileTargets()) {
            if (loadedEnvFiles.containsKey(target.path())) {
                continue;
            }
            if (!configSource.isRegularFile(target.path())) {
                if (!target.optional()) {
                    return Result.err(DomainError.of(
                        "CONFIG_MISSING_ENV_FILE",
                        "Required envFile '" + target.path() + "' not found (" + target.context() + ")",
                        Map.of("path", target.path().toString(), "context", target.context())
                    ));
                }
                continue;
            }
            Result<String, DomainError> envContentRes = configSource.readFile(target.path());
            if (envContentRes.isErr()) {
                return castErr(envContentRes);
            }
            loadedEnvFiles.put(target.path(), okVal(envContentRes));
        }

        return ProjectResolver.finalizeProject(plan, loadedEnvFiles, configSource.allEnvVars());
    }

    private Result<Path, DomainError> discoverConfigPath(Optional<Path> configOverride) {
        Path cwd = configSource.currentWorkingDirectory().normalize();

        if (configOverride.isPresent()) {
            Path resolved = cwd.resolve(configOverride.get()).normalize();
            if (!configSource.isRegularFile(resolved)) {
                return Result.err(DomainError.of(
                    "CONFIG_NOT_FOUND",
                    "Config file not found at --config path '" + resolved + "'",
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

    private static <T, U> Result<U, DomainError> castErr(Result<T, DomainError> err) {
        return err.fold(
            ignored -> { throw new IllegalStateException("Expected Err result"); },
            Result::err
        );
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}

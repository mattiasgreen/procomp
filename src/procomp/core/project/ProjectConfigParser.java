package procomp.core.project;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Pure, strict parser and validator for procomp.json.
 */
final class ProjectConfigParser {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
        "project", "repositoriesRoot", "envFiles", "defaults",
        "builders", "runtimes", "gateway", "catalog", "services", "profiles", "git"
    );
    private static final Set<String> GIT_KEYS = Set.of("hooks", "extraRepositories");
    private static final Set<String> GIT_HOOKS_KEYS = Set.of("sourceDir", "required", "files", "mode");
    private static final Set<String> ENV_FILE_KEYS = Set.of("path", "optional");
    private static final Set<String> DEFAULTS_KEYS = Set.of(
        "builder", "proxyPathTemplate", "remoteUrlTemplate", "workingDir", "vars", "env"
    );
    private static final Set<String> BUILDER_KEYS = Set.of(
        "command", "workingDir", "env"
    );
    private static final Set<String> RUNTIME_KEYS = Set.of(
        "command", "healthPath", "artifactGlob", "artifactExclude", "workingDir", "env"
    );
    private static final Set<String> GATEWAY_KEYS = Set.of(
        "type", "port", "healthPath", "command", "workingDir", "env", "proxyHeaders", "fallbackRemote"
    );
    private static final Set<String> FALLBACK_REMOTE_KEYS = Set.of(
        "targetUrl", "stripPrefix"
    );
    private static final Set<String> CATALOG_KEYS = Set.of("path", "itemsPath", "map");
    private static final Set<String> CATALOG_MAP_SCALAR_TARGETS = Set.of(
        "name", "port", "runtime", "builder", "repository", "repositoryUrl",
        "workingDir", "healthPath", "proxyPath", "remoteUrl"
    );
    private static final Set<String> SERVICE_KEYS = Set.of(
        "port", "runtime", "builder", "repository", "repositoryUrl", "workingDir",
        "healthPath", "proxyPath", "remoteUrl", "vars", "env", "envFiles"
    );
    private static final Set<String> PROFILE_KEYS = Set.of("local");

    private ProjectConfigParser() {}

    static Result<RawProjectConfig, DomainError> parse(String jsonText) {
        return Json.parse(jsonText).flatMap(ProjectConfigParser::parseRoot);
    }

    private static Result<RawProjectConfig, DomainError> parseRoot(JsonValue rootValue) {
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(rootValue, "root");
        if (objRes.isErr()) {
            return objRes.castError();
        }
        JsonValue.JsonObject root = okVal(objRes);

        Result<procomp.core.common.Unit, DomainError> unknownCheck = rejectUnknownKeys(root, TOP_LEVEL_KEYS, "procomp.json");
        if (unknownCheck.isErr()) {
            return unknownCheck.castError();
        }

        Result<String, DomainError> projectRes = requireNonBlankString(root, "project", "procomp.json")
            .flatMap(ProjectConfigParser::validateProjectIdentifier);
        if (projectRes.isErr()) {
            return projectRes.castError();
        }

        Result<Optional<String>, DomainError> reposRootRes = optionalNonBlankString(root, "repositoriesRoot", "procomp.json");
        if (reposRootRes.isErr()) {
            return reposRootRes.castError();
        }

        Result<List<EnvFileSpec>, DomainError> envFilesRes = parseOptionalEnvFiles(root, "envFiles", "procomp.json");
        if (envFilesRes.isErr()) {
            return envFilesRes.castError();
        }

        Result<RawProjectConfig.RawDefaultsConfig, DomainError> defaultsRes = parseOptionalDefaults(root);
        if (defaultsRes.isErr()) {
            return defaultsRes.castError();
        }

        Result<Map<String, RawProjectConfig.RawBuilderConfig>, DomainError> buildersRes = parseOptionalBuilders(root);
        if (buildersRes.isErr()) {
            return buildersRes.castError();
        }

        Result<Map<String, RawProjectConfig.RawRuntimeConfig>, DomainError> runtimesRes = parseRuntimes(root);
        if (runtimesRes.isErr()) {
            return runtimesRes.castError();
        }

        Result<Optional<RawProjectConfig.RawGatewayConfig>, DomainError> gatewayRes = parseOptionalGateway(root);
        if (gatewayRes.isErr()) {
            return gatewayRes.castError();
        }

        Result<Optional<CatalogMapping>, DomainError> catalogRes = parseOptionalCatalog(root);
        if (catalogRes.isErr()) {
            return catalogRes.castError();
        }

        Result<Map<String, RawProjectConfig.RawServiceConfig>, DomainError> servicesRes = parseOptionalServices(root);
        if (servicesRes.isErr()) {
            return servicesRes.castError();
        }

        Result<Map<String, ProfileDefinition>, DomainError> profilesRes = parseOptionalProfiles(root);
        if (profilesRes.isErr()) {
            return profilesRes.castError();
        }

        Result<Optional<RawProjectConfig.RawGitConfig>, DomainError> gitRes = parseOptionalGit(root);
        if (gitRes.isErr()) {
            return gitRes.castError();
        }

        return Result.ok(new RawProjectConfig(
            okVal(projectRes),
            okVal(reposRootRes),
            okVal(envFilesRes),
            okVal(defaultsRes),
            okVal(buildersRes),
            okVal(runtimesRes),
            okVal(gatewayRes),
            okVal(catalogRes),
            okVal(servicesRes),
            okVal(profilesRes),
            okVal(gitRes)
        ));
    }

    static boolean isPathSafeIdentifier(String value) {
        return value != null
            && !value.isBlank()
            && !value.equals(".")
            && !value.equals("..")
            && !value.contains("/")
            && !value.contains("\\")
            && !value.contains("\0");
    }

    private static Result<String, DomainError> validateProjectIdentifier(String project) {
        if (!isPathSafeIdentifier(project)) {
            return Result.err(DomainError.of(
                "CONFIG_INVALID_VALUE",
                "Invalid project identifier '" + project + "': must be a single path-safe segment",
                Map.of("field", "project", "value", project)
            ));
        }
        return Result.ok(project);
    }

    private static Result<RawProjectConfig.RawDefaultsConfig, DomainError> parseOptionalDefaults(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("defaults");
        if (maybe.isEmpty()) {
            return Result.ok(RawProjectConfig.RawDefaultsConfig.EMPTY);
        }
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(maybe.get(), "defaults");
        if (objRes.isErr()) {
            return objRes.castError();
        }
        JsonValue.JsonObject obj = okVal(objRes);
        Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, DEFAULTS_KEYS, "defaults");
        if (unknown.isErr()) {
            return unknown.castError();
        }

        Result<Optional<String>, DomainError> builder = optionalNonBlankString(obj, "builder", "defaults");
        if (builder.isErr()) return builder.castError();
        Result<Optional<String>, DomainError> proxyPath = optionalNonBlankString(obj, "proxyPathTemplate", "defaults");
        if (proxyPath.isErr()) return proxyPath.castError();
        Result<Optional<String>, DomainError> remoteUrl = optionalNonBlankString(obj, "remoteUrlTemplate", "defaults");
        if (remoteUrl.isErr()) return remoteUrl.castError();
        Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(obj, "workingDir", "defaults");
        if (workingDir.isErr()) return workingDir.castError();
        Result<Map<String, VarValue>, DomainError> vars = parseOptionalVars(obj, "defaults.vars");
        if (vars.isErr()) return vars.castError();
        Result<Map<String, String>, DomainError> env = parseOptionalStringMap(obj, "env", "defaults.env");
        if (env.isErr()) return env.castError();

        return Result.ok(new RawProjectConfig.RawDefaultsConfig(
            okVal(builder), okVal(proxyPath), okVal(remoteUrl), okVal(workingDir), okVal(vars), okVal(env)
        ));
    }

    private static Result<Map<String, RawProjectConfig.RawBuilderConfig>, DomainError> parseOptionalBuilders(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("builders");
        if (maybe.isEmpty()) {
            return Result.ok(Map.of());
        }
        Result<JsonValue.JsonObject, DomainError> mapObjRes = asObject(maybe.get(), "builders");
        if (mapObjRes.isErr()) {
            return mapObjRes.castError();
        }
        Map<String, RawProjectConfig.RawBuilderConfig> builders = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(mapObjRes).fields().entrySet()) {
            String builderName = entry.getKey();
            if (builderName.isBlank()) {
                return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "Builder name must not be blank", Map.of("field", "builders")));
            }
            String path = "builders." + builderName;
            Result<JsonValue.JsonObject, DomainError> bObjRes = asObject(entry.getValue(), path);
            if (bObjRes.isErr()) return bObjRes.castError();
            JsonValue.JsonObject bObj = okVal(bObjRes);

            Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(bObj, BUILDER_KEYS, path);
            if (unknown.isErr()) return unknown.castError();

            Result<List<String>, DomainError> cmdRes = requireNonEmptyStringArray(bObj, "command", path);
            if (cmdRes.isErr()) return cmdRes.castError();
            Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(bObj, "workingDir", path);
            if (workingDir.isErr()) return workingDir.castError();
            Result<Map<String, String>, DomainError> env = parseOptionalStringMap(bObj, "env", path + ".env");
            if (env.isErr()) return env.castError();

            builders.put(builderName, new RawProjectConfig.RawBuilderConfig(
                okVal(cmdRes), okVal(workingDir), okVal(env)
            ));
        }
        return Result.ok(builders);
    }

    private static Result<Map<String, RawProjectConfig.RawRuntimeConfig>, DomainError> parseRuntimes(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("runtimes");
        if (maybe.isEmpty()) {
            return Result.err(DomainError.of(
                "CONFIG_MISSING_FIELD",
                "Missing required field 'runtimes'",
                Map.of("field", "runtimes")
            ));
        }
        Result<JsonValue.JsonObject, DomainError> mapObjRes = asObject(maybe.get(), "runtimes");
        if (mapObjRes.isErr()) {
            return mapObjRes.castError();
        }
        Map<String, RawProjectConfig.RawRuntimeConfig> runtimes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(mapObjRes).fields().entrySet()) {
            String runtimeName = entry.getKey();
            if (runtimeName.isBlank()) {
                return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "Runtime name must not be blank", Map.of("field", "runtimes")));
            }
            String path = "runtimes." + runtimeName;
            Result<JsonValue.JsonObject, DomainError> rtObjRes = asObject(entry.getValue(), path);
            if (rtObjRes.isErr()) return rtObjRes.castError();
            JsonValue.JsonObject rtObj = okVal(rtObjRes);

            Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(rtObj, RUNTIME_KEYS, path);
            if (unknown.isErr()) return unknown.castError();

            Result<List<String>, DomainError> cmdRes = requireNonEmptyStringArray(rtObj, "command", path);
            if (cmdRes.isErr()) return cmdRes.castError();
            Result<Optional<String>, DomainError> healthPath = optionalString(rtObj, "healthPath", path);
            if (healthPath.isErr()) return healthPath.castError();
            Result<Optional<String>, DomainError> artifactGlob = optionalNonBlankString(rtObj, "artifactGlob", path);
            if (artifactGlob.isErr()) return artifactGlob.castError();
            Result<Optional<String>, DomainError> artifactExclude = optionalNonBlankString(rtObj, "artifactExclude", path);
            if (artifactExclude.isErr()) return artifactExclude.castError();
            Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(rtObj, "workingDir", path);
            if (workingDir.isErr()) return workingDir.castError();
            Result<Map<String, String>, DomainError> env = parseOptionalStringMap(rtObj, "env", path + ".env");
            if (env.isErr()) return env.castError();

            runtimes.put(runtimeName, new RawProjectConfig.RawRuntimeConfig(
                okVal(cmdRes), okVal(healthPath), okVal(artifactGlob), okVal(artifactExclude), okVal(workingDir), okVal(env)
            ));
        }
        return Result.ok(runtimes);
    }

    private static Result<Optional<RawProjectConfig.RawGatewayConfig>, DomainError> parseOptionalGateway(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("gateway");
        if (maybe.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(maybe.get(), "gateway");
        if (objRes.isErr()) return objRes.castError();
        JsonValue.JsonObject obj = okVal(objRes);

        Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, GATEWAY_KEYS, "gateway");
        if (unknown.isErr()) return unknown.castError();

        Result<String, DomainError> typeRes = requireNonBlankString(obj, "type", "gateway");
        if (typeRes.isErr()) return typeRes.castError();
        String type = okVal(typeRes);
        if (!"wiremock".equals(type)) {
            return Result.err(DomainError.of(
                "CONFIG_INVALID_VALUE",
                "Unsupported gateway type '" + type + "' (expected 'wiremock')",
                Map.of("field", "gateway.type", "value", type)
            ));
        }

        Optional<JsonValue> portVal = obj.get("port");
        if (portVal.isEmpty()) {
            return Result.err(DomainError.of("CONFIG_MISSING_FIELD", "Missing required field 'gateway.port'", Map.of("field", "gateway.port")));
        }
        Result<Integer, DomainError> portRes = parsePort(portVal.get(), "gateway.port");
        if (portRes.isErr()) return portRes.castError();

        Result<Optional<String>, DomainError> healthPath = optionalString(obj, "healthPath", "gateway");
        if (healthPath.isErr()) return healthPath.castError();
        Result<List<String>, DomainError> command = parseOptionalNonEmptyStringArray(obj, "command", "gateway");
        if (command.isErr()) return command.castError();
        Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(obj, "workingDir", "gateway");
        if (workingDir.isErr()) return workingDir.castError();
        Result<Map<String, String>, DomainError> env = parseOptionalStringMap(obj, "env", "gateway.env");
        if (env.isErr()) return env.castError();
        Result<Map<String, String>, DomainError> proxyHeaders =
            parseOptionalStringMap(obj, "proxyHeaders", "gateway.proxyHeaders");
        if (proxyHeaders.isErr()) return proxyHeaders.castError();
        for (String headerName : okVal(proxyHeaders).keySet()) {
            if (headerName.isBlank()) {
                return Result.err(DomainError.of(
                    "CONFIG_INVALID_VALUE",
                    "Header name in 'gateway.proxyHeaders' must not be blank",
                    Map.of("field", "gateway.proxyHeaders")
                ));
            }
        }

        Optional<RawProjectConfig.RawFallbackRemoteConfig> fallbackRemote = Optional.empty();
        Optional<JsonValue> rawFallback = obj.get("fallbackRemote");
        if (rawFallback.isPresent()) {
            Result<JsonValue.JsonObject, DomainError> fbObjRes = asObject(rawFallback.get(), "gateway.fallbackRemote");
            if (fbObjRes.isErr()) return fbObjRes.castError();
            JsonValue.JsonObject fbObj = okVal(fbObjRes);
            Result<procomp.core.common.Unit, DomainError> fbUnknown =
                rejectUnknownKeys(fbObj, FALLBACK_REMOTE_KEYS, "gateway.fallbackRemote");
            if (fbUnknown.isErr()) return fbUnknown.castError();
            Result<String, DomainError> targetUrlRes = requireNonBlankString(fbObj, "targetUrl", "gateway.fallbackRemote");
            if (targetUrlRes.isErr()) return targetUrlRes.castError();
            Result<Optional<String>, DomainError> stripPrefixRes =
                optionalNonBlankString(fbObj, "stripPrefix", "gateway.fallbackRemote");
            if (stripPrefixRes.isErr()) return stripPrefixRes.castError();
            fallbackRemote = Optional.of(new RawProjectConfig.RawFallbackRemoteConfig(
                okVal(targetUrlRes), okVal(stripPrefixRes)
            ));
        }

        return Result.ok(Optional.of(new RawProjectConfig.RawGatewayConfig(
            type, okVal(portRes), okVal(healthPath), okVal(command), okVal(workingDir),
            okVal(env), okVal(proxyHeaders), fallbackRemote
        )));
    }

    private static Result<Optional<CatalogMapping>, DomainError> parseOptionalCatalog(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("catalog");
        if (maybe.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(maybe.get(), "catalog");
        if (objRes.isErr()) return objRes.castError();
        JsonValue.JsonObject obj = okVal(objRes);

        Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, CATALOG_KEYS, "catalog");
        if (unknown.isErr()) return unknown.castError();

        Result<String, DomainError> pathRes = requireNonBlankString(obj, "path", "catalog");
        if (pathRes.isErr()) return pathRes.castError();
        Result<Optional<String>, DomainError> itemsPathRes = optionalNonBlankString(obj, "itemsPath", "catalog");
        if (itemsPathRes.isErr()) return itemsPathRes.castError();

        Optional<JsonValue> mapVal = obj.get("map");
        if (mapVal.isEmpty()) {
            return Result.err(DomainError.of("CONFIG_MISSING_FIELD", "Missing required field 'catalog.map'", Map.of("field", "catalog.map")));
        }
        Result<JsonValue.JsonObject, DomainError> mapObjRes = asObject(mapVal.get(), "catalog.map");
        if (mapObjRes.isErr()) return mapObjRes.castError();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(mapObjRes).fields().entrySet()) {
            String target = entry.getKey();
            if (!isValidCatalogTarget(target)) {
                return Result.err(DomainError.of(
                    "CONFIG_UNRECOGNIZED_FIELD",
                    "Unrecognized catalog.map target field '" + target + "'",
                    Map.of("field", "catalog.map." + target)
                ));
            }
            if (!(entry.getValue() instanceof JsonValue.JsonString(String srcField)) || srcField.isBlank()) {
                return Result.err(DomainError.of(
                    "CONFIG_INVALID_TYPE",
                    "catalog.map." + target + " must be a non-blank string",
                    Map.of("field", "catalog.map." + target)
                ));
            }
            mapping.put(target, srcField);
        }
        if (!mapping.containsKey("name")) {
            return Result.err(DomainError.of(
                "CONFIG_MISSING_FIELD",
                "catalog.map must include a 'name' mapping",
                Map.of("field", "catalog.map.name")
            ));
        }

        return Result.ok(Optional.of(new CatalogMapping(okVal(pathRes), okVal(itemsPathRes), mapping)));
    }

    private static boolean isValidCatalogTarget(String target) {
        if (CATALOG_MAP_SCALAR_TARGETS.contains(target)) {
            return true;
        }
        return target.startsWith("vars.") && target.length() > "vars.".length() && !target.substring("vars.".length()).isBlank();
    }

    private static Result<Map<String, RawProjectConfig.RawServiceConfig>, DomainError> parseOptionalServices(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("services");
        if (maybe.isEmpty()) {
            return Result.ok(Map.of());
        }
        Result<JsonValue.JsonObject, DomainError> mapObjRes = asObject(maybe.get(), "services");
        if (mapObjRes.isErr()) return mapObjRes.castError();

        Map<String, RawProjectConfig.RawServiceConfig> services = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(mapObjRes).fields().entrySet()) {
            String svcName = entry.getKey();
            if (!isPathSafeIdentifier(svcName)) {
                return Result.err(DomainError.of(
                    "CONFIG_INVALID_VALUE",
                    "Invalid service name '" + svcName + "': must be a single path-safe segment",
                    Map.of("field", "services", "value", svcName)
                ));
            }
            String path = "services." + svcName;
            Result<JsonValue.JsonObject, DomainError> svcObjRes = asObject(entry.getValue(), path);
            if (svcObjRes.isErr()) return svcObjRes.castError();
            JsonValue.JsonObject svcObj = okVal(svcObjRes);

            Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(svcObj, SERVICE_KEYS, path);
            if (unknown.isErr()) return unknown.castError();

            Optional<Integer> port = Optional.empty();
            Optional<JsonValue> rawPort = svcObj.get("port");
            if (rawPort.isPresent()) {
                Result<Integer, DomainError> portRes = parsePort(rawPort.get(), path + ".port");
                if (portRes.isErr()) return portRes.castError();
                port = Optional.of(okVal(portRes));
            }

            Result<Optional<String>, DomainError> runtime = optionalNonBlankString(svcObj, "runtime", path);
            if (runtime.isErr()) return runtime.castError();
            Result<Optional<String>, DomainError> builder = optionalNonBlankString(svcObj, "builder", path);
            if (builder.isErr()) return builder.castError();
            Result<Optional<String>, DomainError> repository = optionalNonBlankString(svcObj, "repository", path);
            if (repository.isErr()) return repository.castError();
            Result<Optional<String>, DomainError> repositoryUrl = optionalNonBlankString(svcObj, "repositoryUrl", path);
            if (repositoryUrl.isErr()) return repositoryUrl.castError();
            Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(svcObj, "workingDir", path);
            if (workingDir.isErr()) return workingDir.castError();
            Result<Optional<String>, DomainError> healthPath = optionalString(svcObj, "healthPath", path);
            if (healthPath.isErr()) return healthPath.castError();
            Result<Optional<String>, DomainError> proxyPath = optionalNonBlankString(svcObj, "proxyPath", path);
            if (proxyPath.isErr()) return proxyPath.castError();
            Result<Optional<String>, DomainError> remoteUrl = optionalNonBlankString(svcObj, "remoteUrl", path);
            if (remoteUrl.isErr()) return remoteUrl.castError();
            Result<Map<String, VarValue>, DomainError> vars = parseOptionalVars(svcObj, path + ".vars");
            if (vars.isErr()) return vars.castError();
            Result<Map<String, String>, DomainError> env = parseOptionalStringMap(svcObj, "env", path + ".env");
            if (env.isErr()) return env.castError();
            Result<List<EnvFileSpec>, DomainError> envFiles = parseOptionalEnvFiles(svcObj, "envFiles", path);
            if (envFiles.isErr()) return envFiles.castError();

            services.put(svcName, new RawProjectConfig.RawServiceConfig(
                port, okVal(runtime), okVal(builder), okVal(repository), okVal(repositoryUrl), okVal(workingDir),
                okVal(healthPath), okVal(proxyPath), okVal(remoteUrl), okVal(vars), okVal(env), okVal(envFiles)
            ));
        }
        return Result.ok(services);
    }

    private static Result<Map<String, ProfileDefinition>, DomainError> parseOptionalProfiles(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("profiles");
        if (maybe.isEmpty()) {
            return Result.ok(Map.of());
        }
        Result<JsonValue.JsonObject, DomainError> mapObjRes = asObject(maybe.get(), "profiles");
        if (mapObjRes.isErr()) return mapObjRes.castError();

        Map<String, ProfileDefinition> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(mapObjRes).fields().entrySet()) {
            String profileName = entry.getKey();
            if (profileName.isBlank()) {
                return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "Profile name must not be blank", Map.of("field", "profiles")));
            }
            String path = "profiles." + profileName;
            Result<JsonValue.JsonObject, DomainError> profObjRes = asObject(entry.getValue(), path);
            if (profObjRes.isErr()) return profObjRes.castError();
            JsonValue.JsonObject profObj = okVal(profObjRes);

            Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(profObj, PROFILE_KEYS, path);
            if (unknown.isErr()) return unknown.castError();

            Optional<JsonValue> localVal = profObj.get("local");
            if (localVal.isEmpty()) {
                return Result.err(DomainError.of("CONFIG_MISSING_FIELD", "Missing required field '" + path + ".local'", Map.of("field", path + ".local")));
            }
            Result<List<String>, DomainError> localListRes = parseStringArray(localVal.get(), path + ".local", false);
            if (localListRes.isErr()) return localListRes.castError();

            profiles.put(profileName, new ProfileDefinition(profileName, okVal(localListRes)));
        }
        return Result.ok(profiles);
    }

    private static Result<Optional<RawProjectConfig.RawGitConfig>, DomainError> parseOptionalGit(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("git");
        if (maybe.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(maybe.get(), "git");
        if (objRes.isErr()) return objRes.castError();
        JsonValue.JsonObject obj = okVal(objRes);

        Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, GIT_KEYS, "git");
        if (unknown.isErr()) return unknown.castError();

        Optional<RawProjectConfig.RawGitHooksConfig> hooks = Optional.empty();
        Optional<JsonValue> hooksVal = obj.get("hooks");
        if (hooksVal.isPresent()) {
            Result<RawProjectConfig.RawGitHooksConfig, DomainError> hooksRes = parseGitHooks(hooksVal.get());
            if (hooksRes.isErr()) return hooksRes.castError();
            hooks = Optional.of(okVal(hooksRes));
        }

        List<String> extraRepos = List.of();
        Optional<JsonValue> extraVal = obj.get("extraRepositories");
        if (extraVal.isPresent()) {
            Result<List<String>, DomainError> listRes = parseStringArray(extraVal.get(), "git.extraRepositories", false);
            if (listRes.isErr()) return listRes.castError();
            List<String> rawList = okVal(listRes);
            for (int i = 0; i < rawList.size(); i++) {
                if (rawList.get(i).isBlank()) {
                    return Result.err(DomainError.of(
                        "CONFIG_INVALID_VALUE",
                        "'git.extraRepositories[" + i + "]' must not be blank",
                        Map.of("field", "git.extraRepositories[" + i + "]")
                    ));
                }
            }
            extraRepos = rawList;
        }

        return Result.ok(Optional.of(new RawProjectConfig.RawGitConfig(hooks, extraRepos)));
    }

    private static Result<RawProjectConfig.RawGitHooksConfig, DomainError> parseGitHooks(JsonValue value) {
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(value, "git.hooks");
        if (objRes.isErr()) return objRes.castError();
        JsonValue.JsonObject obj = okVal(objRes);

        Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, GIT_HOOKS_KEYS, "git.hooks");
        if (unknown.isErr()) return unknown.castError();

        Result<Optional<String>, DomainError> sourceDirRes = optionalNonBlankString(obj, "sourceDir", "git.hooks");
        if (sourceDirRes.isErr()) return sourceDirRes.castError();
        Optional<String> sourceDir = okVal(sourceDirRes);

        List<String> required = List.of();
        Optional<JsonValue> reqVal = obj.get("required");
        if (reqVal.isPresent()) {
            Result<List<String>, DomainError> reqRes = parseStringArray(reqVal.get(), "git.hooks.required", true);
            if (reqRes.isErr()) return reqRes.castError();
            List<String> reqList = okVal(reqRes);
            for (int i = 0; i < reqList.size(); i++) {
                String hookName = reqList.get(i);
                if (!isPathSafeIdentifier(hookName)) {
                    return Result.err(DomainError.of(
                        "CONFIG_INVALID_VALUE",
                        "Invalid git hook name '" + hookName + "': must be a single path-safe segment",
                        Map.of("field", "git.hooks.required[" + i + "]", "value", hookName)
                    ));
                }
            }
            required = reqList;
        }

        Result<Map<String, String>, DomainError> filesRes = parseOptionalStringMap(obj, "files", "git.hooks.files");
        if (filesRes.isErr()) return filesRes.castError();
        Map<String, String> files = okVal(filesRes);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String hookName = entry.getKey();
            if (!isPathSafeIdentifier(hookName)) {
                return Result.err(DomainError.of(
                    "CONFIG_INVALID_VALUE",
                    "Invalid git hook name '" + hookName + "': must be a single path-safe segment",
                    Map.of("field", "git.hooks.files." + hookName, "value", hookName)
                ));
            }
            if (entry.getValue().isBlank()) {
                return Result.err(DomainError.of(
                    "CONFIG_INVALID_VALUE",
                    "'git.hooks.files." + hookName + "' must not be blank",
                    Map.of("field", "git.hooks.files." + hookName)
                ));
            }
        }

        GitHookInstallMode mode = GitHookInstallMode.COPY;
        Result<Optional<String>, DomainError> modeRes = optionalNonBlankString(obj, "mode", "git.hooks");
        if (modeRes.isErr()) return modeRes.castError();
        if (okVal(modeRes).isPresent()) {
            String rawMode = okVal(modeRes).get();
            if ("copy".equals(rawMode)) {
                mode = GitHookInstallMode.COPY;
            } else if ("symlink".equals(rawMode)) {
                mode = GitHookInstallMode.SYMLINK;
            } else {
                return Result.err(DomainError.of(
                    "CONFIG_INVALID_VALUE",
                    "Unsupported git.hooks.mode '" + rawMode + "' (expected 'copy' or 'symlink')",
                    Map.of("field", "git.hooks.mode", "value", rawMode)
                ));
            }
        }

        if (sourceDir.isEmpty() && files.isEmpty()) {
            return Result.err(DomainError.of(
                "CONFIG_MISSING_FIELD",
                "git.hooks must configure at least 'sourceDir' or 'files'",
                Map.of("field", "git.hooks")
            ));
        }
        if (sourceDir.isEmpty() && !required.isEmpty()) {
            return Result.err(DomainError.of(
                "CONFIG_MISSING_FIELD",
                "git.hooks.required requires 'git.hooks.sourceDir' to be configured",
                Map.of("field", "git.hooks.sourceDir")
            ));
        }

        return Result.ok(new RawProjectConfig.RawGitHooksConfig(sourceDir, required, files, mode));
    }

    private static Result<List<EnvFileSpec>, DomainError> parseOptionalEnvFiles(JsonValue.JsonObject parent, String key, String context) {
        Optional<JsonValue> maybe = parent.get(key);
        if (maybe.isEmpty()) {
            return Result.ok(List.of());
        }
        String path = context + "." + key;
        if (!(maybe.get() instanceof JsonValue.JsonArray(List<JsonValue> elements))) {
            return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + path + "' must be an array", Map.of("field", path)));
        }
        List<EnvFileSpec> specs = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            String elemPath = path + "[" + i + "]";
            JsonValue elem = elements.get(i);
            switch (elem) {
                case JsonValue.JsonString(String p) -> {
                    if (p.isBlank()) {
                        return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "'" + elemPath + "' must not be blank", Map.of("field", elemPath)));
                    }
                    specs.add(new EnvFileSpec(p, false));
                }
                case JsonValue.JsonObject obj -> {
                    Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, ENV_FILE_KEYS, elemPath);
                    if (unknown.isErr()) return unknown.castError();
                    Result<String, DomainError> pRes = requireNonBlankString(obj, "path", elemPath);
                    if (pRes.isErr()) return pRes.castError();
                    boolean optional = false;
                    if (obj.containsKey("optional")) {
                        if (!(obj.fields().get("optional") instanceof JsonValue.JsonBoolean(boolean b))) {
                            return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + elemPath + ".optional' must be a boolean", Map.of("field", elemPath + ".optional")));
                        }
                        optional = b;
                    }
                    specs.add(new EnvFileSpec(okVal(pRes), optional));
                }
                default -> {
                    return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + elemPath + "' must be a string or object", Map.of("field", elemPath)));
                }
            }
        }
        return Result.ok(specs);
    }

    private static Result<Map<String, VarValue>, DomainError> parseOptionalVars(JsonValue.JsonObject parent, String path) {
        Optional<JsonValue> maybe = parent.get("vars");
        if (maybe.isEmpty()) {
            return Result.ok(Map.of());
        }
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(maybe.get(), path);
        if (objRes.isErr()) return objRes.castError();

        Map<String, VarValue> vars = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(objRes).fields().entrySet()) {
            String k = entry.getKey();
            String fieldPath = path + "." + k;
            if (k.isBlank()) {
                return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "Variable name must not be blank", Map.of("field", path)));
            }
            switch (entry.getValue()) {
                case JsonValue.JsonString(String s) -> vars.put(k, new VarValue.Scalar(s));
                case JsonValue.JsonArray arr -> {
                    Result<List<String>, DomainError> listRes = parseStringArray(arr, fieldPath, false);
                    if (listRes.isErr()) return listRes.castError();
                    vars.put(k, new VarValue.ListValue(okVal(listRes)));
                }
                default -> {
                    return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + fieldPath + "' must be a string or string[]", Map.of("field", fieldPath)));
                }
            }
        }
        return Result.ok(vars);
    }

    private static Result<Map<String, String>, DomainError> parseOptionalStringMap(JsonValue.JsonObject parent, String key, String path) {
        Optional<JsonValue> maybe = parent.get(key);
        if (maybe.isEmpty()) {
            return Result.ok(Map.of());
        }
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(maybe.get(), path);
        if (objRes.isErr()) return objRes.castError();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(objRes).fields().entrySet()) {
            if (!(entry.getValue() instanceof JsonValue.JsonString(String s))) {
                return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + path + "." + entry.getKey() + "' must be a string", Map.of("field", path + "." + entry.getKey())));
            }
            out.put(entry.getKey(), s);
        }
        return Result.ok(out);
    }

    private static Result<List<String>, DomainError> requireNonEmptyStringArray(JsonValue.JsonObject parent, String key, String context) {
        Optional<JsonValue> maybe = parent.get(key);
        if (maybe.isEmpty()) {
            return Result.err(DomainError.of("CONFIG_MISSING_FIELD", "Missing required field '" + context + "." + key + "'", Map.of("field", context + "." + key)));
        }
        return parseStringArray(maybe.get(), context + "." + key, true);
    }

    private static Result<List<String>, DomainError> parseOptionalNonEmptyStringArray(JsonValue.JsonObject parent, String key, String context) {
        Optional<JsonValue> maybe = parent.get(key);
        if (maybe.isEmpty()) {
            return Result.ok(List.of());
        }
        return parseStringArray(maybe.get(), context + "." + key, true);
    }

    private static Result<List<String>, DomainError> parseStringArray(JsonValue value, String path, boolean requireNonEmpty) {
        if (!(value instanceof JsonValue.JsonArray(List<JsonValue> elements))) {
            return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + path + "' must be an array of strings", Map.of("field", path)));
        }
        if (requireNonEmpty && elements.isEmpty()) {
            return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "'" + path + "' must not be empty", Map.of("field", path)));
        }
        List<String> out = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            if (!(elements.get(i) instanceof JsonValue.JsonString(String s))) {
                return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + path + "[" + i + "]' must be a string", Map.of("field", path + "[" + i + "]")));
            }
            out.add(s);
        }
        return Result.ok(out);
    }

    private static Result<Integer, DomainError> parsePort(JsonValue value, String fieldPath) {
        if (!(value instanceof JsonValue.JsonNumber num) || !num.isInteger()) {
            return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + fieldPath + "' must be an integer TCP port", Map.of("field", fieldPath)));
        }
        OptionalInt parsed = num.asInt();
        if (parsed.isEmpty() || parsed.getAsInt() < 1 || parsed.getAsInt() > 65535) {
            return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "'" + fieldPath + "' must be a valid TCP port (1..65535)", Map.of("field", fieldPath, "value", num.raw())));
        }
        return Result.ok(parsed.getAsInt());
    }

    private static Result<JsonValue.JsonObject, DomainError> asObject(JsonValue value, String path) {
        if (value instanceof JsonValue.JsonObject obj) {
            return Result.ok(obj);
        }
        return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + path + "' must be a JSON object", Map.of("field", path)));
    }

    private static Result<procomp.core.common.Unit, DomainError> rejectUnknownKeys(JsonValue.JsonObject obj, Set<String> allowed, String context) {
        for (String key : obj.fields().keySet()) {
            if (!allowed.contains(key)) {
                return Result.err(DomainError.of(
                    "CONFIG_UNRECOGNIZED_FIELD",
                    "Unrecognized field '" + key + "' in " + context,
                    Map.of("field", context + "." + key)
                ));
            }
        }
        return Result.ok(procomp.core.common.Unit.VALUE);
    }

    private static Result<String, DomainError> requireNonBlankString(JsonValue.JsonObject obj, String key, String context) {
        Optional<JsonValue> maybe = obj.get(key);
        if (maybe.isEmpty()) {
            return Result.err(DomainError.of("CONFIG_MISSING_FIELD", "Missing required field '" + context + "." + key + "'", Map.of("field", context + "." + key)));
        }
        if (!(maybe.get() instanceof JsonValue.JsonString(String s))) {
            return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + context + "." + key + "' must be a string", Map.of("field", context + "." + key)));
        }
        if (s.isBlank()) {
            return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "'" + context + "." + key + "' must not be blank", Map.of("field", context + "." + key)));
        }
        return Result.ok(s);
    }

    private static Result<Optional<String>, DomainError> optionalNonBlankString(JsonValue.JsonObject obj, String key, String context) {
        Optional<JsonValue> maybe = obj.get(key);
        if (maybe.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        if (!(maybe.get() instanceof JsonValue.JsonString(String s))) {
            return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + context + "." + key + "' must be a string", Map.of("field", context + "." + key)));
        }
        if (s.isBlank()) {
            return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "'" + context + "." + key + "' must not be blank", Map.of("field", context + "." + key)));
        }
        return Result.ok(Optional.of(s));
    }

    private static Result<Optional<String>, DomainError> optionalString(JsonValue.JsonObject obj, String key, String context) {
        Optional<JsonValue> maybe = obj.get(key);
        if (maybe.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        if (!(maybe.get() instanceof JsonValue.JsonString(String s))) {
            return Result.err(DomainError.of("CONFIG_INVALID_TYPE", "'" + context + "." + key + "' must be a string", Map.of("field", context + "." + key)));
        }
        return Result.ok(Optional.of(s));
    }

    private static <T> T okVal(Result<T, DomainError> res) {
        return ((Result.Ok<T, DomainError>) res).value();
    }
}

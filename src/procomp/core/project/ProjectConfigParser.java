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
public final class ProjectConfigParser {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
        "project", "repositoriesRoot", "envFiles", "defaults",
        "runtimes", "gateway", "catalog", "services", "profiles"
    );
    private static final Set<String> ENV_FILE_KEYS = Set.of("path", "optional");
    private static final Set<String> DEFAULTS_KEYS = Set.of(
        "proxyPathTemplate", "remoteUrlTemplate", "workingDir", "vars", "env"
    );
    private static final Set<String> RUNTIME_KEYS = Set.of(
        "command", "healthPath", "artifactGlob", "artifactExclude", "workingDir", "env"
    );
    private static final Set<String> GATEWAY_KEYS = Set.of(
        "type", "port", "healthPath", "command", "workingDir", "env"
    );
    private static final Set<String> CATALOG_KEYS = Set.of("path", "itemsPath", "map");
    private static final Set<String> CATALOG_MAP_SCALAR_TARGETS = Set.of(
        "name", "port", "runtime", "repository", "repositoryUrl",
        "workingDir", "healthPath", "proxyPath", "remoteUrl"
    );
    private static final Set<String> SERVICE_KEYS = Set.of(
        "port", "runtime", "repository", "repositoryUrl", "workingDir",
        "healthPath", "proxyPath", "remoteUrl", "vars", "env", "envFiles"
    );
    private static final Set<String> PROFILE_KEYS = Set.of("local");

    private ProjectConfigParser() {}

    public static Result<RawProjectConfig, DomainError> parse(String jsonText) {
        return Json.parse(jsonText).flatMap(ProjectConfigParser::parseRoot);
    }

    private static Result<RawProjectConfig, DomainError> parseRoot(JsonValue rootValue) {
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(rootValue, "root");
        if (objRes.isErr()) {
            return castErr(objRes);
        }
        JsonValue.JsonObject root = okVal(objRes);

        Result<procomp.core.common.Unit, DomainError> unknownCheck = rejectUnknownKeys(root, TOP_LEVEL_KEYS, "procomp.json");
        if (unknownCheck.isErr()) {
            return castErr(unknownCheck);
        }

        Result<String, DomainError> projectRes = requireNonBlankString(root, "project", "procomp.json")
            .flatMap(ProjectConfigParser::validateProjectIdentifier);
        if (projectRes.isErr()) {
            return castErr(projectRes);
        }

        Result<Optional<String>, DomainError> reposRootRes = optionalNonBlankString(root, "repositoriesRoot", "procomp.json");
        if (reposRootRes.isErr()) {
            return castErr(reposRootRes);
        }

        Result<List<EnvFileSpec>, DomainError> envFilesRes = parseOptionalEnvFiles(root, "envFiles", "procomp.json");
        if (envFilesRes.isErr()) {
            return castErr(envFilesRes);
        }

        Result<RawProjectConfig.RawDefaultsConfig, DomainError> defaultsRes = parseOptionalDefaults(root);
        if (defaultsRes.isErr()) {
            return castErr(defaultsRes);
        }

        Result<Map<String, RawProjectConfig.RawRuntimeConfig>, DomainError> runtimesRes = parseRuntimes(root);
        if (runtimesRes.isErr()) {
            return castErr(runtimesRes);
        }

        Result<Optional<RawProjectConfig.RawGatewayConfig>, DomainError> gatewayRes = parseOptionalGateway(root);
        if (gatewayRes.isErr()) {
            return castErr(gatewayRes);
        }

        Result<Optional<CatalogMapping>, DomainError> catalogRes = parseOptionalCatalog(root);
        if (catalogRes.isErr()) {
            return castErr(catalogRes);
        }

        Result<Map<String, RawProjectConfig.RawServiceConfig>, DomainError> servicesRes = parseOptionalServices(root);
        if (servicesRes.isErr()) {
            return castErr(servicesRes);
        }

        Result<Map<String, ProfileDefinition>, DomainError> profilesRes = parseOptionalProfiles(root);
        if (profilesRes.isErr()) {
            return castErr(profilesRes);
        }

        return Result.ok(new RawProjectConfig(
            okVal(projectRes),
            okVal(reposRootRes),
            okVal(envFilesRes),
            okVal(defaultsRes),
            okVal(runtimesRes),
            okVal(gatewayRes),
            okVal(catalogRes),
            okVal(servicesRes),
            okVal(profilesRes)
        ));
    }

    private static Result<String, DomainError> validateProjectIdentifier(String project) {
        if (project.equals(".") || project.equals("..") || project.contains("/") || project.contains("\\") || project.contains("\0")) {
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
            return castErr(objRes);
        }
        JsonValue.JsonObject obj = okVal(objRes);
        Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, DEFAULTS_KEYS, "defaults");
        if (unknown.isErr()) {
            return castErr(unknown);
        }

        Result<Optional<String>, DomainError> proxyPath = optionalNonBlankString(obj, "proxyPathTemplate", "defaults");
        if (proxyPath.isErr()) return castErr(proxyPath);
        Result<Optional<String>, DomainError> remoteUrl = optionalNonBlankString(obj, "remoteUrlTemplate", "defaults");
        if (remoteUrl.isErr()) return castErr(remoteUrl);
        Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(obj, "workingDir", "defaults");
        if (workingDir.isErr()) return castErr(workingDir);
        Result<Map<String, VarValue>, DomainError> vars = parseOptionalVars(obj, "defaults.vars");
        if (vars.isErr()) return castErr(vars);
        Result<Map<String, String>, DomainError> env = parseOptionalStringMap(obj, "env", "defaults.env");
        if (env.isErr()) return castErr(env);

        return Result.ok(new RawProjectConfig.RawDefaultsConfig(
            okVal(proxyPath), okVal(remoteUrl), okVal(workingDir), okVal(vars), okVal(env)
        ));
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
            return castErr(mapObjRes);
        }
        Map<String, RawProjectConfig.RawRuntimeConfig> runtimes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(mapObjRes).fields().entrySet()) {
            String runtimeName = entry.getKey();
            if (runtimeName.isBlank()) {
                return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "Runtime name must not be blank", Map.of("field", "runtimes")));
            }
            String path = "runtimes." + runtimeName;
            Result<JsonValue.JsonObject, DomainError> rtObjRes = asObject(entry.getValue(), path);
            if (rtObjRes.isErr()) return castErr(rtObjRes);
            JsonValue.JsonObject rtObj = okVal(rtObjRes);

            Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(rtObj, RUNTIME_KEYS, path);
            if (unknown.isErr()) return castErr(unknown);

            Result<List<String>, DomainError> cmdRes = requireNonEmptyStringArray(rtObj, "command", path);
            if (cmdRes.isErr()) return castErr(cmdRes);
            Result<Optional<String>, DomainError> healthPath = optionalString(rtObj, "healthPath", path);
            if (healthPath.isErr()) return castErr(healthPath);
            Result<Optional<String>, DomainError> artifactGlob = optionalNonBlankString(rtObj, "artifactGlob", path);
            if (artifactGlob.isErr()) return castErr(artifactGlob);
            Result<Optional<String>, DomainError> artifactExclude = optionalNonBlankString(rtObj, "artifactExclude", path);
            if (artifactExclude.isErr()) return castErr(artifactExclude);
            Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(rtObj, "workingDir", path);
            if (workingDir.isErr()) return castErr(workingDir);
            Result<Map<String, String>, DomainError> env = parseOptionalStringMap(rtObj, "env", path + ".env");
            if (env.isErr()) return castErr(env);

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
        if (objRes.isErr()) return castErr(objRes);
        JsonValue.JsonObject obj = okVal(objRes);

        Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, GATEWAY_KEYS, "gateway");
        if (unknown.isErr()) return castErr(unknown);

        Result<String, DomainError> typeRes = requireNonBlankString(obj, "type", "gateway");
        if (typeRes.isErr()) return castErr(typeRes);
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
        if (portRes.isErr()) return castErr(portRes);

        Result<Optional<String>, DomainError> healthPath = optionalString(obj, "healthPath", "gateway");
        if (healthPath.isErr()) return castErr(healthPath);
        Result<List<String>, DomainError> command = parseOptionalNonEmptyStringArray(obj, "command", "gateway");
        if (command.isErr()) return castErr(command);
        Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(obj, "workingDir", "gateway");
        if (workingDir.isErr()) return castErr(workingDir);
        Result<Map<String, String>, DomainError> env = parseOptionalStringMap(obj, "env", "gateway.env");
        if (env.isErr()) return castErr(env);

        return Result.ok(Optional.of(new RawProjectConfig.RawGatewayConfig(
            type, okVal(portRes), okVal(healthPath), okVal(command), okVal(workingDir), okVal(env)
        )));
    }

    private static Result<Optional<CatalogMapping>, DomainError> parseOptionalCatalog(JsonValue.JsonObject root) {
        Optional<JsonValue> maybe = root.get("catalog");
        if (maybe.isEmpty()) {
            return Result.ok(Optional.empty());
        }
        Result<JsonValue.JsonObject, DomainError> objRes = asObject(maybe.get(), "catalog");
        if (objRes.isErr()) return castErr(objRes);
        JsonValue.JsonObject obj = okVal(objRes);

        Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(obj, CATALOG_KEYS, "catalog");
        if (unknown.isErr()) return castErr(unknown);

        Result<String, DomainError> pathRes = requireNonBlankString(obj, "path", "catalog");
        if (pathRes.isErr()) return castErr(pathRes);
        Result<Optional<String>, DomainError> itemsPathRes = optionalNonBlankString(obj, "itemsPath", "catalog");
        if (itemsPathRes.isErr()) return castErr(itemsPathRes);

        Optional<JsonValue> mapVal = obj.get("map");
        if (mapVal.isEmpty()) {
            return Result.err(DomainError.of("CONFIG_MISSING_FIELD", "Missing required field 'catalog.map'", Map.of("field", "catalog.map")));
        }
        Result<JsonValue.JsonObject, DomainError> mapObjRes = asObject(mapVal.get(), "catalog.map");
        if (mapObjRes.isErr()) return castErr(mapObjRes);
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
        if (mapObjRes.isErr()) return castErr(mapObjRes);

        Map<String, RawProjectConfig.RawServiceConfig> services = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(mapObjRes).fields().entrySet()) {
            String svcName = entry.getKey();
            if (svcName.isBlank()) {
                return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "Service name must not be blank", Map.of("field", "services")));
            }
            String path = "services." + svcName;
            Result<JsonValue.JsonObject, DomainError> svcObjRes = asObject(entry.getValue(), path);
            if (svcObjRes.isErr()) return castErr(svcObjRes);
            JsonValue.JsonObject svcObj = okVal(svcObjRes);

            Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(svcObj, SERVICE_KEYS, path);
            if (unknown.isErr()) return castErr(unknown);

            Optional<Integer> port = Optional.empty();
            if (svcObj.containsKey("port")) {
                Result<Integer, DomainError> portRes = parsePort(svcObj.fields().get("port"), path + ".port");
                if (portRes.isErr()) return castErr(portRes);
                port = Optional.of(okVal(portRes));
            }

            Result<Optional<String>, DomainError> runtime = optionalNonBlankString(svcObj, "runtime", path);
            if (runtime.isErr()) return castErr(runtime);
            Result<Optional<String>, DomainError> repository = optionalNonBlankString(svcObj, "repository", path);
            if (repository.isErr()) return castErr(repository);
            Result<Optional<String>, DomainError> repositoryUrl = optionalNonBlankString(svcObj, "repositoryUrl", path);
            if (repositoryUrl.isErr()) return castErr(repositoryUrl);
            Result<Optional<String>, DomainError> workingDir = optionalNonBlankString(svcObj, "workingDir", path);
            if (workingDir.isErr()) return castErr(workingDir);
            Result<Optional<String>, DomainError> healthPath = optionalString(svcObj, "healthPath", path);
            if (healthPath.isErr()) return castErr(healthPath);
            Result<Optional<String>, DomainError> proxyPath = optionalNonBlankString(svcObj, "proxyPath", path);
            if (proxyPath.isErr()) return castErr(proxyPath);
            Result<Optional<String>, DomainError> remoteUrl = optionalNonBlankString(svcObj, "remoteUrl", path);
            if (remoteUrl.isErr()) return castErr(remoteUrl);
            Result<Map<String, VarValue>, DomainError> vars = parseOptionalVars(svcObj, path + ".vars");
            if (vars.isErr()) return castErr(vars);
            Result<Map<String, String>, DomainError> env = parseOptionalStringMap(svcObj, "env", path + ".env");
            if (env.isErr()) return castErr(env);
            Result<List<EnvFileSpec>, DomainError> envFiles = parseOptionalEnvFiles(svcObj, "envFiles", path);
            if (envFiles.isErr()) return castErr(envFiles);

            services.put(svcName, new RawProjectConfig.RawServiceConfig(
                port, okVal(runtime), okVal(repository), okVal(repositoryUrl), okVal(workingDir),
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
        if (mapObjRes.isErr()) return castErr(mapObjRes);

        Map<String, ProfileDefinition> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : okVal(mapObjRes).fields().entrySet()) {
            String profileName = entry.getKey();
            if (profileName.isBlank()) {
                return Result.err(DomainError.of("CONFIG_INVALID_VALUE", "Profile name must not be blank", Map.of("field", "profiles")));
            }
            String path = "profiles." + profileName;
            Result<JsonValue.JsonObject, DomainError> profObjRes = asObject(entry.getValue(), path);
            if (profObjRes.isErr()) return castErr(profObjRes);
            JsonValue.JsonObject profObj = okVal(profObjRes);

            Result<procomp.core.common.Unit, DomainError> unknown = rejectUnknownKeys(profObj, PROFILE_KEYS, path);
            if (unknown.isErr()) return castErr(unknown);

            Optional<JsonValue> localVal = profObj.get("local");
            if (localVal.isEmpty()) {
                return Result.err(DomainError.of("CONFIG_MISSING_FIELD", "Missing required field '" + path + ".local'", Map.of("field", path + ".local")));
            }
            Result<List<String>, DomainError> localListRes = parseStringArray(localVal.get(), path + ".local", false);
            if (localListRes.isErr()) return castErr(localListRes);

            profiles.put(profileName, new ProfileDefinition(profileName, okVal(localListRes)));
        }
        return Result.ok(profiles);
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
                    if (unknown.isErr()) return castErr(unknown);
                    Result<String, DomainError> pRes = requireNonBlankString(obj, "path", elemPath);
                    if (pRes.isErr()) return castErr(pRes);
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
        if (objRes.isErr()) return castErr(objRes);

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
                    if (listRes.isErr()) return castErr(listRes);
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
        if (objRes.isErr()) return castErr(objRes);
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
        return Result.ok(s.isEmpty() ? Optional.empty() : Optional.of(s));
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

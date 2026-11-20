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

/**
 * Pure importer that extracts raw Service definitions from an external Catalog JSON using explicit field mapping.
 */
public final class CatalogParser {

    private CatalogParser() {}

    public static Result<Map<String, RawProjectConfig.RawServiceConfig>, DomainError> parse(
        CatalogMapping mapping,
        String catalogJson
    ) {
        return Json.parse(catalogJson).flatMap(root -> extractItemsArray(root, mapping.itemsPath()))
            .flatMap(items -> mapAllItems(items, mapping.map()));
    }

    private static Result<List<JsonValue>, DomainError> extractItemsArray(JsonValue root, Optional<String> itemsPath) {
        JsonValue current = root;
        if (itemsPath.isPresent()) {
            String rawPath = itemsPath.get();
            String[] segments = rawPath.split("\\.", -1);
            for (String segment : segments) {
                if (segment.isEmpty()) {
                    return Result.err(DomainError.of(
                        "CATALOG_INVALID_PATH",
                        "Invalid catalog itemsPath '" + rawPath + "'",
                        Map.of("itemsPath", rawPath)
                    ));
                }
                if (!(current instanceof JsonValue.JsonObject obj)) {
                    return Result.err(DomainError.of(
                        "CATALOG_INVALID_PATH",
                        "Catalog itemsPath '" + rawPath + "' traversed non-object at '" + segment + "'",
                        Map.of("itemsPath", rawPath, "segment", segment)
                    ));
                }
                Optional<JsonValue> next = obj.get(segment);
                if (next.isEmpty()) {
                    return Result.err(DomainError.of(
                        "CATALOG_MISSING_ITEMS",
                        "Catalog itemsPath '" + rawPath + "' not found (missing '" + segment + "')",
                        Map.of("itemsPath", rawPath, "segment", segment)
                    ));
                }
                current = next.get();
            }
        }
        if (!(current instanceof JsonValue.JsonArray(List<JsonValue> elements))) {
            return Result.err(DomainError.of(
                "CATALOG_INVALID_TYPE",
                "Catalog items target must be a JSON array",
                Map.of("itemsPath", itemsPath.orElse("<root>"))
            ));
        }
        return Result.ok(elements);
    }

    private static Result<Map<String, RawProjectConfig.RawServiceConfig>, DomainError> mapAllItems(
        List<JsonValue> items,
        Map<String, String> fieldMap
    ) {
        String nameSourceField = fieldMap.get("name");
        if (nameSourceField == null || nameSourceField.isBlank()) {
            return Result.err(DomainError.of("CATALOG_INVALID_MAPPING", "Catalog map must define 'name'"));
        }

        Map<String, RawProjectConfig.RawServiceConfig> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            JsonValue itemVal = items.get(i);
            if (!(itemVal instanceof JsonValue.JsonObject itemObj)) {
                return Result.err(DomainError.of(
                    "CATALOG_INVALID_ITEM",
                    "Catalog item at index " + i + " must be a JSON object",
                    Map.of("index", Integer.toString(i))
                ));
            }

            Result<Optional<String>, DomainError> nameRes = readOptionalNonBlankString(itemObj, nameSourceField, i, "name");
            if (nameRes.isErr()) return castErr(nameRes);
            Optional<String> nameOpt = ((Result.Ok<Optional<String>, DomainError>) nameRes).value();
            if (nameOpt.isEmpty()) {
                return Result.err(DomainError.of(
                    "CATALOG_MISSING_NAME",
                    "Catalog item at index " + i + " is missing mapped name field '" + nameSourceField + "'",
                    Map.of("index", Integer.toString(i), "sourceField", nameSourceField)
                ));
            }
            String serviceName = nameOpt.get();
            if (result.containsKey(serviceName)) {
                return Result.err(DomainError.of(
                    "CATALOG_DUPLICATE_SERVICE",
                    "Duplicate service name '" + serviceName + "' in catalog",
                    Map.of("service", serviceName, "index", Integer.toString(i))
                ));
            }

            Result<RawProjectConfig.RawServiceConfig, DomainError> svcRes = mapItem(itemObj, fieldMap, i, serviceName);
            if (svcRes.isErr()) return castErr(svcRes);
            result.put(serviceName, ((Result.Ok<RawProjectConfig.RawServiceConfig, DomainError>) svcRes).value());
        }
        return Result.ok(result);
    }

    private static Result<RawProjectConfig.RawServiceConfig, DomainError> mapItem(
        JsonValue.JsonObject itemObj,
        Map<String, String> fieldMap,
        int index,
        String serviceName
    ) {
        Optional<Integer> port = Optional.empty();
        Optional<String> runtime = Optional.empty();
        Optional<String> repository = Optional.empty();
        Optional<String> repositoryUrl = Optional.empty();
        Optional<String> workingDir = Optional.empty();
        Optional<String> healthPath = Optional.empty();
        Optional<String> proxyPath = Optional.empty();
        Optional<String> remoteUrl = Optional.empty();
        Map<String, VarValue> vars = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : fieldMap.entrySet()) {
            String target = entry.getKey();
            String sourceField = entry.getValue();
            if ("name".equals(target)) {
                continue;
            }
            Optional<JsonValue> rawValOpt = itemObj.get(sourceField);
            if (rawValOpt.isEmpty() || rawValOpt.get() instanceof JsonValue.JsonNull) {
                continue;
            }
            JsonValue rawVal = rawValOpt.get();

            if (target.startsWith("vars.")) {
                String varKey = target.substring("vars.".length());
                Result<VarValue, DomainError> varRes = parseCatalogVar(rawVal, index, serviceName, sourceField, target);
                if (varRes.isErr()) return castErr(varRes);
                vars.put(varKey, ((Result.Ok<VarValue, DomainError>) varRes).value());
                continue;
            }

            switch (target) {
                case "port" -> {
                    Result<Integer, DomainError> portRes = parseCatalogPort(rawVal, index, serviceName, sourceField);
                    if (portRes.isErr()) return castErr(portRes);
                    port = Optional.of(((Result.Ok<Integer, DomainError>) portRes).value());
                }
                case "runtime" -> {
                    Result<String, DomainError> s = expectNonBlankString(rawVal, index, serviceName, sourceField, target);
                    if (s.isErr()) return castErr(s);
                    runtime = Optional.of(okVal(s));
                }
                case "repository" -> {
                    Result<String, DomainError> s = expectNonBlankString(rawVal, index, serviceName, sourceField, target);
                    if (s.isErr()) return castErr(s);
                    repository = Optional.of(okVal(s));
                }
                case "repositoryUrl" -> {
                    Result<String, DomainError> s = expectNonBlankString(rawVal, index, serviceName, sourceField, target);
                    if (s.isErr()) return castErr(s);
                    repositoryUrl = Optional.of(okVal(s));
                }
                case "workingDir" -> {
                    Result<String, DomainError> s = expectNonBlankString(rawVal, index, serviceName, sourceField, target);
                    if (s.isErr()) return castErr(s);
                    workingDir = Optional.of(okVal(s));
                }
                case "healthPath" -> {
                    if (!(rawVal instanceof JsonValue.JsonString(String s))) {
                        return Result.err(invalidFieldType(index, serviceName, sourceField, target, "string"));
                    }
                    healthPath = s.isEmpty() ? Optional.empty() : Optional.of(s);
                }
                case "proxyPath" -> {
                    Result<String, DomainError> s = expectNonBlankString(rawVal, index, serviceName, sourceField, target);
                    if (s.isErr()) return castErr(s);
                    proxyPath = Optional.of(okVal(s));
                }
                case "remoteUrl" -> {
                    Result<String, DomainError> s = expectNonBlankString(rawVal, index, serviceName, sourceField, target);
                    if (s.isErr()) return castErr(s);
                    remoteUrl = Optional.of(okVal(s));
                }
                default -> {
                    return Result.err(DomainError.of(
                        "CATALOG_INVALID_MAPPING",
                        "Unsupported catalog.map target '" + target + "'",
                        Map.of("target", target)
                    ));
                }
            }
        }

        return Result.ok(new RawProjectConfig.RawServiceConfig(
            port, runtime, repository, repositoryUrl, workingDir,
            healthPath, proxyPath, remoteUrl, vars, Map.of(), List.of()
        ));
    }

    private static Result<Optional<String>, DomainError> readOptionalNonBlankString(
        JsonValue.JsonObject obj,
        String sourceField,
        int index,
        String target
    ) {
        Optional<JsonValue> maybe = obj.get(sourceField);
        if (maybe.isEmpty() || maybe.get() instanceof JsonValue.JsonNull) {
            return Result.ok(Optional.empty());
        }
        if (!(maybe.get() instanceof JsonValue.JsonString(String s)) || s.isBlank()) {
            return Result.err(DomainError.of(
                "CATALOG_INVALID_VALUE",
                "Catalog item[" + index + "] field '" + sourceField + "' (mapped to '" + target + "') must be a non-blank string",
                Map.of("index", Integer.toString(index), "sourceField", sourceField, "target", target)
            ));
        }
        return Result.ok(Optional.of(s));
    }

    private static Result<String, DomainError> expectNonBlankString(
        JsonValue val,
        int index,
        String serviceName,
        String sourceField,
        String target
    ) {
        if (!(val instanceof JsonValue.JsonString(String s))) {
            return Result.err(invalidFieldType(index, serviceName, sourceField, target, "string"));
        }
        if (s.isBlank()) {
            return Result.err(DomainError.of(
                "CATALOG_INVALID_VALUE",
                "Catalog item '" + serviceName + "' field '" + sourceField + "' must not be blank",
                Map.of("service", serviceName, "index", Integer.toString(index), "sourceField", sourceField, "target", target)
            ));
        }
        return Result.ok(s);
    }

    private static Result<Integer, DomainError> parseCatalogPort(
        JsonValue val,
        int index,
        String serviceName,
        String sourceField
    ) {
        int port;
        switch (val) {
            case JsonValue.JsonNumber num -> {
                if (!num.isInteger()) {
                    return Result.err(invalidPortError(index, serviceName, sourceField, num.raw()));
                }
                OptionalInt parsed = num.asInt();
                if (parsed.isEmpty()) {
                    return Result.err(invalidPortError(index, serviceName, sourceField, num.raw()));
                }
                port = parsed.getAsInt();
            }
            case JsonValue.JsonString(String s) -> {
                if (!isStrictUnsignedDigits(s)) {
                    return Result.err(invalidPortError(index, serviceName, sourceField, s));
                }
                try {
                    port = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    return Result.err(invalidPortError(index, serviceName, sourceField, s));
                }
            }
            default -> {
                return Result.err(invalidFieldType(index, serviceName, sourceField, "port", "integer or numeric string"));
            }
        }
        if (port < 1 || port > 65535) {
            return Result.err(invalidPortError(index, serviceName, sourceField, Integer.toString(port)));
        }
        return Result.ok(port);
    }

    private static Result<VarValue, DomainError> parseCatalogVar(
        JsonValue val,
        int index,
        String serviceName,
        String sourceField,
        String target
    ) {
        return switch (val) {
            case JsonValue.JsonString(String s) -> Result.ok(new VarValue.Scalar(s));
            case JsonValue.JsonArray(List<JsonValue> elements) -> {
                List<String> items = new ArrayList<>(elements.size());
                for (JsonValue el : elements) {
                    if (!(el instanceof JsonValue.JsonString(String s))) {
                        yield Result.err(invalidFieldType(index, serviceName, sourceField, target, "string or string[]"));
                    }
                    items.add(s);
                }
                yield Result.ok(new VarValue.ListValue(items));
            }
            default -> Result.err(invalidFieldType(index, serviceName, sourceField, target, "string or string[]"));
        };
    }

    private static boolean isStrictUnsignedDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static DomainError invalidPortError(int index, String serviceName, String sourceField, String raw) {
        return DomainError.of(
            "CATALOG_INVALID_PORT",
            "Catalog item '" + serviceName + "' has invalid port '" + raw + "' in field '" + sourceField + "'",
            Map.of("service", serviceName, "index", Integer.toString(index), "sourceField", sourceField, "value", raw)
        );
    }

    private static DomainError invalidFieldType(int index, String serviceName, String sourceField, String target, String expected) {
        return DomainError.of(
            "CATALOG_INVALID_TYPE",
            "Catalog item '" + serviceName + "' field '" + sourceField + "' (mapped to '" + target + "') must be " + expected,
            Map.of("service", serviceName, "index", Integer.toString(index), "sourceField", sourceField, "target", target)
        );
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

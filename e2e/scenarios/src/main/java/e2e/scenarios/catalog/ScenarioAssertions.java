package e2e.scenarios.catalog;

import e2e.scenarios.engine.CliResult;
import e2e.scenarios.inmemory.InMemoryConfigSource;
import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight, JUnit-free assertion helpers for {@link ScenarioCatalog} step assertions.
 */
final class ScenarioAssertions {

    private ScenarioAssertions() {}

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected: <" + expected + "> but was: <" + actual + ">)");
        }
    }

    static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            throw new AssertionError(message + " (did not expect: <" + unexpected + ">)");
        }
    }

    static CliResult assertCliOk(CliResult result, String label) {
        if (result.exitCode() != 0) {
            throw new AssertionError(
                label + " failed with exit " + result.exitCode()
                    + "\nstdout:\n" + result.stdout()
                    + "\nstderr:\n" + result.stderr()
            );
        }
        return result;
    }

    static void assertNoSecretLeak(String text, String context) {
        assertFalse(
            text.contains(InMemoryConfigSource.DEMO_SECRET_VALUE),
            context + " must never leak secret values"
        );
    }

    static JsonValue.JsonObject parseJsonObject(String json, String context) {
        Result<JsonValue, DomainError> parsed = Json.parse(json);
        if (parsed instanceof Result.Ok<JsonValue, DomainError>(JsonValue value)
            && value instanceof JsonValue.JsonObject obj) {
            return obj;
        }
        throw new AssertionError("Expected JSON object for " + context + ", got: " + json);
    }

    static JsonValue.JsonObject gatewayObject(JsonValue.JsonObject statusJson) {
        Optional<JsonValue> gw = statusJson.get("gateway");
        if (gw.isPresent() && gw.get() instanceof JsonValue.JsonObject obj) {
            return obj;
        }
        throw new AssertionError("Missing 'gateway' JSON object in status output: " + statusJson);
    }

    static List<JsonValue.JsonObject> serviceRows(JsonValue.JsonObject statusJson) {
        Optional<JsonValue> services = statusJson.get("services");
        if (services.isEmpty() || !(services.get() instanceof JsonValue.JsonArray(List<JsonValue> elements))) {
            throw new AssertionError("Missing 'services' JSON array in status output: " + statusJson);
        }
        return elements.stream()
            .map(elem -> {
                if (elem instanceof JsonValue.JsonObject obj) {
                    return obj;
                }
                throw new AssertionError("Expected JSON object in 'services' array: " + elem);
            })
            .toList();
    }

    static JsonValue.JsonObject findServiceRow(JsonValue.JsonObject statusJson, String serviceName) {
        for (JsonValue.JsonObject row : serviceRows(statusJson)) {
            if (serviceName.equals(optionalString(row, "service").orElse(null))) {
                return row;
            }
        }
        throw new AssertionError("Service '" + serviceName + "' not found in status --json");
    }

    static String requireString(JsonValue.JsonObject obj, String key, String context) {
        return optionalString(obj, key).orElseThrow(() ->
            new AssertionError("Missing string field '" + key + "' in " + context + ": " + obj)
        );
    }

    static Optional<String> optionalString(JsonValue.JsonObject obj, String key) {
        return obj.get(key)
            .filter(JsonValue.JsonString.class::isInstance)
            .map(v -> ((JsonValue.JsonString) v).value());
    }

    static boolean requireBoolean(JsonValue.JsonObject obj, String key, String context) {
        Optional<JsonValue> val = obj.get(key);
        if (val.isPresent() && val.get() instanceof JsonValue.JsonBoolean(boolean b)) {
            return b;
        }
        throw new AssertionError("Missing boolean field '" + key + "' in " + context + ": " + obj);
    }

    static int requireInt(JsonValue.JsonObject obj, String key, String context) {
        Optional<JsonValue> val = obj.get(key);
        if (val.isPresent() && val.get() instanceof JsonValue.JsonNumber num && num.asInt().isPresent()) {
            return num.asInt().getAsInt();
        }
        throw new AssertionError("Missing integer field '" + key + "' in " + context + ": " + obj);
    }
}

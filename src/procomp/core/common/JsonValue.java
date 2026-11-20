package procomp.core.common;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Immutable JSON AST preserving object key insertion order and exact number tokens.
 */
public sealed interface JsonValue permits
    JsonValue.JsonObject,
    JsonValue.JsonArray,
    JsonValue.JsonString,
    JsonValue.JsonNumber,
    JsonValue.JsonBoolean,
    JsonValue.JsonNull {

    record JsonObject(Map<String, JsonValue> fields) implements JsonValue {
        public JsonObject {
            Objects.requireNonNull(fields, "fields must not be null");
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        public Optional<JsonValue> get(String key) {
            return Optional.ofNullable(fields.get(key));
        }

        public boolean containsKey(String key) {
            return fields.containsKey(key);
        }
    }

    record JsonArray(List<JsonValue> elements) implements JsonValue {
        public JsonArray {
            Objects.requireNonNull(elements, "elements must not be null");
            elements = List.copyOf(elements);
        }
    }

    record JsonString(String value) implements JsonValue {
        public JsonString {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    record JsonNumber(String raw) implements JsonValue {
        public JsonNumber {
            if (raw == null || raw.isEmpty()) {
                throw new IllegalArgumentException("raw JSON number must not be empty");
            }
        }

        public boolean isInteger() {
            return !raw.contains(".") && !raw.contains("e") && !raw.contains("E");
        }

        public OptionalLong asLong() {
            if (!isInteger()) {
                return OptionalLong.empty();
            }
            try {
                return OptionalLong.of(Long.parseLong(raw));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        }

        public OptionalInt asInt() {
            OptionalLong l = asLong();
            if (l.isEmpty()) {
                return OptionalInt.empty();
            }
            long val = l.getAsLong();
            if (val < Integer.MIN_VALUE || val > Integer.MAX_VALUE) {
                return OptionalInt.empty();
            }
            return OptionalInt.of((int) val);
        }

        public double asDouble() {
            return new BigDecimal(raw).doubleValue();
        }
    }

    record JsonBoolean(boolean value) implements JsonValue {}

    record JsonNull() implements JsonValue {
        public static final JsonNull INSTANCE = new JsonNull();
    }
}

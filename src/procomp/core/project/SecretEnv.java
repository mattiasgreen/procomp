package procomp.core.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable environment variable overlay whose {@link #toString()} redacts all secret values.
 */
public record SecretEnv(Map<String, String> values) {

    public static final SecretEnv EMPTY = new SecretEnv(Map.of());

    public SecretEnv {
        Objects.requireNonNull(values, "values must not be null");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static SecretEnv of(Map<String, String> values) {
        return new SecretEnv(values);
    }

    public Set<String> keys() {
        return values.keySet();
    }

    public Map<String, String> asMap() {
        return values;
    }

    @Override
    public String toString() {
        return "SecretEnv[keys=" + values.keySet() + "]";
    }
}

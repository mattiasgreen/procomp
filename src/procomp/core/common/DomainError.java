package procomp.core.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Typed, immutable domain/I-O error with machine-readable code, message, and contextual metadata.
 */
public record DomainError(String code, String message, Map<String, String> details) {

    public DomainError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("DomainError code must be non-blank");
        }
        Objects.requireNonNull(message, "DomainError message must not be null");
        details = details == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static DomainError of(String code, String message) {
        return new DomainError(code, message, Map.of());
    }

    public static DomainError of(String code, String message, Map<String, String> details) {
        return new DomainError(code, message, details);
    }

    public String format() {
        if (details.isEmpty()) {
            return "[" + code + "] " + message;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(code).append("] ").append(message).append(" (");
        boolean first = true;
        for (Map.Entry<String, String> entry : details.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }
}

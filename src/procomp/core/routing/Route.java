package procomp.core.routing;

import procomp.core.common.DomainError;
import procomp.core.common.Result;

import java.util.Map;

/**
 * Declarative traffic destination for a Service in the Routing Table.
 */
public enum Route {
    LOCAL,
    REMOTE;

    /**
     * Strictly parses a case-insensitive route token ({@code "local"} or {@code "remote"}).
     */
    public static Result<Route, DomainError> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.err(DomainError.of(
                "ROUTE_INVALID_VALUE",
                "Invalid route '" + raw + "' (expected 'local' or 'remote')",
                Map.of("value", raw == null ? "null" : raw)
            ));
        }
        if ("local".equalsIgnoreCase(raw)) {
            return Result.ok(LOCAL);
        }
        if ("remote".equalsIgnoreCase(raw)) {
            return Result.ok(REMOTE);
        }
        return Result.err(DomainError.of(
            "ROUTE_INVALID_VALUE",
            "Invalid route '" + raw + "' (expected 'local' or 'remote')",
            Map.of("value", raw)
        ));
    }
}

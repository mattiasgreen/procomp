package procomp.core.gateway;

import procomp.core.routing.Route;

import java.util.Objects;

/**
 * Per-Service discrepancy between the desired Route in the Routing Table and the live Gateway mapping.
 * {@code live} is {@code "LOCAL"}, {@code "REMOTE"}, {@code "UNSET"}, or {@code "MISMATCH"}.
 */
public record RouteDrift(
    String service,
    Route desired,
    String live
) {
    public RouteDrift {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("RouteDrift service must be non-blank");
        }
        Objects.requireNonNull(desired, "desired must not be null");
        if (live == null || live.isBlank()) {
            throw new IllegalArgumentException("RouteDrift live must be non-blank");
        }
    }
}

package procomp.core.gateway;

import java.util.Objects;
import java.util.Optional;

/**
 * A managed WireMock mapping still present in the Gateway for a Service no longer in the Project.
 */
public record StaleManagedMapping(
    Optional<String> id,
    Optional<String> service,
    Optional<String> targetUrl
) {
    public StaleManagedMapping {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(service, "service must not be null");
        Objects.requireNonNull(targetUrl, "targetUrl must not be null");
    }
}

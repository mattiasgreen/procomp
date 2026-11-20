package procomp.core.gateway;

import procomp.core.routing.Route;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable WireMock reverse-proxy stub mapping descriptor for a single Service.
 */
public record WireMockMapping(
    String id,
    String name,
    int priority,
    String projectName,
    String service,
    Route route,
    String urlPathPattern,
    String targetUrl,
    Optional<String> proxyUrlPrefixToRemove
) {
    public WireMockMapping {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("WireMockMapping id must be non-blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("WireMockMapping name must be non-blank");
        }
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("WireMockMapping projectName must be non-blank");
        }
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("WireMockMapping service must be non-blank");
        }
        Objects.requireNonNull(route, "route must not be null");
        if (urlPathPattern == null || urlPathPattern.isBlank()) {
            throw new IllegalArgumentException("WireMockMapping urlPathPattern must be non-blank");
        }
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("WireMockMapping targetUrl must be non-blank");
        }
        Objects.requireNonNull(proxyUrlPrefixToRemove, "proxyUrlPrefixToRemove must not be null");
    }
}

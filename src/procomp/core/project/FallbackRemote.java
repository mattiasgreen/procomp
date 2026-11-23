package procomp.core.project;

import java.util.Objects;
import java.util.Optional;

/**
 * Catch-all remote fallback target for a Gateway (e.g., WireMock priority-10 rule).
 */
public record FallbackRemote(
    String targetUrl,
    Optional<String> stripPrefix
) {
    public FallbackRemote {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("FallbackRemote targetUrl must be non-blank");
        }
        Objects.requireNonNull(stripPrefix, "stripPrefix must not be null");
    }
}

package procomp.core.project;

import java.util.List;
import java.util.Objects;

/**
 * Named routing preset declaring which Services route LOCAL (all others route REMOTE).
 */
public record ProfileDefinition(String name, List<String> localServices) {

    public ProfileDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ProfileDefinition name must be non-blank");
        }
        Objects.requireNonNull(localServices, "localServices must not be null");
        localServices = List.copyOf(localServices);
    }
}

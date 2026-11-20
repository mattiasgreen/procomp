package procomp.core.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Declarative external Catalog import specification in procomp.json.
 */
public record CatalogMapping(String path, Optional<String> itemsPath, Map<String, String> map) {

    public CatalogMapping {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("CatalogMapping path must be non-blank");
        }
        Objects.requireNonNull(itemsPath, "itemsPath must not be null");
        Objects.requireNonNull(map, "map must not be null");
        map = Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
